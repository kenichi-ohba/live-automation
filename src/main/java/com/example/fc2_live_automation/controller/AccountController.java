package com.example.fc2_live_automation.controller;

import com.example.fc2_live_automation.model.Fc2Account;
import com.example.fc2_live_automation.service.AccountService;

import java.util.ArrayList;
import java.util.List;

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

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("accounts", accountService.getAllAccounts());
        return "index";
    }

    @GetMapping("/add")
    public String showAddForm(Model model) {
        model.addAttribute("account", new Fc2Account());
        return "add-account";
    }

    @PostMapping("/save")
    public String saveAccount(@ModelAttribute Fc2Account account) {
        accountService.saveAccount(account);
        return "redirect:/";
    }

    // 🌟 新規追加：編集画面を開く窓口（新規登録画面を使い回します）
    @GetMapping("/edit/{id}")
    public String showUpdateForm(@PathVariable("id") Long id, Model model) {
        Fc2Account account = accountService.getAccountById(id);
        model.addAttribute("account", account);
        return "add-account";
    }

    // 🌟 新規追加：削除する窓口
    @GetMapping("/delete/{id}")
    public String deleteAccount(@PathVariable("id") Long id, Model model) {
        accountService.deleteAccount(id);
        return "redirect:/";
    }

    @GetMapping("/start/{id}")
    public String startStreaming(@PathVariable Long id) {
        accountService.startStreaming(id);
        return "redirect:/";
    }

    // 🌟 新規追加：停止ボタンが押された時の窓口
    @GetMapping("/stop/{id}")
    public String stopStreaming(@PathVariable Long id) {
        // serviceの中で repository.save("IDLE") と 
        // worker.stopStreamingProcess(id) が両方呼ばれているか確認してください
        accountService.stopStreaming(id); 
        return "redirect:/";
    }

    // 🌟 画面更新用に、全アカウントの最新状態をJSON形式で返す窓口
    @GetMapping("/api/accounts")
    @ResponseBody
    public List<Fc2Account> getAccountsApi() {
        return accountService.getAllAccounts();
    }

    // 🌟 特定アカウントのログを取得するAPI
    @GetMapping("/api/logs/{id}")
    @ResponseBody
    public List<String> getLogs(@PathVariable Long id) {
        Fc2Account account = accountService.getAccountById(id);
        return (account != null) ? account.getLogs() : new ArrayList<>();
    }
}