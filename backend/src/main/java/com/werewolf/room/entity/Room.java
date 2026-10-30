package com.werewolf.room.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * rooms 表（方案第十节）。房间的“实时”状态（谁在线、谁准备）在内存 RoomRuntime 中，
 * 本表只落库房间的创建信息与配置。
 */
@TableName(value="rooms", autoResultMap=true)
public class Room {

    /** 房间生命周期状态，与 GamePhase 解耦：房间可以等待多局游戏。 */
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

    public enum Status {
        WAITING,
        PLAYING,
        ENDED
    }

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 6 位房间码，玩家输入它加入。 */
    @TableField("room_code")
    private String roomCode;

    @TableField("room_name")
    private String roomName;

    /** BCrypt 哈希后的房间密码；空表示无密码。 */
    private String password;

    @TableField("max_players")
    private Integer maxPlayers;

    @TableField("werewolf_count")
    private Integer werewolfCount;

    @TableField(value="role_counts", updateStrategy=com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS, typeHandler=com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private java.util.Map<String,Integer> roleCounts;
    public java.util.Map<String,Integer> getRoleCounts() { return roleCounts; }
    public void setRoleCounts(java.util.Map<String,Integer> value) {
        roleCounts = value == null ? null : new java.util.LinkedHashMap<>(value);
    }
    public java.util.Map<String,Integer> effectiveRoleCounts() {
        return roleCounts == null ? com.werewolf.game.domain.RoleConfiguration.defaults(maxPlayers,werewolfCount)
                : new java.util.LinkedHashMap<>(roleCounts);
    }


    /** 允许加入的 AI 数量上限。 */
    @TableField("ai_count")
    private Integer aiCount;

    @TableField("allow_ai")
    private Boolean allowAi;

    /** 是否公开房间（公开房间会出现在大厅列表，可免密码加入）。 */
    private Boolean publicRoom;

    @TableField("game_status")
    private String gameStatus;

    @TableField("created_by")
    private Long createdBy;

    @TableField("created_at")
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRoomCode() {
        return roomCode;
    }

    public void setRoomCode(String roomCode) {
        this.roomCode = roomCode;
    }

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

    public String getGameStatus() {
        return gameStatus;
    }

    public void setGameStatus(String gameStatus) {
        this.gameStatus = gameStatus;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
