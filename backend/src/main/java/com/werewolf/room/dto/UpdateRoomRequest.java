package com.werewolf.room.dto;

import jakarta.validation.constraints.Size;

/**
 * 修改房间配置（仅房主、仅等待阶段）。
 */
public class UpdateRoomRequest {

    @Size(max = 30, message = "房间名称不能超过 30 字")
    private String roomName;

    private Integer maxPlayers;

    private Integer werewolfCount;

    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(contentUsing=RoleCountDeserializer.class)
    private java.util.Map<String,Integer> roleCounts;
    public java.util.Map<String,Integer> getRoleCounts() { return roleCounts; }
    public void setRoleCounts(java.util.Map<String,Integer> value) { roleCounts=value; }


    private Boolean resetRoleCounts;
    public Boolean getResetRoleCounts(){return resetRoleCounts;}
    public void setResetRoleCounts(Boolean value){resetRoleCounts=value;}

    private Integer aiCount;

    private Boolean allowAi;

    private Boolean publicRoom;

    @Size(max=32,message="密码不能超过32位")
    private String password;

    /** 是否清除密码。 */
    private Boolean clearPassword;

    private Boolean allowInvites;
    public Boolean getAllowInvites(){return allowInvites;}
    public void setAllowInvites(Boolean value){allowInvites=value;}
    private Boolean voiceEnabled;
    public Boolean getVoiceEnabled(){return voiceEnabled;}
    public void setVoiceEnabled(Boolean value){voiceEnabled=value;}
    private Boolean privateChatEnabled;
    public Boolean getPrivateChatEnabled(){return privateChatEnabled;}
    public void setPrivateChatEnabled(Boolean value){privateChatEnabled=value;}

    public String getRoomName() {
        return roomName;
    }

    public void setRoomName(String roomName) {
        this.roomName = roomName;
    }

    public Integer getMaxPlayers() {
        return maxPlayers;
    }

    public void setMaxPlayers(Integer maxPlayers) {
        this.maxPlayers = maxPlayers;
    }

    public Integer getWerewolfCount() {
        return werewolfCount;
    }

    public void setWerewolfCount(Integer werewolfCount) {
        this.werewolfCount = werewolfCount;
    }

    public Integer getAiCount() {
        return aiCount;
    }

    public void setAiCount(Integer aiCount) {
        this.aiCount = aiCount;
    }

    public Boolean getAllowAi() {
        return allowAi;
    }

    public void setAllowAi(Boolean allowAi) {
        this.allowAi = allowAi;
    }

    public Boolean getPublicRoom() {
        return publicRoom;
    }

    public void setPublicRoom(Boolean publicRoom) {
        this.publicRoom = publicRoom;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Boolean getClearPassword() {
        return clearPassword;
    }

    public void setClearPassword(Boolean clearPassword) {
        this.clearPassword = clearPassword;
    }
}
