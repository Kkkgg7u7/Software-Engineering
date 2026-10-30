package com.werewolf.websocket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * messages 表（方案第七、十节）：聊天记录与 room_id 绑定。
 *
 * <p>receiver_id 为空 = 公共频道；等于 sender_id 且类型为 WOLF = 狼人频道；
 * 其余为点对点私聊。
 */
@TableName("messages")
public class Message {

    public enum MessageType {
        /** 公共聊天：房间内所有玩家可见。 */
        PUBLIC,
        /** 私聊：仅发送者与接收者可见。 */
        PRIVATE,
        /** 狼人频道：仅狼人阵营可见。 */
        WOLF,
        /** 系统消息：由服务器产生，如“3 号玩家已准备”。 */
        SYSTEM
    }

    public enum Channel {
        /** 房间大厅（等待阶段）的聊天。 */
        LOBBY,
        /** 对局内聊天。 */
        GAME
    }

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("room_id")
    private Long roomId;

    @TableField("sender_id")
    private Long senderId;

    @TableField("receiver_id")
    private Long receiverId;

    private String content;

    @TableField("message_type")
    private String messageType;

    private String channel;

    @TableField("sender_nickname")
    private String senderNickname;

    /** 是否来自 AI NPC（前端加机器人标识）。 */
    @TableField("is_ai")
    private Boolean isAi;

    @TableField("created_at")
    private LocalDateTime createdAt;

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

    public Long getSenderId() {
        return senderId;
    }

    public void setSenderId(Long senderId) {
        this.senderId = senderId;
    }

    public Long getReceiverId() {
        return receiverId;
    }

    public void setReceiverId(Long receiverId) {
        this.receiverId = receiverId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getSenderNickname() {
        return senderNickname;
    }

    public void setSenderNickname(String senderNickname) {
        this.senderNickname = senderNickname;
    }

    public Boolean getIsAi() {
        return isAi;
    }

    public void setIsAi(Boolean isAi) {
        this.isAi = isAi;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
