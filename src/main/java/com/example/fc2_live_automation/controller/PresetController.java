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
import java.util.Comparator;
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
        
        // 1. 一旦「仮のリスト」として受け取る
        List<Fc2Account> tempPlaylist = preset.getAccounts();
        if (tempPlaylist == null || tempPlaylist.isEmpty()) {
            tempPlaylist = accountRepository.findAll().stream()
                .filter(a -> preset.getPresetName().equals(a.getPresetName()))
                .collect(Collectors.toList());
        }
        
        // 🌟 2. Javaのルールに従い、上書きされない「確定版のリスト（final）」に詰め直す
        final List<Fc2Account> playlist = new ArrayList<>(tempPlaylist);
        
        // 保存時の順番（displayOrder）に従って並び替える
        playlist.sort(Comparator.comparing(Fc2Account::getDisplayOrder, Comparator.nullsLast(Comparator.naturalOrder())));
        model.addAttribute("playlist", playlist);
        
        // これでエラーなく filter の中で playlist を使えるようになります
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

        List<Fc2Account> allAccs = accountRepository.findAll();
        for (Fc2Account acc : allAccs) {
            if (newPresetName.equals(acc.getPresetName())) {
                acc.setPresetName("");
                accountRepository.save(acc);
            }
        }

        // 🌟 ここが重要！リストに追加された順番通りに「整理番号（DisplayOrder）」を記録しながら保存する
        List<Fc2Account> finalAccounts = new ArrayList<>();
        if (selectedAccountIds != null && !selectedAccountIds.isEmpty()) {
            int orderIndex = 1;
            for (Long accountId : selectedAccountIds) {
                Fc2Account acc = accountRepository.findById(accountId).orElse(null);
                if (acc != null) {
                    acc.setPresetName(newPresetName);
                    acc.setDisplayOrder(orderIndex++); // 順番を記憶
                    accountRepository.save(acc);
                    finalAccounts.add(acc);
                }
            }
        }
        
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
        
        // 🌟 ワーカーに渡す前にも、名前で強制的に紐づいた動画を集めて確実に空っぽを防ぐ
        List<Fc2Account> tempPlaylist = preset.getAccounts();
        if (tempPlaylist == null || tempPlaylist.isEmpty()) {
            tempPlaylist = accountRepository.findAll().stream()
                .filter(a -> preset.getPresetName().equals(a.getPresetName()))
                .collect(Collectors.toList());
        }
        
        // ここも安全のため新しいリストに詰め直して並び替える
        List<Fc2Account> playlist = new ArrayList<>(tempPlaylist);
        playlist.sort(Comparator.comparing(Fc2Account::getDisplayOrder, Comparator.nullsLast(Comparator.naturalOrder())));

        worker.startPresetProcess(preset, playlist);
        return "redirect:/";
    }

    @GetMapping("/stop/{id}")
    public String stopPreset(@PathVariable Long id) {
        Fc2Preset preset = presetRepository.findById(id).orElseThrow();
        worker.stopPresetProcess(preset.getPresetName());
        return "redirect:/";
    }
}