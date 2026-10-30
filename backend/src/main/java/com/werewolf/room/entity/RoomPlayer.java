package com.werewolf.room.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * room_players 表：记录玩家与房间的绑定关系以及座位信息。
 *
 * <p>注意：{@code role} 只在一局结束后回写（用于复盘），对局中的真实身份只存在于 GameEngine 内存。
 */
@TableName("room_players")
public class RoomPlayer {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("room_id")
    private Long roomId;

    /** AI 玩家使用负数 id。 */
    @TableField("user_id")
    private Long userId;

    @TableField("nickname")
    private String nickname;

    /** 1-based 座位号。 */
    @TableField("seat_id")
    private Integer seatId;

    private String role;

    @TableField("is_ai")
    private Boolean isAi;

    @TableField("is_alive")
    private Boolean isAlive;

    private Boolean ready;

    @TableField("joined_at")
    private LocalDateTime joinedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRoomId() {
        return roomId;
    }

    public void setRoomId(Long roomId) {
        this.roomId = roomId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public Integer getSeatId() {
        return seatId;
    }

    public void setSeatId(Integer seatId) {
        this.seatId = seatId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Boolean getIsAi() {
        return isAi;
    }

    public void setIsAi(Boolean isAi) {
        this.isAi = isAi;
    }

    public Boolean getIsAlive() {
        return isAlive;
    }

    public void setIsAlive(Boolean isAlive) {
        this.isAlive = isAlive;
    }

    public Boolean getReady() {
        return ready;
    }

    public void setReady(Boolean ready) {
        this.ready = ready;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(LocalDateTime joinedAt) {
        this.joinedAt = joinedAt;
    }
}
