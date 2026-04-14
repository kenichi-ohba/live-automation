package com.example.fc2_live_automation.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Fc2Preset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String presetName;          // プリセット名（例：深夜用プレイリスト）
    private String accountIds;          // 再生するアカウントのIDリスト（例："1,4,2"）
    
    private int loopCount = 0;          // 全体のループ回数（0=無限）
    private Integer currentLoop = 0;    // 現在のループ回数
    private Integer loopWaitMinutes = 0;// 1周終わったあとの休憩時間（分）
    private String scheduledStartTime;  // 予約日時
    
    private boolean shuffleMode = false;// 🌟 提案2: シャッフル再生モード
    private String status = "IDLE";     // 稼働ステータス

    // Getter & Setter
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPresetName() { return presetName; }
    public void setPresetName(String presetName) { this.presetName = presetName; }
    public String getAccountIds() { return accountIds; }
    public void setAccountIds(String accountIds) { this.accountIds = accountIds; }
    public int getLoopCount() { return loopCount; }
    public void setLoopCount(int loopCount) { this.loopCount = loopCount; }
    public Integer getCurrentLoop() { return currentLoop; }
    public void setCurrentLoop(Integer currentLoop) { this.currentLoop = currentLoop; }
    public Integer getLoopWaitMinutes() { return loopWaitMinutes; }
    public void setLoopWaitMinutes(Integer loopWaitMinutes) { this.loopWaitMinutes = loopWaitMinutes; }
    public String getScheduledStartTime() { return scheduledStartTime; }
    public void setScheduledStartTime(String scheduledStartTime) { this.scheduledStartTime = scheduledStartTime; }
    public boolean isShuffleMode() { return shuffleMode; }
    public void setShuffleMode(boolean shuffleMode) { this.shuffleMode = shuffleMode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}