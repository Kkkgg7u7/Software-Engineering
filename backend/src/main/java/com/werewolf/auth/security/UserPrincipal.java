package com.werewolf.auth.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.security.Principal;
import java.util.Collection;
import java.util.List;

/**
 * 认证主体。REST 请求与 STOMP CONNECT 帧共用同一份实现，
 * 因此 {@code CurrentUser.id()} 在两种通道下都能拿到用户 id。
 *
 * <p>实现 {@link Principal} 是为了能直接作为 STOMP session 的 user
 * （{@code accessor.setUser(principal)}），从而支持 /user/queue 私密投递。
 */
public class UserPrincipal implements UserDetails, Principal {

    private final Long id;
    private final String username;
    private final String nickname;
    private final String avatar;
    private final String password;
    /** 认证版本号（users.auth_version），用于让旧登录失效。 */
    private final Integer authVersion;

    public UserPrincipal(Long id, String username, String nickname, String avatar, String password) {
        this(id, username, nickname, avatar, password, 0);
    }

    public UserPrincipal(Long id, String username, String nickname, String avatar, String password,
                         Integer authVersion) {
        this.id = id;
        this.username = username;
        this.nickname = nickname;
        this.avatar = avatar;
        this.password = password;
        this.authVersion = authVersion == null ? 0 : authVersion;
    }

    public Integer getAuthVersion() {
        return authVersion;
    }

    public Long getId() {
        return id;
    }

    public String getNickname() {
        return nickname;
    }

    public String getAvatar() {
        return avatar;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    /**
     * STOMP 的 /user/queue 目的地以 Principal#getName() 作为路由键，
     * 因此这里必须返回 userId（与 {@code convertAndSendToUser(String.valueOf(userId), ...)} 一致）。
     */
    @Override
    public String getName() {
        return String.valueOf(id);
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
