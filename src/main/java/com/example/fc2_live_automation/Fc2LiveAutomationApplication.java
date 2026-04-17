package com.example.fc2_live_automation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class Fc2LiveAutomationApplication {

    public static void main(String[] args) {
        SpringApplication.run(Fc2LiveAutomationApplication.class, args);
    }

    /**
     * 🌟 Java 25 完全対応：非推奨の Runtime.exec を廃止し、ProcessBuilder を使用
     * シークレット（インコグニート）モードかつアプリモードでダッシュボードを起動します。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void openBrowser() {
        var url = "http://localhost:8082/";
        var os = System.getProperty("os.name").toLowerCase();

        try {
            if (os.contains("win")) {
                // Windows: Chromeを優先し、シークレット+アプリモードで起動。なければEdge。
                try {
                    new ProcessBuilder("cmd", "/c", "start", "chrome", "--app=" + url, "--incognito").start();
                } catch (Exception e) {
                    new ProcessBuilder("cmd", "/c", "start", "msedge", "--app=" + url, "-inprivate").start();
                }
            } else if (os.contains("mac")) {
                // Mac: Google Chromeをシークレット+アプリモードで起動
                new ProcessBuilder("open", "-a", "Google Chrome", "--args", "--app=" + url, "--incognito").start();
            } else {
                // その他のOS (Linux等)
                new ProcessBuilder("xdg-open", url).start();
            }
            
            System.out.println("✅ 管理ダッシュボードを分離されたブラウザ（シークレットモード）で起動しました: " + url);

        } catch (Exception e) {
            System.err.println("❌ ブラウザの自動起動に失敗しました: " + e.getMessage());
        }
    }
}