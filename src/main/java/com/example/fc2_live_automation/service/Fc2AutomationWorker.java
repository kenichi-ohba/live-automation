package com.example.fc2_live_automation.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import com.example.fc2_live_automation.model.Fc2Account;
import com.example.fc2_live_automation.model.Fc2Preset;
import com.example.fc2_live_automation.repository.Fc2AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    // 🌟 プロ仕様のファイル出力対応ロガーを追加
    private static final Logger logger = LoggerFactory.getLogger(Fc2AutomationWorker.class);

    private final Fc2AccountRepository repository;
    private final Map<Long, Process> activeProcesses = new ConcurrentHashMap<>();
    private final Map<Long, Browser> activeBrowsers = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> stopSignals = new ConcurrentHashMap<>();

    private final Map<Long, List<String>> memoryLogs = new ConcurrentHashMap<>();
    private final Map<Long, String> latestVideoTimes = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> streamReadyFlags = new ConcurrentHashMap<>();
    
    private final Map<String, Thread> activePresetThreads = new ConcurrentHashMap<>();
    private final Map<Long, Thread> activeAccountThreads = new ConcurrentHashMap<>();

    public Fc2AutomationWorker(Fc2AccountRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void cleanupOrphanProcesses() {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                logger.info("🧹 起動時クリーンアップ: ゾンビ化したFFmpegプロセスを停止します");
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
        // 🌟 System.out.println から logger.info に変更（ファイルにも出力される）
        logger.info("[Account {}] {}", id, message);
    }

    public List<String> getLogs(Long id) {
        return memoryLogs.getOrDefault(id, new ArrayList<>());
    }

    @Async
    public void startStreamingProcess(Fc2Account account) {
        runStreamingLogic(account, false);
    }

    private boolean isStopRequested(Long id) {
        return Boolean.TRUE.equals(stopSignals.get(id)) || Thread.currentThread().isInterrupted();
    }

    private void runStreamingLogic(Fc2Account account, boolean isPresetMode) {
        Long id = account.getId();
        stopSignals.put(id, false);
        memoryLogs.put(id, new ArrayList<>());
        activeAccountThreads.put(id, Thread.currentThread());

        Thread.interrupted();

        try {
            if (!isPresetMode) {
                String scheduledTimeStr = account.getScheduledStartTime();
                if (scheduledTimeStr != null && !scheduledTimeStr.isEmpty()) {
                    try {
                        LocalDateTime scheduledTime = LocalDateTime.parse(scheduledTimeStr);
                        if (LocalDateTime.now().isBefore(scheduledTime)) {
                            addLog(id, "⏳ 【予約待機】 指定された日時まで待機します...");
                            while (!isStopRequested(id) && LocalDateTime.now().isBefore(scheduledTime)) {
                                try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                            }
                            if (isStopRequested(id)) return;
                        }
                    } catch (Exception e) {
                        addLog(id, "⚠️ 予約日時の形式が読み取れませんでした。即時開始します。");
                    }
                }
            }

            int loopMax = isPresetMode ? 1 : (account.getLoopCount() <= 0 ? 9999 : account.getLoopCount());
            int currentCycle = 0;

            while (!isStopRequested(id) && currentCycle < loopMax) {
                currentCycle++;
                Fc2Account loopState = repository.findById(id).orElse(account);
                loopState.setCurrentLoop(currentCycle);
                loopState.setStatus("RUNNING");
                repository.save(loopState);

                addLog(id, "🔄 【配信サイクル " + currentCycle + "回目 開始】");
                boolean cycleSuccess = false;
                int maxRetries = 3;

                for (int i = 1; i <= maxRetries; i++) {
                    if (isStopRequested(id)) return;
                    
                    addLog(id, "▶ ログイン・配信設定試行 (" + i + "/" + maxRetries + ")");
                    try {
                        if (executePlaywrightProcess(account)) {
                            cycleSuccess = true;
                            break;
                        }
                    } catch (Exception e) {
                        String msg = e.getMessage();
                        if (msg != null && msg.contains("STOPPED")) {
                            addLog(id, "⏹ 停止信号を検知。安全な終了処理へ移行します。");
                            return;
                        }
                        String shortMsg = msg != null ? (msg.length() > 150 ? msg.substring(0, 150) + "..." : msg) : e.getClass().getName();
                        addLog(id, "❌ 試行 " + i + " 失敗: " + shortMsg);
                        stopBrowsers(id);
                    }
                    
                    if (i < maxRetries && !isStopRequested(id)) {
                        addLog(id, "⏳ 5秒後に再試行します...");
                        try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    }
                }

                if (!cycleSuccess || isStopRequested(id)) break;

                if (!isPresetMode && currentCycle < loopMax) {
                    int waitMins = account.getLoopWaitMinutes() != null ? account.getLoopWaitMinutes() : 0;
                    if (waitMins > 0) {
                        addLog(id, "☕ 【休憩】 次の配信まで " + waitMins + " 分間待機します...");
                        long waitMillis = waitMins * 60 * 1000L;
                        long startWait = System.currentTimeMillis();
                        long lastLogTime = 0;
                        while (!isStopRequested(id) && (System.currentTimeMillis() - startWait) < waitMillis) {
                            long remainSec = (waitMillis - (System.currentTimeMillis() - startWait)) / 1000;
                            if (System.currentTimeMillis() - lastLogTime > 60000) { 
                                addLog(id, "⏳ 休憩中... 残り約 " + (remainSec / 60) + " 分");
                                lastLogTime = System.currentTimeMillis();
                            }
                            try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                        }
                    } else {
                        addLog(id, "⏳ 次の配信サイクルまで30秒待機します（サーバーの切断リセット待ち）...");
                        try { Thread.sleep(30000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    }
                }
            }

            if (!isStopRequested(id)) {
                addLog(id, "✅ 全ての配信ループ（" + currentCycle + "回）が完了しました。");
            }
        } finally {
            Fc2Account finalState = repository.findById(id).orElse(account);
            finalState.setStatus("IDLE");
            finalState.setCurrentLoop(0);
            repository.save(finalState);
            activeAccountThreads.remove(id);
            stopSignals.remove(id);
        }
    }

    private boolean executePlaywrightProcess(Fc2Account account) throws Exception {
        Long id = account.getId();
        Browser browser = null;

        try (Playwright playwright = Playwright.create()) {
            browser = playwright.chromium()
                    .launch(new BrowserType.LaunchOptions().setHeadless(!account.isShowBrowser()));
            activeBrowsers.put(id, browser);

            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                    .setPermissions(java.util.Arrays.asList("camera", "microphone", "notifications")));
            Page page = context.newPage();
            page.onDialog(dialog -> {
                addLog(id, "⚠️ ブラウザの確認ダイアログを検知しました: 自動で「OK」を押します");
                dialog.accept();
            });
            
            addLog(id, "🌐 FC2トップページへアクセス...");
            checkStopBeforeStream(id);
            page.navigate("https://live.fc2.com/");
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);

            checkStopBeforeStream(id);
            page.locator("a.c-btn.c-btnBs").first().click();
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);

            checkStopBeforeStream(id);
            page.locator("input[name='email']").fill(account.getEmail());
            page.locator("input[name='pass']").fill(account.getPass());
            if (page.locator("input[name='keep_login']").isVisible()) {
                page.locator("input[name='keep_login']").check();
            }
            page.locator("input[name='image']").click();

            page.waitForTimeout(4000);

            checkStopBeforeStream(id);
            if (page.locator("text='サービスへログインする'").isVisible()) {
                page.locator("text='サービスへログインする'").click();
                page.waitForTimeout(3000);
            }
            addLog(id, "✅ ログイン完了");

            addLog(id, "⚙️ 設定画面へ移動します...");
            checkStopBeforeStream(id);

            page.navigate("https://live.fc2.com/live_start/",
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.waitForTimeout(2000);

            if (!page.locator("#title").isVisible()) {
                throw new Exception("設定画面の要素が見つかりません");
            }

            checkStopBeforeStream(id);
            if (account.isUseThumbnail() && account.getThumbnailPath() != null
                    && !account.getThumbnailPath().isEmpty()) {
                File imgFile = new File(account.getThumbnailPath());
                if (imgFile.exists()) {
                    addLog(id, "🖼️ 番組画像をセットします...");
                    page.locator("#image").check();
                    page.setInputFiles("input[name='img_pathimage']", imgFile.toPath());
                    page.waitForTimeout(2000);
                } else {
                    page.locator("#image1").check();
                }
            } else {
                page.locator("#image1").check();
            }

            checkStopBeforeStream(id);
            String title = (account.getTitle() != null && !account.getTitle().isEmpty()) ? account.getTitle() : "自動配信";
            String info = (account.getInfo() != null && !account.getInfo().isEmpty()) ? account.getInfo() : "配信中です";
            page.locator("#title").fill(title);
            page.locator("#info").fill(info);

            String categoryValue = (account.getCategory() == null || account.getCategory() == 0) ? "5"
                    : String.valueOf(account.getCategory());
            page.locator("select.category").nth(1).selectOption(categoryValue);

            checkStopBeforeStream(id);
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

            checkStopBeforeStream(id);
            page.locator("#submit").click();
            page.waitForTimeout(2000);

            checkStopBeforeStream(id);
            page.locator("text='利用規約に同意して配信する'").first().click();

            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            String broadcastUrl = page.url();

            Fc2Account latest = repository.findById(id).orElse(account);
            latest.setBroadcastUrl(broadcastUrl);
            repository.save(latest);

            page.waitForTimeout(3000);
            checkStopBeforeStream(id);
            if (page.locator("#age_ok_btn").count() > 0 && page.locator("#age_ok_btn").isVisible()) {
                page.locator("#age_ok_btn").click();
                page.waitForTimeout(2000);
            }
            if (page.locator(".js-yesBtn").count() > 0 && page.locator(".js-yesBtn").first().isVisible()) {
                page.locator(".js-yesBtn").first().click();
                page.waitForTimeout(1000);
            }

            streamReadyFlags.put(id, false);
            addLog(id, "🎥 FFmpegを起動し、動画ファイルの解析・流し込みを開始します...");
            startFfmpegProcess(account);

            addLog(id, "⏳ 大容量ファイル対応: FFmpegからの映像パケット送信開始を待機しています...");
            int waited = 0;
            while (!isStopRequested(id) && !Boolean.TRUE.equals(streamReadyFlags.get(id)) && waited < 60) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                waited++;
            }

            if (!isStopRequested(id)) {
                if (waited >= 60) {
                    addLog(id, "⚠️ 60秒待機しましたが、映像の開始が確認できません。強制的に開始ボタンを押します。");
                } else {
                    addLog(id, "✅ FFmpegからの映像送信を確認しました！（待機: " + waited + "秒）");
                    page.waitForTimeout(2000);
                }

                addLog(id, "▶ FC2サーバー側で配信開始ボタンを押します...");
                if (page.locator(".js-toolStartBtn").count() > 0) {
                    page.locator(".js-toolStartBtn").evaluate("node => node.click()");
                    page.waitForTimeout(3000);
                }

                long switchTargetTime = -1;
                boolean hasSwitchedToPaid = false;
                long lastCountdownLog = 0;

                int m = account.getPaidSwitchMinute() != null ? account.getPaidSwitchMinute() : 0;
                int s = account.getPaidSwitchSecond() != null ? account.getPaidSwitchSecond() : 0;
                int totalSeconds = (m * 60) + s;
                
                int lagOffset = account.getSwitchLagSeconds() != null ? account.getSwitchLagSeconds() : 5;
                int triggerSeconds = totalSeconds - lagOffset;
                if (triggerSeconds < 0) { triggerSeconds = 0; }
                
                if (totalSeconds > 0) {
                    switchTargetTime = System.currentTimeMillis() + (triggerSeconds * 1000L);
                    addLog(id, "⏳ 自動切り替え設定: " + totalSeconds + "秒 (操作ラグ相殺のため " + triggerSeconds + "秒後にクリック開始)");
                } else {
                    hasSwitchedToPaid = true; 
                }
                
                while (!isStopRequested(id) && activeProcesses.containsKey(id)) {
                    if (!hasSwitchedToPaid && switchTargetTime > 0) {
                        long now = System.currentTimeMillis();
                        if (now - lastCountdownLog > 10000) {
                            long remain = (switchTargetTime - now) / 1000;
                            if (remain > 0) addLog(id, "⏱️ 切り替え待機中... 残り約 " + remain + " 秒");
                            lastCountdownLog = now;
                        }

                        if (now >= switchTargetTime) {
                            try {
                                String vTimeStart = latestVideoTimes.getOrDefault(id, "取得不可");
                                addLog(id, "💰 時間が来ました。切り替え操作を開始します... (動画再生時間: " + vTimeStart + ")");
                                
                                if (page.locator(".js-switchFeeBtn").count() > 0) {
                                    page.locator(".js-switchFeeBtn").first().evaluate("node => node.click()");
                                    addLog(id, "🔘 1/3: 切り替えボタンクリック");
                                }
                                
                                addLog(id, "🔘 2/3: 「はい」ボタン待機...");
                                Locator yesBtn = page.locator(".js-popupWindow .js-yesBtn");
                                yesBtn.first().waitFor(new Locator.WaitForOptions().setTimeout(5000));
                                yesBtn.first().evaluate("node => node.click()");
                                
                                addLog(id, "🔘 3/3: 「OK」ボタン待機...");
                                Locator okBtn = page.locator(".js-popupWindow .js-yesBtn");
                                page.waitForTimeout(1000); 
                                okBtn.first().evaluate("node => node.click()");
                                
                                String vTimeEnd = latestVideoTimes.getOrDefault(id, "取得不可");
                                addLog(id, "✅ 切り替え操作完了 (動画再生時間: " + vTimeEnd + ")");
                            } catch (Exception e) {
                                addLog(id, "❌ 切り替え処理中にエラー発生: " + e.getMessage());
                            }
                            hasSwitchedToPaid = true;
                        }
                    }
                    try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            }

            if (!activeProcesses.containsKey(id)) {
                addLog(id, "⚠️ 動画の再生が終了した（FFmpegが停止した）ため、監視ループを抜けました。");
            }

            try {
                addLog(id, "⏹ FC2サーバーのセッションを安全に切断します...");
                if (page.locator(".js-toolStopBtn").count() > 0) {
                    page.locator(".js-toolStopBtn").evaluate("node => node.click()");
                } else if (page.locator("text='配信を終了する'").count() > 0) {
                    page.locator("text='配信を終了する'").first().evaluate("node => node.click()");
                } else if (page.locator("text='配信終了'").count() > 0) {
                    page.locator("text='配信終了'").first().evaluate("node => node.click()");
                }
                page.waitForTimeout(3000);
            } catch (Exception e) { }

            Process p = activeProcesses.get(id);
            if (p != null) killProcessTree(p);
            activeProcesses.remove(id);

            addLog(id, "🏁 配信枠を安全に閉じました。");
            return true;

        } catch (PlaywrightException pe) {
            throw new Exception("Playwrightエラー: " + pe.getMessage());
        } finally {
            if (browser != null) { try { browser.close(); } catch (Exception ignore) {} }
            activeBrowsers.remove(id);
        }
    }

    private void checkStopBeforeStream(Long id) throws Exception {
        if (isStopRequested(id)) {
            throw new Exception("STOPPED");
        }
    }


    private void startFfmpegProcess(Fc2Account account) throws Exception {
        Long id = account.getId();

        Process oldProcess = activeProcesses.get(id);
        if (oldProcess != null && oldProcess.isAlive()) {
            killProcessTree(oldProcess);
            activeProcesses.remove(id);
        }

        String os = System.getProperty("os.name").toLowerCase();
        String ffmpegPath = Paths
                .get(System.getProperty("user.dir"), "bin", os.contains("win") ? "ffmpeg.exe" : "ffmpeg").toString();

        String cleanVideoPath = account.getVideoPath() != null ? account.getVideoPath().replaceAll("^\"|\"$", "").trim()
                : "";

        File videoFile = new File(cleanVideoPath);
        if (!videoFile.exists()) {
            addLog(id, "❌ 致命的エラー: 動画ファイルが存在しません: " + cleanVideoPath);
            throw new Exception("Video file not found");
        }

        String safeVideoPath = getSafeWindowsShortPath(cleanVideoPath);

        String baseUrl = account.getServerUrl();
        String key = account.getStreamKey();
        String rtmpUrl = baseUrl.endsWith("/") ? baseUrl + key : baseUrl + "/" + key;
        
        ProcessBuilder pb = new ProcessBuilder(
                ffmpegPath, "-re", "-i", safeVideoPath, "-c:v", "libx264", "-preset", "veryfast", "-b:v", "2000k",
                "-maxrate", "2000k", "-bufsize", "4000k", "-pix_fmt", "yuv420p", "-g", "60", "-c:a", "aac", "-b:a",
                "128k", "-ar", "44100", "-f", "flv", rtmpUrl);

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
                                streamReadyFlags.put(id, true);

                                Matcher m = Pattern.compile("time=(\\d{2}:\\d{2}:\\d{2})").matcher(line);
                                if (m.find()) {
                                    String currentTime = m.group(1);
                                    latestVideoTimes.put(id, currentTime);

                                    if (System.currentTimeMillis() - lastLogTime > 5000) {
                                        addLog(id, "⏱️ 動画再生中... " + currentTime);
                                        lastLogTime = System.currentTimeMillis();
                                    }
                                }
                            } else if (!line.startsWith("frame=")) {
                                // FFmpegのログも logger.info で出力
                                logger.info("[FFmpeg Account {}] {}", id, line);
                            }
                        }
                    } else {
                        sb.append((char) c);
                    }
                }
            } catch (Exception ignore) {
            }
        }).start();

        new Thread(() -> {
            try {
                process.waitFor();
                
                if (activeProcesses.get(id) == process) {
                    activeProcesses.remove(id);
                }
                
                if (System.currentTimeMillis() - startTime < 10000) {
                    addLog(id, "❌ 警告: FFmpegが数秒で異常終了しました。ストリームキーや動画パスを見直してください。");
                } else {
                    addLog(id, "⏹ 動画の再生が完了しました。");
                }
            } catch (Exception e) {
            }
        }).start();
    }

    public void stopStreamingProcess(Long accountId) {
        addLog(accountId, "🛑 停止ボタンが押されました。安全な切断処理を開始します...");
        stopSignals.put(accountId, true);
        
        Thread t = activeAccountThreads.get(accountId);
        if (t != null) {
            t.interrupt(); 
        } else {
            Process p = activeProcesses.get(accountId);
            if (p != null) killProcessTree(p);
            activeProcesses.remove(accountId);
            stopBrowsers(accountId);

            Fc2Account account = repository.findById(accountId).orElse(null);
            if (account != null) {
                account.setStatus("IDLE");
                account.setCurrentLoop(0); 
                repository.save(account);
            }
        }
    }

    private void stopBrowsers(Long id) {
        Browser b = activeBrowsers.get(id);
        if (b != null) {
            try {
                b.close();
            } catch (Exception ignore) {
            }
        }
        activeBrowsers.remove(id);
    }

    // ==========================================
    // 🌟 プリセット（番組表）連続再生ロジック
    // ==========================================
    
    public void startPresetProcess(Fc2Preset preset, List<Fc2Account> playlist) {
        String presetName = preset.getPresetName();

        if (activePresetThreads.containsKey(presetName)) {
            logger.warn("⚠️ プリセット [{}] はすでに稼働中です。", presetName);
            return;
        }

        Thread presetThread = new Thread(() -> {
            try {
                logger.info("▶️ プリセット [{}] の連続再生を開始します！", presetName);
                
                int maxLoop = preset.getLoopCount();
                int currentLoop = 1;

                while ((maxLoop == 0 || currentLoop <= maxLoop) && !Thread.currentThread().isInterrupted()) {
                    logger.info("🔄 プリセット [{}] - {}周目を開始", presetName, currentLoop);

                    List<Fc2Account> currentPlaylist = new ArrayList<>(playlist);
                    if (preset.isShuffleMode()) {
                        java.util.Collections.shuffle(currentPlaylist);
                    }

                    for (Fc2Account account : currentPlaylist) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();

                        logger.info("🎬 次の動画を開始: {}", account.getAccountName());
                        
                        runStreamingLogic(account, true);
                    }

                    logger.info("✅ プリセット [{}] - {}周目が完了", presetName, currentLoop);

                    if (preset.getLoopWaitMinutes() != null && preset.getLoopWaitMinutes() > 0) {
                        logger.info("⏳ {}分間の休憩（待機）に入ります...", preset.getLoopWaitMinutes());
                        Thread.sleep(preset.getLoopWaitMinutes() * 60 * 1000L);
                    }
                    
                    currentLoop++;
                }
                if (!Thread.currentThread().isInterrupted()) {
                    logger.info("🎉 プリセット [{}] の全ループが完了しました！", presetName);
                }

            } catch (InterruptedException e) {
                logger.info("⏹ プリセット [{}] が安全に停止されました。", presetName);
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
            logger.info("🛑 プリセット [{}] の停止シグナルを送信しました。", presetName);
            thread.interrupt(); 
        }
    }
}