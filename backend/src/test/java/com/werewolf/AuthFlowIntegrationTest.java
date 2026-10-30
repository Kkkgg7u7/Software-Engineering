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
 * 认证 / 房间 / 元数据主流程集成测试。
 *
 * <p>用 h2 profile + MockMvc 跑真实 Spring 上下文（含 Security 过滤器链、MyBatis-Plus、
 * H2 建表脚本），覆盖前端首屏必须走通的那条路径：
 * <pre>
 * 注册 -> 拿 token -> /api/users/me -> 创建房间 -> /api/rooms/current -> /api/game/meta
 * </pre>
 *
 * <p>响应体统一为 {@code {code, message, data, timestamp}}，{@code code == 0} 表示成功；
 * 未登录统一返回业务码 1001，用户名重复返回 2000。
 *
 * <p>注意：断言里只使用 ASCII / 数字 / 结构断言。MockMvc 的 jsonPath 默认按响应声明
 * 的字符集解码，中文一律通过 {@code getContentAsString(UTF_8)} 读取后校验。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class AuthFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ============================================================ 夹具 ====

    /** 生成 9 位以内的唯一后缀，保证多次运行 / 多个用例之间不冲突。 */
    private static String uniqueSuffix() {
        return String.valueOf(System.nanoTime() % 1_000_000_000L);
    }

    private String toJson(Map<String, ?> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    /** 读取响应体（显式按 UTF-8 解码，避免中文昵称被按 ISO-8859-1 误解码）。 */
    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private Map<String, Object> registerBody(String username, String nickname) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", "demo1234");
        body.put("nickname", nickname);
        return body;
    }

    private String register(String username, String nickname) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(registerBody(username, nickname))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String token = read(result).path("data").path("token").asText();
        assertFalse(token.isBlank(), "注册成功必须返回 token");
        return token;
    }

    /** 登录并返回 token（用于单会话强制的用例）。 */
    private String login(String username) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", "demo1234");
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return read(result).path("data").path("token").asText();
    }

    // ============================================================ 用例 ====

    @Test
    @DisplayName("单会话强制：同一账号在别处重新登录后，旧 Token 立即失效（防止多人共用一个账号）")
    void secondLoginInvalidatesPreviousToken() throws Exception {
        String suffix = uniqueSuffix();
        String username = "ss" + suffix;
        String nickname = "会话" + suffix;

        // 1) 注册拿到第一个 Token，此时它可以正常访问受保护接口
        String tokenA = register(username, nickname);
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 2) 在"另一台设备"上登录同一账号，拿到第二个 Token
        String tokenB = login(username);
        assertFalse(tokenB.equals(tokenA), "每次登录都必须是不同的 Token");

        // 3) 旧 Token 必须失效 —— 这是修复"多设备共用一个账号"的关键
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));

        // 4) 旧 Token 也不能再提交任何对局行动
        mockMvc.perform(get("/api/game/state").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));

        // 5) 新 Token 正常工作
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("单会话强制：第三个设备登录后，前两个 Token 全部失效")
    void onlyLatestTokenRemainsValid() throws Exception {
        String suffix = uniqueSuffix();
        String username = "ss3" + suffix;
        register(username, "三次" + suffix);

        String t1 = login(username);
        String t2 = login(username);
        String t3 = login(username);

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + t1))
                .andExpect(jsonPath("$.code").value(1001));
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + t2))
                .andExpect(jsonPath("$.code").value(1001));
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + t3))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("不同账号互不影响：A 重新登录不会让 B 掉线")
    void reloginOfOneUserDoesNotAffectOthers() throws Exception {
        String suffix = uniqueSuffix();
        String userA = "ua" + suffix;
        String userB = "ub" + suffix;
        String tokenA = register(userA, "甲" + suffix);
        String tokenB = register(userB, "乙" + suffix);

        // A 在别处重新登录
        login(userA);

        // A 的旧 Token 失效，但 B 完全不受影响
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.code").value(1001));
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + tokenB))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("注册 -> 取资料 -> 建房 -> 当前房间 -> 元数据 全流程")
    void registerMeCreateRoomAndMeta() throws Exception {
        String suffix = uniqueSuffix();
        String username = "it" + suffix;
        String nickname = "集成" + suffix;

        // 1) 注册，拿到 token
        String token = register(username, nickname);

        // 2) 带 token 访问受保护接口
        MvcResult me = mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.nickname").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNumber())
                .andReturn();
        assertEquals(nickname, read(me).path("data").path("nickname").asText(),
                "昵称必须原样返回（UTF-8）");

        // 3) 创建房间：默认 AI 关闭，纯真人 4 人房
        Map<String, Object> room = new LinkedHashMap<>();
        room.put("roomName", "集成测试房");
        room.put("maxPlayers", 4);
        room.put("werewolfCount", 1);
        room.put("aiCount", 0);
        room.put("allowAi", false);
        room.put("publicRoom", true);
        MvcResult created = mockMvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(room)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.roomCode").isNotEmpty())
                .andExpect(jsonPath("$.data.roomName").isNotEmpty())
                .andExpect(jsonPath("$.data.maxPlayers").value(4))
                .andExpect(jsonPath("$.data.werewolfCount").value(1))
                .andExpect(jsonPath("$.data.iAmHost").value(true))
                .andExpect(jsonPath("$.data.seats").isArray())
                .andReturn();
        String roomCode = read(created).path("data").path("roomCode").asText();
        assertTrue(roomCode.length() >= 4, "房间码应由后端生成，实际：" + roomCode);

        // 4) 查询当前房间（刷新页面后恢复用）
        mockMvc.perform(get("/api/rooms/current")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.roomCode").value(roomCode))
                .andExpect(jsonPath("$.data.playerCount").value(1))
                .andExpect(jsonPath("$.data.mySeatId").value(1));

        // 5) 游戏元数据：4 个角色 + 阶段 / 行动枚举
        mockMvc.perform(get("/api/game/meta")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.roles.length()").value(9))
                .andExpect(jsonPath("$.data.phases").isArray())
                .andExpect(jsonPath("$.data.actions").isArray())
                .andExpect(jsonPath("$.data.cardBack").isNotEmpty());

        // 6) 健康检查（无需 token）
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("UP"));
    }

    @Test
    @DisplayName("不带 token 访问受保护接口返回业务码 1001")
    void protectedEndpointsWithoutTokenReturn1001() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));

        mockMvc.perform(get("/api/rooms/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));

        mockMvc.perform(get("/api/game/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));

        mockMvc.perform(post("/api/game/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("伪造 token 访问受保护接口同样返回 1001")
    void invalidTokenReturns1001() throws Exception {
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("用户名重复注册返回 2000")
    void duplicateUsernameReturns2000() throws Exception {
        String suffix = uniqueSuffix();
        String username = "dup" + suffix;
        register(username, "重名甲" + suffix);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(registerBody(username, "重名乙" + suffix))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2000))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("注册参数不合法返回 1000（BAD_REQUEST）")
    void invalidRegisterRequestReturns1000() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", "a");           // 长度不足 3
        body.put("password", "123");         // 长度不足 6
        body.put("nickname", "");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000));
    }

    @Test
    @DisplayName("登录：密码正确返回 token，密码错误返回 2003")
    void loginSucceedsWithCorrectPasswordAndFailsWithWrongOne() throws Exception {
        String suffix = uniqueSuffix();
        String username = "lg" + suffix;
        String nickname = "登录" + suffix;
        register(username, nickname);

        Map<String, Object> login = new LinkedHashMap<>();
        login.put("username", username);
        login.put("password", "demo1234");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.user.username").value(username));

        login.put("password", "wrong-password");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2003));
    }
}
