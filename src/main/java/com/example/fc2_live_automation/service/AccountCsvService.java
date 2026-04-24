package com.example.fc2_live_automation.service;

import com.example.fc2_live_automation.model.Fc2Account;
import com.example.fc2_live_automation.repository.Fc2AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class AccountCsvService {

    private final Fc2AccountRepository repository;

    public AccountCsvService(Fc2AccountRepository repository) {
        this.repository = repository;
    }

    // CSVインポート（フル項目対応 ＆ ブランク許容 ＆ 見えない文字BOMの除去）
    public void importAccountsFromCsv(MultipartFile file) throws Exception {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean isFirstLine = true;
            
            while ((line = br.readLine()) != null) {
                if (isFirstLine) { isFirstLine = false; continue; } // ヘッダーをスキップ
                
                String[] data = line.split(",(?=([^\"]*\"[^\"]*\")*[^\"]*$)");
                if (data.length < 1) continue; 
                
                String email = getSafeData(data, 0);
                if (email.isEmpty()) continue; // メールアドレス空欄はスキップ

                String pass = getSafeData(data, 1);
                String accountName = getSafeData(data, 2);
                String title = getSafeData(data, 3);
                
                // 🌟 修正2: 動画パスやURLでスプレッドシート等によって増殖した不要な「"」を完全に除去する
                String videoPath = getSafeData(data, 4).replace("\"", ""); 
                String serverUrl = getSafeData(data, 5).replace("\"", "");
                String streamKey = getSafeData(data, 6).replace("\"", "");
                String customLiveUrl = getSafeData(data, 7).replace("\"", "");
                
                String info = getSafeData(data, 8);
                String adultFlgStr = getSafeData(data, 9);
                String categoryStr = getSafeData(data, 10);
                String paidMinStr = getSafeData(data, 11);
                String paidSecStr = getSafeData(data, 12);
                
                // 🌟 修正1: プリセット名の読み込み（14列目）
                String presetName = getSafeData(data, 13);

                Fc2Account account = repository.findByEmailAndVideoPathAndAccountName(email, videoPath, accountName)
                        .orElseGet(() -> {
                            Fc2Account newAcc = new Fc2Account();
                            // 🌟 修正3: CSVからの「新規登録時」はブラウザ設定をデフォルトでONにする
                            newAcc.setShowBrowser(true);
                            return newAcc;
                        });

                account.setEmail(email);
                account.setPass(pass);
                account.setAccountName(accountName);
                account.setTitle(title);
                account.setVideoPath(videoPath);
                account.setServerUrl(serverUrl);
                account.setStreamKey(streamKey);
                account.setCustomLiveUrl(customLiveUrl);
                account.setInfo(info);
                
                account.setAdultflg(parseAdultFlg(adultFlgStr));
                account.setCategory(parseCategory(categoryStr));
                account.setPaidSwitchMinute(parseIntSafe(paidMinStr, 0));
                account.setPaidSwitchSecond(parseIntSafe(paidSecStr, 0));
                
                // 🌟 修正1: プリセット名をアカウント情報に保存
                account.setPresetName(presetName);
                
                repository.save(account);
            }
        }
    }

    public void exportAccountsToCsv(PrintWriter writer) {
        List<Fc2Account> accounts = repository.findAllByOrderByDisplayOrderAsc();
        
        // 🌟 修正1: ヘッダーの末尾に「プリセット名」を追加
        writer.println("ログインメールアドレス,パスワード,アカウント管理名,番組タイトル,配信動画のパス,サーバーURL(RTMP),ストリームキー,視聴用配信URL,番組情報(説明文),配信場所(一般/アダルト),カテゴリー,有料切替(分),有料切替(秒),プリセット名");
        
        for (Fc2Account acc : accounts) {
            writer.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%d,%d,%s\n",
                    safeCsv(acc.getEmail()),
                    safeCsv(acc.getPass()),
                    safeCsv(acc.getAccountName()),
                    safeCsv(acc.getTitle()),
                    safeCsv(acc.getVideoPath()),
                    safeCsv(acc.getServerUrl()),
                    safeCsv(acc.getStreamKey()),
                    safeCsv(acc.getCustomLiveUrl()),
                    safeCsv(acc.getInfo()),
                    getAdultFlgLabel(acc.getAdultflg()), 
                    getCategoryLabel(acc.getCategory()), 
                    acc.getPaidSwitchMinute() != null ? acc.getPaidSwitchMinute() : 0,
                    acc.getPaidSwitchSecond() != null ? acc.getPaidSwitchSecond() : 0,
                    safeCsv(acc.getPresetName()) // 🌟 修正1: プリセット名を出力
            );
        }
    }

    private String getSafeData(String[] data, int index) {
        if (index < data.length) {
            return cleanCsvText(data[index]);
        }
        return "";
    }

    // 🌟 修正2: スプレッドシートなどで保存を繰り返して増殖したダブルクォーテーションをループで全て綺麗に剥がす
    private String cleanCsvText(String text) {
        if (text == null) return "";
        text = text.replace("\uFEFF", "").replace("\u200B", "").trim();
        
        while (text.startsWith("\"") && text.endsWith("\"") && text.length() > 1) {
            text = text.substring(1, text.length() - 1);
            if (text.contains("\"\"")) {
                text = text.replace("\"\"", "\"");
            }
        }
        return text.trim();
    }

    private String safeCsv(String text) {
        if (text == null) return "";
        text = text.replace("\"", "\"\"");
        if (text.contains(",") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text + "\"";
        }
        return text;
    }

    private int parseAdultFlg(String str) {
        if (str == null || str.isBlank()) return 0;
        if (str.equals("1") || str.contains("アダルト")) return 1;
        return 0; 
    }

    private String getAdultFlgLabel(Integer flg) {
        return (flg != null && flg == 1) ? "アダルト" : "一般";
    }

    private int parseCategory(String str) {
        if (str == null || str.isBlank()) return 0;
        if (str.contains("雑談")) return 1;
        if (str.contains("ゲーム")) return 2;
        if (str.contains("作業")) return 3;
        if (str.contains("動画")) return 4;
        if (str.contains("音声")) return 9;
        if (str.contains("その他")) return 5;
        try {
            int val = Integer.parseInt(str);
            if (val == 1 || val == 2 || val == 3 || val == 4 || val == 9 || val == 5) return val;
        } catch (NumberFormatException e) {}
        return 0;
    }

    private String getCategoryLabel(Integer cat) {
        if (cat == null) return "未設定";
        return switch (cat) {
            case 1 -> "雑談";
            case 2 -> "ゲーム";
            case 3 -> "作業";
            case 4 -> "動画";
            case 9 -> "音声";
            case 5 -> "その他";
            default -> "未設定";
        };
    }

    private int parseIntSafe(String str, int defaultVal) {
        if (str == null || str.isBlank()) return defaultVal;
        try {
            return Integer.parseInt(str.trim());
        } catch (NumberFormatException e) {
            return defaultVal; 
        }
    }
}