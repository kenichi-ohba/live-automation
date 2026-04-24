package com.example.fc2_live_automation.service;

import com.example.fc2_live_automation.model.Fc2Account;
import com.example.fc2_live_automation.model.Fc2Preset;
import com.example.fc2_live_automation.repository.Fc2AccountRepository;
import com.example.fc2_live_automation.repository.Fc2PresetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@EnableScheduling // 🌟 追加：Spring Bootの「定期実行（タイマー）機能」を有効化する魔法のスイッチ
public class AutomationScheduler {

    private static final Logger logger = LoggerFactory.getLogger(AutomationScheduler.class);

    private final Fc2AccountRepository accountRepository;
    private final Fc2PresetRepository presetRepository;
    private final Fc2AutomationWorker worker; // 🌟 追加：自動で再生させるためにワーカーを呼び出す

    public AutomationScheduler(Fc2AccountRepository accountRepository, Fc2PresetRepository presetRepository, Fc2AutomationWorker worker) {
        this.accountRepository = accountRepository;
        this.presetRepository = presetRepository;
        this.worker = worker;
    }

    /**
     * アプリ起動時に実行されるクリーンアップ処理
     */
    @PostConstruct
    public void resetStatusOnStartup() {
        logger.info("⚙️ 起動時クリーンアップ: データベース上の「配信中(RUNNING)」ステータスをリセットします");

        List<Fc2Account> runningAccounts = accountRepository.findAll().stream()
                .filter(a -> "RUNNING".equals(a.getStatus()))
                .toList();
        for (Fc2Account account : runningAccounts) {
            account.setStatus("IDLE");
            account.setCurrentLoop(0);
            accountRepository.save(account);
        }

        List<Fc2Preset> runningPresets = presetRepository.findAll().stream()
                .filter(p -> "RUNNING".equals(p.getStatus()))
                .toList();
        for (Fc2Preset preset : runningPresets) {
            preset.setStatus("IDLE");
            preset.setCurrentLoop(0);
            presetRepository.save(preset);
        }

        logger.info("✅ クリーンアップ完了: プリセット {} 件、アカウント {} 件を停止状態に戻しました", 
                    runningPresets.size(), runningAccounts.size());
    }

    /**
     * 🌟 追加：60秒（60000ミリ秒）に1回、自動で予約時間をチェックして発動させるメソッド
     */
    @Scheduled(fixedDelay = 60000)
    public void checkScheduledPresets() {
        List<Fc2Preset> allPresets = presetRepository.findAll();
        LocalDateTime now = LocalDateTime.now();

        for (Fc2Preset preset : allPresets) {
            String schedStr = preset.getScheduledStartTime();
            
            // 予約時間が設定されており、かつ現在「実行中」ではない場合
            if (schedStr != null && !schedStr.isEmpty() && !"RUNNING".equals(preset.getStatus())) {
                try {
                    LocalDateTime scheduledTime = LocalDateTime.parse(schedStr);
                    
                    // 現在時刻が、予約時間を過ぎている（または同じ）なら発動！
                    if (!now.isBefore(scheduledTime)) {
                        logger.info("⏰ 予約時間到達: プリセット[{}] の自動再生を開始します！", preset.getPresetName());
                        
                        // 1. 次回以降に誤作動しないよう、予約時間をクリアして保存
                        preset.setScheduledStartTime("");
                        presetRepository.save(preset);

                        // 2. 再生する動画リスト（プレイリスト）を安全に取得
                        List<Fc2Account> tempPlaylist = preset.getAccounts();
                        if (tempPlaylist == null || tempPlaylist.isEmpty()) {
                            tempPlaylist = accountRepository.findAll().stream()
                                .filter(a -> preset.getPresetName().equals(a.getPresetName()))
                                .collect(Collectors.toList());
                        }

                        // 3. 設定された順番通りに並び替える
                        List<Fc2Account> playlist = new ArrayList<>(tempPlaylist);
                        playlist.sort(Comparator.comparing(Fc2Account::getDisplayOrder, Comparator.nullsLast(Comparator.naturalOrder())));

                        // 4. ワーカーに「自動再生」の命令を送る
                        worker.startPresetProcess(preset, playlist);
                    }
                } catch (Exception e) {
                    logger.error("⚠️ 予約時間の解析エラー: プリセット[{}] 時間[{}]", preset.getPresetName(), schedStr);
                }
            }
        }
    }
}