package com.werewolf.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.werewolf.auth.dto.AuthResponse;
import com.werewolf.auth.dto.LoginRequest;
import com.werewolf.auth.dto.RegisterRequest;
import com.werewolf.auth.security.JwtTokenService;
import com.werewolf.auth.security.UserPrincipal;
import com.werewolf.common.BizException;
import com.werewolf.common.ErrorCode;
import com.werewolf.user.entity.User;
import com.werewolf.user.mapper.UserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 注册 / 登录 / 退出。
 *
 * <p>退出（logout）在无状态 JWT 下由客户端清除 Token 完成，
 * 服务端保留接口用于统一前端交互与审计日志。
 */
@Service
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokenService;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final com.werewolf.media.MediaService media;

    public AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder,
                       JwtTokenService tokenService, org.springframework.context.ApplicationEventPublisher events, com.werewolf.media.MediaService media) {
        this.media = media;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.events = events;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        Long exists = userMapper.selectCount(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, request.getUsername()));
        if (exists != null && exists > 0) {
            throw BizException.of(ErrorCode.USERNAME_TAKEN);
        }
        Long nickExists = userMapper.selectCount(Wrappers.<User>lambdaQuery()
                .eq(User::getNickname, request.getNickname()));
        if (nickExists != null && nickExists > 0) {
            throw BizException.of(ErrorCode.NICKNAME_TAKEN);
        }
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname());
        user.setAvatar(request.getAvatar() == null || request.getAvatar().isBlank()
                ? "/assets/avatars/bot-01.svg" : request.getAvatar());
        user.setTotalGames(0);
        user.setWinGames(0);
        user.setAuthVersion(0);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(user.getCreatedAt());
        user.setSignature("");
        userMapper.insert(user);
        return buildResponse(user);
    }

    /**
     * 登录。
     *
     * <p><b>单会话强制</b>：每次登录都把 {@code users.auth_version} 加一，
     * 新签发的 Token 带上新版本号，此前所有旧 Token 立即失效。
     *
     * <p>为什么需要：玩家身份按 {@code userId} 绑定到座位，如果同一账号在多台设备上
     * 同时在线，所有人都会被映射到同一个座位，表现为"多人共用一个账号"。
     * 让旧登录失效可以从根上阻止这种用法（前端会提示"该账号已在其他设备登录"）。
     *
     * <p>注意这里必须返回<b>更新后</b>的用户对象，否则签发的 Token 版本号会落后一位。
     */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, request.getUsername()));
        if (user == null) {
            throw BizException.of(ErrorCode.PASSWORD_WRONG);
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw BizException.of(ErrorCode.PASSWORD_WRONG);
        }
        // Only update authentication state: never overwrite a concurrently changed password/profile.
        int updated = userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, user.getId()).eq(User::getPassword, user.getPassword())
                .setSql("auth_version = auth_version + 1"));
        if (updated != 1) {
            throw BizException.of(ErrorCode.PASSWORD_WRONG);
        }
        return buildResponse(requireUser(user.getId()));
    }

    private AuthResponse buildResponse(User user) {
        UserPrincipal principal = new UserPrincipal(user.getId(), user.getUsername(),
                user.getNickname(), user.getAvatar(), user.getPassword(), user.getAuthVersion());
        AuthResponse response = new AuthResponse();
        response.setToken(tokenService.generate(principal));
        response.setExpiresIn(tokenService.getExpireMillis() / 1000);
        response.setUser(AuthResponse.UserInfo.of(user));
        return response;
    }

    public User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    @Transactional
    public void updateProfile(Long userId, String nickname, String avatar, String signature) {
        media.requireOwnedAvatar(userId, avatar);
        User user = requireUser(userId);
        if (nickname != null && !nickname.isBlank() && !nickname.equals(user.getNickname())) {
            Long exists = userMapper.selectCount(Wrappers.<User>lambdaQuery()
                    .eq(User::getNickname, nickname).ne(User::getId, userId));
            if (exists != null && exists > 0) {
                throw BizException.of(ErrorCode.NICKNAME_TAKEN);
            }
            user.setNickname(nickname);
        }
        if (avatar != null && !avatar.isBlank()) {
            user.setAvatar(avatar);
        }
        // Update only profile fields so a concurrent password change cannot be overwritten.
        var update = Wrappers.<User>lambdaUpdate().eq(User::getId, userId)
                .set(User::getNickname, user.getNickname())
                .set(User::getAvatar, user.getAvatar())
                .set(User::getUpdatedAt, LocalDateTime.now());
        if (signature != null) { update.set(User::getSignature, signature.trim()); }
        userMapper.update(null, update);
    }

    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = requireUser(userId);
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw BizException.of(ErrorCode.PASSWORD_WRONG, "原密码不正确");
        }
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "新密码不能与原密码相同");
        }
        int changed = userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId).eq(User::getPassword, user.getPassword())
                .set(User::getPassword, passwordEncoder.encode(newPassword))
                .set(User::getUpdatedAt, LocalDateTime.now())
                .setSql("auth_version = auth_version + 1"));
        if (changed != 1) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "密码已发生变化，请重新登录后重试");
        }
        events.publishEvent(new CredentialsChanged(userId, requireUser(userId).getAuthVersion()));
    }
}
