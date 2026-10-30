package com.werewolf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.werewolf.auth.security.UserPrincipal;
import com.werewolf.room.RoomService;
import com.werewolf.websocket.RoomTopicAccessInterceptor;
import com.werewolf.websocket.WsAuthChannelInterceptor;
import com.werewolf.websocket.WsSessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class UserProfileIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RoomService rooms;
    @Autowired WsAuthChannelInterceptor inbound;
    @Autowired RoomTopicAccessInterceptor outbound;
    @Autowired WsSessionRegistry sessions;
    @Autowired com.werewolf.auth.AuthService auth;
    @Autowired com.werewolf.user.mapper.UserMapper users;

    JsonNode call(String method, String path, String token, Object body) throws Exception {
        var request = MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method), path);
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(request).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    JsonNode register() throws Exception {
        String id = Long.toUnsignedString(System.nanoTime(), 36);
        JsonNode response = call("POST", "/api/auth/register", null,
                Map.of("username", "pf" + id, "nickname", "P" + id, "password", "demo1234"));
        assertEquals(0, response.path("code").asInt(), response.toString());
        return response.path("data");
    }

    @Test
    void profileIsValidatedAndCannotTargetAnotherAccount() throws Exception {
        var a = register(); var b = register();
        String token = a.path("token").asText();
        var updated = call("PUT", "/api/users/me", token, Map.of("signature", "一起玩桌游 👩🏽‍💻", "userId", b.path("user").path("id").asLong()));
        assertEquals(0, updated.path("code").asInt());
        assertEquals(a.path("user").path("id"), updated.path("data").path("id"));
        assertEquals("一起玩桌游 👩🏽‍💻", updated.path("data").path("signature").asText());
        assertTrue(updated.path("data").hasNonNull("updatedAt"));
        assertFalse(updated.path("data").has("password"));
        assertFalse(updated.path("data").has("authVersion"));
        assertNotEquals("一起玩桌游 👩🏽‍💻", call("GET", "/api/users/me", b.path("token").asText(), null).path("data").path("signature").asText());
        for (Object invalid : new Object[]{Map.of("nickname", " "), Map.of("nickname", "x".repeat(17)), Map.of("signature", "x".repeat(121)), Map.of("avatar", "javascript:alert(1)")}) {
            assertEquals(1000, call("PUT", "/api/users/me", token, invalid).path("code").asInt());
        }
        assertEquals(1001, call("GET", "/api/users/me", null, null).path("code").asInt());
    }

    @Test
    void passwordChangeChecksOldPasswordAndRevokesOldLoginOnly() throws Exception {
        var a = register(); var b = register();
        String token = a.path("token").asText();
        assertEquals(2003, call("POST", "/api/users/me/password", token,
                Map.of("currentPassword", "wrong123", "newPassword", "newpass123")).path("code").asInt());
        assertEquals(1000, call("POST", "/api/users/me/password", token,
                Map.of("currentPassword", "demo1234", "newPassword", "short")).path("code").asInt());
        assertEquals(0, call("GET", "/api/users/me", token, null).path("code").asInt());
        assertEquals(0, call("POST", "/api/users/me/password", token,
                Map.of("currentPassword", "demo1234", "newPassword", "newpass123")).path("code").asInt());
        assertEquals(1001, call("GET", "/api/users/me", token, null).path("code").asInt());
        assertEquals(0, call("GET", "/api/users/me", b.path("token").asText(), null).path("code").asInt());
        String username = a.path("user").path("username").asText();
        assertEquals(2003, call("POST", "/api/auth/login", null, Map.of("username", username, "password", "demo1234")).path("code").asInt());
        assertEquals(0, call("POST", "/api/auth/login", null, Map.of("username", username, "password", "newpass123")).path("code").asInt());
    }

    @Test
    void restoredDatabaseRejectsTokensFromAFutureAuthenticationVersion() throws Exception {
        var account = register();
        String username = account.path("user").path("username").asText();
        var login = call("POST", "/api/auth/login", null, Map.of("username", username, "password", "demo1234"));
        String token = login.path("data").path("token").asText();
        users.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.werewolf.user.entity.User>lambdaUpdate()
                .eq(com.werewolf.user.entity.User::getId, account.path("user").path("id").asLong())
                .set(com.werewolf.user.entity.User::getAuthVersion, 0));
        assertEquals(1001, call("GET", "/api/users/me", token, null).path("code").asInt());
    }

    @Test
    void simultaneousProfileAndPasswordChangesPreserveBoth() throws Exception {
        var a = register();
        Long id = a.path("user").path("id").asLong();
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var profile = pool.submit(() -> { start.await(); auth.updateProfile(id, null, null, "concurrent profile"); return true; });
            var password = pool.submit(() -> { start.await(); auth.changePassword(id, "demo1234", "concurrent123"); return true; });
            start.countDown();
            assertTrue(profile.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(password.get(10, java.util.concurrent.TimeUnit.SECONDS));
            var login = call("POST", "/api/auth/login", null, Map.of("username", a.path("user").path("username").asText(), "password", "concurrent123"));
            assertEquals(0, login.path("code").asInt());
            assertEquals("concurrent profile", login.path("data").path("user").path("signature").asText());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void privateRoomHttpAndSubscriptionsRequireMembershipAndStopAfterLeave() throws Exception {
        var host = register(); var guest = register();
        String hostToken = host.path("token").asText(), guestToken = guest.path("token").asText();
        String code = call("POST", "/api/rooms", hostToken, Map.of("roomName", "private access", "maxPlayers", 4, "werewolfCount", 1, "publicRoom", false)).path("data").path("roomCode").asText();
        Long guestId = guest.path("user").path("id").asLong();
        try {
            assertEquals(1002, call("GET", "/api/rooms/" + code, guestToken, null).path("code").asInt());
            assertEquals(1002, call("GET", "/api/game/state/" + code, guestToken, null).path("code").asInt());
            var sub = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            sub.setSessionId("profile-access-test");
            sub.setUser(new UserPrincipal(guestId, "guest", "guest", null, "unused"));
            sub.setDestination("/topic/room/" + code + "/game");
            var subscribe = MessageBuilder.createMessage(new byte[0], sub.getMessageHeaders());
            assertThrows(AccessDeniedException.class, () -> inbound.preSend(subscribe, null));
            assertEquals(0, call("POST", "/api/rooms/join", guestToken, Map.of("roomCode", code)).path("code").asInt());
            assertEquals(0, call("GET", "/api/rooms/" + code, guestToken, null).path("code").asInt());
            assertDoesNotThrow(() -> inbound.preSend(subscribe, null));
            sessions.register(guestId, "profile-access-test", 0);
            var delivery = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            delivery.setSessionId("profile-access-test");
            delivery.setDestination("/topic/room/" + code + "/game");
            var message = MessageBuilder.createMessage(new byte[0], delivery.getMessageHeaders());
            assertNotNull(outbound.preSend(message, null));
            call("POST", "/api/rooms/" + code + "/leave", guestToken, null);
            assertNull(outbound.preSend(message, null), "An existing subscription must stop delivering after leave");
            assertThrows(AccessDeniedException.class, () -> inbound.preSend(subscribe, null));
        } finally {
            sessions.unregister("profile-access-test");
            rooms.dissolve(rooms.requireByCode(code), "access test cleanup");
        }
    }
}
