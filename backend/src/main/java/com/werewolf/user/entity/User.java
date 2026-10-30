package com.werewolf.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * users 表（方案第十节）。
 */
@TableName("users")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 哈希，任何情况下不返回给前端。 */
    private String password;

    private String nickname;

    private String avatar;
    private String signature;
    private LocalDateTime updatedAt;

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    /** 连续对局场次、胜场，用于简单排行榜（P2）。 */
    private Integer totalGames;

    private Integer winGames;

    /**
     * 认证版本号：每次成功登录 +1。
     *
     * <p>JWT 里携带签发时的版本号，服务端每次请求都比对一次。
     * 比对不上就说明"该账号在别处重新登录过"，旧 Token 立即失效。
     *
     * <p><b>为什么需要它</b>：玩家身份是按 {@code userId} 绑定的，
     * 如果同一个账号在多台设备/多个浏览器窗口上同时在线，
     * 所有人都会映射到<b>同一个座位</b>，表现为"多个玩家共用了一个账号"。
     * 单会话强制可以让旧登录自动失效，从根上避免这个问题。
     */
    @TableField("auth_version")
    private Integer authVersion;

    @TableField("created_at")
    private LocalDateTime createdAt;

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

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
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

    public Integer getAuthVersion() {
        return authVersion;
    }

    public void setAuthVersion(Integer authVersion) {
        this.authVersion = authVersion;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
