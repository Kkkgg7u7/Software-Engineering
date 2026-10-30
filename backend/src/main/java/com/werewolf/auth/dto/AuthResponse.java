package com.werewolf.auth.dto;

import com.werewolf.user.entity.User;

/**
 * 登录/注册成功后返回给前端的用户信息 + Token。
 */
public class AuthResponse {

    private String token;
    private long expiresIn;
    private UserInfo user;

    public static class UserInfo {
        private Long id;
        private String username;
        private String nickname;
        private String avatar;
        private String signature;
        private java.time.LocalDateTime createdAt;
        private java.time.LocalDateTime updatedAt;

        public String getSignature() { return signature; }
        public void setSignature(String signature) { this.signature = signature; }
        public java.time.LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(java.time.LocalDateTime createdAt) { this.createdAt = createdAt; }
        public java.time.LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(java.time.LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
        private Integer totalGames;
        private Integer winGames;

        public static UserInfo of(User user) {
            UserInfo info = new UserInfo();
            info.setId(user.getId());
            info.setUsername(user.getUsername());
            info.setNickname(user.getNickname());
            info.setAvatar(user.getAvatar());
            info.setSignature(user.getSignature());
            info.setCreatedAt(user.getCreatedAt());
            info.setUpdatedAt(user.getUpdatedAt());
            info.setTotalGames(user.getTotalGames());
            info.setWinGames(user.getWinGames());
            return info;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
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

        public Integer getTotalGames() {
            return totalGames;
        }

        public void setTotalGames(Integer totalGames) {
            this.totalGames = totalGames;
        }

        public Integer getWinGames() {
            return winGames;
        }

        public void setWinGames(Integer winGames) {
            this.winGames = winGames;
        }
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public long getExpiresIn() {
        return expiresIn;
    }

    public void setExpiresIn(long expiresIn) {
        this.expiresIn = expiresIn;
    }

    public UserInfo getUser() {
        return user;
    }

    public void setUser(UserInfo user) {
        this.user = user;
    }
}
