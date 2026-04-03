<<<<<<< HEAD
package com.example.fc2_live_automation; // ※パッケージ名が違う場合はご自身のものに合わせてください

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

    // 🌟 追加：サーバーの準備が完全に整ったタイミングで発動するイベント
    @EventListener(ApplicationReadyEvent.class)
    public void openBrowserAfterStartup() {
        try {
            String url = "http://localhost:8081/";
            String os = System.getProperty("os.name").toLowerCase();
            
            // 🌟 修正：Java 18以降の推奨設定（ProcessBuilder）を使用してブラウザを安全に開く
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            }
            System.out.println("🌟 ブラウザを自動起動しました: " + url);
        } catch (Exception e) {
            System.out.println("⚠️ ブラウザの自動起動に失敗しました。手動で http://localhost:8080/ を開いてください。");
        }
    }
}
=======
package com.example.fc2_live_automation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Fc2LiveAutomationApplication {

	public static void main(String[] args) {
		SpringApplication.run(Fc2LiveAutomationApplication.class, args);
	}

}
>>>>>>> 03c382712fca97b74dbaa62e3ded248cec43418e
