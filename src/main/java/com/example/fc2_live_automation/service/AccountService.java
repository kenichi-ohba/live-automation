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

    public List<Fc2Account> getAllAccounts() {
        return repository.findAll();
    }

    public Fc2Account getAccountById(Long id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("Account not found"));
    }

    public void saveAccount(Fc2Account account) {
        
        // 編集時にパスワードやストリームキーが空欄なら、元のDBデータを維持して上書きを防ぐ
        if (account.getId() != null) {
            Fc2Account existing = repository.findById(account.getId()).orElse(null);
            if (existing != null) {
                if (account.getPass() == null || account.getPass().isBlank()) {
                    account.setPass(existing.getPass());
                }
                if (account.getStreamKey() == null || account.getStreamKey().isBlank()) {
                    account.setStreamKey(existing.getStreamKey());
                }
                if (account.getEmail() == null || account.getEmail().isBlank()) {
                    account.setEmail(existing.getEmail());
                }
            }
        }

        if (account.getStatus() == null || account.getStatus().isEmpty()) {
            account.setStatus("IDLE");
        }
        if (account.getCurrentLoop() == null) {
            account.setCurrentLoop(0);
        }

        // 🌟 修正：アカウント名が「空欄だった場合」のみ、自動設定を行う（上書きバグ解消）
        if (account.getAccountName() == null || account.getAccountName().trim().isEmpty()) {
            if (account.getTitle() != null && !account.getTitle().trim().isEmpty()) {
                account.setAccountName(account.getTitle());
            } else {
                long currentCount = repository.count();
                account.setAccountName("アカウント" + (currentCount + 1));
            }
        }

        // 動画の解析を実行
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

    // 🌟 追加：コントローラーから呼び出される「システム全終了」メソッド
    public void stopAll() {
        worker.stopAll();
    }

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