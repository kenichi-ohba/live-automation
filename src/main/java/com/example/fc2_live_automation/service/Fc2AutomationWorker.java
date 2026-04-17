package com.example.fc2_live_automation.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
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
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class Fc2AutomationWorker {

    private static final Logger logger = LoggerFactory.getLogger(Fc2AutomationWorker.class);

    private final Fc2AccountRepository repository;
    private final Map<Long, Process> activeProcesses = new ConcurrentHashMap<>();
    private final Map<Long, Browser> activeBrowsers = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> stopSignals = new ConcurrentHashMap<>();
    private final Map<String, Boolean> presetStopSignals = new ConcurrentHashMap<>();

    private final Map<Long, List<String>> memoryLogs = new ConcurrentHashMap<>();
    private final Map<Long, String> latestVideoTimes = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> streamReadyFlags = new ConcurrentHashMap<>();
    
    private final Map<String, Thread> activePresetThreads = new ConcurrentHashMap<>();
    private final Map<Long, Thread> activeAccountThreads = new ConcurrentHashMap<>();
    private final Map<String, List<String>> presetLogs = new ConcurrentHashMap<>();

    public Fc2AutomationWorker(Fc2AccountRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void cleanupOrphanProcesses() {
        try {
            var os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("taskkill", "/F", "/IM", "ffmpeg.exe", "/T").start().waitFor();
            }
        } catch (Exception ignore) {}
    }

    private void killProcessTree(Process p) {
        if (p == null) return;
        try {
            if (p.isAlive()) {
                var os = System.getProperty("os.name").toLowerCase();
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
        var os = System.getProperty("os.name").toLowerCase();
        if (!os.contains("win")) return originalPath; 
        try {
            var p = new ProcessBuilder("cmd", "/c", "for %I in (\"" + originalPath + "\") do @echo %~sI").start();
            try (var reader = new java.io.BufferedReader(new InputStreamReader(p.getInputStream(), Charset.forName("Shift_JIS")))) {
                var shortPath = reader.readLine();
                if (shortPath != null && !shortPath.isBlank()) return shortPath.trim();
            }
        } catch (Exception e) {}
        return originalPath;
    }

    public void addLog(Long id, String message) {
        memoryLogs.putIfAbsent(id, new ArrayList<>());
        var logs = memoryLogs.get(id);
        synchronized (logs) {
            if (logs.size() >= 100) logs.removeFirst(); // Java 21+ syntax
            logs.add(message);
        }
        logger.info("[Account {}] {}", id, message);
    }

    public void addPresetLog(String presetName, String message) {
        if (presetName == null || presetName.isBlank()) return;
        presetLogs.putIfAbsent(presetName, new ArrayList<>());
        var logs = presetLogs.get(presetName);
        synchronized (logs) {
            if (logs.size() >= 50) logs.removeFirst(); // Java 21+ syntax
            logs.add(message);
        }
        logger.info("[Preset {}] {}", presetName, message);
    }

    public List<String> getPresetLogs(String presetName) {
        return presetLogs.getOrDefault(presetName, new ArrayList<>());
    }

    @Async
    public void startStreamingProcess(Fc2Account account) {
        runStreamingLogic(account, false, "");
    }

    private boolean isStopRequested(Long id, String presetName) {
        if (Boolean.TRUE.equals(stopSignals.get(id))) return true;
        if (!presetName.isBlank() && Boolean.TRUE.equals(presetStopSignals.get(presetName))) return true;
        return Thread.currentThread().isInterrupted();
    }

    private void checkStop(Long id, String presetName) throws InterruptedException {
        if (isStopRequested(id, presetName)) throw new InterruptedException("STOPPED");
    }

    // =========================================================================
    // 🌟 原点回帰：JavaScriptによる画面要素の強制クリック（一番確実な突破方法）
    // =========================================================================
    private void forceClickBySelector(Page page, String selector) {
        try {
            var js = "document.querySelectorAll('" + selector + "').forEach(el => { if(el.offsetParent !== null) el.click(); });";
            page.evaluate(js);
        } catch (Exception ignore) {}
    }

    private void forceClickByText(Page page, String text) {
        try {
            var js = "Array.from(document.querySelectorAll('a, button, span, div')).filter(el => el.textContent.includes('" + text + "')).forEach(btn => { if (btn.offsetParent !== null) btn.click(); });";
            page.evaluate(js);
        } catch (Exception ignore) {}
    }
    // =========================================================================

    private void runStreamingLogic(Fc2Account account, boolean isPresetMode, String presetName) {
        var id = account.getId();
        stopSignals.put(id, false);
        activeAccountThreads.put(id, Thread.currentThread());

        try {
            if (!isPresetMode) {
                var scheduledTimeStr = account.getScheduledStartTime();
                if (scheduledTimeStr != null && !scheduledTimeStr.isBlank()) {
                    try {
                        var scheduledTime = LocalDateTime.parse(scheduledTimeStr);
                        while (LocalDateTime.now().isBefore(scheduledTime)) {
                            checkStop(id, "");
                            Thread.sleep(2000);
                        }
                    } catch (DateTimeParseException e) {
                        addLog(id, "⚠️ 予約時間の形式エラーのため即時開始します。");
                    }
                }
            }

            int loopMax = isPresetMode ? 1 : (account.getLoopCount() <= 0 ? 9999 : account.getLoopCount());
            int currentCycle = 0;

            while (currentCycle < loopMax) {
                checkStop(id, presetName);

                currentCycle++;
                var loopState = repository.findById(id).orElse(account);
                loopState.setCurrentLoop(currentCycle);
                loopState.setStatus("RUNNING");
                repository.save(loopState);

                var accName = (account.getAccountName() != null && !account.getAccountName().isBlank()) ? account.getAccountName() : "名称未設定";
                if (!presetName.isBlank()) addPresetLog(presetName, "🎬 [" + accName + "] の配信準備を開始します");
                
                boolean cycleSuccess = false;

                for (int i = 1; i <= 3; i++) {
                    checkStop(id, presetName);
                    try {
                        if (executePlaywrightProcess(account, presetName)) {
                            cycleSuccess = true;
                            break;
                        }
                    } catch (InterruptedException e) {
                        throw e;
                    } catch (Exception e) {
                        var msg = e.getMessage();
                        if (msg != null && (msg.contains("STOPPED") || msg.contains("TargetClosedError"))) {
                            throw new InterruptedException();
                        }
                        if (!presetName.isBlank()) addPresetLog(presetName, "❌ [" + accName + "] 試行失敗: " + msg);
                        stopBrowsers(id);
                    }
                    if (i < 3) {
                        checkStop(id, presetName);
                        Thread.sleep(5000);
                    }
                }

                if (!cycleSuccess) break;

                if (!isPresetMode && currentCycle < loopMax) {
                    int waitMins = account.getLoopWaitMinutes() != null ? account.getLoopWaitMinutes() : 0;
                    long waitEnd = System.currentTimeMillis() + (waitMins * 60 * 1000L);
                    while (System.currentTimeMillis() < waitEnd) {
                        checkStop(id, "");
                        Thread.sleep(5000);
                    }
                }
            }
        } catch (InterruptedException e) {
            var accName = (account.getAccountName() != null && !account.getAccountName().isBlank()) ? account.getAccountName() : "名称未設定";
            if (!presetName.isBlank()) addPresetLog(presetName, "⏹ [" + accName + "] の処理を安全に中断しました");
        } finally {
            var finalState = repository.findById(id).orElse(account);
            finalState.setStatus("IDLE");
            finalState.setCurrentLoop(0);
            repository.save(finalState);
            activeAccountThreads.remove(id);
            stopSignals.remove(id);
        }
    }

    private boolean executePlaywrightProcess(Fc2Account account, String presetName) throws Exception {
        var id = account.getId();
        Browser browser = null;
        var accName = (account.getAccountName() != null && !account.getAccountName().isBlank()) ? account.getAccountName() : "名称未設定";

        try (var playwright = Playwright.create()) {
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(!account.isShowBrowser()));
            activeBrowsers.put(id, browser);

            var context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                    .setPermissions(List.of("camera", "microphone", "notifications"))); // Java 9+ syntax
            
            var page = context.newPage();
            
            checkStop(id, presetName);
            page.navigate("https://live.fc2.com/");
            page.locator("a.c-btn.c-btnBs").first().click();
            
            checkStop(id, presetName);
            page.locator("input[name='email']").fill(account.getEmail());
            page.locator("input[name='pass']").fill(account.getPass());
            page.locator("input[name='image']").click();
            
            page.waitForTimeout(3000);
            if (page.locator("text='サービスへログインする'").isVisible()) {
                page.locator("text='サービスへログインする'").click();
            }

            if (!presetName.isBlank()) addPresetLog(presetName, "✅ [" + accName + "] ログイン完了、設定画面へ移行");

            checkStop(id, presetName);
            page.navigate("https://live.fc2.com/live_start/");
            page.locator("#title").waitFor();

            checkStop(id, presetName);
            page.locator("#title").fill(account.getTitle() != null && !account.getTitle().isBlank() ? account.getTitle() : "自動配信");
            page.locator("#info").fill(account.getInfo() != null ? account.getInfo() : "");
            
            var categoryValue = (account.getCategory() == null || account.getCategory() == 0) ? "5" : String.valueOf(account.getCategory());
            page.locator("select.category").nth(1).selectOption(categoryValue);

            int adultFlg = account.getAdultflg() != null ? account.getAdultflg() : 0;
            page.locator("#adultflg" + adultFlg).check();
            page.locator("#loginonlyflg" + (account.getLoginonlyflg() != null ? account.getLoginonlyflg() : 0)).check(); 
            page.locator("#feeflg" + (account.getFeeflg() != null ? account.getFeeflg() : 0)).check();

            if (account.getFeesetting() != null && account.getFeesetting() == 1) {
                page.locator("#feesetting1").check();
                page.locator("#feeinterval").fill(String.valueOf(account.getFeeinterval()));
                page.locator("#feeamount").fill(String.valueOf(account.getFeeamount()));
            }

            checkStop(id, presetName);
            page.locator("#submit").click();
            
            page.waitForTimeout(2000); // 画面遷移・モーダル出現を確実に待つ

            // 🌟 復活：初期の「最強突破」ロジック（JSでの強制クリック）
            forceClickBySelector(page, ".js-popupWindow .js-yesBtn"); // 画像なし警告
            page.waitForTimeout(1000);
            
            forceClickByText(page, "利用規約に同意して配信する"); // 規約
            page.waitForTimeout(1000);

            forceClickBySelector(page, "#age_ok_btn"); // 18歳以上（アダルト時）
            
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForTimeout(3000); 

            var broadcastUrl = page.url();
            var latest = repository.findById(id).orElse(account);
            latest.setBroadcastUrl(broadcastUrl);
            repository.save(latest);

            streamReadyFlags.put(id, false);
            startFfmpegProcess(account);

            if (!presetName.isBlank()) addPresetLog(presetName, "🎥 [" + accName + "] FFmpegによる映像送信を開始しました");

            int waited = 0;
            while (!Boolean.TRUE.equals(streamReadyFlags.get(id)) && waited < 60) {
                checkStop(id, presetName);
                Thread.sleep(1000);
                waited++;
            }

            // ツール配信開始ボタンも強制クリック
            forceClickBySelector(page, ".js-toolStartBtn");

            int totalSeconds = ((account.getPaidSwitchMinute() != null ? account.getPaidSwitchMinute() : 0) * 60) + 
                               (account.getPaidSwitchSecond() != null ? account.getPaidSwitchSecond() : 0);
            long switchTime = (totalSeconds > 0) ? System.currentTimeMillis() + (totalSeconds * 1000L) : -1;
            boolean switched = (totalSeconds <= 0);

            while (!isStopRequested(id, presetName) && activeProcesses.containsKey(id)) {
                if (!switched && switchTime > 0 && System.currentTimeMillis() >= switchTime) {
                    try {
                        if (page.locator(".js-switchFeeBtn").isVisible()) {
                            // 有料切替ボタンを強制クリック
                            forceClickBySelector(page, ".js-switchFeeBtn");
                            page.waitForTimeout(1000);
                            
                            // 有料切替の確認モーダルの「はい」を強制クリック
                            forceClickBySelector(page, ".js-popupWindow .js-yesBtn");
                            
                            var vTime = latestVideoTimes.getOrDefault(id, "取得不可");
                            if (!presetName.isBlank()) addPresetLog(presetName, "💰 [" + accName + "] 有料放送へ切り替え完了 (再生時間: " + vTime + ")");
                        }
                    } catch (Exception e) {}
                    switched = true;
                }
                Thread.sleep(1000);
            }

            return true;
        } catch (PlaywrightException pe) {
            if (pe.getMessage() != null && pe.getMessage().contains("TargetClosedError")) {
                throw new InterruptedException("TargetClosedError");
            }
            throw pe;
        } finally {
            if (!presetName.isBlank()) addPresetLog(presetName, "🏁 [" + accName + "] 配信枠を終了しました");
            if (browser != null) try { browser.close(); } catch (Exception ignore) {}
            activeBrowsers.remove(id);
        }
    }

    private void startFfmpegProcess(Fc2Account account) throws Exception {
        var id = account.getId();
        var ffmpegPath = Paths.get(System.getProperty("user.dir"), "bin", "ffmpeg.exe").toString();
        var safePath = getSafeWindowsShortPath(account.getVideoPath().replaceAll("^\"|\"$", "").trim());
        var rtmpUrl = account.getServerUrl().endsWith("/") ? account.getServerUrl() + account.getStreamKey() : account.getServerUrl() + "/" + account.getStreamKey();
        
        var pb = new ProcessBuilder(
            ffmpegPath, "-re", "-i", safePath, 
            "-c:v", "libx264", "-preset", "veryfast", "-b:v", "2000k", 
            "-maxrate", "2000k", "-bufsize", "4000k", "-pix_fmt", "yuv420p", "-g", "60", 
            "-c:a", "aac", "-b:a", "128k", "-ar", "44100", 
            "-f", "flv", rtmpUrl
        );
        pb.redirectErrorStream(true);
        var process = pb.start();
        activeProcesses.put(id, process);

        // 🌟 Java 21+ 対応：軽量なVirtual Threadを使用してログを監視するモダンな書き方
        Thread.startVirtualThread(() -> {
            try (var isr = new InputStreamReader(process.getInputStream(), Charset.forName("Shift_JIS"))) {
                int c; 
                var sb = new StringBuilder();
                while ((c = isr.read()) != -1) {
                    char ch = (char) c;
                    if (ch == '\r' || ch == '\n') {
                        if (!sb.isEmpty()) { // Java 15+ syntax
                            var line = sb.toString();
                            sb.setLength(0);
                            if (line.contains("time=")) {
                                streamReadyFlags.put(id, true);
                                Matcher m = Pattern.compile("time=(\\d{2}:\\d{2}:\\d{2})").matcher(line);
                                if (m.find()) latestVideoTimes.put(id, m.group(1));
                            }
                        }
                    } else { sb.append(ch); }
                }
            } catch (Exception ignore) {}
        });
    }

    public void stopStreamingProcess(Long accountId) {
        stopSignals.put(accountId, true);
        var p = activeProcesses.get(accountId);
        if (p != null) killProcessTree(p);
        activeProcesses.remove(accountId);
        stopBrowsers(accountId);
    }

    private void stopBrowsers(Long id) {
        var b = activeBrowsers.get(id);
        if (b != null) try { b.close(); } catch (Exception ignore) {}
        activeBrowsers.remove(id);
    }

    public void startPresetProcess(Fc2Preset preset, List<Fc2Account> playlist) {
        var presetName = preset.getPresetName();
        if (activePresetThreads.containsKey(presetName)) return;
        
        presetStopSignals.put(presetName, false);
        addPresetLog(presetName, "▶️ プリセットの連続再生を開始します");

        var presetThread = new Thread(() -> {
            try {
                int maxLoop = preset.getLoopCount();
                int currentLoop = 1;
                while ((maxLoop == 0 || currentLoop <= maxLoop) && !Boolean.TRUE.equals(presetStopSignals.get(presetName))) {
                    var currentPlaylist = new ArrayList<>(playlist);
                    if (preset.isShuffleMode()) Collections.shuffle(currentPlaylist);
                    
                    for (var account : currentPlaylist) {
                        if (Boolean.TRUE.equals(presetStopSignals.get(presetName))) break;
                        runStreamingLogic(account, true, presetName);
                    }
                    if (Boolean.TRUE.equals(presetStopSignals.get(presetName))) break;
                    
                    if (preset.getLoopWaitMinutes() > 0) {
                        addPresetLog(presetName, "☕ 次のループまで " + preset.getLoopWaitMinutes() + "分間休憩します...");
                        long waitEnd = System.currentTimeMillis() + (preset.getLoopWaitMinutes() * 60 * 1000L);
                        while (System.currentTimeMillis() < waitEnd && !Boolean.TRUE.equals(presetStopSignals.get(presetName))) {
                            Thread.sleep(5000);
                        }
                    }
                    currentLoop++;
                }
            } catch (Exception e) {
                logger.error("異常終了: {}", e.getMessage());
            } finally {
                addPresetLog(presetName, "⏹ プリセットの再生がすべて終了・停止しました");
                activePresetThreads.remove(presetName);
                presetStopSignals.remove(presetName);
            }
        });
        activePresetThreads.put(presetName, presetThread);
        presetThread.start();
    }

    public void stopPresetProcess(String presetName) {
        presetStopSignals.put(presetName, true);
        for (var accountId : activeAccountThreads.keySet()) {
            var acc = repository.findById(accountId).orElse(null);
            if (acc != null && presetName.equals(acc.getPresetName())) stopStreamingProcess(accountId);
        }
        var thread = activePresetThreads.get(presetName);
        if (thread != null) thread.interrupt();
    }
}