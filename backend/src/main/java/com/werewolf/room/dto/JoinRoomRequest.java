package com.werewolf.room.dto;

/**
 * 加入房间请求（按房间码 + 可选密码）。
 */
public class JoinRoomRequest {

    private String roomCode;

    private String password;

    public String getRoomCode() {
        return roomCode;
    }

    public void setRoomCode(String roomCode) {
        this.roomCode = roomCode;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
