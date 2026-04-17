package com.example.fc2_live_automation.service;

import com.example.fc2_live_automation.model.Fc2Account;
import com.example.fc2_live_automation.model.Fc2Preset;
import com.example.fc2_live_automation.repository.Fc2AccountRepository;
import com.example.fc2_live_automation.repository.Fc2PresetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class AutomationScheduler {

    private static final Logger logger = LoggerFactory.getLogger(AutomationScheduler.class);

    private final Fc2AccountRepository accountRepository;
    private final Fc2PresetRepository presetRepository;
    private final Fc2AutomationWorker worker;

    public AutomationScheduler(Fc2AccountRepository accountRepository, 
                               Fc2PresetRepository presetRepository, 
                               Fc2AutomationWorker worker) {
        this.accountRepository = accountRepository;
        this.presetRepository = presetRepository;
        this.worker = worker;
    }

    // 🌟 1分ごと（60000ミリ秒）に自動で実行されるパトロールメソッド
    @Scheduled(fixedRate = 60000)
    public void checkScheduledTasks() {
        LocalDateTime now = LocalDateTime.now();

        // ==========================================
        // 1. プリセット（番組表）の予約チェック
        // ==========================================
        List<Fc2Preset> presets = presetRepository.findAll();
        for (Fc2Preset preset : presets) {
            String startTimeStr = preset.getScheduledStartTime();
            
            // 予約時間が設定されていて、かつIDLE（停止中）の場合
            if (startTimeStr != null && !startTimeStr.isEmpty() && "IDLE".equals(preset.getStatus())) {
                try {
                    LocalDateTime scheduledTime = LocalDateTime.parse(startTimeStr);
                    
                    // 現在時刻が、予約時間を過ぎていたら発動！
                    if (!now.isBefore(scheduledTime)) {
                        logger.info("⏰ 予約時間到達: プリセット [{}] を自動起動します！", preset.getPresetName());
                        
                        // 二重起動を防ぐため、予約時間を空（発動済み）にして保存
                        preset.setScheduledStartTime("");
                        preset.setStatus("RUNNING");
                        presetRepository.save(preset);

                        // プレイリストを取得してWorkerに渡す
                        List<Fc2Account> playlist = accountRepository.findAll().stream()
                                .filter(a -> preset.getPresetName().equals(a.getPresetName()))
                                .collect(Collectors.toList());

                        worker.startPresetProcess(preset, playlist);
                    }
                } catch (Exception e) {
                    logger.warn("⚠️ プリセット [{}] の予約時間パースエラー: {}", preset.getPresetName(), e.getMessage());
                }
            }
        }

        // ==========================================
        // 2. 個別アカウント（未設定）の予約チェック
        // ==========================================
        List<Fc2Account> accounts = accountRepository.findAll();
        for (Fc2Account acc : accounts) {
            String startTimeStr = acc.getScheduledStartTime();
            boolean isUnassigned = acc.getPresetName() == null || acc.getPresetName().isEmpty();

            // プリセットに属していない、かつ停止中で、予約時間がある場合
            if (isUnassigned && startTimeStr != null && !startTimeStr.isEmpty() && "IDLE".equals(acc.getStatus())) {
                try {
                    LocalDateTime scheduledTime = LocalDateTime.parse(startTimeStr);
                    
                    if (!now.isBefore(scheduledTime)) {
                        logger.info("⏰ 予約時間到達: 個別アカウント [{}] を自動起動します！", acc.getAccountName());
                        
                        // 二重起動防止
                        acc.setScheduledStartTime("");
                        accountRepository.save(acc);

                        worker.startStreamingProcess(acc);
                    }
                } catch (Exception e) {
                    logger.warn("⚠️ アカウント [{}] の予約時間パースエラー: {}", acc.getAccountName(), e.getMessage());
                }
            }
        }
    }
}