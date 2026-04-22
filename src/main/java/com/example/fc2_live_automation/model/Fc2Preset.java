package com.example.fc2_live_automation.model;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
public class Fc2Preset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String presetName;
    private int loopCount;
    private boolean shuffleMode;
    private int loopWaitMinutes;
    private String status;
    private String scheduledStartTime;
    
    // 🌟 追加：現在のループ実行回数を保持するフィールド
    private int currentLoop;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "preset_accounts",
        joinColumns = @JoinColumn(name = "preset_id"),
        inverseJoinColumns = @JoinColumn(name = "account_id")
    )
    private List<Fc2Account> accounts = new ArrayList<>();

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPresetName() { return presetName; }
    public void setPresetName(String presetName) { this.presetName = presetName; }

    public int getLoopCount() { return loopCount; }
    public void setLoopCount(int loopCount) { this.loopCount = loopCount; }

    public boolean isShuffleMode() { return shuffleMode; }
    public void setShuffleMode(boolean shuffleMode) { this.shuffleMode = shuffleMode; }

    public int getLoopWaitMinutes() { return loopWaitMinutes; }
    public void setLoopWaitMinutes(int loopWaitMinutes) { this.loopWaitMinutes = loopWaitMinutes; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getScheduledStartTime() { return scheduledStartTime; }
    public void setScheduledStartTime(String scheduledStartTime) { this.scheduledStartTime = scheduledStartTime; }

    // 🌟 追加：currentLoopのGetter/Setter
    public int getCurrentLoop() { return currentLoop; }
    public void setCurrentLoop(int currentLoop) { this.currentLoop = currentLoop; }

    public List<Fc2Account> getAccounts() { return accounts; }
    public void setAccounts(List<Fc2Account> accounts) { this.accounts = accounts; }
}