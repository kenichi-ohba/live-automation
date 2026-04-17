package com.example.fc2_live_automation.controller;

import com.example.fc2_live_automation.model.Fc2Account;
import com.example.fc2_live_automation.service.AccountService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class AccountController {

    private final AccountService accountService;

    // 🌟 Repositoryではなく、Serviceを利用するように宣言
    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    // ダッシュボード表示
    @GetMapping("/")
    public String index(Model model) {
        List<Fc2Account> accounts = accountService.getAllAccounts();
        Map<String, List<Fc2Account>> grouped = accounts.stream()
                .collect(Collectors.groupingBy(
                        acc -> (acc.getPresetName() != null && !acc.getPresetName().isEmpty()) ? acc.getPresetName() : "未設定 (個別アカウント)"
                ));
        model.addAttribute("accounts", accounts);
        model.addAttribute("groupedAccounts", grouped);
        return "index";
    }

    // アカウント一覧表示
    @GetMapping("/accounts")
    public String listAccounts(Model model) {
        model.addAttribute("accounts", accountService.getAllAccounts());
        return "accounts";
    }

    // 新規作成画面
    @GetMapping("/add")
    public String showAddForm(Model model) {
        model.addAttribute("account", new Fc2Account());
        return "add-account";
    }

    // 編集画面
    @GetMapping("/edit/{id}")
    public String showEditForm(@PathVariable Long id, Model model) {
        Fc2Account account = accountService.getAccountById(id);
        model.addAttribute("account", account);
        return "add-account";
    }

    // =========================================================
    // 🌟 ここが最重要修正ポイント！
    // 直接DBに保存するのではなく、必ず保護ロジック（Service）を経由させる
    // =========================================================
    @PostMapping("/save")
    public String saveAccount(@ModelAttribute Fc2Account account) {
        accountService.saveAccount(account); 
        return "redirect:/accounts";
    }

    // 削除処理
    @GetMapping("/delete/{id}")
    public String deleteAccount(@PathVariable Long id) {
        accountService.deleteAccount(id);
        return "redirect:/accounts";
    }

    // 個別再生開始
    @GetMapping("/start/{id}")
    public String startStreaming(@PathVariable Long id) {
        accountService.startStreaming(id);
        return "redirect:/";
    }

    // 個別再生停止
    @GetMapping("/stop/{id}")
    public String stopStreaming(@PathVariable Long id) {
        accountService.stopStreaming(id);
        return "redirect:/";
    }
}