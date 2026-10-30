package com.werewolf.room.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建房间请求（方案第六节：自定义房间）。
 *
 * <p>第一版只允许调整人数、狼人数量、AI 数量，不做任意角色拖拽配置。
 */
public class CreateRoomRequest {

    @NotBlank(message = "房间名称不能为空")
    @Size(max = 30, message = "房间名称不能超过 30 字")
    private String roomName;

    /** 房间密码，留空表示无密码。 */
    @Size(max = 32, message = "密码不能超过 32 位")
    private String password;

    @Min(value = 4, message = "房间人数至少 4 人")
    @Max(value = 13, message = "房间人数最多 13 人")
    private Integer maxPlayers = 8;

    @Min(value = 1, message = "狼人数量至少 1")
    private Integer werewolfCount = 2;

    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(contentUsing=RoleCountDeserializer.class)
    private java.util.Map<String,Integer> roleCounts;
    public java.util.Map<String,Integer> getRoleCounts() { return roleCounts; }
    public void setRoleCounts(java.util.Map<String,Integer> value) { roleCounts=value; }


    @Min(value = 0, message = "AI 数量不能为负")
    @Max(value = 12, message = "AI 数量最多 12")
    private Integer aiCount = 0;

    /**
     * 是否允许 AI 加入。
     *
     * <p>未指定时按 aiCount 推导；默认数量为零，不添加 AI。
     * 显式关闭会清零数量。此开关只控制 AI 座位，未配置外部模型时使用规则 AI。
     */
    private Boolean allowAi;

    private Boolean publicRoom = Boolean.TRUE;

    private String gameType = "WEREWOLF";
    public String getGameType(){return gameType;}
    public void setGameType(String value){gameType=value;}
    private Boolean allowInvites = true;
    public Boolean getAllowInvites(){return allowInvites;}
    public void setAllowInvites(Boolean value){allowInvites=value;}
    private Boolean voiceEnabled = false;
    public Boolean getVoiceEnabled(){return voiceEnabled;}
    public void setVoiceEnabled(Boolean value){voiceEnabled=value;}
    private Boolean privateChatEnabled = true;
    public Boolean getPrivateChatEnabled(){return privateChatEnabled;}
    public void setPrivateChatEnabled(Boolean value){privateChatEnabled=value;}

    public String getRoomName() {
        return roomName;
    }

    public void setRoomName(String roomName) {
        this.roomName = roomName;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
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
}
