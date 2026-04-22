package com.example.fc2_live_automation.controller;

import com.example.fc2_live_automation.model.Fc2Account;
import com.example.fc2_live_automation.model.Fc2Preset;
import com.example.fc2_live_automation.repository.Fc2AccountRepository;
import com.example.fc2_live_automation.repository.Fc2PresetRepository;
import com.example.fc2_live_automation.service.Fc2AutomationWorker;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/presets")
public class PresetController {

    private final Fc2PresetRepository presetRepository;
    private final Fc2AccountRepository accountRepository;
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
        model.addAttribute("availableAccounts", accountRepository.findAll());
        model.addAttribute("playlist", List.of());
        return "preset-form";
    }

    @GetMapping("/edit/{id}")
    public String showEditForm(@PathVariable Long id, Model model) {
        Fc2Preset preset = presetRepository.findById(id).orElseThrow();
        model.addAttribute("preset", preset);
        
        List<Fc2Account> playlist = preset.getAccounts();
        model.addAttribute("playlist", playlist);
        
        List<Fc2Account> allAccounts = accountRepository.findAll();
        List<Fc2Account> availableAccounts = allAccounts.stream()
                .filter(acc -> playlist.stream().noneMatch(p -> p.getId().equals(acc.getId())))
                .collect(Collectors.toList());
        model.addAttribute("availableAccounts", availableAccounts);
        
        return "preset-form";
    }

    @PostMapping("/save")
    public String savePreset(@ModelAttribute Fc2Preset preset, @RequestParam(value = "selectedAccountIds", required = false) List<Long> selectedAccountIds) {
        String newPresetName = preset.getPresetName();

        // 1. 古いプリセット名を保持していたアカウントの名前を一旦クリアする
        if (preset.getId() != null) {
            presetRepository.findById(preset.getId()).ifPresent(oldPreset -> {
                String oldName = oldPreset.getPresetName();
                if (!oldName.equals(newPresetName)) {
                    List<Fc2Account> oldAccs = accountRepository.findAll();
                    for (Fc2Account acc : oldAccs) {
                        if (oldName.equals(acc.getPresetName())) {
                            acc.setPresetName("");
                            accountRepository.save(acc);
                        }
                    }
                }
            });
        }

        // 2. 重複防止のため、今回の新しい名前を持っているアカウントも一旦所属をクリアする
        List<Fc2Account> allAccs = accountRepository.findAll();
        for (Fc2Account acc : allAccs) {
            if (newPresetName.equals(acc.getPresetName())) {
                acc.setPresetName("");
                accountRepository.save(acc);
            }
        }

        // 3. 🌟 今回チェックされたアカウントリストを構築し、プリセットとアカウントの両方に情報をセットする
        List<Fc2Account> finalAccounts = new ArrayList<>();
        if (selectedAccountIds != null && !selectedAccountIds.isEmpty()) {
            for (Long accountId : selectedAccountIds) {
                accountRepository.findById(accountId).ifPresent(acc -> {
                    acc.setPresetName(newPresetName);
                    accountRepository.save(acc);
                    finalAccounts.add(acc);
                });
            }
        }
        
        // 🌟 プリセット本体にアカウントリストを紐づけて保存！これが抜けていました。
        preset.setAccounts(finalAccounts);
        presetRepository.save(preset);
        
        return "redirect:/";
    }

    @GetMapping("/delete/{id}")
    public String deletePreset(@PathVariable Long id) {
        presetRepository.findById(id).ifPresent(preset -> {
            worker.stopPresetProcess(preset.getPresetName());
            
            List<Fc2Account> accounts = accountRepository.findAll();
            for (Fc2Account acc : accounts) {
                if (preset.getPresetName().equals(acc.getPresetName())) {
                    acc.setPresetName(""); 
                    accountRepository.save(acc);
                }
            }
            
            presetRepository.delete(preset);
        });
        return "redirect:/";
    }

    @GetMapping("/start/{id}")
    public String startPreset(@PathVariable Long id) {
        Fc2Preset preset = presetRepository.findById(id).orElseThrow();
        // 🌟 再生開始時、紐づけられているアカウントリスト（動画たち）と一緒にワーカーへ渡す
        worker.startPresetProcess(preset, preset.getAccounts());
        return "redirect:/";
    }

    @GetMapping("/stop/{id}")
    public String stopPreset(@PathVariable Long id) {
        Fc2Preset preset = presetRepository.findById(id).orElseThrow();
        worker.stopPresetProcess(preset.getPresetName());
        return "redirect:/";
    }
}