package com.atakmap.android.murmurptt.model;

/**
 * Representación de un usuario en Murmur
 */
public class MurmurUser {
    
    private int sessionId;
    private String name;
    private int channelId;
    private boolean mute;
    private boolean deaf;
    private boolean suppress;
    private boolean selfMute;
    private boolean selfDeaf;
    private boolean prioritySpeaker;
    private boolean recording;
    private String comment;
    private boolean locallyMuted;
    private long lastActivity;
    
    public MurmurUser(int sessionId, String name) {
        this.sessionId = sessionId;
        this.name = name;
        this.lastActivity = System.currentTimeMillis();
    }
    
    // Getters y Setters
    public int getSessionId() { return sessionId; }
    public String getName() { return name; }
    public int getChannelId() { return channelId; }
    public void setChannelId(int channelId) { this.channelId = channelId; }
    
    public boolean isMute() { return mute; }
    public void setMute(boolean mute) { this.mute = mute; }
    
    public boolean isDeaf() { return deaf; }
    public void setDeaf(boolean deaf) { this.deaf = deaf; }
    
    public boolean isSuppress() { return suppress; }
    public void setSuppress(boolean suppress) { this.suppress = suppress; }
    
    public boolean isSelfMute() { return selfMute; }
    public void setSelfMute(boolean selfMute) { this.selfMute = selfMute; }
    
    public boolean isSelfDeaf() { return selfDeaf; }
    public void setSelfDeaf(boolean selfDeaf) { this.selfDeaf = selfDeaf; }
    
    public boolean isPrioritySpeaker() { return prioritySpeaker; }
    public void setPrioritySpeaker(boolean prioritySpeaker) { this.prioritySpeaker = prioritySpeaker; }
    
    public boolean isRecording() { return recording; }
    public void setRecording(boolean recording) { this.recording = recording; }
    
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    
    public boolean isLocallyMuted() { return locallyMuted; }
    public void setLocallyMuted(boolean locallyMuted) { this.locallyMuted = locallyMuted; }
    
    public boolean isSpeaking() {
        return System.currentTimeMillis() - lastActivity < 500;
    }
    
    public void updateActivity() {
        this.lastActivity = System.currentTimeMillis();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MurmurUser that = (MurmurUser) o;
        return sessionId == that.sessionId;
    }
    
    @Override
    public int hashCode() {
        return sessionId;
    }
    
    @Override
    public String toString() {
        return name + (locallyMuted ? " [MUTE]" : "");
    }
}
