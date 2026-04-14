package com.example.fc2_live_automation.controller;

import com.example.fc2_live_automation.model.Fc2Account;
import com.example.fc2_live_automation.model.Fc2Preset;
import com.example.fc2_live_automation.repository.Fc2PresetRepository;
import com.example.fc2_live_automation.service.AccountService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;


@Controller
@RequestMapping("/presets")
public class PresetController {

    private final Fc2PresetRepository presetRepository;
    private final AccountService accountService;

    public PresetController(Fc2PresetRepository presetRepository, AccountService accountService) {
        this.presetRepository = presetRepository;
        this.accountService = accountService;
    }

    // プリセット作成画面の表示
    @GetMapping("/add")
    public String showPresetForm(Model model) {
        model.addAttribute("preset", new Fc2Preset());
        // 登録されているすべてのアカウント（素材）を画面に渡す
        model.addAttribute("allAccounts", accountService.getAllAccounts());
        return "preset-form";
    }

    // プリセット編集画面の表示
    @GetMapping("/edit/{id}")
    public String editPresetForm(@PathVariable Long id, Model model) {
        Fc2Preset preset = presetRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Invalid preset Id:" + id));
        model.addAttribute("preset", preset);
        model.addAttribute("allAccounts", accountService.getAllAccounts());
        return "preset-form";
    }

    // プリセットの保存
    @PostMapping("/save")
    public String savePreset(@ModelAttribute Fc2Preset preset) {
        if (preset.getStatus() == null || preset.getStatus().isEmpty()) {
            preset.setStatus("IDLE");
        }
        if (preset.getCurrentLoop() == null) {
            preset.setCurrentLoop(0);
        }
        
        presetRepository.save(preset);
        
        // 🌟 保存後、選ばれたアカウントたちの「所属プリセット名」を一括で更新する
        if (preset.getAccountIds() != null && !preset.getAccountIds().isEmpty()) {
            String[] ids = preset.getAccountIds().split(",");
            for (String idStr : ids) {
                try {
                    Long accountId = Long.parseLong(idStr);
                    Fc2Account acc = accountService.getAccountById(accountId);
                    acc.setPresetName(preset.getPresetName());
                    accountService.saveAccount(acc); // 再保存
                } catch (Exception e) {
                    // 無視（不正なIDなど）
                }
            }
        }
        return "redirect:/"; // 保存後はダッシュボードへ戻る
    }

    // プリセットの削除
    @GetMapping("/delete/{id}")
    public String deletePreset(@PathVariable Long id) {
        // 削除する前に、このプリセットに属していたアカウントの presetName をクリアする処理を追加することも可能
        presetRepository.deleteById(id);
        return "redirect:/";
    }

    // 🌟 プリセット名から編集画面を開く機能
    @GetMapping("/editByName")
    public String editPresetByName(@RequestParam String name, Model model) {
        // 同じ名前のプリセットを探す（簡易的な検索）
        Fc2Preset preset = presetRepository.findAll().stream()
                .filter(p -> name.equals(p.getPresetName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("見つかりません: " + name));
        
        model.addAttribute("preset", preset);
        model.addAttribute("allAccounts", accountService.getAllAccounts());
        return "preset-form";
    }
}