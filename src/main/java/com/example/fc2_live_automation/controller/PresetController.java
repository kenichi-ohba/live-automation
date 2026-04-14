package com.example.fc2_live_automation.controller;

import com.example.fc2_live_automation.model.Fc2Account;
import com.example.fc2_live_automation.model.Fc2Preset;
import com.example.fc2_live_automation.repository.Fc2PresetRepository;
import com.example.fc2_live_automation.service.AccountService;
import com.example.fc2_live_automation.service.Fc2AutomationWorker;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/presets")
public class PresetController {

    private final Fc2PresetRepository presetRepository;
    private final AccountService accountService;
    private final Fc2AutomationWorker worker;

    public PresetController(Fc2PresetRepository presetRepository, AccountService accountService, Fc2AutomationWorker worker) {
        this.presetRepository = presetRepository;
        this.accountService = accountService;
        this.worker = worker;
    }

    @GetMapping("/add")
    public String showPresetForm(Model model) {
        model.addAttribute("preset", new Fc2Preset());
        model.addAttribute("allAccounts", accountService.getAllAccounts());
        return "preset-form";
    }

    @GetMapping("/editByName")
    public String editPresetByName(@RequestParam String name, Model model) {
        Fc2Preset preset = presetRepository.findAll().stream()
                .filter(p -> name.equals(p.getPresetName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("見つかりません: " + name));
        
        model.addAttribute("preset", preset);
        model.addAttribute("allAccounts", accountService.getAllAccounts());
        return "preset-form";
    }

    @PostMapping("/save")
    public String savePreset(@ModelAttribute Fc2Preset preset) {
        if (preset.getStatus() == null || preset.getStatus().isEmpty()) {
            preset.setStatus("IDLE");
        }
        if (preset.getCurrentLoop() == null) {
            preset.setCurrentLoop(0);
        }
        
        presetRepository.save(preset);
        
        if (preset.getAccountIds() != null && !preset.getAccountIds().isEmpty()) {
            String[] ids = preset.getAccountIds().split(",");
            for (String idStr : ids) {
                try {
                    Long accountId = Long.parseLong(idStr);
                    Fc2Account acc = accountService.getAccountById(accountId);
                    acc.setPresetName(preset.getPresetName());
                    accountService.saveAccount(acc);
                } catch (Exception e) {}
            }
        }
        return "redirect:/";
    }

    @GetMapping("/delete/{id}")
    public String deletePreset(@PathVariable Long id) {
        presetRepository.deleteById(id);
        return "redirect:/";
    }

    // ==========================================
    // 🌟 プリセットの一括再生・停止 コントロール
    // ==========================================
    
    @GetMapping("/start")
    public String startPreset(@RequestParam String name) {
        // 名前からプリセット情報を検索
        Fc2Preset preset = presetRepository.findAll().stream()
                .filter(p -> name.equals(p.getPresetName()))
                .findFirst().orElse(null);
        
        if (preset != null && preset.getAccountIds() != null) {
            // カンマ区切りのIDから、実際のアカウント情報を取得して並べる
            List<Fc2Account> playlist = accountService.getAllAccounts().stream()
                    .filter(acc -> name.equals(acc.getPresetName()))
                    .collect(Collectors.toList());
            
            // Workerに「このプリセットを、このプレイリストで再生して！」と命令
            worker.startPresetProcess(preset, playlist);
        }
        return "redirect:/";
    }

    @GetMapping("/stop")
    public String stopPreset(@RequestParam String name) {
        // Workerに「このプリセットを止めて！」と命令
        worker.stopPresetProcess(name);
        return "redirect:/";
    }
}