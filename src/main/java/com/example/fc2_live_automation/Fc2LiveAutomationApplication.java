package com.example.fc2_live_automation; 

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

@SpringBootApplication
@EnableAsync
public class Fc2LiveAutomationApplication {

    public static void main(String[] args) {
        SpringApplication.run(Fc2LiveAutomationApplication.class, args);
    }

    // 🌟 サーバーの準備が完全に整ったタイミングで管理画面（ブラウザ）を自動で開く
    @EventListener(ApplicationReadyEvent.class)
    public void openBrowserAfterStartup() {
        try {
            String url = "http://localhost:8081/"; // ※必要に応じて8080などに変更してください
            String os = System.getProperty("os.name").toLowerCase();
            
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            }
            System.out.println("🌟 管理ダッシュボードを自動起動しました: " + url);
        } catch (Exception e) {
            System.out.println("⚠️ 管理ダッシュボードの自動起動に失敗しました。手動で開いてください。");
        }
    }
}