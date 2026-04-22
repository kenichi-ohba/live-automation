package com.example.fc2_live_automation.controller;

import com.example.fc2_live_automation.model.Fc2Account;
import com.example.fc2_live_automation.model.Fc2Preset;
import com.example.fc2_live_automation.repository.Fc2AccountRepository;
import com.example.fc2_live_automation.repository.Fc2PresetRepository;
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
    private final Fc2AccountRepository accountRepository;
    // 🌟 修正: 使われていない AccountService を削除しました
    private final Fc2AutomationWorker worker;

    public PresetController(Fc2PresetRepository presetRepository, Fc2AccountRepository accountRepository, Fc2AutomationWorker worker) {
        this.presetRepository = presetRepository;
        this.accountRepository = accountRepository;
        this.worker = worker;
    }

    @GetMapping
    public String listPresets(Model model) {
        model.addAttribute("presets", presetRepository.findAll());
        return "index";
    }

    @GetMapping("/add")
    public String showAddForm(Model model) {
        model.addAttribute("preset", new Fc2Preset());
        model.addAttribute("allAccounts", accountRepository.findAll());
        model.addAttribute("selectedAccountIds", List.of());
        return "preset-form";
    }

    @GetMapping("/edit/{id}")
    public String showEditForm(@PathVariable Long id, Model model) {
        Fc2Preset preset = presetRepository.findById(id).orElseThrow();
        model.addAttribute("preset", preset);
        model.addAttribute("allAccounts", accountRepository.findAll());
        
        // 紐づいているアカウントのIDリストを取得して画面に渡す
        List<Long> selectedIds = preset.getAccounts().stream()
                .map(Fc2Account::getId)
                .collect(Collectors.toList());
        model.addAttribute("selectedAccountIds", selectedIds);
        
        return "preset-form";
    }

    @PostMapping("/save")
    public String savePreset(@ModelAttribute Fc2Preset preset, @RequestParam(value = "selectedAccountIds", required = false) List<Long> selectedAccountIds) {
        if (selectedAccountIds != null) {
            // 選択されたIDからアカウントの実体を取得してプリセットにセットする
            List<Fc2Account> accounts = accountRepository.findAllById(selectedAccountIds);
            preset.setAccounts(accounts);
        }
        presetRepository.save(preset);
        return "redirect:/";
    }

    @GetMapping("/start/{id}")
    public String startPreset(@PathVariable Long id) {
        Fc2Preset preset = presetRepository.findById(id).orElseThrow();
        worker.startPresetProcess(preset, preset.getAccounts());
        return "redirect:/";
    }

    @GetMapping("/stop/{id}")
    public String stopPreset(@PathVariable Long id) {
        Fc2Preset preset = presetRepository.findById(id).orElseThrow();
        worker.stopPresetProcess(preset.getPresetName());
        return "redirect:/";
    }

    @GetMapping("/delete/{id}")
    public String deletePreset(@PathVariable Long id) {
        presetRepository.findById(id).ifPresent(preset -> {
            worker.stopPresetProcess(preset.getPresetName());
            presetRepository.delete(preset);
        });
        return "redirect:/";
    }
}