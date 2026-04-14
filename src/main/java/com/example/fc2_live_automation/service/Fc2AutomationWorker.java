package com.example.fc2_live_automation.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.example.fc2_live_automation.model.Fc2Account;
import com.example.fc2_live_automation.model.Fc2Preset;
import com.example.fc2_live_automation.repository.Fc2AccountRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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

    private final Map<Long, List<String>> memoryLogs = new ConcurrentHashMap<>();
    private final Map<Long, String> latestVideoTimes = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> streamReadyFlags = new ConcurrentHashMap<>();
    
    // プリセット管理用マップ
    private final Map<String, Thread> activePresetThreads = new ConcurrentHashMap<>();

    public Fc2AutomationWorker(Fc2AccountRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void cleanupOrphanProcesses() {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("taskkill", "/F", "/IM", "ffmpeg.exe", "/T").start().waitFor();
            }
        } catch (Exception ignore) {}
    }

    private void killProcessTree(Process p) {
        if (p == null) return;
        try {
            if (p.isAlive()) {
                String os = System.getProperty("os.name").toLowerCase();
                if (os.contains("win")) {
                    new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(p.pid())).start().waitFor();
                }
                p.destroyForcibly();
            }
        } catch (Exception e) {
            p.destroyForcibly();
        }
    }

    private String getSafeWindowsShortPath(String originalPath) {
        String os = System.getProperty("os.name").toLowerCase();
        if (!os.contains("win")) return originalPath; 
        
        try {
            Process p = new ProcessBuilder("cmd", "/c", "for %I in (\"" + originalPath + "\") do @echo %~sI").start();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), "Shift_JIS"))) {
                String shortPath = reader.readLine();
                if (shortPath != null && !shortPath.isEmpty()) {
                    return shortPath.trim();
                }
            }
        } catch (Exception e) {}
        return originalPath;
    }

    public void addLog(Long id, String message) {
        memoryLogs.putIfAbsent(id, new ArrayList<>());
        List<String> logs = memoryLogs.get(id);
        synchronized (logs) {
            if (logs.size() >= 100) logs.remove(0);
            logs.add(message);
        }
        System.out.println("[Account " + id + "] " + message);
    }

    public List<String> getLogs(Long id) {
        return memoryLogs.getOrDefault(id, new ArrayList<>());
    }

    @Async
    public void startStreamingProcess(Fc2Account account) {
        runStreamingLogic(account, false);
    }

    /**
     * コア配信ロジック
     */
    private void runStreamingLogic(Fc2Account account, boolean isPresetMode) {
        Long id = account.getId();
        stopSignals.put(id, false);
        memoryLogs.put(id, new ArrayList<>());

        // スレッドの停止フラグ（毒）をクリア
        Thread.interrupted();

        if (!isPresetMode) {
            String scheduledTimeStr = account.getScheduledStartTime();
            if (scheduledTimeStr != null && !scheduledTimeStr.isEmpty()) {
                try {
                    LocalDateTime scheduledTime = LocalDateTime.parse(scheduledTimeStr);
                    if (LocalDateTime.now().isBefore(scheduledTime)) {
                        addLog(id, "⏳ 【予約待機】 指定された日時まで待機します...");
                        while (!isStopped(id) && LocalDateTime.now().isBefore(scheduledTime)) {
                            try { Thread.sleep(5000); } catch (InterruptedException e) { handleInterrupt(isPresetMode); return; }
                        }
                        if (isStopped(id)) return;
                    }
                } catch (Exception e) {
                    addLog(id, "⚠️ 予約日時の形式が読み取れませんでした。即時開始します。");
                }
            }
        }

        int loopMax = isPresetMode ? 1 : (account.getLoopCount() <= 0 ? 9999 : account.getLoopCount());
        int currentCycle = 0;

        while (!isStopped(id) && currentCycle < loopMax) {
            if (isPresetMode && Thread.currentThread().isInterrupted()) return; 

            currentCycle++;
            Fc2Account loopState = repository.findById(id).orElse(account);
            loopState.setCurrentLoop(currentCycle);
            loopState.setStatus("RUNNING");
            repository.save(loopState);

            addLog(id, "🔄 【配信サイクル " + currentCycle + "回目 開始】");
            boolean cycleSuccess = false;
            int maxRetries = 3;

            for (int i = 1; i <= maxRetries; i++) {
                if (isStopped(id)) return;
                if (isPresetMode && Thread.currentThread().isInterrupted()) return;
                
                addLog(id, "▶ ログイン・配信設定試行 (" + i + "/" + maxRetries + ")");
                try {
                    if (executePlaywrightProcess(account)) {
                        cycleSuccess = true;
                        break;
                    }
                } catch (InterruptedException e) {
                    addLog(id, "⏹ 停止信号を受信しました。処理を完全に中断します。");
                    stopStreamingProcess(id); 
                    handleInterrupt(isPresetMode);
                    return; 
                } catch (Exception e) {
                    String msg = e.getMessage();
                    if (msg != null && msg.contains("STOPPED")) return;
                    addLog(id, "❌ 試行 " + i + " 失敗: " + e.getMessage());
                    stopBrowsers(id);
                }
                
                if (i < maxRetries && !isStopped(id)) {
                    addLog(id, "⏳ 5秒後に再試行します...");
                    try { 
                        Thread.sleep(5000); 
                    } catch (InterruptedException ie) { 
                        handleInterrupt(isPresetMode);
                        return; 
                    }
                }
            }

            if (!cycleSuccess || isStopped(id)) break;
            if (isPresetMode && Thread.currentThread().isInterrupted()) break;

            if (!isPresetMode && currentCycle < loopMax) {
                int waitMins = account.getLoopWaitMinutes() != null ? account.getLoopWaitMinutes() : 0;
                if (waitMins > 0) {
                    addLog(id, "☕ 【休憩】 次の配信まで " + waitMins + " 分間待機します...");
                    long waitMillis = waitMins * 60 * 1000L;
                    long startWait = System.currentTimeMillis();
                    while (!isStopped(id) && (System.currentTimeMillis() - startWait) < waitMillis) {
                        try { Thread.sleep(5000); } catch (InterruptedException e) { handleInterrupt(isPresetMode); return; }
                    }
                } else {
                    addLog(id, "⏳ 次の配信サイクルまで30秒待機します（サーバー切断待ち）...");
                    try { Thread.sleep(30000); } catch (InterruptedException e) { handleInterrupt(isPresetMode); return; }
                }
            }
        }

        if (!isStopped(id)) {
            addLog(id, "✅ 配信完了しました。");
            Fc2Account finalState = repository.findById(id).orElse(account);
            finalState.setStatus("IDLE");
            finalState.setCurrentLoop(0);
            repository.save(finalState);
        }
    }

    private void handleInterrupt(boolean isPresetMode) {
        if (isPresetMode) {
            Thread.currentThread().interrupt(); // プリセットモードの時だけフラグを伝達
        }
    }

    private boolean executePlaywrightProcess(Fc2Account account) throws Exception {
        Long id = account.getId();
        Browser browser = null;

        try (Playwright playwright = Playwright.create()) {
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(!account.isShowBrowser()));
            activeBrowsers.put(id, browser);

            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .setPermissions(java.util.Arrays.asList("camera", "microphone", "notifications")));
            Page page = context.newPage();
            page.onDialog(Dialog::accept);
            
            checkStop(id);
            page.navigate("https://live.fc2.com/");
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);

            checkStop(id);
            page.locator("a.c-btn.c-btnBs").first().click();
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);

            checkStop(id);
            page.locator("input[name='email']").fill(account.getEmail());
            page.locator("input[name='pass']").fill(account.getPass());
            if (page.locator("input[name='keep_login']").isVisible()) page.locator("input[name='keep_login']").check();
            page.locator("input[name='image']").click();

            page.waitForTimeout(4000);
            checkStop(id);
            if (page.locator("text='サービスへログインする'").isVisible()) {
                page.locator("text='サービスへログインする'").click();
                page.waitForTimeout(3000);
            }

            checkStop(id);
            page.navigate("https://live.fc2.com/live_start/");
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForTimeout(2000);

            if (!page.locator("#title").isVisible()) throw new Exception("設定画面が見つかりません");

            checkStop(id);
            if (account.isUseThumbnail() && account.getThumbnailPath() != null && !account.getThumbnailPath().isEmpty()) {
                File imgFile = new File(account.getThumbnailPath());
                if (imgFile.exists()) {
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
            page.locator("#title").fill((account.getTitle() != null && !account.getTitle().isEmpty()) ? account.getTitle() : "自動配信");
            page.locator("#info").fill((account.getInfo() != null && !account.getInfo().isEmpty()) ? account.getInfo() : "配信中です");
            page.locator("select.category").nth(1).selectOption((account.getCategory() == null || account.getCategory() == 0) ? "5" : String.valueOf(account.getCategory()));

            checkStop(id);
            page.locator("#adultflg" + (account.getAdultflg() != null ? account.getAdultflg() : 0)).check();
            page.locator("#loginonlyflg" + (account.getLoginonlyflg() != null ? account.getLoginonlyflg() : 0)).check();
            page.locator("#feeflg" + (account.getFeeflg() != null ? account.getFeeflg() : 0)).check();

            if (account.getFeesetting() != null && account.getFeesetting() == 1) {
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

            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            String broadcastUrl = page.url();

            Fc2Account latest = repository.findById(id).orElse(account);
            latest.setBroadcastUrl(broadcastUrl);
            repository.save(latest);

            page.waitForTimeout(3000);
            checkStop(id);
            if (page.locator("#age_ok_btn").isVisible()) { page.locator("#age_ok_btn").click(); page.waitForTimeout(2000); }
            if (page.locator(".js-yesBtn").first().isVisible()) { page.locator(".js-yesBtn").first().click(); page.waitForTimeout(1000); }

            streamReadyFlags.put(id, false);
            startFfmpegProcess(account);

            int waited = 0;
            while (!Boolean.TRUE.equals(streamReadyFlags.get(id)) && waited < 60) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { throw e; }
                waited++; checkStop(id);
            }

            if (page.locator(".js-toolStartBtn").count() > 0) {
                page.locator(".js-toolStartBtn").evaluate("node => node.click()");
                page.waitForTimeout(3000);
            }

            long switchTargetTime = -1;
            boolean hasSwitchedToPaid = false;
            
            int totalSeconds = ((account.getPaidSwitchMinute() != null ? account.getPaidSwitchMinute() : 0) * 60) 
                             + (account.getPaidSwitchSecond() != null ? account.getPaidSwitchSecond() : 0);
            
            int triggerSeconds = totalSeconds - (account.getSwitchLagSeconds() != null ? account.getSwitchLagSeconds() : 5);
            if (triggerSeconds < 0) triggerSeconds = 0;
            
            if (totalSeconds > 0) {
                switchTargetTime = System.currentTimeMillis() + (triggerSeconds * 1000L);
            } else {
                hasSwitchedToPaid = true; 
            }
            
            while (!isStopped(id) && activeProcesses.containsKey(id)) {
                checkStop(id);
                if (!hasSwitchedToPaid && switchTargetTime > 0 && System.currentTimeMillis() >= switchTargetTime) {
                    try {
                        if (page.locator(".js-switchFeeBtn").count() > 0) {
                            page.locator(".js-switchFeeBtn").first().evaluate("node => node.click()");
                        }
                        Locator yesBtn = page.locator(".js-popupWindow .js-yesBtn");
                        yesBtn.first().waitFor(new Locator.WaitForOptions().setTimeout(5000));
                        yesBtn.first().evaluate("node => node.click()");
                        page.waitForTimeout(1000); 
                        yesBtn.first().evaluate("node => node.click()");
                    } catch (Exception e) {}
                    hasSwitchedToPaid = true;
                }
                try { Thread.sleep(2000); } catch (InterruptedException e) { throw e; }
            }

            try {
                if (page.locator(".js-toolStopBtn").count() > 0) page.locator(".js-toolStopBtn").evaluate("node => node.click()");
                else if (page.locator("text='配信を終了する'").count() > 0) page.locator("text='配信を終了する'").first().evaluate("node => node.click()");
                page.waitForTimeout(3000);
            } catch (Exception e) {}

            return true;
        } catch (PlaywrightException pe) {
            throw new Exception("Playwrightエラー: " + pe.getMessage());
        } finally {
            if (browser != null) { try { browser.close(); } catch (Exception ignore) {} }
            activeBrowsers.remove(id);
        }
    }

    private void checkStop(Long id) throws InterruptedException {
        if (Boolean.TRUE.equals(stopSignals.get(id)) || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("STOPPED");
        }
    }

    private boolean isStopped(Long id) {
        return Boolean.TRUE.equals(stopSignals.get(id));
    }

    private void startFfmpegProcess(Fc2Account account) throws Exception {
        Long id = account.getId();
        Process oldProcess = activeProcesses.get(id);
        if (oldProcess != null && oldProcess.isAlive()) { killProcessTree(oldProcess); activeProcesses.remove(id); }

        String os = System.getProperty("os.name").toLowerCase();
        String ffmpegPath = Paths.get(System.getProperty("user.dir"), "bin", os.contains("win") ? "ffmpeg.exe" : "ffmpeg").toString();
        String cleanVideoPath = account.getVideoPath() != null ? account.getVideoPath().replaceAll("^\"|\"$", "").trim() : "";
        if (!new File(cleanVideoPath).exists()) throw new Exception("Video file not found");

        String safeVideoPath = getSafeWindowsShortPath(cleanVideoPath);
        String baseUrl = account.getServerUrl();
        String key = account.getStreamKey();
        String rtmpUrl = baseUrl.endsWith("/") ? baseUrl + key : baseUrl + "/" + key;
        
        ProcessBuilder pb = new ProcessBuilder(ffmpegPath, "-re", "-i", safeVideoPath, "-c:v", "libx264", "-preset", "veryfast", "-b:v", "2000k", "-maxrate", "2000k", "-bufsize", "4000k", "-pix_fmt", "yuv420p", "-g", "60", "-c:a", "aac", "-b:a", "128k", "-ar", "44100", "-f", "flv", rtmpUrl);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        activeProcesses.put(id, process);
        final long startTime = System.currentTimeMillis();

        new Thread(() -> {
            try (InputStreamReader isr = new InputStreamReader(process.getInputStream(), "Shift_JIS")) {
                StringBuilder sb = new StringBuilder(); int c;
                while ((c = isr.read()) != -1) {
                    if (c == '\r' || c == '\n') {
                        if (sb.length() > 0) {
                            String line = sb.toString(); sb.setLength(0);
                            if (line.contains("time=")) {
                                streamReadyFlags.put(id, true);
                                Matcher m = Pattern.compile("time=(\\d{2}:\\d{2}:\\d{2})").matcher(line);
                                if (m.find()) latestVideoTimes.put(id, m.group(1));
                            }
                        }
                    } else sb.append((char) c);
                }
            } catch (Exception ignore) {}
        }).start();

        new Thread(() -> {
            try {
                process.waitFor();
                if (activeProcesses.get(id) == process) activeProcesses.remove(id);
                if (System.currentTimeMillis() - startTime < 10000) addLog(id, "❌ 警告: FFmpegが異常終了しました。");
                else addLog(id, "⏹ 動画の再生が完了しました。");
            } catch (Exception e) {}
        }).start();
    }

    public void stopStreamingProcess(Long accountId) {
        stopSignals.put(accountId, true);
        Process p = activeProcesses.get(accountId);
        if (p != null) killProcessTree(p);
        activeProcesses.remove(accountId);
        stopBrowsers(accountId);

        Fc2Account account = repository.findById(accountId).orElse(null);
        if (account != null) {
            account.setStatus("IDLE");
            account.setCurrentLoop(0); 
            addLog(accountId, "⏹ 強制停止処理が完了しました。");
            repository.save(account);
        }
    }

    private void stopBrowsers(Long id) {
        Browser b = activeBrowsers.get(id);
        if (b != null) {
            try { b.close(); } catch (Exception ignore) {}
        }
        activeBrowsers.remove(id);
    }

    // ==========================================
    // 🌟 プリセット（番組表）連続再生ロジック
    // ==========================================
    
    public void startPresetProcess(Fc2Preset preset, List<Fc2Account> playlist) {
        String presetName = preset.getPresetName();

        if (activePresetThreads.containsKey(presetName)) {
            System.out.println("⚠️ プリセット [" + presetName + "] はすでに稼働中です。");
            return;
        }

        Thread presetThread = new Thread(() -> {
            try {
                System.out.println("▶️ プリセット [" + presetName + "] の連続再生を開始します！");
                
                // 🌟 修正ポイント1: int型のため、nullチェックは不要。そのまま代入。
                int maxLoop = preset.getLoopCount();
                int currentLoop = 1;

                while ((maxLoop == 0 || currentLoop <= maxLoop) && !Thread.currentThread().isInterrupted()) {
                    System.out.println("🔄 プリセット [" + presetName + "] - " + currentLoop + "周目を開始");

                    List<Fc2Account> currentPlaylist = new ArrayList<>(playlist);
                    if (preset.isShuffleMode()) {
                        java.util.Collections.shuffle(currentPlaylist);
                    }

                    for (Fc2Account account : currentPlaylist) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();

                        System.out.println("🎬 次の動画を開始: " + account.getAccountName());
                        
                        runStreamingLogic(account, true);
                    }

                    System.out.println("✅ プリセット [" + presetName + "] - " + currentLoop + "周目が完了");

                    // 🌟 修正ポイント2: Integer型のため、nullチェックが【必須】。
                    if (preset.getLoopWaitMinutes() != null && preset.getLoopWaitMinutes() > 0) {
                        System.out.println("⏳ " + preset.getLoopWaitMinutes() + "分間の休憩（待機）に入ります...");
                        Thread.sleep(preset.getLoopWaitMinutes() * 60 * 1000L);
                    }
                    
                    currentLoop++;
                }
                if (!Thread.currentThread().isInterrupted()) {
                    System.out.println("🎉 プリセット [" + presetName + "] の全ループが完了しました！");
                }

            } catch (InterruptedException e) {
                System.out.println("⏹ プリセット [" + presetName + "] が完全に停止されました。");
            } finally {
                activePresetThreads.remove(presetName);
            }
        });

        activePresetThreads.put(presetName, presetThread);
        presetThread.start();
    }

    public void stopPresetProcess(String presetName) {
        Thread thread = activePresetThreads.get(presetName);
        if (thread != null && thread.isAlive()) {
            System.out.println("🛑 プリセット [" + presetName + "] の停止シグナルを送信しました。");
            thread.interrupt(); 
        }
    }
}