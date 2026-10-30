package com.werewolf.websocket;

import org.springframework.lang.NonNull;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/**
 * WebSocket 处理器装饰器：把每个底层会话登记到 {@link WsSessionCloser}，
 * 使服务端具备"按 sessionId 主动关闭连接"的能力。
 *
 * <p>没有这一层就无法实现"单会话强制"——Spring 的 STOMP 抽象不提供
 * 从外部关闭任意会话的 API，必须拿到底层 {@link WebSocketSession}。
 */
public class SessionTrackingDecorator extends WebSocketHandlerDecorator {

    public SessionTrackingDecorator(WebSocketHandler delegate) {
        super(delegate);
    }

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) throws Exception {
        WsSessionCloser.register(session);
        super.afterConnectionEstablished(session);
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus closeStatus)
            throws Exception {
        WsSessionCloser.unregister(session.getId());
        super.afterConnectionClosed(session, closeStatus);
    }
}
