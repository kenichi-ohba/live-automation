package com.example.fc2_live_automation.repository;

import com.example.fc2_live_automation.model.Fc2Account;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface Fc2AccountRepository extends JpaRepository<Fc2Account, Long> {
    // 🌟 displayOrder の数字が小さい順（昇順）で取得するメソッドを追加
    List<Fc2Account> findAllByOrderByDisplayOrderAsc();
}