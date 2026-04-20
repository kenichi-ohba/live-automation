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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
            if (logs.size() >= 100) logs.removeFirst();
            logs.add(message);
        }
        logger.info("[Account {}] {}", id, message);
    }

    public void addPresetLog(String presetName, String message) {
        if (presetName == null || presetName.isBlank()) return;
        presetLogs.putIfAbsent(presetName, new ArrayList<>());
        var logs = presetLogs.get(presetName);
        synchronized (logs) {
            if (logs.size() >= 50) logs.removeFirst();
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

    private int parseDurationString(String durStr) {
        if (durStr == null || durStr.isBlank() || durStr.contains("失敗") || durStr.contains("エラー")) return 0;
        int total = 0;
        try {
            String clean = durStr.replaceAll("⚠️音声なし", "").trim();
            Matcher hM = Pattern.compile("(\\d+)時間").matcher(clean);
            if (hM.find()) total += Integer.parseInt(hM.group(1)) * 3600;
            
            Matcher mM = Pattern.compile("(\\d+)分").matcher(clean);
            if (mM.find()) total += Integer.parseInt(mM.group(1)) * 60;
            
            Matcher sM = Pattern.compile("(\\d+)秒").matcher(clean);
            if (sM.find()) total += Integer.parseInt(sM.group(1));
        } catch (Exception e) {}
        return total;
    }

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
                        if (executePlaywrightProcess(account, presetName, i)) {
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

    private boolean executePlaywrightProcess(Fc2Account account, String presetName, int attempt) throws Exception {
        var id = account.getId();
        Browser browser = null;
        var accName = (account.getAccountName() != null && !account.getAccountName().isBlank()) ? account.getAccountName() : "名称未設定";

        try (var playwright = Playwright.create()) {
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(!account.isShowBrowser()));
            activeBrowsers.put(id, browser);

            var context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                    .setPermissions(List.of("camera", "microphone", "notifications"))); 
            
            context.setDefaultTimeout(60000);
            
            var page = context.newPage();
            page.onDialog(dialog -> dialog.accept());
            
            checkStop(id, presetName);
            page.navigate("https://live.fc2.com/");
            page.locator("a.c-btn.c-btnBs").first().click();
            
            checkStop(id, presetName);
            page.locator("input[name='email']").fill(account.getEmail());
            page.locator("input[name='pass']").fill(account.getPass());
            if (page.locator("input[name='keep_login']").isVisible()) {
                page.locator("input[name='keep_login']").check();
            }
            page.locator("input[name='image']").click();
            
            page.waitForTimeout(3000);
            if (page.locator("text='サービスへログインする'").isVisible()) {
                page.locator("text='サービスへログインする'").click();
            }

            if (!presetName.isBlank()) addPresetLog(presetName, "✅ [" + accName + "] ログイン完了、設定画面へ移行");

            checkStop(id, presetName);
            
            page.waitForTimeout(2000); 
            if (!page.url().contains("live_start")) {
                try {
                    page.evaluate("() => { let cb = document.querySelector('#noDisplayCampaign'); if(cb) cb.click(); }");
                    page.waitForTimeout(1000);
                    page.evaluate("Array.from(document.querySelectorAll('a, button, span, div, label')).filter(el => el.textContent.includes('サービスへログイン')).forEach(btn => { if (btn.offsetParent !== null) btn.click(); });");
                    page.waitForTimeout(2000);
                } catch (Exception e) {}
                
                if (!page.url().contains("live_start")) {
                    page.navigate("https://live.fc2.com/live_start/");
                    page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                    page.waitForTimeout(2000);
                }
            }

            try {
                page.locator("#title").waitFor(new Locator.WaitForOptions().setTimeout(10000));
            } catch (Exception e) {
                throw new Exception("配信画面の読み込みに失敗しました");
            }

            checkStop(id, presetName);
            String streamTitle = (account.getTitle() != null && !account.getTitle().isBlank()) 
                                 ? account.getTitle() 
                                 : account.getAccountName();
            page.locator("#title").fill(streamTitle);
            
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
            } else {
                page.locator("#feesetting0").check();
            }

            checkStop(id, presetName);
            
            page.locator("#submit").click();
            page.waitForTimeout(2000);
            
            try {
                if (page.locator("text='利用規約に同意して配信する'").count() > 0) {
                    page.locator("text='利用規約に同意して配信する'").first().evaluate("node => node.click()");
                } else if (page.locator(".js-agreeBtn").count() > 0) {
                    page.locator(".js-agreeBtn").first().evaluate("node => node.click()");
                }
                page.waitForTimeout(1500);
            } catch(Exception e) {}

            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            var broadcastUrl = page.url();
            var latest = repository.findById(id).orElse(account);
            latest.setBroadcastUrl(broadcastUrl);
            repository.save(latest);

            page.waitForTimeout(3000);
            checkStop(id, presetName);

            try {
                if (page.locator("#age_ok_btn").count() > 0 && page.locator("#age_ok_btn").isVisible()) {
                    page.locator("#age_ok_btn").evaluate("node => node.click()");
                    page.waitForTimeout(1500);
                }
            } catch(Exception e) {}

            try {
                if (page.locator(".js-yesBtn").count() > 0) {
                    page.locator(".js-yesBtn").first().evaluate("node => node.click()");
                    page.waitForTimeout(1500);
                }
            } catch(Exception e) {}

            checkStop(id, presetName);

            streamReadyFlags.put(id, false);
            startFfmpegProcess(account);
            if (!presetName.isBlank()) addPresetLog(presetName, "🎥 [" + accName + "] FFmpegによる映像送信を開始しました");

            int waited = 0;
            while (!Boolean.TRUE.equals(streamReadyFlags.get(id)) && waited < 60) {
                checkStop(id, presetName);
                Thread.sleep(1000);
                waited++;
            }

            if (!presetName.isBlank()) {
                addPresetLog(presetName, "☕ [" + accName + "] 映像のバッファリングのため15秒待機します...");
            }
            for (int j = 0; j < 15; j++) {
                checkStop(id, presetName);
                Thread.sleep(1000);
            }

            int startBtnWaitMs = 30000;
            if (attempt == 2) startBtnWaitMs = 45000;
            else if (attempt >= 3) startBtnWaitMs = 60000;

            if (!presetName.isBlank()) {
                addPresetLog(presetName, "⏳ 配信開始ボタンを待機します（最大 " + (startBtnWaitMs / 1000) + "秒 / 試行 " + attempt + "回目）...");
            }

            long btnWaitEnd = System.currentTimeMillis() + startBtnWaitMs;
            boolean isToolStartBtnClicked = false;

            while (System.currentTimeMillis() < btnWaitEnd) {
                checkStop(id, presetName);
                try {
                    Boolean isBtnVisible = (Boolean) page.evaluate("() => { let btn = document.querySelector('.js-toolStartBtn'); return btn && btn.offsetParent !== null; }");
                    if (Boolean.TRUE.equals(isBtnVisible)) {
                        page.evaluate("() => { document.querySelector('.js-toolStartBtn').click(); }");
                        isToolStartBtnClicked = true;
                        page.waitForTimeout(3000); 
                        
                        try {
                            if (page.locator("#age_ok_btn").count() > 0 && page.locator("#age_ok_btn").isVisible()) {
                                page.locator("#age_ok_btn").evaluate("node => node.click()");
                                page.waitForTimeout(1000);
                            }
                        } catch(Exception e) {}

                        try {
                            if (page.locator(".js-yesBtn").count() > 0) {
                                page.locator(".js-yesBtn").first().evaluate("node => node.click()");
                                page.waitForTimeout(1000);
                            }
                        } catch(Exception e) {}
                        
                        break;
                    }
                } catch(Exception e) {}
                Thread.sleep(1000);
            }

            if (!isToolStartBtnClicked) {
                throw new Exception("時間内に配信開始ボタンが出現しませんでした（FC2サーバー遅延の可能性）");
            }

            // =================================================================================
            // 🌟🌟🌟 新規追加：次回配信時のNGワード自動登録予約の実行（実際の配信画面で行う）
            // =================================================================================
            File ngCsvFile = java.nio.file.Paths.get(System.getProperty("user.dir"), "ng_csvs", "account_" + id + ".csv").toFile();
            if (ngCsvFile.exists()) {
                addLog(id, "🛡️ [" + accName + "] 配信が開始されました。予約されていたNGコメントの自動登録を実行します...");
                if (!presetName.isBlank()) addPresetLog(presetName, "🛡️ [" + accName + "] 配信が開始されました。予約されていたNGコメントの自動登録を実行します...");
                
                try {
                    List<String> ngWords = new ArrayList<>();
                    List<String> lines = Files.readAllLines(ngCsvFile.toPath(), StandardCharsets.UTF_8);
                    for (String line : lines) {
                        for (String word : line.split(",")) {
                            if (!word.trim().isEmpty()) ngWords.add(word.trim());
                        }
                    }
                    
                    if (!ngWords.isEmpty()) {
                        // ブロック設定のタブを開く
                        page.evaluate("() => { let icon = document.querySelector('use[*|href=\"#icon-tabBlock\"]'); if(icon) { let btn = icon.closest('li'); if(btn) btn.click(); } }");
                        page.waitForTimeout(1000);
                        
                        if (page.locator("#js-blockList-ngComment-tab").count() > 0) {
                            page.locator("#js-blockList-ngComment-tab").click();
                            page.waitForTimeout(1000);
                        }

                        // 💡 人間と同じ「確実なキーボード入力」を再現（安定性強化版）
                        if (page.locator(".js-addNgCommentText").count() > 0 && page.locator(".js-addCommentBtn").count() > 0) {
                            int count = 0;
                            for (String word : ngWords) {
                                try {
                                    // 毎回最新の要素を取得して確実に入力（DOMの更新ズレ対策）
                                    page.locator(".js-addNgCommentText").first().fill(word); 
                                    page.locator(".js-addCommentBtn").first().click();
                                    
                                    // 🌟 修正：FC2サーバーが確実に保存処理を終えるのを待つため、0.5秒待機
                                    page.waitForTimeout(500); 
                                    
                                    count++;
                                    // 🌟 修正：50件処理するごとに、サーバーを休ませるため2秒待機（より安全に）
                                    if (count % 50 == 0) {
                                        addLog(id, "⏳ [" + accName + "] サーバー負荷軽減のため一時待機中... (" + count + "件完了)");
                                        page.waitForTimeout(2000);
                                    }
                                } catch (Exception innerE) {
                                    // 万が一1文字失敗しても、プログラム全体を止めずに次の文字へ進む
                                    logger.warn("⚠️ [" + accName + "] '" + word + "' の登録をスキップしました: " + innerE.getMessage());
                                }
                            }
                        } else {
                            addLog(id, "⚠️ [" + accName + "] NGワードの入力欄が見つかりませんでした。");
                        }
                        
                        page.waitForTimeout(1000); // 念のためFC2側の処理完了を待つ
                        
                        // 登録が終わったら設定ウィンドウを閉じておく
                        try {
                            page.evaluate("() => { let closeBtn = document.querySelector('.setting_close a'); if(closeBtn) closeBtn.click(); }");
                        } catch(Exception e) {}
                    }
                    
                    addLog(id, "✅ [" + accName + "] NGワードの一括登録完了。予約ファイル（CSV）を削除します。");
                    if (!presetName.isBlank()) addPresetLog(presetName, "✅ [" + accName + "] NGワードの一括登録完了。予約ファイル（CSV）を削除します。");
                    ngCsvFile.delete(); // 次回は実行されないようにファイルを削除
                    
                } catch (Exception e) {
                    addLog(id, "❌ [" + accName + "] NG登録エラー: " + e.getMessage());
                    if (!presetName.isBlank()) addPresetLog(presetName, "❌ [" + accName + "] NG登録エラー: " + e.getMessage());
                }
            }
            // =================================================================================

            int targetMinute = account.getPaidSwitchMinute() != null ? account.getPaidSwitchMinute() : 0;
            int targetSecond = account.getPaidSwitchSecond() != null ? account.getPaidSwitchSecond() : 0;
            int totalSeconds = (targetMinute * 60) + targetSecond;
            
            int lagOffset = account.getSwitchLagSeconds() != null ? account.getSwitchLagSeconds() : 5;
            int triggerSeconds = totalSeconds - lagOffset;
            if (triggerSeconds < 0) { triggerSeconds = 0; }
            
            long switchTargetTime = -1;
            boolean hasSwitchedToPaid = false;
            
            if (totalSeconds > 0) {
                switchTargetTime = System.currentTimeMillis() + (triggerSeconds * 1000L);
                if (!presetName.isBlank()) addPresetLog(presetName, "⏳ 有料切替タイマーセット: " + totalSeconds + "秒 (ラグ相殺のため " + triggerSeconds + "秒後に自動実行)");
            } else {
                hasSwitchedToPaid = true; 
            }

            int videoDurationSec = parseDurationString(account.getVideoDuration());
            int timeoutSec = (videoDurationSec > 0) ? videoDurationSec + 300 : 7200; 
            long processStartTime = System.currentTimeMillis();

            Process ffmpegProcess = activeProcesses.get(id);
            
            while (!isStopRequested(id, presetName) && ffmpegProcess != null && ffmpegProcess.isAlive()) {
                
                long elapsedSec = (System.currentTimeMillis() - processStartTime) / 1000;
                if (elapsedSec > timeoutSec) {
                    if (!presetName.isBlank()) addPresetLog(presetName, "❌ [" + accName + "] 通信エラー等によるフリーズを検知。強制終了して次へ進みます。");
                    killProcessTree(ffmpegProcess);
                    break;
                }

                if (!hasSwitchedToPaid && switchTargetTime > 0) {
                    long now = System.currentTimeMillis();
                    if (now >= switchTargetTime) {
                        try {
                            if (!presetName.isBlank()) addPresetLog(presetName, "💰 [" + accName + "] 時間が来ました。有料切替操作を開始します...");
                            
                            if (page.locator(".js-switchFeeBtn").count() > 0) {
                                page.locator(".js-switchFeeBtn").first().evaluate("node => node.click()");
                                Thread.sleep(1500);
                                
                                if (page.locator(".js-popupWindow .js-yesBtn").count() > 0) {
                                    page.locator(".js-popupWindow .js-yesBtn").first().evaluate("node => node.click()");
                                }
                                Thread.sleep(1500);
                                
                                if (page.locator(".js-popupWindow .js-yesBtn").count() > 0) {
                                    page.locator(".js-popupWindow .js-yesBtn").first().evaluate("node => node.click()");
                                }
                                
                                if (!presetName.isBlank()) addPresetLog(presetName, "✅ [" + accName + "] 有料放送へ切り替え操作が完了しました");
                            } else {
                                if (!presetName.isBlank()) addPresetLog(presetName, "⚠️ [" + accName + "] 切替ボタンが見つかりません。すでに切り替わっている可能性があります。");
                            }
                        } catch (Exception e) {
                            if (!presetName.isBlank()) addPresetLog(presetName, "❌ [" + accName + "] 切替処理中にエラー: " + e.getMessage());
                        }
                        
                        hasSwitchedToPaid = true;
                    }
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
        
        boolean hasNoAudio = account.getVideoDuration() != null && account.getVideoDuration().contains("音声なし");
        
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-re");
        command.add("-i"); command.add(safePath);
        
        if (hasNoAudio) {
            command.add("-f"); command.add("lavfi");
            command.add("-i"); command.add("anullsrc"); 
        }
        
        command.addAll(List.of("-c:v", "libx264", "-preset", "ultrafast", "-b:v", "2000k", 
                               "-maxrate", "2000k", "-bufsize", "4000k", "-pix_fmt", "yuv420p", "-g", "60", 
                               "-c:a", "aac", "-b:a", "128k", "-ar", "44100"));
        
        if (hasNoAudio) {
            command.add("-shortest"); 
            command.addAll(List.of("-map", "0:v:0", "-map", "1:a:0")); 
        }
        
        command.addAll(List.of("-f", "flv", rtmpUrl));
        
        var pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        var process = pb.start();
        activeProcesses.put(id, process);

        Thread.startVirtualThread(() -> {
            try (var isr = new InputStreamReader(process.getInputStream(), Charset.forName("Shift_JIS"))) {
                int c; 
                var sb = new StringBuilder();
                while ((c = isr.read()) != -1) {
                    char ch = (char) c;
                    if (ch == '\r' || ch == '\n') {
                        if (!sb.isEmpty()) { 
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