package com.werewolf.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 底层 WebSocket 会话持有者 —— 让服务端能主动关闭指定会话。
 *
 * <p><b>为什么需要它</b>：Spring 的 STOMP 抽象没有提供"从外部关闭任意会话"的 API，
 * 但"单会话强制"要求新登录设备把旧设备的连接踢掉。因此这里在 WebSocket 传输层
 * 注册每一个 session（见 {@code WebSocketConfig#configureWebSocketTransport}），
 * 需要时直接调用 {@link WebSocketSession#close}。
 *
 * <p>关闭状态码用 {@link #SESSION_REPLACED}（4001，自定义业务码），
 * 前端据此识别"被顶下线"，弹出明确提示而不是当成普通断线重连。
 */
public final class WsSessionCloser {

    private static final Logger log = LoggerFactory.getLogger(WsSessionCloser.class);

    /** 自定义关闭码：该账号已在其他设备登录。 */
    public static final CloseStatus SESSION_REPLACED = new CloseStatus(4001, "SESSION_REPLACED");

    public static final CloseStatus CREDENTIALS_CHANGED = new CloseStatus(4002, "CREDENTIALS_CHANGED");

    private static final Map<String, WebSocketSession> SESSIONS = new ConcurrentHashMap<>();

    private WsSessionCloser() {
    }

    static void register(WebSocketSession session) {
        SESSIONS.put(session.getId(), session);
    }

    static void unregister(String sessionId) {
        SESSIONS.remove(sessionId);
    }
    /**
     * 关闭指定会话。
     *
     * @return 是否成功找到并关闭
     */
    public static boolean close(String sessionId, CloseStatus status) {
        if (sessionId == null) {
            return false;
        }
        WebSocketSession session = SESSIONS.remove(sessionId);
        if (session == null) {
            return false;
        }
        try {
            if (session.isOpen()) {
                session.close(status);
                log.info("已主动关闭 WebSocket 会话 {}（{}）", sessionId, status);
            }
            return true;
        } catch (IOException ex) {
            log.warn("关闭会话 {} 失败: {}", sessionId, ex.getMessage());
            return false;
        }
    }

    public static int openSessions() {
        return SESSIONS.size();
    }
}
