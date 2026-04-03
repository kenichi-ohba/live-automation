package com.example.fc2_live_automation.service;

import com.microsoft.playwright.*;
import com.example.fc2_live_automation.model.Fc2Account;
import com.example.fc2_live_automation.repository.Fc2AccountRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class Fc2AutomationWorker {

    private final Fc2AccountRepository repository;
    private final Map<Long, Process> activeProcesses = new ConcurrentHashMap<>();
    private final Map<Long, Browser> activeBrowsers = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> stopSignals = new ConcurrentHashMap<>();

    public Fc2AutomationWorker(Fc2AccountRepository repository) {
        this.repository = repository;
    }

    @Async
    public void startStreamingProcess(Fc2Account account) {
        Long id = account.getId();
        stopSignals.put(id, false); 

        int loopMax = account.getLoopCount() <= 0 ? 9999 : account.getLoopCount();
        int currentCycle = 0;

        while (!isStopped(id) && currentCycle < loopMax) {
            currentCycle++;
            account.addLog("🔄 【配信サイクル " + currentCycle + "回目 開始】");

            boolean cycleSuccess = false;
            int maxRetries = 3;

            for (int i = 1; i <= maxRetries; i++) {
                if (isStopped(id)) return;

                account.addLog("▶ ログイン・配信設定試行 (" + i + "/" + maxRetries + ")");
                try {
                    if (executePlaywrightProcess(account)) {
                        cycleSuccess = true;
                        break; 
                    }
                } catch (Exception e) {
                    String msg = e.getMessage();
                    if (msg != null && msg.contains("STOPPED")) {
                        account.addLog("⏹ 停止信号を検知。プロセスを中断しました。");
                        return;
                    }
                    String shortMsg = msg != null ? (msg.length() > 150 ? msg.substring(0, 150) + "..." : msg) : e.getClass().getName();
                    account.addLog("❌ 試行 " + i + " 失敗: " + shortMsg);
                    stopBrowsers(id);
                }

                if (i < maxRetries && !isStopped(id)) {
                    account.addLog("⏳ 5秒後に再試行します...");
                    try { Thread.sleep(5000); } catch (InterruptedException ignore) {}
                }
            }

            if (!cycleSuccess || isStopped(id)) break;

            if (!isStopped(id) && currentCycle < loopMax) {
                account.addLog("⏳ 次の配信サイクルまで10秒待機します...");
                try { Thread.sleep(10000); } catch (InterruptedException ignore) {}
            }
        }

        if (!isStopped(id)) {
            account.addLog("✅ 全ての配信ループ（" + currentCycle + "回）が完了しました。");
            Fc2Account finalState = repository.findById(id).orElse(account);
            finalState.setStatus("IDLE");
            repository.save(finalState);
        }
    }

    private boolean executePlaywrightProcess(Fc2Account account) throws Exception {
        Long id = account.getId();
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(!account.isShowBrowser()));
            activeBrowsers.put(id, browser);
            
            BrowserContext context = browser.newContext(new Browser.NewContextOptions().setPermissions(java.util.Arrays.asList("camera", "microphone", "notifications")));
            Page page = context.newPage();

            account.addLog("🌐 FC2トップページへアクセス...");
            checkStop(id);
            page.navigate("https://live.fc2.com/");
            page.waitForLoadState();
            
            checkStop(id);
            page.locator("a.c-btn.c-btnBs").first().click();
            page.waitForLoadState(); // 🌟 URL待機をやめ、ロード待機のみにする

            checkStop(id);
            page.locator("input[name='email']").fill(account.getEmail());
            page.locator("input[name='pass']").fill(account.getPass());
            if (page.locator("input[name='keep_login']").isVisible()) {
                page.locator("input[name='keep_login']").check();
            }
            page.locator("input[name='image']").click();

            page.waitForLoadState(); // 🌟 URL待機をやめる
            checkStop(id);
            if (page.locator("text='サービスへログインする'").isVisible()) {
                page.locator("text='サービスへログインする'").click();
                page.waitForLoadState();
            }
            account.addLog("✅ ログイン完了");

            account.addLog("⚙️ 設定画面へ移動します...");
            checkStop(id);
            page.navigate("https://live.fc2.com/live_start/"); // 🌟 ボタンクリックの代わりに直接設定画面へ飛ぶ
            page.waitForLoadState();
            
            checkStop(id);
            if (account.isUseThumbnail() && account.getThumbnailPath() != null && !account.getThumbnailPath().isEmpty()) {
                File imgFile = new File(account.getThumbnailPath());
                if (imgFile.exists()) {
                    account.addLog("🖼️ 番組画像をセットします...");
                    page.locator("#image").check(); 
                    page.setInputFiles("input[name='img_pathimage']", imgFile.toPath());
                    page.waitForTimeout(2000);
                } else {
                    page.locator("#image1").check();
                }
            } else {
                page.locator("#image1").check();
            }

            checkStop(id);
            String title = (account.getTitle() != null && !account.getTitle().isEmpty()) ? account.getTitle() : "自動配信テスト";
            String info = (account.getInfo() != null && !account.getInfo().isEmpty()) ? account.getInfo() : "配信中です";
            page.locator("#title").fill(title);
            page.locator("#info").fill(info);

            String categoryValue = (account.getCategory() == null || account.getCategory() == 0) ? "5" : String.valueOf(account.getCategory());
            page.locator("select.category").nth(1).selectOption(categoryValue);

            checkStop(id);
            int adultFlg = account.getAdultflg() != null ? account.getAdultflg() : 0;
            int loginOnlyFlg = account.getLoginonlyflg() != null ? account.getLoginonlyflg() : 0;
            int feeFlg = account.getFeeflg() != null ? account.getFeeflg() : 0;
            int feeSetting = account.getFeesetting() != null ? account.getFeesetting() : 0;

            page.locator("#adultflg" + adultFlg).check();
            page.locator("#loginonlyflg" + loginOnlyFlg).check();
            page.locator("#feeflg" + feeFlg).check();

            if (feeSetting == 1) {
                page.locator("#feesetting1").check();
                page.locator("#feeinterval").fill(String.valueOf(account.getFeeinterval() != null ? account.getFeeinterval() : 60));
                page.locator("#feeamount").fill(String.valueOf(account.getFeeamount() != null ? account.getFeeamount() : 80));
            } else {
                page.locator("#feesetting0").check();
            }

            checkStop(id);
            page.locator("#submit").click();
            page.waitForTimeout(2000); 
            
            checkStop(id);
            page.locator("text='利用規約に同意して配信する'").first().click();

            page.waitForLoadState(); // 🌟 URL待機をやめる
            String broadcastUrl = page.url();

            Fc2Account latest = repository.findById(id).orElse(account);
            latest.setBroadcastUrl(broadcastUrl);
            latest.setStatus("RUNNING");
            repository.save(latest);

            page.waitForTimeout(3000);
            checkStop(id);
            if (page.locator("#age_ok_btn").count() > 0 && page.locator("#age_ok_btn").isVisible()) {
                page.locator("#age_ok_btn").click();
                page.waitForTimeout(2000);
            }
            if (page.locator(".js-yesBtn").count() > 0 && page.locator(".js-yesBtn").first().isVisible()) {
                page.locator(".js-yesBtn").first().click();
                page.waitForTimeout(1000);
            }

            checkStop(id);
            page.locator(".js-toolStartBtn").evaluate("node => node.click()");
            page.waitForTimeout(3000);

            account.addLog("🎥 FFmpegを起動して動画の流し込みを開始します...");
            startFfmpegProcess(account);
            
            long switchTargetTime = -1;
            boolean hasSwitchedToPaid = false;

            if (feeFlg == 1) { 
                int m = account.getPaidSwitchMinute() != null ? account.getPaidSwitchMinute() : 0;
                int s = account.getPaidSwitchSecond() != null ? account.getPaidSwitchSecond() : 0;
                int totalSeconds = (m * 60) + s;
                
                if (totalSeconds > 0) {
                    switchTargetTime = System.currentTimeMillis() + (totalSeconds * 1000L);
                    account.addLog("⏳ 有料切り替えタイマーセット完了: " + m + "分" + s + "秒後");
                } else {
                    hasSwitchedToPaid = true;
                }
            } else {
                hasSwitchedToPaid = true; 
            }

            while (!isStopped(id) && activeProcesses.containsKey(id)) {
                if (!hasSwitchedToPaid && switchTargetTime > 0 && System.currentTimeMillis() >= switchTargetTime) {
                    try {
                        account.addLog("💰 時間が来ました。有料切り替え操作を開始します...");
                        
                        page.locator(".js-switchFeeBtn").first().click(new Locator.ClickOptions().setForce(true));
                        page.waitForTimeout(1500); 

                        account.addLog("🔘 確認画面の「はい」をクリックします...");
                        page.locator(".js-popupWindow .js-yesBtn:has-text('はい')").first().click(new Locator.ClickOptions().setForce(true));
                        page.waitForTimeout(2500); 

                        account.addLog("🔘 完了画面の「OK」をクリックします...");
                        page.locator(".js-popupWindow .js-yesBtn:has-text('OK')").first().click(new Locator.ClickOptions().setForce(true));
                        
                        account.addLog("✅ 有料への切り替えに完全に成功しました！");
                    } catch (Exception e) {
                        account.addLog("❌ 有料切り替え失敗: " + e.getMessage());
                    }
                    hasSwitchedToPaid = true;
                }
                Thread.sleep(2000);
            }
            
            account.addLog("🏁 配信終了（ブラウザを閉じます）。");
            browser.close();
            activeBrowsers.remove(id);
            return true;
        }
    }

    private void checkStop(Long id) throws Exception {
        if (Boolean.TRUE.equals(stopSignals.get(id))) throw new Exception("STOPPED");
    }

    private boolean isStopped(Long id) {
        return Boolean.TRUE.equals(stopSignals.get(id));
    }

    private void startFfmpegProcess(Fc2Account account) throws Exception {
        Long id = account.getId();
        String os = System.getProperty("os.name").toLowerCase();
        String ffmpegPath = Paths.get(System.getProperty("user.dir"), "bin", os.contains("win") ? "ffmpeg.exe" : "ffmpeg").toString();
        
        File videoFile = new File(account.getVideoPath());
        if (!videoFile.exists()) {
            account.addLog("❌ 致命的エラー: 動画ファイルが存在しません: " + account.getVideoPath());
            throw new Exception("Video file not found");
        }

        String rtmpUrl = account.getServerUrl().endsWith("/") ? account.getServerUrl() + account.getStreamKey() : account.getServerUrl() + "/" + account.getStreamKey();
        
        ProcessBuilder pb = new ProcessBuilder(
            ffmpegPath, "-re", "-i", account.getVideoPath(), "-c:v", "libx264", "-preset", "veryfast", "-b:v", "2000k", "-maxrate", "2000k", "-bufsize", "4000k", "-pix_fmt", "yuv420p", "-g", "60", "-c:a", "aac", "-b:a", "128k", "-ar", "44100", "-f", "flv", rtmpUrl
        );

        pb.redirectErrorStream(true);
        Process process = pb.start();
        activeProcesses.put(id, process);
        final long startTime = System.currentTimeMillis();

        new Thread(() -> {
            try (InputStreamReader isr = new InputStreamReader(process.getInputStream(), "Shift_JIS")) {
                StringBuilder sb = new StringBuilder();
                int c;
                long lastLogTime = 0;
                while ((c = isr.read()) != -1) {
                    if (c == '\r' || c == '\n') {
                        if (sb.length() > 0) {
                            String line = sb.toString();
                            sb.setLength(0);
                            
                            if (line.contains("time=")) {
                                if (System.currentTimeMillis() - lastLogTime > 5000) {
                                    Matcher m = Pattern.compile("time=(\\d{2}:\\d{2}:\\d{2})").matcher(line);
                                    if (m.find()) {
                                        account.addLog("⏱️ 動画再生中... " + m.group(1));
                                        lastLogTime = System.currentTimeMillis();
                                    }
                                }
                            } else if (!line.startsWith("frame=")) {
                                account.addLog("[FFmpeg] " + line);
                            }
                        }
                    } else {
                        sb.append((char) c);
                    }
                }
            } catch (Exception ignore) {}
        }).start();

        new Thread(() -> {
            try {
                int exitCode = process.waitFor();
                activeProcesses.remove(id);
                if (System.currentTimeMillis() - startTime < 10000) {
                    account.addLog("❌ 警告: FFmpegが数秒で異常終了しました。ストリームキーや動画パスを見直してください。");
                } else {
                    account.addLog("⏹ 動画の再生が完了しました。");
                }
            } catch (Exception e) {}
        }).start();
    }

    public void stopStreamingProcess(Long accountId) {
        stopSignals.put(accountId, true);
        Process p = activeProcesses.get(accountId);
        if (p != null) { p.destroyForcibly(); }
        activeProcesses.remove(accountId);
        stopBrowsers(accountId);

        Fc2Account account = repository.findById(accountId).orElse(null);
        if (account != null) {
            account.setStatus("IDLE");
            account.addLog("⏹ 強制停止命令により終了しました。");
            repository.save(account);
        }
    }

    private void stopBrowsers(Long id) {
        Browser b = activeBrowsers.get(id);
        if (b != null) { try { b.close(); } catch (Exception ignore) {} }
        activeBrowsers.remove(id);
    }
}