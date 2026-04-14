package com.example.fc2_live_automation.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;

@Entity
public class Fc2Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String accountName;
    private String videoDuration;

    private String email;
    private String pass;
    private String title;
    private String info;
    private Integer category = 5;

    private Integer adultflg = 0;
    private Integer loginonlyflg = 0;
    private Integer feeflg = 0;
    private Integer feesetting = 0;
    private Integer feeinterval = 60;
    private Integer feeamount = 80;

    private String videoPath;
    private String streamKey;
    private String serverUrl = "rtmp://video.live.fc2.com/live/";
    private boolean useThumbnail = false;
    private String thumbnailPath;

    private Integer paidSwitchMinute = 0;
    private Integer paidSwitchSecond = 0;
    private Integer switchLagSeconds = 5;

    private boolean showBrowser = false;
    private int loopCount = 0;
    private Integer currentLoop = 0;

    private String status = "IDLE";
    private String broadcastUrl;

    private String customLiveUrl;
    private String scheduledStartTime;
    private Integer loopWaitMinutes = 0;
    private String presetName;

    // 🌟 DBには保存せず、画面に渡すためだけの「一時的な箱」
    @Transient
    private String formattedPaidSwitchTime;

    public String getFormattedPaidSwitchTime() {
        return formattedPaidSwitchTime;
    }

    public void setFormattedPaidSwitchTime(String formattedPaidSwitchTime) {
        this.formattedPaidSwitchTime = formattedPaidSwitchTime;
    }

    // Getter & Setter
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public String getVideoDuration() {
        return videoDuration;
    }

    public void setVideoDuration(String videoDuration) {
        this.videoDuration = videoDuration;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPass() {
        return pass;
    }

    public void setPass(String pass) {
        this.pass = pass;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getInfo() {
        return info;
    }

    public void setInfo(String info) {
        this.info = info;
    }

    public Integer getCategory() {
        return category;
    }

    public void setCategory(Integer category) {
        this.category = category;
    }

    public Integer getAdultflg() {
        return adultflg;
    }

    public void setAdultflg(Integer adultflg) {
        this.adultflg = adultflg;
    }

    public Integer getLoginonlyflg() {
        return loginonlyflg;
    }

    public void setLoginonlyflg(Integer loginonlyflg) {
        this.loginonlyflg = loginonlyflg;
    }

    public Integer getFeeflg() {
        return feeflg;
    }

    public void setFeeflg(Integer feeflg) {
        this.feeflg = feeflg;
    }

    public Integer getFeesetting() {
        return feesetting;
    }

    public void setFeesetting(Integer feesetting) {
        this.feesetting = feesetting;
    }

    public Integer getFeeinterval() {
        return feeinterval;
    }

    public void setFeeinterval(Integer feeinterval) {
        this.feeinterval = feeinterval;
    }

    public Integer getFeeamount() {
        return feeamount;
    }

    public void setFeeamount(Integer feeamount) {
        this.feeamount = feeamount;
    }

    public String getVideoPath() {
        return videoPath;
    }

    public void setVideoPath(String videoPath) {
        this.videoPath = videoPath;
    }

    public String getStreamKey() {
        return streamKey;
    }

    public void setStreamKey(String streamKey) {
        this.streamKey = streamKey;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public boolean isUseThumbnail() {
        return useThumbnail;
    }

    public void setUseThumbnail(boolean useThumbnail) {
        this.useThumbnail = useThumbnail;
    }

    public String getThumbnailPath() {
        return thumbnailPath;
    }

    public void setThumbnailPath(String thumbnailPath) {
        this.thumbnailPath = thumbnailPath;
    }

    public Integer getPaidSwitchMinute() {
        return paidSwitchMinute;
    }

    public void setPaidSwitchMinute(Integer paidSwitchMinute) {
        this.paidSwitchMinute = paidSwitchMinute;
    }

    public Integer getPaidSwitchSecond() {
        return paidSwitchSecond;
    }

    public void setPaidSwitchSecond(Integer paidSwitchSecond) {
        this.paidSwitchSecond = paidSwitchSecond;
    }

    public Integer getSwitchLagSeconds() {
        return switchLagSeconds;
    }

    public void setSwitchLagSeconds(Integer switchLagSeconds) {
        this.switchLagSeconds = switchLagSeconds;
    }

    public boolean isShowBrowser() {
        return showBrowser;
    }

    public void setShowBrowser(boolean showBrowser) {
        this.showBrowser = showBrowser;
    }

    public int getLoopCount() {
        return loopCount;
    }

    public void setLoopCount(int loopCount) {
        this.loopCount = loopCount;
    }

    public Integer getCurrentLoop() {
        return currentLoop;
    }

    public void setCurrentLoop(Integer currentLoop) {
        this.currentLoop = currentLoop;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getBroadcastUrl() {
        return broadcastUrl;
    }

    public void setBroadcastUrl(String broadcastUrl) {
        this.broadcastUrl = broadcastUrl;
    }

    public String getCustomLiveUrl() {
        return customLiveUrl;
    }

    public void setCustomLiveUrl(String customLiveUrl) {
        this.customLiveUrl = customLiveUrl;
    }

    public String getScheduledStartTime() {
        return scheduledStartTime;
    }

    public void setScheduledStartTime(String scheduledStartTime) {
        this.scheduledStartTime = scheduledStartTime;
    }

    public Integer getLoopWaitMinutes() {
        return loopWaitMinutes;
    }

    public void setLoopWaitMinutes(Integer loopWaitMinutes) {
        this.loopWaitMinutes = loopWaitMinutes;
    }

    public String getPresetName() {
        return presetName;
    }

    public void setPresetName(String presetName) {
        this.presetName = presetName;
    }
}