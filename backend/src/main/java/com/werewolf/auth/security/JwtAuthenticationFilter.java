package com.werewolf.auth.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.werewolf.user.entity.User;
import com.werewolf.user.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 从 Authorization: Bearer &lt;token&gt; 解析登录态，写入 SecurityContext。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtTokenService tokenService;
    private final UserMapper userMapper;

    public JwtAuthenticationFilter(JwtTokenService tokenService, UserMapper userMapper) {
        this.tokenService = tokenService;
        this.userMapper = userMapper;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        Claims claims = tokenService.parse(header);
        if (claims != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(claims, request);
        }
        filterChain.doFilter(request, response);
    }

    /** 供 STOMP 拦截器复用：只解析验签，不做用户装载。 */
    public Claims claimsOf(String token) {
        return tokenService.parse(token);
    }

    /** 供 REST 过滤器和 STOMP 拦截器共用：依据 claims 装载用户并写入 SecurityContext。 */
    public UserPrincipal authenticate(Claims claims) {
        return authenticate(claims, null);
    }

    /**
     * 校验"认证版本号"是否仍然有效（单会话强制的核心）。
     *
     * <p>每次登录都会把 {@code users.auth_version} +1，Token 里带着签发时的版本。
     * 如果版本对不上，说明该账号在别处重新登录过，这个 Token 已经失效。
     *
     * <p>不这么做的话：同一个账号在多台设备/多个标签页同时在线时，
     * 所有人都会被映射到<b>同一个座位</b>（玩家身份是按 userId 绑定的），
     * 表现为"多个玩家共用了一个账号"。
     *
     * @return true 表示应该接受这个 Token
     */
    public boolean isAuthVersionValid(Claims claims, User user) {
        Object claim = claims.get("ver");
        int tokenVersion = 0;
        if (claim instanceof Number n) {
            tokenVersion = n.intValue();
        } else if (claim instanceof String s) {
            try {
                tokenVersion = Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                tokenVersion = 0;
            }
        }
        int currentVersion = user.getAuthVersion() == null ? 0 : user.getAuthVersion();
        return tokenVersion == currentVersion;
    }

    /** 当前用户是否已被"别处登录"顶下线（用于 WebSocket 主动断开旧连接）。 */
    public boolean isStale(UserPrincipal principal, User user) {
        int principalVersion = principal.getAuthVersion() == null ? 0 : principal.getAuthVersion();
        int currentVersion = user.getAuthVersion() == null ? 0 : user.getAuthVersion();
        return principalVersion != currentVersion;
    }

    private UserPrincipal authenticate(Claims claims, HttpServletRequest request) {
        Long userId;
        try {
            userId = Long.valueOf(claims.getSubject());
        } catch (NumberFormatException ex) {
            return null;
        }
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getId, userId));
        if (user == null) {
            return null;
        }
        // 单会话强制：Token 的认证版本落后于数据库，说明该账号已在别处重新登录
        if (!isAuthVersionValid(claims, user)) {
            log.info("拒绝过期 Token：用户 {} 已在其他设备重新登录（tokenVer={}, currentVer={}）",
                    user.getUsername(), claims.get("ver"), user.getAuthVersion());
            return null;
        }
        UserPrincipal principal = new UserPrincipal(user.getId(), user.getUsername(),
                user.getNickname(), user.getAvatar(), user.getPassword(), user.getAuthVersion());
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        if (request != null) {
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        }
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return principal;
    }
}
