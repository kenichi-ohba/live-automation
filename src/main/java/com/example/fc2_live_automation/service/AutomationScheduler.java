package com.example.fc2_live_automation.service;

import com.example.fc2_live_automation.model.Fc2Account;
import com.example.fc2_live_automation.model.Fc2Preset;
import com.example.fc2_live_automation.repository.Fc2AccountRepository;
import com.example.fc2_live_automation.repository.Fc2PresetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.List;

@Service
public class AutomationScheduler {

    private static final Logger logger = LoggerFactory.getLogger(AutomationScheduler.class);

    private final Fc2AccountRepository accountRepository;
    private final Fc2PresetRepository presetRepository;

    public AutomationScheduler(Fc2AccountRepository accountRepository, Fc2PresetRepository presetRepository) {
        this.accountRepository = accountRepository;
        this.presetRepository = presetRepository;
    }

    /**
     * アプリ起動時に実行されるクリーンアップ処理
     * 異常終了などで DB に残った「RUNNING」状態を解除します
     */
    @PostConstruct
    public void resetStatusOnStartup() {
        logger.info("⚙️ 起動時クリーンアップ: データベース上の「配信中(RUNNING)」ステータスをリセットします");

        // 1. アカウントのリセット
        List<Fc2Account> runningAccounts = accountRepository.findAll().stream()
                .filter(a -> "RUNNING".equals(a.getStatus()))
                .toList();
        for (Fc2Account account : runningAccounts) {
            account.setStatus("IDLE");
            account.setCurrentLoop(0);
            accountRepository.save(account);
        }

        // 2. プリセットのリセット
        List<Fc2Preset> runningPresets = presetRepository.findAll().stream()
                .filter(p -> "RUNNING".equals(p.getStatus()))
                .toList();
        for (Fc2Preset preset : runningPresets) {
            preset.setStatus("IDLE");
            // 🌟 モデルに定義した setCurrentLoop が正常に呼び出せるようになります
            preset.setCurrentLoop(0);
            presetRepository.save(preset);
        }

        logger.info("✅ クリーンアップ完了: プリセット {} 件、アカウント {} 件を停止状態に戻しました", 
                    runningPresets.size(), runningAccounts.size());
    }
}