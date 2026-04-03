package com.example.fc2_live_automation.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.fc2_live_automation.model.Fc2Account;

import java.util.List;

@Repository
public interface Fc2AccountRepository extends JpaRepository<Fc2Account, Long> {
    
    // Status（ステータス：待機中、エラーなど）でアカウントを検索するための便利なMethod（メソッド）
    List<Fc2Account> findByStatus(String status);
}