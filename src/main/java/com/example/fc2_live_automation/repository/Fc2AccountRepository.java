package com.example.fc2_live_automation.repository;

import com.example.fc2_live_automation.model.Fc2Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional; // 🌟 新規追加：重複チェックで使用します

public interface Fc2AccountRepository extends JpaRepository<Fc2Account, Long> {
    
    // 🌟 既存：displayOrder の数字が小さい順（昇順）で取得するメソッド
    List<Fc2Account> findAllByOrderByDisplayOrderAsc();

    // 🌟 新規：CSVインポート時の重複チェック用メソッド（メール、動画パス、アカウント名で完全一致検索）
    Optional<Fc2Account> findByEmailAndVideoPathAndAccountName(String email, String videoPath, String accountName);
}