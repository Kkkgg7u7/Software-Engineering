package com.werewolf.websocket;

import com.werewolf.auth.security.JwtAuthenticationFilter;
import com.werewolf.auth.security.UserPrincipal;
import com.werewolf.user.entity.User;
import com.werewolf.user.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * STOMP 鉴权拦截器。
 *
 * <p>与 REST 共用同一套 JWT：Token 放在 CONNECT 帧的 {@code Authorization} 头，
 * 或者握手 URL 的 {@code ?token=xxx}（浏览器原生 WebSocket 无法自定义请求头，
 * 因此 query 参数是必要的兜底，见 docs/WEBSOCKET.md）。
 *
 * <p>鉴权成功后把 {@link UserPrincipal} 设为 STOMP session 的 user，
 * 这样 {@code convertAndSendToUser(userId, ...)} 才能把私密消息投递到正确连接。
 *
 * <p><b>单会话强制</b>：除了验签，还会比对 Token 里的认证版本号与数据库中的当前值。
 * 版本落后说明该账号已在别处重新登录，这里直接拒绝 STOMP 连接，
 * 避免"旧设备继续以同一账号收发消息、和玩家共用一个座位"。
 * SUBSCRIBE 只接受当前会话的标准用户队列和公开主题；SEND 只能进入 /app。
 */
@Component
public class WsAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WsAuthChannelInterceptor.class);
    private static final Set<String> USER_QUEUES = Set.of(
            "/user/queue/game", "/user/queue/event", "/user/queue/error",
            "/user/queue/lobby", "/user/queue/room", "/user/queue/chat", "/user/queue/social", "/user/queue/voice");
    private static final Pattern ROOM_TOPIC = Pattern.compile(
            "^/topic/room/[A-HJ-NP-Z2-9]{6}(?:/(?:game|event|chat))?$");

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final UserMapper userMapper;
    private final com.werewolf.room.RoomAccessPolicy roomAccess;

    public WsAuthChannelInterceptor(JwtAuthenticationFilter jwtAuthenticationFilter,
                                    UserMapper userMapper, com.werewolf.room.RoomAccessPolicy roomAccess) {
        this.roomAccess = roomAccess;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.userMapper = userMapper;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (StompCommand.SUBSCRIBE.equals(command)) {
            requireAuthenticated(accessor);
            String destination = accessor.getDestination();
            if (destination == null || (!USER_QUEUES.contains(destination)
                    && !WsDestinations.TOPIC_ROOMS.equals(destination)
                    && !ROOM_TOPIC.matcher(destination).matches())) {
                throw new AccessDeniedException("不允许订阅该 WebSocket 目的地");
            }
            if (ROOM_TOPIC.matcher(destination).matches()) {
                String code = destination.split("/")[3];
                Long userId = ((UserPrincipal) accessor.getUser()).getId();
                if (!roomAccess.isMember(code, userId)) {
                    throw new AccessDeniedException("只有房间成员可以订阅房间消息");
                }
            }
            return message;
        }
        if (StompCommand.SEND.equals(command)) {
            requireAuthenticated(accessor);
            String destination = accessor.getDestination();
            if (destination == null || !destination.startsWith(WsDestinations.APP_PREFIX + "/")) {
                throw new AccessDeniedException("不允许直接向 WebSocket 广播通道发送消息");
            }
            return message;
        }
        if (!StompCommand.CONNECT.equals(command)) {
            return message;
        }
        String token = resolveToken(accessor);
        if (token == null || token.isBlank()) {
            throw new AccessDeniedException("WebSocket 连接需要登录");
        }
        // 复用 REST 的解析逻辑：验签 + 查询用户 + 填充 SecurityContext
        Claims claims = jwtAuthenticationFilter.claimsOf(token);
        if (claims == null) {
            log.info("STOMP CONNECT token 非法");
            throw new AccessDeniedException("WebSocket token 无效");
        }
        UserPrincipal principal = jwtAuthenticationFilter.authenticate(claims);
        if (principal == null) {
            log.info("STOMP CONNECT 被拒绝：token 已失效（可能该账号已在其他设备重新登录）");
            throw new AccessDeniedException("WebSocket token 已失效");
        }
        // 二次确认版本号：authenticate 已经校验过，这里再查一次是为了给出明确日志，
        // 并覆盖"authenticate 内部静默返回 null"的诊断场景。
        User user = userMapper.selectById(principal.getId());
        if (user == null || jwtAuthenticationFilter.isStale(principal, user)) {
            log.info("STOMP CONNECT 被拒绝：用户 {} 的认证版本已过期", principal.getId());
            throw new AccessDeniedException("WebSocket token 已失效");
        }
        accessor.setUser(principal);
        return message;
    }

    private void requireAuthenticated(StompHeaderAccessor accessor) {
        if (!(accessor.getUser() instanceof UserPrincipal)) {
            throw new AccessDeniedException("WebSocket 连接需要登录");
        }
    }

    private String resolveToken(StompHeaderAccessor accessor) {
        List<String> auth = accessor.getNativeHeader(HttpHeaders.AUTHORIZATION);
        if (auth != null && !auth.isEmpty()) {
            return auth.get(0);
        }
        List<String> fallback = accessor.getNativeHeader("token");
        if (fallback != null && !fallback.isEmpty()) {
            return fallback.get(0);
        }
        // SockJS / 原生握手时带在 URL query 上：/ws?token=xxx
        Object sessionAttrs = accessor.getSessionAttributes();
        if (sessionAttrs instanceof java.util.Map<?, ?> map) {
            Object t = map.get("token");
            if (t instanceof String s) {
                return s;
            }
        }
        return null;
    }
}
