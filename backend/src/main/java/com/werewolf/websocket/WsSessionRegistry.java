package com.werewolf.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 在线会话注册表 —— 实现"单会话强制"的连接侧收口。
 *
 * <p><b>解决什么问题</b>：玩家身份按 {@code userId} 绑定座位。如果同一个账号在多个
 * 设备/标签页上同时在线，所有人都会落到<b>同一个座位</b>，表现为"多个玩家共用一个账号"。
 *
 * <p>HTTP 侧已经通过 Token 里的 {@code auth_version} 让旧登录失效；
 * 但 WebSocket 是一次性握手的长连接 —— 旧连接握手时 Token 还有效，
 * 之后即使别处重新登录，那条连接也会一直挂着继续收发消息。
 * 因此这里额外维护一份 {@code userId -> 最新会话}，新会话建立时主动断开旧会话。
 *
 * <p>断开前会先给旧会话推一条 {@code KICKED} 消息，让前端能显示
 * "该账号已在其他设备登录" 而不是当成普通掉线。
 */
@Component
public class WsSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(WsSessionRegistry.class);

    /** userId -> 会话信息（sessionId + 认证版本号）。 */
    private final Map<Long, SessionInfo> latestSessions = new ConcurrentHashMap<>();

    /** sessionId -> userId，用于断开时精确清理。 */
    private final Map<String, Long> sessionOwners = new ConcurrentHashMap<>();

    private final GameEventMessenger messenger;

    public WsSessionRegistry(GameEventMessenger messenger) {
        this.messenger = messenger;
    }

    public record SessionInfo(String sessionId, int authVersion) {
    }

    /** 会话建立：若同一账号已有更旧版本的会话，返回它以便调用方断开。 */
    public String register(Long userId, String sessionId, int authVersion) {
        if (userId == null || sessionId == null) {
            return null;
        }
        sessionOwners.put(sessionId, userId);
        SessionInfo previous = latestSessions.put(userId, new SessionInfo(sessionId, authVersion));
        if (previous != null && !previous.sessionId().equals(sessionId)) {
            sessionOwners.remove(previous.sessionId());
            log.info("用户 {} 建立了新连接，准备断开旧会话 {}（旧版本 {}，新版本 {}）",
                    userId, previous.sessionId(), previous.authVersion(), authVersion);
            // 先提示再断开，让旧设备上的用户知道发生了什么
            try {
                messenger.kicked(userId, "该账号已在其他设备登录，你已下线。"
                        + "多人游戏请各自注册账号。");
            } catch (Exception ex) {
                log.debug("推送下线提示失败（连接可能已断）: {}", ex.getMessage());
            }
            WsSessionCloser.close(previous.sessionId(), WsSessionCloser.SESSION_REPLACED);
            return previous.sessionId();
        }
        return null;
    }

    /** 会话断开：只有当断开的就是"当前最新会话"时才清理，避免被旧会话的断开事件误删。 */
    public void unregister(String sessionId) {
        if (sessionId == null) {
            return;
        }
        Long userId = sessionOwners.remove(sessionId);
        if (userId == null) {
            return;
        }
        latestSessions.computeIfPresent(userId, (k, info) ->
                info.sessionId().equals(sessionId) ? null : info);
    }

    public Long ownerOf(String sessionId) { return sessionOwners.get(sessionId); }

    @org.springframework.transaction.event.TransactionalEventListener
    public void onCredentialsChanged(com.werewolf.auth.CredentialsChanged event) {
        SessionInfo current = latestSessions.get(event.userId());
        if (current != null && current.authVersion() < event.authVersion()) {
            unregister(current.sessionId());
            WsSessionCloser.close(current.sessionId(), WsSessionCloser.CREDENTIALS_CHANGED);
        }
    }

    public boolean isCurrent(Long userId, String sessionId) {
        SessionInfo info = latestSessions.get(userId);
        return info != null && info.sessionId().equals(sessionId);
    }

    public int onlineUsers() {
        return latestSessions.size();
    }

    /** 供测试与排障使用。 */
    public Map<Long, SessionInfo> snapshot() {
        return Map.copyOf(latestSessions);
    }

    // ------------------------------------------------- Spring 事件桥接 ----

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        if (accessor.getUser() instanceof com.werewolf.auth.security.UserPrincipal principal) {
            register(principal.getId(), accessor.getSessionId(), principal.getAuthVersion());
        }
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        unregister(event.getSessionId());
    }
}
