package com.example.fc2_live_automation.model;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
public class Fc2Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;
    private String pass;
    private String videoPath;
    
    private boolean useThumbnail = false; 
    private String thumbnailPath;

    // 🌟 修正：文字列をやめ、「分」と「秒」の数値に分割して確実にする
    private Integer paidSwitchMinute = 0;
    private Integer paidSwitchSecond = 0;

    private String title;
    
    @Column(length = 1000)
    private String info;
    
    private Integer category;
    private Integer adultflg;
    private Integer feeflg;
    private Integer loginonlyflg;
    private Integer feesetting;
    private Integer feeinterval;
    private Integer feeamount;
    
    private String status;
    private String broadcastUrl;

    @Transient 
    private List<String> logs = new ArrayList<>();

    private String serverUrl;
    private String streamKey;
    private int loopCount;
    private boolean showBrowser = false;

    public void addLog(String message) {
        if (logs == null) logs = new ArrayList<>();
        if (logs.size() >= 100) logs.remove(0);
        logs.add(message);
    }
    public List<String> getLogs() { return logs; }

    // --- Getter & Setter ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPass() { return pass; }
    public void setPass(String pass) { this.pass = pass; }
    public String getVideoPath() { return videoPath; }
    public void setVideoPath(String videoPath) { this.videoPath = videoPath; }
    public boolean isUseThumbnail() { return useThumbnail; }
    public void setUseThumbnail(boolean useThumbnail) { this.useThumbnail = useThumbnail; }
    public String getThumbnailPath() { return thumbnailPath; }
    public void setThumbnailPath(String thumbnailPath) { this.thumbnailPath = thumbnailPath; }
    
    // 🌟 新しい項目のGetter/Setter
    public Integer getPaidSwitchMinute() { return paidSwitchMinute; }
    public void setPaidSwitchMinute(Integer paidSwitchMinute) { this.paidSwitchMinute = paidSwitchMinute; }
    public Integer getPaidSwitchSecond() { return paidSwitchSecond; }
    public void setPaidSwitchSecond(Integer paidSwitchSecond) { this.paidSwitchSecond = paidSwitchSecond; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getInfo() { return info; }
    public void setInfo(String info) { this.info = info; }
    public Integer getCategory() { return category; }
    public void setCategory(Integer category) { this.category = category; }
    public Integer getAdultflg() { return adultflg; }
    public void setAdultflg(Integer adultflg) { this.adultflg = adultflg; }
    public Integer getFeeflg() { return feeflg; }
    public void setFeeflg(Integer feeflg) { this.feeflg = feeflg; }
    public Integer getLoginonlyflg() { return loginonlyflg; }
    public void setLoginonlyflg(Integer loginonlyflg) { this.loginonlyflg = loginonlyflg; }
    public Integer getFeesetting() { return feesetting; }
    public void setFeesetting(Integer feesetting) { this.feesetting = feesetting; }
    public Integer getFeeinterval() { return feeinterval; }
    public void setFeeinterval(Integer feeinterval) { this.feeinterval = feeinterval; }
    public Integer getFeeamount() { return feeamount; }
    public void setFeeamount(Integer feeamount) { this.feeamount = feeamount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getBroadcastUrl() { return broadcastUrl; }
    public void setBroadcastUrl(String broadcastUrl) { this.broadcastUrl = broadcastUrl; }
    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }
    public String getStreamKey() { return streamKey; }
    public void setStreamKey(String streamKey) { this.streamKey = streamKey; }
    public int getLoopCount() { return loopCount; }
    public void setLoopCount(int loopCount) { this.loopCount = loopCount; }
    public boolean isShowBrowser() { return showBrowser; }
    public void setShowBrowser(boolean showBrowser) { this.showBrowser = showBrowser; }
}