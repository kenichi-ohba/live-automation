package com.example.fc2_live_automation.controller;

import com.example.fc2_live_automation.service.Fc2AutomationWorker;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/logs")
public class LogApiController {

    private final Fc2AutomationWorker worker;

    public LogApiController(Fc2AutomationWorker worker) {
        this.worker = worker;
    }

    // 🌟 アカウントIDではなく、プリセット名でログを取得する
    @GetMapping("/preset/{name}")
    public List<String> getPresetLogs(@PathVariable String name) {
        return worker.getPresetLogs(name);
    }
}