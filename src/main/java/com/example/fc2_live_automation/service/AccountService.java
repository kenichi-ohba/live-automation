package com.example.fc2_live_automation.service;

import com.example.fc2_live_automation.model.Fc2Account;
import com.example.fc2_live_automation.repository.Fc2AccountRepository;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;

@Service
public class AccountService {

    private final Fc2AccountRepository repository;
    private final Fc2AutomationWorker worker;

    public AccountService(Fc2AccountRepository repository, Fc2AutomationWorker worker) {
        this.repository = repository;
        this.worker = worker;
    }

    // 🌟 修正ポイント：DBから取り出した直後に、全員分の時間をフォーマットする
    public List<Fc2Account> getAllAccounts() {
        List<Fc2Account> accounts = repository.findAll();
        for (Fc2Account account : accounts) {
            formatDisplayData(account); // 一人ずつ計算してセット
        }
        return accounts;
    }

    public Fc2Account getAccountById(Long id) {
        Fc2Account account = repository.findById(id).orElseThrow(() -> new RuntimeException("Account not found"));
        formatDisplayData(account);
        return account;
    }

    public void saveAccount(Fc2Account account) {
        if (account.getStatus() == null || account.getStatus().isEmpty()) {
            account.setStatus("IDLE");
        }
        if (account.getCurrentLoop() == null) {
            account.setCurrentLoop(0);
        }
        if (account.getAccountName() == null || account.getAccountName().trim().isEmpty()) {
            account.setAccountName(account.getTitle() != null ? account.getTitle() : "無題のアカウント");
        }

        // 動画の解析のみ実行
        account.setVideoDuration(analyzeVideo(account.getVideoPath()));
        repository.save(account);
    }

    public void deleteAccount(Long id) {
        worker.stopStreamingProcess(id);
        repository.deleteById(id);
    }

    public void startStreaming(Long id) {
        Fc2Account account = getAccountById(id);
        worker.startStreamingProcess(account);
    }

    public void stopStreaming(Long id) {
        worker.stopStreamingProcess(id);
    }

    // ==========================================
    // 💡 データベースの値を読み取って「〇時間〇分〇秒」のテキストを作る魔法
    // ==========================================
    private void formatDisplayData(Fc2Account account) {
        int mins = (account.getPaidSwitchMinute() != null) ? account.getPaidSwitchMinute() : 0;
        int secs = (account.getPaidSwitchSecond() != null) ? account.getPaidSwitchSecond() : 0;
        int totalSeconds = (mins * 60) + secs;
        
        if (totalSeconds <= 0) {
            account.setFormattedPaidSwitchTime("0秒 (即時)");
        } else {
            int h = totalSeconds / 3600;
            int m = (totalSeconds % 3600) / 60;
            int s = totalSeconds % 60;
            if (h > 0) {
                account.setFormattedPaidSwitchTime(String.format("%d時間%02d分%02d秒", h, m, s));
            } else {
                account.setFormattedPaidSwitchTime(String.format("%d分%02d秒", m, s));
            }
        }
    }

    // ==========================================
    // ffprobe 動画解析
    // ==========================================
    private String analyzeVideo(String videoPath) {
        if (videoPath == null || videoPath.trim().isEmpty()) return "未設定";
        try {
            String os = System.getProperty("os.name").toLowerCase();
            String ffprobePath = Paths.get(System.getProperty("user.dir"), "bin", os.contains("win") ? "ffprobe.exe" : "ffprobe").toString();
            String cleanPath = videoPath.replaceAll("^\"|\"$", "").trim();

            if (!new File(cleanPath).exists()) return "ファイルなし";

            ProcessBuilder pbDur = new ProcessBuilder(ffprobePath, "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", cleanPath);
            Process pDur = pbDur.start();
            String durationStr = "解析失敗";
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(pDur.getInputStream()))) {
                String line = br.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    double totalSeconds = Double.parseDouble(line.trim());
                    int h = (int) (totalSeconds / 3600);
                    int m = (int) ((totalSeconds % 3600) / 60);
                    int s = (int) (totalSeconds % 60);
                    durationStr = (h > 0) ? String.format("%d時間%02d分%02d秒", h, m, s) : String.format("%d分%02d秒", m, s);
                }
            }

            ProcessBuilder pbAud = new ProcessBuilder(ffprobePath, "-v", "error", "-select_streams", "a", "-show_entries", "stream=codec_type", "-of", "default=noprint_wrappers=1:nokey=1", cleanPath);
            Process pAud = pbAud.start();
            boolean hasAudio = false;
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(pAud.getInputStream()))) {
                if (br.readLine() != null) hasAudio = true;
            }
            return hasAudio ? durationStr : durationStr + " ⚠️音声なし";

        } catch (Exception e) {
            return "解析エラー";
        }
    }
}