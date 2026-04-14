package com.example.fc2_live_automation.controller;

import com.example.fc2_live_automation.model.Fc2Account;
import com.example.fc2_live_automation.service.AccountService;
import com.example.fc2_live_automation.service.Fc2AutomationWorker;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class AccountController {

    private final AccountService accountService;
    private final Fc2AutomationWorker fc2AutomationWorker;

    public AccountController(AccountService accountService, Fc2AutomationWorker fc2AutomationWorker) {
        this.accountService = accountService;
        this.fc2AutomationWorker = fc2AutomationWorker;
    }

    // 🌟 ダッシュボード（今回はそのまま。フェーズ3以降でプリセット専用に作り変えます）
    @GetMapping("/")
    public String index(Model model) {
        List<Fc2Account> accounts = accountService.getAllAccounts();
        Map<String, List<Fc2Account>> groupedAccounts = accounts.stream()
            .collect(Collectors.groupingBy(a -> 
                (a.getPresetName() == null || a.getPresetName().trim().isEmpty()) 
                    ? "未設定 (個別アカウント)" 
                    : a.getPresetName()
            ));
        model.addAttribute("accounts", accounts);
        model.addAttribute("groupedAccounts", groupedAccounts);
        return "index";
    }

    // 🌟 新規追加：アカウント（素材）管理画面の表示
    @GetMapping("/accounts")
    public String accountsList(Model model) {
        model.addAttribute("accounts", accountService.getAllAccounts());
        return "accounts";
    }

    @GetMapping("/add")
    public String showAddForm(Model model) {
        model.addAttribute("account", new Fc2Account());
        return "add-account";
    }

    @PostMapping("/save")
    public String saveAccount(@ModelAttribute Fc2Account account) {
        accountService.saveAccount(account);
        return "redirect:/accounts"; // 🌟 保存後は「アカウント一覧」に戻る
    }

    @GetMapping("/edit/{id}")
    public String showUpdateForm(@PathVariable("id") Long id, Model model) {
        Fc2Account account = accountService.getAccountById(id);
        model.addAttribute("account", account);
        return "add-account";
    }

    @GetMapping("/delete/{id}")
    public String deleteAccount(@PathVariable("id") Long id, Model model) {
        accountService.deleteAccount(id);
        return "redirect:/accounts"; // 🌟 削除後も「アカウント一覧」に戻る
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

    @GetMapping("/api/accounts")
    @ResponseBody
    public List<Fc2Account> getAccountsApi() {
        return accountService.getAllAccounts();
    }

    @GetMapping("/api/logs/{id}")
    @ResponseBody
    public List<String> getLogs(@PathVariable Long id) {
        return fc2AutomationWorker.getLogs(id);
    }
}