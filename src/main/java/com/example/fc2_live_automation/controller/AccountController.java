package com.example.fc2_live_automation.controller;

import com.example.fc2_live_automation.model.Fc2Account;
import com.example.fc2_live_automation.model.Fc2Preset;
import com.example.fc2_live_automation.service.AccountService;
import com.example.fc2_live_automation.service.AccountCsvService;
import com.example.fc2_live_automation.repository.Fc2PresetRepository;
import com.example.fc2_live_automation.repository.Fc2AccountRepository;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class AccountController {

    private final AccountService accountService;
    private final Fc2PresetRepository presetRepository;
    private final Fc2AccountRepository accountRepository;
    private final AccountCsvService accountCsvService;

    public AccountController(AccountService accountService, Fc2PresetRepository presetRepository, Fc2AccountRepository accountRepository, AccountCsvService accountCsvService) {
        this.accountService = accountService;
        this.presetRepository = presetRepository;
        this.accountRepository = accountRepository;
        this.accountCsvService = accountCsvService;
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

    // 🌟 予約時間設定の修正
    @PostMapping("/schedule/set")
    public String setSchedule(@RequestParam String presetName, @RequestParam String scheduledTime) {
        presetRepository.findAll().stream()
                .filter(p -> presetName.equals(p.getPresetName()))
                .findFirst()
                .ifPresent(preset -> {
                    preset.setScheduledStartTime(scheduledTime);
                    preset.setStatus("IDLE"); 
                    presetRepository.save(preset);
                });
        return "redirect:/";
    }

    // 🌟 予約キャンセル
    @PostMapping("/schedule/cancel")
    public String cancelSchedule(@RequestParam String presetName) {
        presetRepository.findAll().stream()
                .filter(p -> presetName.equals(p.getPresetName()))
                .findFirst()
                .ifPresent(preset -> {
                    preset.setScheduledStartTime("");
                    presetRepository.save(preset);
                });
        return "redirect:/";
    }

    // (以下、以前の正常なメソッドが続くため省略、必要に応じて他も上書きしてください)
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

    @PostMapping("/save")
    public String saveAccount(@ModelAttribute Fc2Account account) {
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

    @GetMapping("/stop-all")
    public String stopAll(RedirectAttributes redirectAttributes) {
        accountService.stopAll();
        redirectAttributes.addFlashAttribute("message", "🛑 すべてのプロセスに終了命令を送信しました。アプリが終了します。");
        return "redirect:/";
    }

    @PostMapping("/accounts/import")
    public String importAccounts(@RequestParam("csvFile") MultipartFile file, RedirectAttributes redirectAttributes) {
        try {
            accountCsvService.importAccountsFromCsv(file);
            redirectAttributes.addFlashAttribute("message", "✅ CSVのインポートが完了しました！");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "❌ インポートに失敗しました: " + e.getMessage());
        }
        return "redirect:/accounts"; 
    }

    @GetMapping("/accounts/export")
    public void exportAccounts(HttpServletResponse response) throws IOException {
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"accounts_export.csv\"");
        try (PrintWriter writer = response.getWriter()) {
            accountCsvService.exportAccountsToCsv(writer);
        }
    }

    @PostMapping("/accounts/{id}/ng-words")
    public String registerNgWords(@PathVariable Long id, @RequestParam("ngCsvFile") MultipartFile ngCsvFile, RedirectAttributes redirectAttributes) {
        try {
            Path dirPath = Paths.get(System.getProperty("user.dir"), "ng_csvs");
            if (!Files.exists(dirPath)) Files.createDirectories(dirPath);
            Path savePath = dirPath.resolve("account_" + id + ".csv");
            Files.copy(ngCsvFile.getInputStream(), savePath, StandardCopyOption.REPLACE_EXISTING);
            redirectAttributes.addFlashAttribute("message", "✅ NGワード予約完了。");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "❌ 失敗: " + e.getMessage());
        }
        return "redirect:/accounts";
    }
}