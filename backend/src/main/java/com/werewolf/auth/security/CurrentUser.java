package com.werewolf.auth.security;

import com.werewolf.common.BizException;
import com.werewolf.common.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户的读取工具。REST Controller 与 STOMP Controller 都通过它取 user_id。
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UserPrincipal principalOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof UserPrincipal up) {
            return up;
        }
        return null;
    }

    /** 未登录时抛 1001。 */
    public static UserPrincipal require() {
        UserPrincipal principal = principalOrNull();
        if (principal == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        return principal;
    }

    public static Long id() {
        return require().getId();
    }

    public static Long idOrNull() {
        UserPrincipal p = principalOrNull();
        return p == null ? null : p.getId();
    }

    public static String nickname() {
        return require().getNickname();
    }
}
