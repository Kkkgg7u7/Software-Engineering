package com.werewolf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.werewolf.room.RoomService;
import com.werewolf.room.runtime.RoomManager;
import com.werewolf.room.runtime.RoomRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@TestPropertySource(properties = "werewolf.test.match-isolation=true")
class RoomMatchIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RoomManager roomManager;
    @Autowired private RoomService roomService;

    @AfterEach
    void clearRooms() {
        for (RoomRuntime runtime : roomManager.all()) {
            roomService.dissolve(runtime, "match test cleanup");
        }
    }

    private JsonNode request(String token, String path, String body) throws Exception {
        var builder = post(path);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        String raw = mockMvc.perform(builder)
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode result = objectMapper.readTree(raw);
        assertEquals(0, result.path("code").asInt(), raw);
        return result.path("data");
    }

    private String register(String label) throws Exception {
        String suffix = Long.toUnsignedString(System.nanoTime(), 36);
        String username = "mt" + label.substring(0,Math.min(5,label.length())) + suffix;
        String nickname = "M" + suffix;
        JsonNode data = request(null, "/api/auth/register", "{\"username\":\"" + username
                + "\",\"password\":\"demo1234\",\"nickname\":\"" + nickname + "\"}");
        return data.path("token").asText();
    }

    @Test
    void matchJoinsCompatibleManualRoomAndRepeatedRequestIsIdempotent() throws Exception {
        String host = register("host");
        String guest = register("guest");
        JsonNode manual = request(host, "/api/rooms", "{\"roomName\":\"公开测试房\","
                + "\"maxPlayers\":4,\"werewolfCount\":1,\"aiCount\":0,"
                + "\"allowAi\":false,\"publicRoom\":true}");
        JsonNode matched = request(guest, "/api/rooms/match", null);
        JsonNode repeated = request(guest, "/api/rooms/match", null);
        assertEquals(manual.path("roomCode").asText(), matched.path("roomCode").asText());
        assertEquals(2, matched.path("mySeatId").asInt());
        assertEquals(matched.path("roomCode").asText(), repeated.path("roomCode").asText());
        assertEquals(2, repeated.path("playerCount").asInt());
    }

    @Test
    void matchSkipsPrivatePasswordAndIncompatibleRooms() throws Exception {
        String privateHost = register("private");
        String passwordHost = register("password");
        String largeHost = register("large");
        String player = register("player");
        JsonNode privateRoom = request(privateHost, "/api/rooms", "{\"roomName\":\"私密\","
                + "\"maxPlayers\":4,\"werewolfCount\":1,\"aiCount\":0,\"publicRoom\":false}");
        JsonNode passwordRoom = request(passwordHost, "/api/rooms", "{\"roomName\":\"密码\","
                + "\"password\":\"secret\",\"maxPlayers\":4,\"werewolfCount\":1,"
                + "\"aiCount\":0,\"publicRoom\":true}");
        JsonNode largeRoom = request(largeHost, "/api/rooms", "{\"roomName\":\"八人房\","
                + "\"maxPlayers\":8,\"werewolfCount\":2,\"aiCount\":0,\"publicRoom\":true}");
        JsonNode matched = request(player, "/api/rooms/match", null);
        assertNotEquals(privateRoom.path("roomCode").asText(), matched.path("roomCode").asText());
        assertNotEquals(passwordRoom.path("roomCode").asText(), matched.path("roomCode").asText());
        assertNotEquals(largeRoom.path("roomCode").asText(), matched.path("roomCode").asText());
        assertEquals(4, matched.path("maxPlayers").asInt());
        assertEquals(1, matched.path("werewolfCount").asInt());
        assertEquals(0, matched.path("aiCount").asInt());
        assertTrue(matched.path("publicRoom").asBoolean());
        assertFalse(matched.path("hasPassword").asBoolean());
    }

    @Test
    void concurrentMatchesFillEachRoomWithoutDuplicateSeats() throws Exception {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tokens.add(register("parallel" + i));
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tokens.size());
        try {
            List<Future<JsonNode>> futures = new ArrayList<>();
            for (String token : tokens) {
                Callable<JsonNode> task = () -> {
                    start.await();
                    return request(token, "/api/rooms/match", null);
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            Map<String, Set<Integer>> seatsByRoom = new HashMap<>();
            for (Future<JsonNode> future : futures) {
                JsonNode room = future.get(30, TimeUnit.SECONDS);
                seatsByRoom.computeIfAbsent(room.path("roomCode").asText(), key -> new HashSet<>())
                        .add(room.path("mySeatId").asInt());
            }
            assertEquals(2, seatsByRoom.size(), "八个玩家应正好进入两个 4 人房");
            for (Set<Integer> seats : seatsByRoom.values()) {
                assertEquals(Set.of(1, 2, 3, 4), seats);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
