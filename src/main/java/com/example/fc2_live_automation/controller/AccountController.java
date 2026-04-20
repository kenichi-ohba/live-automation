package com.example.fc2_live_automation.controller;

import com.example.fc2_live_automation.model.Fc2Account;
import com.example.fc2_live_automation.model.Fc2Preset;
import com.example.fc2_live_automation.service.AccountService;
import com.example.fc2_live_automation.repository.Fc2PresetRepository;
import com.example.fc2_live_automation.repository.Fc2AccountRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class AccountController {

    private final AccountService accountService;
    private final Fc2PresetRepository presetRepository;
    private final Fc2AccountRepository accountRepository;

    public AccountController(AccountService accountService, Fc2PresetRepository presetRepository, Fc2AccountRepository accountRepository) {
        this.accountService = accountService;
        this.presetRepository = presetRepository;
        this.accountRepository = accountRepository;
    }

    @GetMapping("/")
    public String index(Model model) {
        List<Fc2Account> accounts = accountRepository.findAllByOrderByDisplayOrderAsc();

        Map<String, List<Fc2Account>> grouped = accounts.stream()
                .collect(Collectors.groupingBy(
                        acc -> (acc.getPresetName() != null && !acc.getPresetName().isEmpty()) ? acc.getPresetName() : "未設定 (個別アカウント)",
                        LinkedHashMap::new, 
                        Collectors.toList()
                ));
        
        List<Fc2Preset> presets = presetRepository.findAll();
        
        Map<String, Fc2Preset> presetMap = presets.stream().collect(Collectors.toMap(
                Fc2Preset::getPresetName, 
                p -> p,
                (existing, replacement) -> existing
        ));

        model.addAttribute("accounts", accounts);
        model.addAttribute("groupedAccounts", grouped);
        model.addAttribute("presetMap", presetMap);
        return "index";
    }

    @PostMapping("/schedule/set")
    public String setSchedule(@RequestParam String presetName, @RequestParam String scheduledTime) {
        Fc2Preset preset = presetRepository.findAll().stream()
                .filter(p -> presetName.equals(p.getPresetName()))
                .findFirst()
                .orElse(null);

        if (preset != null) {
            preset.setScheduledStartTime(scheduledTime);
            preset.setStatus("IDLE"); 
            presetRepository.save(preset);
        }
        return "redirect:/";
    }

    @PostMapping("/schedule/cancel")
    public String cancelSchedule(@RequestParam String presetName) {
        Fc2Preset preset = presetRepository.findAll().stream()
                .filter(p -> presetName.equals(p.getPresetName()))
                .findFirst()
                .orElse(null);

        if (preset != null) {
            preset.setScheduledStartTime("");
            presetRepository.save(preset);
        }
        return "redirect:/";
    }

    @GetMapping("/accounts")
    public String listAccounts(Model model) {
        model.addAttribute("accounts", accountService.getAllAccounts());
        return "accounts";
    }

    @GetMapping("/add")
    public String showAddForm(Model model) {
        model.addAttribute("account", new Fc2Account());
        return "add-account";
    }

    @GetMapping("/edit/{id}")
    public String showEditForm(@PathVariable Long id, Model model) {
        Fc2Account account = accountService.getAccountById(id);
        model.addAttribute("account", account);
        return "add-account";
    }

    // ==========================================
    // 🌟 復活：アカウント保存時の「データ欠損・空欄上書き防止」ロジック
    // ==========================================
    @PostMapping("/save")
    public String saveAccount(@ModelAttribute Fc2Account account) {
        if (account.getId() != null) {
            // 既存アカウントの編集の場合、DBから元のデータを取得
            Fc2Account existing = accountRepository.findById(account.getId()).orElse(null);
            
            if (existing != null) {
                // 1. セキュリティ保護：パスワードとストリームキーが空欄で送られてきたら、元のデータを維持する
                if (account.getPass() == null || account.getPass().isEmpty()) {
                    account.setPass(existing.getPass());
                }
                if (account.getStreamKey() == null || account.getStreamKey().isEmpty()) {
                    account.setStreamKey(existing.getStreamKey());
                }

                // 2. 状態保護：編集画面に存在しない「裏側の管理データ」が消えないように引き継ぐ
                account.setPresetName(existing.getPresetName());
                account.setDisplayOrder(existing.getDisplayOrder());
                account.setStatus(existing.getStatus());
                account.setCurrentLoop(existing.getCurrentLoop());
                account.setBroadcastUrl(existing.getBroadcastUrl());
                account.setScheduledStartTime(existing.getScheduledStartTime());
            }
        } else {
            // 新規作成時の初期値設定
            if (account.getStatus() == null || account.getStatus().isEmpty()) {
                account.setStatus("IDLE");
            }
            if (account.getCurrentLoop() == null) {
                account.setCurrentLoop(0);
            }
        }

        accountService.saveAccount(account); 
        return "redirect:/accounts";
    }

    @GetMapping("/delete/{id}")
    public String deleteAccount(@PathVariable Long id) {
        accountService.deleteAccount(id);
        return "redirect:/accounts";
    }

    @GetMapping("/start/{id}")
    public String startStreaming(@PathVariable Long id) {
        accountService.startStreaming(id);
        return "redirect:/";
    }

    @GetMapping("/stop/{id}")
    public String stopStreaming(@PathVariable Long id) {
        accountService.stopStreaming(id);
        return "redirect:/";
    }

    // ⬇️ 必要なサービスを呼び出せるように宣言を追加（すでに宣言されている場合は不要です）
    @Autowired
    private com.example.fc2_live_automation.service.AccountCsvService accountCsvService;
    
    @Autowired
    private com.example.fc2_live_automation.service.Fc2AutomationWorker fc2AutomationWorker;

    // ====================================================================
    // 🌟 新機能：アカウントCSVインポート（画面からのアップロード受付）
    // ====================================================================
    @PostMapping("/accounts/import")
    public String importAccounts(@org.springframework.web.bind.annotation.RequestParam("csvFile") org.springframework.web.multipart.MultipartFile file, org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        try {
            accountCsvService.importAccountsFromCsv(file);
            redirectAttributes.addFlashAttribute("message", "✅ CSVのインポートが完了しました！");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "❌ インポートに失敗しました: " + e.getMessage());
        }
        return "redirect:/accounts"; // 一覧画面にリダイレクト
    }

    // ====================================================================
    // 🌟 新機能：アカウントCSVエクスポート（画面からのダウンロード受付）
    // ====================================================================
    @GetMapping("/accounts/export")
    public void exportAccounts(jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"accounts_export.csv\"");
        try (java.io.PrintWriter writer = response.getWriter()) {
            accountCsvService.exportAccountsToCsv(writer);
        }
    }

    // ====================================================================
    // 🌟 新機能：NGワードの自動登録（画面からの実行受付）
    // ====================================================================
    @PostMapping("/accounts/{id}/ng-words")
    public String registerNgWords(@org.springframework.web.bind.annotation.PathVariable Long id, @org.springframework.web.bind.annotation.RequestParam("ngCsvFile") org.springframework.web.multipart.MultipartFile ngCsvFile, org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        try {
            com.example.fc2_live_automation.model.Fc2Account account = accountRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("無効なアカウントIDです: " + id));
            
            // 🌟 修正：Tomcatの一時フォルダの迷子を防ぐため、プロジェクトルートを基準に「絶対パス」を指定
            java.nio.file.Path dirPath = java.nio.file.Paths.get(System.getProperty("user.dir"), "ng_csvs");
            if (!java.nio.file.Files.exists(dirPath)) {
                java.nio.file.Files.createDirectories(dirPath);
            }
            java.nio.file.Path savePath = dirPath.resolve("account_" + id + ".csv");
            
            // 🌟 修正：transferToの代わりに、より安全な標準コピー機能を使用（上書き保存）
            java.nio.file.Files.copy(ngCsvFile.getInputStream(), savePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            
            redirectAttributes.addFlashAttribute("message", "✅ [" + account.getAccountName() + "] のNGワード予約が完了しました！次回の実際の配信開始時に、1回だけ自動で全登録されます。");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "❌ NGワードの予約に失敗しました: " + e.getMessage());
        }
        return "redirect:/accounts";
    }
}