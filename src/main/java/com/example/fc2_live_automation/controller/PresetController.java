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
    private final Fc2AutomationWorker worker;

    public PresetController(Fc2PresetRepository presetRepository, 
                            Fc2AccountRepository accountRepository,
                            Fc2AutomationWorker worker) {
        this.presetRepository = presetRepository;
        this.accountRepository = accountRepository;
        this.worker = worker;
    }

    @GetMapping("/add")
    public String showAddForm(Model model) {
        model.addAttribute("preset", new Fc2Preset());
        
        // 🌟 修正：新規作成時は、左側の候補に「すべてのアカウント」を表示する
        List<Fc2Account> availableAccounts = accountRepository.findAll();
        
        model.addAttribute("availableAccounts", availableAccounts);
        model.addAttribute("playlist", List.of()); // 新規作成時は右側（再生リスト）は空
        
        return "preset-form";
    }

    @GetMapping("/editByName")
    public String editPresetByName(@RequestParam("name") String name, Model model) {
        Fc2Preset preset = presetRepository.findAll().stream()
                .filter(p -> name.equals(p.getPresetName()))
                .findFirst()
                .orElse(new Fc2Preset());
        preset.setPresetName(name);

        // 右側：このプリセットに所属するアカウントを displayOrder 順で取得
        List<Fc2Account> playlist = accountRepository.findAllByOrderByDisplayOrderAsc().stream()
                .filter(a -> name.equals(a.getPresetName()))
                .collect(Collectors.toList());

        // 🌟 修正：左側には「このプリセット『以外』のすべてのアカウント」を表示する
        // （他のプリセットに属しているものも表示し、大庭さんの未登録フィルターで絞り込めるようにする）
        List<Fc2Account> availableAccounts = accountRepository.findAll().stream()
                .filter(a -> a.getPresetName() == null || !a.getPresetName().equals(name))
                .collect(Collectors.toList());

        model.addAttribute("preset", preset);
        model.addAttribute("playlist", playlist);
        model.addAttribute("availableAccounts", availableAccounts);

        return "preset-form";
    }

    @PostMapping("/save")
    public String savePreset(@ModelAttribute Fc2Preset preset, @RequestParam(required = false) List<Long> playlistIds) {
        
        Fc2Preset existing = presetRepository.findAll().stream()
                .filter(p -> preset.getPresetName().equals(p.getPresetName()))
                .findFirst()
                .orElse(null);

        if (existing != null) {
            preset.setId(existing.getId()); 
            preset.setStatus(existing.getStatus());
            preset.setCurrentLoop(existing.getCurrentLoop());
            preset.setScheduledStartTime(existing.getScheduledStartTime());
        } else {
            if (preset.getStatus() == null || preset.getStatus().isEmpty()) preset.setStatus("IDLE");
            if (preset.getCurrentLoop() == null) preset.setCurrentLoop(0);
        }

        // 1. プリセット本体を保存
        presetRepository.save(preset);

        // 2. プレイリスト（所属アカウント）の順番を更新
        if (playlistIds != null) {
            for (int i = 0; i < playlistIds.size(); i++) {
                Long accId = playlistIds.get(i);
                Fc2Account acc = accountRepository.findById(accId).orElse(null);
                if (acc != null) {
                    acc.setPresetName(preset.getPresetName());
                    acc.setDisplayOrder(i);
                    accountRepository.save(acc);
                }
            }
        }
        
        // 3. このプリセットから外されたアカウントの紐付けを解除
        List<Fc2Account> allInPreset = accountRepository.findAll().stream()
                .filter(a -> preset.getPresetName().equals(a.getPresetName()))
                .collect(Collectors.toList());
        
        for (Fc2Account acc : allInPreset) {
            if (playlistIds == null || !playlistIds.contains(acc.getId())) {
                acc.setPresetName("");
                acc.setDisplayOrder(999); 
                accountRepository.save(acc);
            }
        }

        return "redirect:/";
    }

    @GetMapping("/start")
    public String startPreset(@RequestParam("name") String name) {
        Fc2Preset preset = presetRepository.findAll().stream()
                .filter(p -> name.equals(p.getPresetName()))
                .findFirst()
                .orElse(null);

        if (preset != null) {
            List<Fc2Account> playlist = accountRepository.findAllByOrderByDisplayOrderAsc().stream()
                    .filter(a -> name.equals(a.getPresetName()))
                    .collect(Collectors.toList());
            
            preset.setStatus("RUNNING");
            presetRepository.save(preset);
            worker.startPresetProcess(preset, playlist);
        }
        return "redirect:/";
    }

    @GetMapping("/stop")
    public String stopPreset(@RequestParam("name") String name) {
        Fc2Preset preset = presetRepository.findAll().stream()
                .filter(p -> name.equals(p.getPresetName()))
                .findFirst()
                .orElse(null);

        if (preset != null) {
            preset.setStatus("IDLE");
            presetRepository.save(preset);
            worker.stopPresetProcess(name);
        }
        return "redirect:/";
    }

    @GetMapping("/delete/{id}")
    public String deletePreset(@PathVariable Long id) {
        presetRepository.deleteById(id);
        return "redirect:/";
    }
}