package com.werewolf.websocket;

import com.werewolf.room.RoomAccessPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

/** Re-check delivery after leave/kick, including subscriptions established earlier. */
@Component
public class RoomTopicAccessInterceptor implements ChannelInterceptor {
    private final RoomAccessPolicy access;
    private final ObjectProvider<WsSessionRegistry> sessions;
    public RoomTopicAccessInterceptor(RoomAccessPolicy access, ObjectProvider<WsSessionRegistry> sessions) {
        this.access = access;
        this.sessions = sessions;
    }
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        var headers = SimpMessageHeaderAccessor.wrap(message);
        String destination = headers.getDestination();
        if (headers.getMessageType() == SimpMessageType.MESSAGE && destination != null
                && destination.startsWith("/topic/room/")) {
            String sessionId = headers.getSessionId();
            String code = destination.split("/")[3];
            Long userId = sessionId == null ? null : sessions.getObject().ownerOf(sessionId);
            if (!access.isMember(code, userId)) { return null; }
        }
        return message;
    }
}
