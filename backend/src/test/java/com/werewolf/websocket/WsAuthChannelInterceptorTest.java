package com.werewolf.websocket;

import com.werewolf.auth.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WsAuthChannelInterceptorTest {

    // These cases do not reach JWT parsing; live CONNECT authentication is covered by the
    // WebSocket regression check. Avoid Mockito's Java-version-sensitive bytecode agent here.
    private final WsAuthChannelInterceptor interceptor = new WsAuthChannelInterceptor(null, null, null);
    private final Principal user = new UserPrincipal(1L, "demo", "demo", null, "unused");

    private Message<byte[]> frame(StompCommand command, String destination, Principal principal) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("test-session");
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (principal != null) {
            accessor.setUser(principal);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void privateSubscriptionsOnlyUseOwnSessionDestination() {
        assertDoesNotThrow(() -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "/user/queue/game", user), null));
        assertDoesNotThrow(() -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "/user/queue/chat", user), null));
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "/user/2/queue/game", user), null));
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "/queue/game-userOtherSession", user), null));
    }

    @Test
    void anonymousConnectionsAndSubscriptionsAreRejected() {
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(
                frame(StompCommand.CONNECT, null, null), null));
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "/topic/rooms", null), null));
    }

    @Test
    void clientsCannotPublishDirectlyToBroker() {
        assertDoesNotThrow(() -> interceptor.preSend(
                frame(StompCommand.SEND, "/app/game/resync", user), null));
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(
                frame(StompCommand.SEND, "/topic/room/ABCDEF/game", user), null));
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(
                frame(StompCommand.SEND, "/queue/chat", user), null));
    }
}
