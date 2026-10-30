package com.werewolf.user;

import com.werewolf.ai.AiService;
import com.werewolf.auth.AuthService;
import com.werewolf.auth.dto.AuthResponse;
import com.werewolf.auth.security.CurrentUser;
import com.werewolf.common.ApiResponse;
import com.werewolf.room.runtime.RoomManager;
import com.werewolf.user.entity.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户信息与健康检查。
 */
@RestController
@RequestMapping("/api")
public class UserController {

    private final AuthService authService;
    private final RoomManager roomManager;
    private final AiService aiService;

    public UserController(AuthService authService, RoomManager roomManager, AiService aiService) {
        this.authService = authService;
        this.roomManager = roomManager;
        this.aiService = aiService;
    }

    /** 当前登录用户资料。 */
    @GetMapping("/users/me")
    public ApiResponse<AuthResponse.UserInfo> me() {
        User user = authService.requireUser(CurrentUser.id());
        return ApiResponse.ok(AuthResponse.UserInfo.of(user));
    }

    /** 修改昵称/头像。 */
    @PutMapping("/users/me")
    public ApiResponse<AuthResponse.UserInfo> updateMe(@Valid @RequestBody UpdateProfileRequest request) {
        Long userId = CurrentUser.id();
        authService.updateProfile(userId, request.getNickname(), request.getAvatar(), request.getSignature());
        return ApiResponse.ok(AuthResponse.UserInfo.of(authService.requireUser(userId)));
    }

    /**
     * 健康检查：同时暴露 AI 提供方、在线房间数与本机网卡地址，
     * 方便别人连不上时第一时间确认"服务监听在哪个地址"。
     */
    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("status", "UP");
        info.put("service", "werewolf-server");
        info.put("time", System.currentTimeMillis());
        return ApiResponse.ok(info);
    }

    @PostMapping("/users/me/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(CurrentUser.id(), request.getCurrentPassword(), request.getNewPassword());
        return ApiResponse.ok();
    }

    public static class ChangePasswordRequest {
        @NotBlank @Size(min = 6, max = 32)
        private String currentPassword;
        @NotBlank @Size(min = 6, max = 32)
        private String newPassword;
        public String getCurrentPassword() { return currentPassword; }
        public void setCurrentPassword(String value) { currentPassword = value; }
        public String getNewPassword() { return newPassword; }
        public void setNewPassword(String value) { newPassword = value; }
    }

    public static class UpdateProfileRequest {
        @Size(max = 16, message = "昵称长度不能超过 16")
        @Pattern(regexp = "(?s).*\\S.*", message = "昵称不能为空白")
        private String nickname;
        @Size(max = 255)
        @Pattern(regexp = "(?:https?://[^\\s]+|/assets/avatars/[A-Za-z0-9._-]+|/api/media/avatars/[a-f0-9]{32})", message = "头像必须是 HTTP(S) 地址或默认头像")
        private String avatar;
        @Size(max = 120, message = "签名不能超过 120 个字符")
        private String signature;
        public String getSignature() { return signature; }
        public void setSignature(String value) { signature = value; }

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
    }
}
