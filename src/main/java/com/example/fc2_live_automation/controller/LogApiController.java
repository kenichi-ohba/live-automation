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

    // 画面からのリクエストに対して、そのアカウントの最新ログ100行をJSONで返す
    @GetMapping("/{id}")
    public List<String> getAccountLogs(@PathVariable Long id) {
        return worker.getLogs(id);
    }
}