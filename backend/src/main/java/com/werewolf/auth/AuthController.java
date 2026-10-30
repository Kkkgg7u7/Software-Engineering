package com.werewolf.auth;

import com.werewolf.auth.dto.AuthResponse;
import com.werewolf.auth.dto.LoginRequest;
import com.werewolf.auth.dto.RegisterRequest;
import com.werewolf.auth.security.CurrentUser;
import com.werewolf.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（无需 Token，见 SecurityConfig 白名单）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 注册。 */
    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    /** 登录。 */
    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    /**
     * 退出。JWT 无状态，服务端只需确认 Token 有效；
     * 客户端负责清除本地 Token 与断开 WebSocket。
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        Long userId = CurrentUser.id();
        return ApiResponse.ok();
    }
}
