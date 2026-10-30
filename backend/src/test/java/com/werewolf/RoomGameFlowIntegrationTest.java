package com.werewolf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 房间 + 开局流程集成测试（h2 profile + MockMvc）。
 *
 * <p>两个场景：
 * <ol>
 *   <li><b>房主 + 3 个 AI</b>：建房（必须显式 {@code allowAi=true, aiCount=3}，因为当前默认
 *       AI 关闭）-> 房主准备 -> 开局 -> 校验状态结构（本人身份、4 名玩家、可用行动是数组）；</li>
 *   <li><b>纯真人房</b>：{@code maxPlayers=4, werewolfCount=1, aiCount=0, allowAi=false}，
 *       只有房主 1 人时开局必须被拒绝（3008 配置不合法 / 3007 未准备），且房间状态不被破坏。</li>
 * </ol>
 *
 * <p><b>刻意不依赖 AI 行动</b>：AI 调度由 {@code @Scheduled} 每秒 tick 驱动，
 * 属于异步行为；这里只断言状态结构，不断言 AI 是否已经行动。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class RoomGameFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ============================================================ 夹具 ====

    private static String uniqueSuffix() {
        return String.valueOf(System.nanoTime() % 1_000_000_000L);
    }

    private String toJson(Map<String, ?> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String register(String username, String nickname) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", "demo1234");
        body.put("nickname", nickname);

        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String token = read(result).path("data").path("token").asText();
        assertFalse(token.isBlank(), "注册成功必须返回 token");
        return token;
    }

    private static Map<String, Object> roomBody(String roomName, int maxPlayers, int werewolfCount,
                                                int aiCount, boolean allowAi) {
        Map<String, Object> room = new LinkedHashMap<>();
        room.put("roomName", roomName);
        room.put("maxPlayers", maxPlayers);
        room.put("werewolfCount", werewolfCount);
        room.put("aiCount", aiCount);
        room.put("allowAi", allowAi);
        room.put("publicRoom", true);
        return room;
    }

    // ============================================================ 用例 ====

    @Test
    @DisplayName("房主 + 3 AI：建房 -> 准备 -> 开局 -> 状态与元数据结构正确")
    void hostStartsGameWithThreeBots() throws Exception {
        String suffix = uniqueSuffix();
        String token = register("rg" + suffix, "房主" + suffix);

        // 1) 建房：4 人 / 1 狼 / 3 AI（AI 默认关闭，必须显式打开）
        //    开局强制要求至少 4 个已入座玩家（真人 + AI），否则 3 人局会出现
        //    "1 狼 + 2 神职、没有平民"的退化配置（狼人开局即达成人数压制）。
        MvcResult created = mockMvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(roomBody("AI 演示房", 4, 1, 3, true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.roomCode").isNotEmpty())
                .andExpect(jsonPath("$.data.seats.length()").value(4))
                .andExpect(jsonPath("$.data.aiCount").value(3))
                .andExpect(jsonPath("$.data.iAmHost").value(true))
                .andReturn();

        JsonNode roomData = read(created).path("data");
        String roomCode = roomData.path("roomCode").asText();

        int aiSeats = 0;
        for (JsonNode seat : roomData.path("seats")) {
            if (seat.path("ai").asBoolean()) {
                aiSeats++;
            }
        }
        assertEquals(3, aiSeats, "4 人房里应包含 3 个 AI 座位");
        assertEquals(1, roomData.path("humanCount").asInt(), "真人只有房主 1 人");

        // 2) 房主准备（AI 座位由后端直接置为已准备）
        mockMvc.perform(post("/api/rooms/" + roomCode + "/ready")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ready\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.iAmReady").value(true))
                .andExpect(jsonPath("$.data.allReady").value(true));

        // 3) 房主开局（房主 1 真人 + 3 AI = 4 个座位 -> 1 狼 + 1 预言家 + 1 女巫 + 1 平民）
        //    开局后先进入"身份确认"阶段，AI 由服务端视为已确认，
        //    因此房主确认一次就会进入第一晚。
        mockMvc.perform(post("/api/game/start")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.players.length()").value(4))
                .andExpect(jsonPath("$.data.phase").value("STARTING"))
                .andExpect(jsonPath("$.data.gameOver").value(false));

        // 3b) 发牌结束 -> 身份确认阶段：房主确认一次即可开打
        //
        // 注意：STARTING -> CONFIRM 由每秒 tick 驱动，且 CONFIRM 只能在确认阶段提交。
        // 因此这里"轮询 + 重试"：在确认阶段提交成功前，收到 4003（阶段不对）就等下一拍。
        // 这也正是前端的行为——用户在发牌动画期间点了确认，会被拒绝并提示稍后再试。
        String stateBody = "";
        boolean confirmed = false;
        for (int i = 0; i < 60 && !confirmed; i++) {
            MvcResult st = mockMvc.perform(get("/api/game/state")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();
            stateBody = st.getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (!stateBody.contains("\"CONFIRM\"")) {
                Thread.sleep(250);
                continue;
            }
            MvcResult act = mockMvc.perform(post("/api/game/action")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"CONFIRM_ROLE\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode actBody = read(act);
            if (actBody.path("code").asInt() == 0) {
                confirmed = true;
                // 用行动响应本身作为"最新状态"，避免引用上一轮轮询到的旧快照
                stateBody = act.getResponse().getContentAsString(StandardCharsets.UTF_8);
            } else {
                // 尚未进入确认阶段（4003）：等下一拍再试
                Thread.sleep(250);
            }
        }
        assertTrue(confirmed, "应能在身份确认阶段成功提交确认，最后响应：" + stateBody);

        // 4) 本人视角状态：身份非空、4 名玩家、可用行动是数组（发牌阶段可以为空数组）
        mockMvc.perform(get("/api/game/state")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.players.length()").value(4))
                .andExpect(jsonPath("$.data.me.role").isNotEmpty())
                .andExpect(jsonPath("$.data.me.camp").isNotEmpty())
                .andExpect(jsonPath("$.data.me.availableActions").isArray())
                .andExpect(jsonPath("$.data.me.selectableTargets").isArray());

        // 5) 元数据：4 个角色（狼人 / 预言家 / 女巫 / 平民）+ 9 个阶段（含身份确认）
        mockMvc.perform(get("/api/game/meta")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.roles.length()").value(9))
                .andExpect(jsonPath("$.data.phases.length()").value(11));

        // 6) 不在房间里的用户提交行动必须被拒绝（3004 你不在该房间中）
        String strangerToken = register("st" + suffix, "路人" + suffix);
        mockMvc.perform(post("/api/game/action")
                        .header("Authorization", "Bearer " + strangerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"VOTE\",\"targetSeatId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(3004));
    }

    @Test
    @DisplayName("纯真人房：只有房主 1 人时不能开局，且房间状态不被破坏")
    void humanOnlyRoomCannotStartWithOnePlayer() throws Exception {
        String suffix = uniqueSuffix();
        String token = register("hm" + suffix, "真人" + suffix);

        // 1) 建纯真人房：AI 全关
        MvcResult created = mockMvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(roomBody("纯真人房", 4, 1, 0, false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.allowAi").value(false))
                .andReturn();

        JsonNode roomData = read(created).path("data");
        String roomCode = roomData.path("roomCode").asText();
        int seatCount = roomData.path("seats").size();

        assertTrue(seatCount == 0 || seatCount == 1,
                "只有房主时房间应有 0~1 个座位（取决于是否自动加入创建者），实际 " + seatCount);
        assertEquals(0, roomData.path("aiCount").asInt(), "纯真人房不应有 AI 数量");
        assertEquals(1, roomData.path("humanCount").asInt(), "应有且只有房主 1 名真人");

        // 2) 房主准备后人数仍不足 4 -> 开局必须被拒绝
        mockMvc.perform(post("/api/rooms/" + roomCode + "/ready")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ready\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        MvcResult startFailed = mockMvc.perform(post("/api/game/start")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        int code = read(startFailed).path("code").asInt();
        assertTrue(code == 3008 || code == 3007,
                "人数不足 4 人时开局必须被拒绝（3008 房间配置不合法 / 3007 还有玩家未准备），实际 " + code);

        // 3) 失败后房间仍是等待状态，座位数不变
        mockMvc.perform(get("/api/rooms/current")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.roomCode").value(roomCode))
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andExpect(jsonPath("$.data.seats.length()").value(seatCount));
    }

    @Test
    @DisplayName("每个玩家看到的 mySeatId 必须是自己那个座位（F-12：跨用户串视角）")
    void eachPlayerSeesOwnSeatAsMySeat() throws Exception {
        String suffix = uniqueSuffix();
        String hostToken = register("h" + suffix, "房主" + suffix);
        String guestToken = register("g" + suffix, "客人" + suffix);

        // 1) 房主建房 -> 座位 1
        MvcResult created = mockMvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + hostToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(roomBody("视角测试房", 4, 1, 0, false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode hostRoom = read(created).path("data");
        String roomCode = hostRoom.path("roomCode").asText();
        assertEquals(1, hostRoom.path("mySeatId").asInt(), "房主应坐在 1 号座位");
        assertTrue(hostRoom.path("iAmHost").asBoolean(), "建房的人应当是房主");
        int hostUserId = hostRoom.path("seats").get(0).path("userId").asInt();

        // 2) 第二个玩家加入 -> 座位 2
        MvcResult joined = mockMvc.perform(post("/api/rooms/join")
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomCode\":\"" + roomCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode guestRoom = read(joined).path("data");
        assertEquals(2, guestRoom.path("mySeatId").asInt(), "第二个加入的人应坐在 2 号座位");
        assertFalse(guestRoom.path("iAmHost").asBoolean(), "后加入的人不是房主");

        // 3) 核心断言：房主再查一次房间，mySeatId 必须仍然是 1（而不是被"最后加入者"覆盖成 2）
        MvcResult hostAgain = mockMvc.perform(get("/api/rooms/" + roomCode)
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode hostRoomAgain = read(hostAgain).path("data");
        assertEquals(1, hostRoomAgain.path("mySeatId").asInt(),
                "有新玩家加入后，房主的 mySeatId 必须仍为自己那个座位（修复前会被覆盖成最后加入者的座位）");
        assertTrue(hostRoomAgain.path("iAmHost").asBoolean(), "房主标记不能被覆盖");

        // 4) 两种视角里 seats 内容一致（公共部分相同）
        assertEquals(2, hostRoomAgain.path("seats").size(), "两个人时应有 2 个座位");
        assertEquals(hostUserId, hostRoomAgain.path("seats").get(0).path("userId").asInt(),
                "1 号座位仍应属于房主");

        // 5) 房主的 iAmReady 只反映房主自己的准备状态
        mockMvc.perform(post("/api/rooms/" + roomCode + "/ready")
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ready\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        JsonNode guestAfterReady = read(mockMvc.perform(get("/api/rooms/" + roomCode)
                        .header("Authorization", "Bearer " + guestToken))
                .andReturn()).path("data");
        assertTrue(guestAfterReady.path("iAmReady").asBoolean(), "客人准备后自己的 iAmReady 应为 true");

        JsonNode hostAfterGuestReady = read(mockMvc.perform(get("/api/rooms/" + roomCode)
                        .header("Authorization", "Bearer " + hostToken))
                .andReturn()).path("data");
        assertFalse(hostAfterGuestReady.path("iAmReady").asBoolean(),
                "别人准备不应把房主标记成已准备（修复前 iAmReady 会串到其他人身上）");
        assertEquals(1, hostAfterGuestReady.path("readyCount").asInt(), "已准备人数应为 1");
    }

    @Test
    @DisplayName("AI 数量非法（超过人数-1）时建房返回 3008")
    void invalidAiCountIsRejected() throws Exception {
        String suffix = uniqueSuffix();
        String token = register("iv" + suffix, "非法" + suffix);

        mockMvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(roomBody("非法配置房", 4, 1, 4, true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(3008));
    }
}
