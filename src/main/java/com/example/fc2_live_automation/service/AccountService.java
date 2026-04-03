package com.example.fc2_live_automation.service;

import com.example.fc2_live_automation.model.Fc2Account;
import com.example.fc2_live_automation.repository.Fc2AccountRepository;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class AccountService {

    private final Fc2AccountRepository repository;
    private final Fc2AutomationWorker fc2AutomationWorker;

    public AccountService(Fc2AccountRepository repository, Fc2AutomationWorker fc2AutomationWorker) {
        this.repository = repository;
        this.fc2AutomationWorker = fc2AutomationWorker;
    }

    public List<Fc2Account> getAllAccounts() {
        return repository.findAll();
    }

    public Fc2Account getAccountById(Long id) {
        return repository.findById(id).orElse(null);
    }

<<<<<<< HEAD
    public void saveAccount(Fc2Account account) {
        if (account.getId() != null) {
            Fc2Account existing = getAccountById(account.getId());
            if (existing != null) {
                if (account.getPass() == null || account.getPass().isEmpty()) {
                    account.setPass(existing.getPass());
                }
                if (account.getStreamKey() == null || account.getStreamKey().isEmpty()) {
                    account.setStreamKey(existing.getStreamKey());
                }
=======
    // 🌟 修正：編集時にパスワードとストリームキーが消えないように保護する
    public void saveAccount(Fc2Account account) {
        if (account.getId() != null) {
            // 既存アカウントの編集の場合
            Fc2Account existing = getAccountById(account.getId());
            if (existing != null) {
                // パスワードが空なら元のものを維持
                if (account.getPass() == null || account.getPass().isEmpty()) {
                    account.setPass(existing.getPass());
                }
                // ストリームキーが空なら元のものを維持
                if (account.getStreamKey() == null || account.getStreamKey().isEmpty()) {
                    account.setStreamKey(existing.getStreamKey());
                }
                // ステータスとURLを維持
>>>>>>> 03c382712fca97b74dbaa62e3ded248cec43418e
                account.setStatus(existing.getStatus());
                account.setBroadcastUrl(existing.getBroadcastUrl());
            }
        } else {
<<<<<<< HEAD
=======
            // 新規作成の場合
>>>>>>> 03c382712fca97b74dbaa62e3ded248cec43418e
            if (account.getStatus() == null) {
                account.setStatus("IDLE");
            }
        }
        repository.save(account);
    }

    public void deleteAccount(Long id) {
        stopStreaming(id);
        repository.deleteById(id);
    }

    public void startStreaming(Long id) {
        Fc2Account account = getAccountById(id);
        if (account != null) {
            account.setStatus("STARTING");
            repository.save(account);
            fc2AutomationWorker.startStreamingProcess(account);
        }
    }

    public void stopStreaming(Long id) {
        fc2AutomationWorker.stopStreamingProcess(id);
    }
<<<<<<< HEAD

    // 🌟 新規：Workerから直接ログを取得してコントローラーに渡す
    public List<String> getLogs(Long id) {
        return fc2AutomationWorker.getLogs(id);
    }
=======
>>>>>>> 03c382712fca97b74dbaa62e3ded248cec43418e
}