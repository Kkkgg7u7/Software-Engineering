package com.werewolf.room.dto;

import com.werewolf.room.entity.Room;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/**
 * 房间视图（等待阶段）。前端大厅/房间页只认这个结构。
 *
 * <p><b>命名注意</b>：Jackson 对 {@code boolean} 的 getter 会剥掉 {@code is} 前缀，
 * 因此字段 {@code iAmHost} 必须用 {@code isHostViewer()} 命名，
 * 否则会被序列化成 {@code hostViewer}，与前端类型定义不一致。
 */
public class RoomView {

    private Long id;
    private String roomCode;
    private String roomName;
    private boolean hasPassword;
    private int maxPlayers;
    private int werewolfCount;
    private Map<String,Integer> roleCounts;
    public Map<String,Integer> getRoleCounts(){return roleCounts;}
    public void setRoleCounts(Map<String,Integer> value){roleCounts=value;}

    private int aiCount;
    private boolean allowAi;
    private boolean publicRoom;
    private String status;
    private Long hostUserId;
    private String hostNickname;
    private int playerCount;
    private int humanCount;
    private int readyCount;
    private boolean allReady;
    private List<SeatView> seats = new ArrayList<>();
    /** 当前请求者所在座位号，未在房间内为 null。 */
    private Integer mySeatId;
    private boolean iAmHost;
    private boolean iAmReady;

    private String gameType;
    public String getGameType(){return gameType;}
    public void setGameType(String value){gameType=value;}
    private Boolean allowInvites;
    public Boolean getAllowInvites(){return allowInvites;}
    public void setAllowInvites(Boolean value){allowInvites=value;}
    private Boolean voiceEnabled;
    public Boolean getVoiceEnabled(){return voiceEnabled;}
    public void setVoiceEnabled(Boolean value){voiceEnabled=value;}
    private Boolean privateChatEnabled;
    public Boolean getPrivateChatEnabled(){return privateChatEnabled;}
    public void setPrivateChatEnabled(Boolean value){privateChatEnabled=value;}
    private LocalDateTime createdAt;
    public LocalDateTime getCreatedAt(){return createdAt;}
    public void setCreatedAt(LocalDateTime value){createdAt=value;}

    public static class SeatView {
        private int seatId;
        private Long userId;
        private String nickname;
        private String avatar;
        private boolean ai;
        private boolean ready;
        private boolean alive = true;
        private String role;
        private boolean host;

        public int getSeatId() {
            return seatId;
        }

        public void setSeatId(int seatId) {
            this.seatId = seatId;
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

        public String getAvatar() {
            return avatar;
        }

        public void setAvatar(String avatar) {
            this.avatar = avatar;
        }

        public boolean isAi() {
            return ai;
        }

        public void setAi(boolean ai) {
            this.ai = ai;
        }

        public boolean isReady() {
            return ready;
        }

        public void setReady(boolean ready) {
            this.ready = ready;
        }

        public boolean isAlive() {
            return alive;
        }

        public void setAlive(boolean alive) {
            this.alive = alive;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public boolean isHost() {
            return host;
        }

        public void setHost(boolean host) {
            this.host = host;
        }
    }

    public static RoomView of(Room room, Long viewerUserId) {
        RoomView v = new RoomView();
        v.setId(room.getId());
        v.setGameType(room.getGameType());
        v.setAllowInvites(room.getAllowInvites());
        v.setVoiceEnabled(room.getVoiceEnabled());
        v.setPrivateChatEnabled(room.getPrivateChatEnabled());
        v.setCreatedAt(room.getCreatedAt());
        v.setRoomCode(room.getRoomCode());
        v.setRoomName(room.getRoomName());
        v.setHasPassword(room.getPassword() != null && !room.getPassword().isBlank());
        v.setMaxPlayers(room.getMaxPlayers());
        v.setWerewolfCount(room.getWerewolfCount());
        v.setRoleCounts(room.effectiveRoleCounts());
        v.setAiCount(room.getAiCount());
        v.setAllowAi(Boolean.TRUE.equals(room.getAllowAi()));
        v.setPublicRoom(Boolean.TRUE.equals(room.getPublicRoom()));
        v.setStatus(room.getGameStatus());
        v.setHostUserId(room.getCreatedBy());
        v.setIAmHost(room.getCreatedBy() != null && room.getCreatedBy().equals(viewerUserId));
        return v;
    }
    public Map<String,Object> getConfiguration(){return Map.of("roleCounts",roleCounts,"voiceEnabled",Boolean.TRUE.equals(voiceEnabled),"maxPlayers",maxPlayers,"werewolfCount",werewolfCount,"aiCount",aiCount,"allowAi",allowAi,"privateChatEnabled",Boolean.TRUE.equals(privateChatEnabled));}
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

    public boolean isHasPassword() {
        return hasPassword;
    }

    public void setHasPassword(boolean hasPassword) {
        this.hasPassword = hasPassword;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public void setMaxPlayers(int maxPlayers) {
        this.maxPlayers = maxPlayers;
    }

    public int getWerewolfCount() {
        return werewolfCount;
    }

    public void setWerewolfCount(int werewolfCount) {
        this.werewolfCount = werewolfCount;
    }

    public int getAiCount() {
        return aiCount;
    }

    public void setAiCount(int aiCount) {
        this.aiCount = aiCount;
    }

    public boolean isAllowAi() {
        return allowAi;
    }

    public void setAllowAi(boolean allowAi) {
        this.allowAi = allowAi;
    }

    public boolean isPublicRoom() {
        return publicRoom;
    }

    public void setPublicRoom(boolean publicRoom) {
        this.publicRoom = publicRoom;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getHostUserId() {
        return hostUserId;
    }

    public void setHostUserId(Long hostUserId) {
        this.hostUserId = hostUserId;
    }

    public String getHostNickname() {
        return hostNickname;
    }

    public void setHostNickname(String hostNickname) {
        this.hostNickname = hostNickname;
    }

    public int getPlayerCount() {
        return playerCount;
    }

    public void setPlayerCount(int playerCount) {
        this.playerCount = playerCount;
    }

    public int getHumanCount() {
        return humanCount;
    }

    public void setHumanCount(int humanCount) {
        this.humanCount = humanCount;
    }

    public int getReadyCount() {
        return readyCount;
    }

    public void setReadyCount(int readyCount) {
        this.readyCount = readyCount;
    }

    public boolean isAllReady() {
        return allReady;
    }

    public void setAllReady(boolean allReady) {
        this.allReady = allReady;
    }

    public List<SeatView> getSeats() {
        return seats;
    }

    public void setSeats(List<SeatView> seats) {
        this.seats = seats;
    }

    public Integer getMySeatId() {
        return mySeatId;
    }

    public void setMySeatId(Integer mySeatId) {
        this.mySeatId = mySeatId;
    }

    /** 序列化为 JSON 字段 {@code iAmHost}（不能用 isIAmHost，会被 Jackson 改写）。 */
    @com.fasterxml.jackson.annotation.JsonProperty("iAmHost")
    public boolean isHostViewer() {
        return iAmHost;
    }

    public void setIAmHost(boolean iAmHost) {
        this.iAmHost = iAmHost;
    }

    /** 序列化为 JSON 字段 {@code iAmReady}。 */
    @com.fasterxml.jackson.annotation.JsonProperty("iAmReady")
    public boolean isReadyViewer() {
        return iAmReady;
    }

    public void setIAmReady(boolean iAmReady) {
        this.iAmReady = iAmReady;
    }
}
