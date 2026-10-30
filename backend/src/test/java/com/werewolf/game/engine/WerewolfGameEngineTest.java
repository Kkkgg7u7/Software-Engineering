package com.werewolf.game.engine;

import com.werewolf.common.ErrorCode;
import com.werewolf.game.domain.ActionType;
import com.werewolf.game.domain.GameAction;
import com.werewolf.game.domain.GameEvent;
import com.werewolf.game.domain.GamePhase;
import com.werewolf.game.domain.GameState;
import com.werewolf.game.domain.PlayerState;
import com.werewolf.game.domain.RoleType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WerewolfGameEngine} 纯单元测试（不启动 Spring，不依赖数据库 / WebSocket）。
 *
 * <p>覆盖四类关注点：
 * <ol>
 *   <li><b>角色分配</b>：狼人数量、1 预言家、人数 &gt;= 5 时 1 女巫、其余平民；</li>
 *   <li><b>完整对局</b>：发牌 -> 夜晚（狼刀 / 查验 / 解药）-> 讨论发言 -> 投票放逐 -> 胜负结算；</li>
 *   <li><b>非法行动</b>：不仅要求被拒绝，还要求<b>状态完全不变</b>（服务器权威的关键保证）；</li>
 *   <li><b>超时兜底</b>：{@code autoCompleteCurrentPhase} 必须让夜晚/投票正常结算，不能卡死。</li>
 * </ol>
 *
 * <p>所有用例都注入固定随机种子；对随机目标不敏感，只断言结构与不变量。
 */
class WerewolfGameEngineTest {

    /** 固定随机种子，保证用例可重复。 */
    private static final long SEED = 20240501L;

    // ============================================================ 夹具 ====

    private static GameState newState(int playerCount) {
        GameState state = new GameState(1L, "TEST01");
        for (int seat = 1; seat <= playerCount; seat++) {
            state.getPlayers().add(new PlayerState(seat, (long) seat,
                    "玩家" + seat, "/assets/avatars/bot-01.svg", false));
        }
        return state;
    }

    private static WerewolfGameEngine newEngine(GameState state) {
        return new WerewolfGameEngine(state, new Random(SEED));
    }

    /**
     * 开局（WAITING -> STARTING -> CONFIRM -> NIGHT）。
     *
     * <p>注意：发牌结束后会先进入<b>身份确认阶段</b>，必须所有人都确认才会进入第一晚。
     * 这里让所有玩家都提交一次确认，模拟"大家都看过牌了"。
     */
    private static void startAndEnterNight(GameState state, WerewolfGameEngine engine,
                                           List<GameEvent> events, int werewolfCount) {
        GameActionResult started = engine.startGame(werewolfCount, events);
        assertTrue(started.isOk(), "开局应成功：" + started.getMessage());
        assertEquals(GamePhase.STARTING, state.getPhase());

        GameActionResult advanced = engine.nextPhase(events);
        assertTrue(advanced.isOk(), "STARTING -> CONFIRM 应成功");
        assertEquals(GamePhase.CONFIRM, state.getPhase(), "发牌后应进入身份确认阶段");

        confirmAll(state, engine, events);

        assertEquals(GamePhase.NIGHT, state.getPhase(), "全部确认后应进入第一晚");
        assertEquals(1, state.getRound());
    }

    /** 让所有玩家确认身份；最后一个人确认后应自动进入夜晚。 */
    private static void confirmAll(GameState state, WerewolfGameEngine engine,
                                   List<GameEvent> events) {
        for (PlayerState p : state.getPlayers()) {
            if (p.isRoleConfirmed()) {
                continue;
            }
            GameActionResult r = engine.applyAction(
                    GameAction.human(ActionType.CONFIRM_ROLE, p.getSeatId(), null, null), events);
            assertTrue(r.isOk(), "确认身份应成功：seat=" + p.getSeatId() + " " + r.getMessage());
        }
    }

    /**
     * 让所有还没点"完成"的夜间行动者点一次完成。
     *
     * <p>新规则（F-16）：夜晚要等所有相关人员都点完成才结算。
     * 另外预言家查验后会开启 30 秒翻牌确认窗口（F-18），必须先确认才能继续，
     * 因此这里先替预言家点一次"知道了"。
     */
    private static void markNightDone(GameState state, WerewolfGameEngine engine,
                                      List<GameEvent> events) {
        if (engine.isSeerRevealPending()) {
            PlayerState seer = state.firstAliveWithRole(RoleType.SEER);
            if (seer != null) {
                engine.applyAction(GameAction.human(
                        ActionType.SEER_CONFIRM, seer.getSeatId(), null, null), events);
            }
        }
        for (PlayerState p : new ArrayList<>(engine.pendingActors())) {
            if (engine.availableActions(p.getSeatId()).contains(ActionType.PHASE_DONE)) {
                engine.applyAction(GameAction.human(
                        ActionType.PHASE_DONE, p.getSeatId(), null, null), events);
            }
        }
    }

    private static PlayerState onlyRole(GameState state, RoleType role) {
        for (PlayerState p : state.getPlayers()) {
            if (p.getRole() == role) {
                return p;
            }
        }
        throw new AssertionError("没有找到角色 " + role);
    }

    private static int countRole(GameState state, RoleType role) {
        int count = 0;
        for (PlayerState p : state.getPlayers()) {
            if (p.getRole() == role) {
                count++;
            }
        }
        return count;
    }

    /** 取一个存活的平民（可排除若干座位），用于构造“无辜受害者”。 */
    private static PlayerState plainVillager(GameState state, int... excludeSeats) {
        for (PlayerState p : state.getPlayers()) {
            if (p.isAlive() && p.getRole() == RoleType.VILLAGER
                    && !contains(excludeSeats, p.getSeatId())) {
                return p;
            }
        }
        throw new AssertionError("没有可用的平民座位");
    }

    private static boolean contains(int[] seats, int value) {
        for (int seat : seats) {
            if (seat == value) {
                return true;
            }
        }
        return false;
    }

    private static GameEvent lastEvent(List<GameEvent> events, GameEvent.EventType type) {
        GameEvent found = null;
        for (GameEvent e : events) {
            if (e.getEventType() == type) {
                found = e;
            }
        }
        return found;
    }

    private static boolean hasEvent(List<GameEvent> events, GameEvent.EventType type) {
        return lastEvent(events, type) != null;
    }

    /** 状态指纹：非法行动前后必须完全一致。 */
    private static String snapshot(GameState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("phase=").append(state.getPhase())
                .append("|round=").append(state.getRound())
                .append("|seconds=").append(state.getPhaseSecondsLeft())
                .append("|wolfTarget=").append(state.getWolfTargetSeatId())
                .append("|seerTarget=").append(state.getSeerTargetSeatId())
                .append("|usedHeal=").append(state.isWitchUsedHeal())
                .append("|usedPoison=").append(state.isWitchUsedPoison())
                .append("|poisonTarget=").append(state.getWitchPoisonSeatId())
                .append("|wolfVotes=").append(state.getWolfVotes())
                .append("|votes=").append(state.getVotes())
                .append("|speakCursor=").append(state.getSpeakCursor())
                .append("|speeches=").append(state.getSpeeches().size())
                .append("|deaths=").append(state.getLastNightDeaths())
                .append("|exiled=").append(state.getLastExiledSeatId())
                .append("|finished=").append(state.isFinished())
                .append("|winner=").append(state.getWinner());
        for (PlayerState p : state.getPlayers()) {
            sb.append("|seat").append(p.getSeatId())
                    .append(":role=").append(p.getRole())
                    .append(":alive=").append(p.isAlive())
                    .append(":heal=").append(p.isHealPotionAvailable())
                    .append(":poison=").append(p.isPoisonPotionAvailable())
                    .append(":checks=").append(p.getCheckRecords().size());
        }
        return sb.toString();
    }

    /** 断言行动被拒绝：错误码正确、状态不变、不产生任何事件。 */
    private static void assertRejected(GameState state, WerewolfGameEngine engine,
                                       GameAction action, ErrorCode expected) {
        String before = snapshot(state);
        List<GameEvent> events = new ArrayList<>();
        GameActionResult result = engine.applyAction(action, events);

        assertFalse(result.isOk(), "非法行动必须被拒绝：" + action);
        assertEquals(expected, result.getErrorCode(),
                "错误码不符：" + action + " -> " + result.getMessage());
        assertEquals(before, snapshot(state), "非法行动不允许改变任何状态：" + action);
        assertTrue(events.isEmpty(), "被拒绝的行动不应产生事件：" + action);
    }

    /** 按当前阶段推进一步（模拟计时器 / 玩家操作），用于跨回合用例。 */
    private static void step(GameState state, WerewolfGameEngine engine, List<GameEvent> events) {
        switch (state.getPhase()) {
            case CONFIRM -> confirmAll(state, engine, events);
            case NIGHT -> {
                // 新规则：夜晚要等所有相关人员都点"完成"才结算。
                // 这里先补默认行动，再让每个还没完成的人点一次完成。
                engine.autoCompleteCurrentPhase(events);
                markNightDone(state, engine, events);
                if (state.getPhase() == GamePhase.NIGHT) {
                    engine.nextPhase(events);
                }
            }
            case DISCUSSION -> {
                PlayerState speaker = state.currentSpeaker();
                if (speaker == null) {
                    engine.nextPhase(events);
                } else if (speaker.getSeatId() % 2 == 0) {
                    // 一半的人"没什么要说的"，直接结束发言（F-19 覆盖）
                    engine.applyAction(GameAction.human(
                            ActionType.SPEAK_PASS, speaker.getSeatId(), null, null), events);
                } else {
                    engine.applyAction(GameAction.human(ActionType.SPEAK, speaker.getSeatId(),
                            null, "（自动推进发言）"), events);
                }
            }
            case VOTING -> {
                List<PlayerState> pending = engine.pendingVoters();
                if (pending.isEmpty()) {
                    engine.nextPhase(events);
                } else {
                    engine.applyAction(GameAction.human(ActionType.ABSTAIN,
                            pending.get(0).getSeatId(), null, null), events);
                }
            }
            default -> engine.nextPhase(events);
        }
    }

    private static void advanceTo(GameState state, WerewolfGameEngine engine, GamePhase target) {
        List<GameEvent> events = new ArrayList<>();
        int guard = 0;
        while (state.getPhase() != target && guard++ < 40) {
            assertNotEquals(GamePhase.END, state.getPhase(), "推进过程中对局不应提前结束");
            step(state, engine, events);
        }
        assertEquals(target, state.getPhase(),
                "未能在 " + guard + " 步内推进到阶段 " + target);
    }

    /** 结算当前夜晚并一路推进到下一晚（依赖超时兜底，不依赖随机目标）。 */
    private static void nextNight(GameState state, WerewolfGameEngine engine) {
        int round = state.getRound();
        List<GameEvent> events = new ArrayList<>();
        if (state.getPhase() == GamePhase.NIGHT) {
            step(state, engine, events);
        }
        int guard = 0;
        while (state.getRound() == round && guard++ < 40) {
            assertNotEquals(GamePhase.END, state.getPhase(), "推进过程中对局不应提前结束");
            step(state, engine, events);
        }
        assertTrue(guard < 40, "推进到下一晚的步数超出预期");
        assertEquals(GamePhase.NIGHT, state.getPhase());
        assertEquals(round + 1, state.getRound());
    }

    // ====================================================== 角色分配 ====

    @Test
    @DisplayName("6 人局 2 狼：2 狼 + 1 预言家 + 1 女巫 + 2 平民")
    void startGame_assignsRolesForSixPlayers() {
        GameState state = newState(6);
        WerewolfGameEngine engine = newEngine(state);
        List<GameEvent> events = new ArrayList<>();

        GameActionResult result = engine.startGame(2, events);

        assertTrue(result.isOk(), "开局应成功：" + result.getMessage());
        assertEquals(GamePhase.STARTING, state.getPhase());
        assertEquals(2, countRole(state, RoleType.WEREWOLF), "狼人数量应等于配置值");
        assertEquals(1, countRole(state, RoleType.SEER), "必须有且只有 1 名预言家");
        assertEquals(1, countRole(state, RoleType.WITCH), "人数 >= 5 时必须有 1 名女巫");
        assertEquals(2, countRole(state, RoleType.VILLAGER), "其余全部是平民");
        for (PlayerState p : state.getPlayers()) {
            assertNotNull(p.getRole(), "每个座位都必须拿到身份");
            assertTrue(p.isAlive(), "开局时所有人存活");
            assertTrue(p.isHealPotionAvailable() && p.isPoisonPotionAvailable(),
                    "开局时女巫药水应重置为可用");
        }
        assertTrue(hasEvent(events, GameEvent.EventType.GAME_START), "开局应产出 GAME_START 事件");
        assertTrue(hasEvent(events, GameEvent.EventType.PHASE_CHANGE), "开局应产出阶段切换事件");
    }

    @Test
    @DisplayName("4 人局 1 狼：1 狼 + 1 预言家 + 1 女巫 + 1 平民")
    void startGame_assignsRolesForFourPlayers() {
        GameState state = newState(4);
        WerewolfGameEngine engine = newEngine(state);

        assertTrue(engine.startGame(1, new ArrayList<>()).isOk());

        assertEquals(1, countRole(state, RoleType.WEREWOLF));
        assertEquals(1, countRole(state, RoleType.SEER), "4 人局同样保留预言家，保证核心玩法完整");
        assertEquals(1, countRole(state, RoleType.WITCH),
                "女巫是 P0 角色，4 人局也必须存在（否则解药/毒药机制缺失）");
        assertEquals(1, countRole(state, RoleType.VILLAGER));
    }

    // ====================================================== 完整对局 ====

    @Test
    @DisplayName("完整对局：狼刀 -> 查验 -> 解药 -> 讨论 -> 投票放逐 -> 好人胜利")
    void fullRoundFlow_villageWins() {
        GameState state = newState(6);
        WerewolfGameEngine engine = newEngine(state);
        List<GameEvent> events = new ArrayList<>();
        startAndEnterNight(state, engine, events, 1);

        PlayerState wolf = onlyRole(state, RoleType.WEREWOLF);
        PlayerState seer = onlyRole(state, RoleType.SEER);
        PlayerState witch = onlyRole(state, RoleType.WITCH);
        PlayerState victim = plainVillager(state, wolf.getSeatId(), seer.getSeatId(), witch.getSeatId());

        // 1) 狼人刀人
        GameActionResult kill = engine.applyAction(GameAction.human(
                ActionType.WOLF_KILL, wolf.getSeatId(), victim.getSeatId(), null), events);
        assertTrue(kill.isOk(), "狼人刀人应成功：" + kill.getMessage());
        assertEquals(Integer.valueOf(victim.getSeatId()), state.getWolfTargetSeatId());
        assertTrue(victim.isAlive(), "夜晚结算前被刀者仍存活");

        // 2) 预言家查验狼人：SEER_RESULT 事件的 wolf 必须为 true
        GameActionResult check = engine.applyAction(GameAction.human(
                ActionType.SEER_CHECK, seer.getSeatId(), wolf.getSeatId(), null), events);
        assertTrue(check.isOk(), "预言家查验应成功：" + check.getMessage());
        GameEvent seerResult = lastEvent(events, GameEvent.EventType.SEER_RESULT);
        assertNotNull(seerResult, "查验必须产出 SEER_RESULT 事件");
        assertEquals(Boolean.TRUE, seerResult.getPayload().get("wolf"), "查验狼人必须返回 wolf=true");
        assertEquals(wolf.getSeatId(), seerResult.getPayload().get("targetSeatId"));
        assertEquals(seer.getSeatId(), seerResult.getPayload().get("actorSeatId"));
        assertTrue(seerResult.isPrivate(), "查验结果只能下发给查验者本人");
        assertEquals(1, seer.getCheckRecords().size(), "查验记录应写入预言家私有状态");

        // 3) 女巫用解药：被刀者存活；夜晚还要等所有行动者点"完成"才结算
        GameActionResult heal = engine.applyAction(GameAction.human(
                ActionType.WITCH_HEAL, witch.getSeatId(), null, null), events);
        assertTrue(heal.isOk(), "女巫用解药应成功：" + heal.getMessage());
        assertFalse(witch.isHealPotionAvailable(), "解药应被消耗");
        assertEquals(GamePhase.NIGHT, state.getPhase(),
                "未全员点完成前夜晚不结算（F-16）：女巫可能还要用毒药");
        markNightDone(state, engine, events);

        assertTrue(victim.isAlive(), "女巫用解药后，当晚被刀者必须存活");
        assertTrue(state.getLastNightDeaths().isEmpty(), "解药生效后当晚应是平安夜");
        assertTrue(hasEvent(events, GameEvent.EventType.PEACEFUL_NIGHT), "平安夜应产出对应事件");
        assertEquals(GamePhase.DISCUSSION, state.getPhase(),
                "狼人/预言家/女巫都点完成后，夜晚应结算并进入讨论");

        // 4) 依次发言，最后一位发言完自动进入投票
        int guard = 0;
        while (state.getPhase() == GamePhase.DISCUSSION && guard++ < 20) {
            PlayerState speaker = state.currentSpeaker();
            assertNotNull(speaker, "讨论阶段必须有当前发言人");
            GameActionResult spoke = engine.applyAction(GameAction.human(ActionType.SPEAK,
                    speaker.getSeatId(), null, "我是 " + speaker.getSeatId() + " 号，先听大家的。"), events);
            assertTrue(spoke.isOk(), "发言应成功：" + spoke.getMessage());
        }
        assertEquals(GamePhase.VOTING, state.getPhase(), "全部发言结束后应进入投票");
        assertEquals(6, state.getSpeeches().size(), "6 名存活玩家各发言一次");
        assertEquals(6, engine.pendingVoters().size(), "投票开始时 6 人都还没投票");
        assertTrue(hasEvent(events, GameEvent.EventType.VOTE_START));

        // 5) 投票放逐狼人：5 票 vs 1 票
        for (PlayerState voter : state.alivePlayers()) {
            int target = voter.getSeatId() == wolf.getSeatId()
                    ? seer.getSeatId() : wolf.getSeatId();
            GameActionResult voted = engine.applyAction(GameAction.human(
                    ActionType.VOTE, voter.getSeatId(), target, null), events);
            assertTrue(voted.isOk(), "投票应成功：" + voted.getMessage());
        }

        assertFalse(wolf.isAlive(), "得票最高的狼人应被放逐");
        assertEquals(Integer.valueOf(wolf.getSeatId()), state.getLastExiledSeatId());
        assertTrue(hasEvent(events, GameEvent.EventType.VOTE_RESULT));
        assertTrue(hasEvent(events, GameEvent.EventType.GAME_OVER));

        // 6) 胜负结算：狼人全灭 -> 好人阵营胜
        assertEquals(GamePhase.END, state.getPhase());
        assertTrue(state.isFinished());
        assertEquals(RoleType.Camp.VILLAGE.name(), state.getWinner());
        assertEquals(RoleType.Camp.VILLAGE.name(), engine.evaluateWinner());
        assertTrue(state.aliveWolves().isEmpty(), "结算时不应还有存活狼人");
    }

    // ================================================== 非法行动校验 ====

    @Test
    @DisplayName("非狼人提交 WOLF_KILL 必须被拒绝（预言家/平民都不行）")
    void nonWolfCannotWolfKill() {
        GameState state = newState(6);
        WerewolfGameEngine engine = newEngine(state);
        List<GameEvent> events = new ArrayList<>();
        startAndEnterNight(state, engine, events, 1);

        PlayerState seer = onlyRole(state, RoleType.SEER);
        PlayerState villager = plainVillager(state, seer.getSeatId());

        assertRejected(state, engine, GameAction.human(ActionType.WOLF_KILL,
                villager.getSeatId(), seer.getSeatId(), null), ErrorCode.GAME_ROLE_NOT_ALLOWED);
        assertRejected(state, engine, GameAction.human(ActionType.WOLF_KILL,
                seer.getSeatId(), villager.getSeatId(), null), ErrorCode.GAME_ROLE_NOT_ALLOWED);
        assertRejected(state, engine, GameAction.human(ActionType.SEER_CHECK,
                villager.getSeatId(), seer.getSeatId(), null), ErrorCode.GAME_ROLE_NOT_ALLOWED);
        assertRejected(state, engine, GameAction.human(ActionType.WITCH_POISON,
                villager.getSeatId(), seer.getSeatId(), null), ErrorCode.GAME_ROLE_NOT_ALLOWED);
    }

    @Test
    @DisplayName("狼人不能刀狼同伴，但可以正常刀好人")
    void wolfCannotKillTeammate() {
        GameState state = newState(8);
        WerewolfGameEngine engine = newEngine(state);
        List<GameEvent> events = new ArrayList<>();
        startAndEnterNight(state, engine, events, 2);

        List<PlayerState> wolves = state.aliveWolves();
        assertEquals(2, wolves.size(), "8 人局应有 2 名狼人");
        PlayerState wolfA = wolves.get(0);
        PlayerState wolfB = wolves.get(1);
        PlayerState target = plainVillager(state);

        assertRejected(state, engine, GameAction.human(ActionType.WOLF_KILL,
                wolfA.getSeatId(), wolfB.getSeatId(), null), ErrorCode.GAME_TARGET_INVALID);

        // 合法的刀人必须生效
        assertTrue(engine.applyAction(GameAction.human(ActionType.WOLF_KILL,
                wolfA.getSeatId(), target.getSeatId(), null), new ArrayList<>()).isOk());
        assertNull(state.getWolfTargetSeatId(),"所有狼人一致前不产生刀口");
        assertTrue(engine.applyAction(GameAction.human(ActionType.WOLF_KILL,
                wolfB.getSeatId(), target.getSeatId(), null), new ArrayList<>()).isOk());
        assertEquals(Integer.valueOf(target.getSeatId()), state.getWolfTargetSeatId(),
                "两名狼人一致时刀口不变");
        assertEquals(2, state.getWolfVotes().size(), "狼人内部投票应被记录");
    }

    @Test
    @DisplayName("预言家不能查验自己")
    void seerCannotCheckSelf() {
        GameState state = newState(6);
        WerewolfGameEngine engine = newEngine(state);
        List<GameEvent> events = new ArrayList<>();
        startAndEnterNight(state, engine, events, 1);

        PlayerState seer = onlyRole(state, RoleType.SEER);
        assertRejected(state, engine, GameAction.human(ActionType.SEER_CHECK,
                seer.getSeatId(), seer.getSeatId(), null), ErrorCode.GAME_TARGET_INVALID);
        assertTrue(seer.getCheckRecords().isEmpty(), "被拒绝的查验不能留下记录");
    }

    @Test
    @DisplayName("同一晚重复行动被拒绝；跨回合重复查验 / 重复用药也被拒绝")
    void repeatedNightActionsAreRejected() {
        GameState state = newState(6);
        WerewolfGameEngine engine = newEngine(state);
        List<GameEvent> events = new ArrayList<>();
        startAndEnterNight(state, engine, events, 1);

        PlayerState wolf = onlyRole(state, RoleType.WEREWOLF);
        PlayerState seer = onlyRole(state, RoleType.SEER);
        PlayerState witch = onlyRole(state, RoleType.WITCH);
        PlayerState checked = plainVillager(state, wolf.getSeatId(), seer.getSeatId(), witch.getSeatId());
        PlayerState victim = plainVillager(state, wolf.getSeatId(), seer.getSeatId(),
                witch.getSeatId(), checked.getSeatId());

        // 第 1 晚：查验平民 -> wolf=false
        assertTrue(engine.applyAction(GameAction.human(ActionType.SEER_CHECK,
                seer.getSeatId(), checked.getSeatId(), null), events).isOk());
        GameEvent seerResult = lastEvent(events, GameEvent.EventType.SEER_RESULT);
        assertNotNull(seerResult);
        assertEquals(Boolean.FALSE, seerResult.getPayload().get("wolf"), "查验平民必须返回 wolf=false");

        // 同一晚再查验 -> GAME_ALREADY_ACTED
        assertRejected(state, engine, GameAction.human(ActionType.SEER_CHECK,
                seer.getSeatId(), victim.getSeatId(), null), ErrorCode.GAME_ALREADY_ACTED);

        // 狼人刀人 + 女巫解药（预言家已行动 -> 本晚结算为平安夜）
        assertTrue(engine.applyAction(GameAction.human(ActionType.WOLF_KILL,
                wolf.getSeatId(), victim.getSeatId(), null), events).isOk());
        assertTrue(engine.applyAction(GameAction.human(ActionType.WITCH_HEAL,
                witch.getSeatId(), null, null), events).isOk());
        // 新规则：还要所有人点完成才会结算（F-16），此时仍停留在夜晚
        assertEquals(GamePhase.NIGHT, state.getPhase(), "未全员点完成前夜晚不结算");
        markNightDone(state, engine, events);
        assertTrue(victim.isAlive(), "解药应救回被刀者");
        assertFalse(witch.isHealPotionAvailable(), "解药应被消耗");
        assertEquals(GamePhase.DISCUSSION, state.getPhase());

        // 推进到第 2 晚
        nextNight(state, engine);
        assertEquals(2, state.getRound());

        // 跨回合重复查验同一人 -> 拒绝
        assertRejected(state, engine, GameAction.human(ActionType.SEER_CHECK,
                seer.getSeatId(), checked.getSeatId(), null), ErrorCode.GAME_TARGET_INVALID);
        // 解药已经用过 -> 拒绝
        assertRejected(state, engine, GameAction.human(ActionType.WITCH_HEAL,
                witch.getSeatId(), null, null), ErrorCode.GAME_POTION_USED);
    }

    @Test
    @DisplayName("夜晚超时兜底：所有未完成者被自动补默认操作并结算（防挂机）")
    void nightTimeoutAutoCompletesEveryone() {
        GameState state = newState(6);
        WerewolfGameEngine engine = newEngine(state);
        List<GameEvent> events = new ArrayList<>();
        startAndEnterNight(state, engine, events, 1);

        // 谁都没操作，直接模拟 3 分钟倒计时耗尽
        assertEquals(GamePhase.NIGHT, state.getPhase());
        assertEquals(3, engine.pendingActors().size(), "狼人/预言家/女巫都在等");

        engine.autoCompleteCurrentPhase(events);
        engine.nextPhase(events);

        assertNotEquals(GamePhase.NIGHT, state.getPhase(), "超时后夜晚必须推进，不能卡住");
        assertTrue(engine.pendingActors().isEmpty(), "超时兜底后不应再有人待完成");
        assertTrue(events.stream().anyMatch(e -> e.getEventType() == GameEvent.EventType.PHASE_DONE_BY
                        && Boolean.TRUE.equals(e.getPayload().get("auto"))),
                "超时自动完成应广播 auto=true 的事件");
    }

    @Test
    @DisplayName("女巫一晚可以用两瓶药（解药+毒药各一次），且用完即结束本晚操作")
    void witchCanUseBothPotionsInSameNight() {
        GameState state = newState(6);
        WerewolfGameEngine engine = newEngine(state);
        List<GameEvent> events = new ArrayList<>();
        startAndEnterNight(state, engine, events, 1);

        PlayerState wolf = onlyRole(state, RoleType.WEREWOLF);
        PlayerState seer = onlyRole(state, RoleType.SEER);
        PlayerState witch = onlyRole(state, RoleType.WITCH);
        PlayerState victim = plainVillager(state, wolf.getSeatId(), seer.getSeatId(), witch.getSeatId());

        assertTrue(engine.applyAction(GameAction.human(ActionType.WOLF_KILL,
                wolf.getSeatId(), victim.getSeatId(), null), events).isOk());
        assertTrue(engine.applyAction(GameAction.human(ActionType.WITCH_HEAL,
                witch.getSeatId(), null, null), events).isOk());
        // 关键：用了救药之后夜晚绝不能结算，否则女巫再没有机会用毒药（F-16）
        assertEquals(GamePhase.NIGHT, state.getPhase(), "救药之后夜晚不应结算，女巫还要用毒药");
        assertTrue(witch.isPoisonPotionAvailable(), "毒药仍然可用");
        assertTrue(engine.availableActions(witch.getSeatId()).contains(ActionType.WITCH_POISON),
                "用完救药后仍应能选择毒药");

        // 第二瓶药：毒一个目标
        PlayerState poisonTarget = state.alivePlayers().stream()
                .filter(p -> p.getSeatId() != witch.getSeatId() && p.getSeatId() != victim.getSeatId())
                .findFirst().orElseThrow();
        assertTrue(engine.applyAction(GameAction.human(ActionType.WITCH_POISON,
                witch.getSeatId(), poisonTarget.getSeatId(), null), events).isOk());
        assertFalse(witch.isPoisonPotionAvailable(), "毒药已消耗");

        // 两瓶都用完后不能再用药
        // 注意：毒药用完即视为本晚结束，因此这里被"你已经结束操作"拦下
        assertRejected(state, engine, GameAction.human(ActionType.WITCH_HEAL,
                witch.getSeatId(), null, null), ErrorCode.GAME_ALREADY_ACTED);
        assertRejected(state, engine, GameAction.human(ActionType.WITCH_POISON,
                witch.getSeatId(), seer.getSeatId(), null), ErrorCode.GAME_ALREADY_ACTED);
        assertFalse(engine.pendingActors().stream()
                        .anyMatch(p -> p.getSeatId() == witch.getSeatId()),
                "两瓶药都用完后女巫不再需要操作");
    }

    @Test
    @DisplayName("同一瓶药不能在一晚用两次（解药只能用一次）")
    void witchCannotReuseSamePotion() {
        GameState state = newState(6);
        WerewolfGameEngine engine = newEngine(state);
        List<GameEvent> events = new ArrayList<>();
        startAndEnterNight(state, engine, events, 1);

        PlayerState wolf = onlyRole(state, RoleType.WEREWOLF);
        PlayerState seer = onlyRole(state, RoleType.SEER);
        PlayerState witch = onlyRole(state, RoleType.WITCH);
        PlayerState victim = plainVillager(state, wolf.getSeatId(), seer.getSeatId(), witch.getSeatId());

        assertTrue(engine.applyAction(GameAction.human(ActionType.WOLF_KILL,
                wolf.getSeatId(), victim.getSeatId(), null), events).isOk());
        assertTrue(engine.applyAction(GameAction.human(ActionType.WITCH_HEAL,
                witch.getSeatId(), null, null), events).isOk());

        // 解药已经没了，再点救人就应当被"药水已用完"拦下
        assertRejected(state, engine, GameAction.human(ActionType.WITCH_HEAL,
                witch.getSeatId(), null, null), ErrorCode.GAME_POTION_USED);
        assertEquals(1, engine.pendingActors().stream()
                        .filter(p -> p.getSeatId() == witch.getSeatId()).count(),
                "女巫还剩毒药，仍应在待完成列表里");
    }

    @Test
    @DisplayName("女巫点「不使用」等于放弃剩余药水并结束本晚操作")
    void witchPassEndsHerNight() {
        GameState state = newState(6);
        WerewolfGameEngine engine = newEngine(state);
        List<GameEvent> events = new ArrayList<>();
        startAndEnterNight(state, engine, events, 1);

        PlayerState wolf = onlyRole(state, RoleType.WEREWOLF);
        PlayerState witch = onlyRole(state, RoleType.WITCH);
        PlayerState victim = plainVillager(state, wolf.getSeatId(), witch.getSeatId());

        assertTrue(engine.applyAction(GameAction.human(ActionType.WOLF_KILL,
                wolf.getSeatId(), victim.getSeatId(), null), events).isOk());
        assertTrue(engine.applyAction(GameAction.human(ActionType.WITCH_PASS,
                witch.getSeatId(), null, null), events).isOk());

        assertTrue(state.isPhaseDone(witch.getSeatId()), "点不使用后女巫本晚已结束");
        assertFalse(engine.pendingActors().stream()
                        .anyMatch(p -> p.getSeatId() == witch.getSeatId()),
                "女巫不应再出现在待完成列表");
        // 放弃之后不能再反悔用药
        assertRejected(state, engine, GameAction.human(ActionType.WITCH_POISON,
                witch.getSeatId(), wolf.getSeatId(), null), ErrorCode.GAME_ALREADY_ACTED);
    }

    @Test
    @DisplayName("预言家查验后开启翻牌确认窗口：未确认前夜晚不推进，确认后才结算")
    void seerRevealGatesNightResolution() {
        GameState state = newState(6);
        WerewolfGameEngine engine = newEngine(state);
        engine.setSeerConfirmSeconds(30);
        List<GameEvent> events = new ArrayList<>();
        startAndEnterNight(state, engine, events, 1);

        PlayerState wolf = onlyRole(state, RoleType.WEREWOLF);
        PlayerState seer = onlyRole(state, RoleType.SEER);
        PlayerState witch = onlyRole(state, RoleType.WITCH);
        PlayerState victim = plainVillager(state, wolf.getSeatId(), seer.getSeatId(), witch.getSeatId());

        // 查验前没有窗口
        assertFalse(engine.isSeerRevealPending(), "还没查验时不应有翻牌窗口");

        assertTrue(engine.applyAction(GameAction.human(ActionType.SEER_CHECK,
                seer.getSeatId(), wolf.getSeatId(), null), events).isOk());

        // 查验后窗口打开，并且广播了 SEER_REVEAL（只给预言家）
        assertTrue(engine.isSeerRevealPending(), "查验后应开启翻牌确认窗口");
        GameEvent reveal = lastEvent(events, GameEvent.EventType.SEER_REVEAL);
        assertNotNull(reveal, "应产出 SEER_REVEAL 事件");
        assertEquals(Boolean.TRUE, reveal.getPayload().get("wolf"), "查验狼人应返回 wolf=true");
        assertEquals(wolf.getSeatId(), reveal.getPayload().get("targetSeatId"));
        assertTrue(reveal.isPrivate(), "翻牌结果只能发给预言家本人");

        // 窗口开着时，预言家不能直接点完成（必须先看完结果）
        assertFalse(engine.availableActions(seer.getSeatId()).contains(ActionType.PHASE_DONE),
                "翻牌窗口未关闭时预言家不能直接点完成");
        assertFalse(engine.applyAction(GameAction.human(ActionType.PHASE_DONE,
                seer.getSeatId(), null, null), events).isOk(),
                "强行点完成应被拒绝");

        // 其他人（非预言家）不能替他确认
        assertRejected(state, engine, GameAction.human(ActionType.SEER_CONFIRM,
                wolf.getSeatId(), null, null), ErrorCode.GAME_ROLE_NOT_ALLOWED);

        // 狼人刀人 + 女巫不救 + 全部完成，但窗口还开着 -> 夜晚仍不结算
        assertTrue(engine.applyAction(GameAction.human(ActionType.WOLF_KILL,
                wolf.getSeatId(), victim.getSeatId(), null), events).isOk());
        assertTrue(engine.applyAction(GameAction.human(ActionType.WITCH_PASS,
                witch.getSeatId(), null, null), events).isOk());
        assertTrue(engine.applyAction(GameAction.human(ActionType.PHASE_DONE,
                wolf.getSeatId(), null, null), events).isOk());
        assertEquals(GamePhase.NIGHT, state.getPhase(),
                "翻牌窗口未关闭前，即使其他人都完成了也不能结算");

        // 预言家确认 -> 窗口关闭；此时只剩预言家自己没点完成，补上后夜晚立刻结算
        assertTrue(engine.applyAction(GameAction.human(ActionType.SEER_CONFIRM,
                seer.getSeatId(), null, null), events).isOk());
        assertFalse(engine.isSeerRevealPending(), "确认后窗口应关闭");
        assertEquals(GamePhase.NIGHT, state.getPhase(),
                "确认完翻牌但还没点完成，夜晚仍不应结算");
        assertTrue(engine.availableActions(seer.getSeatId()).contains(ActionType.PHASE_DONE),
                "确认后预言家才可以点完成");
        assertTrue(engine.applyAction(GameAction.human(ActionType.PHASE_DONE,
                seer.getSeatId(), null, null), events).isOk());
        assertNotEquals(GamePhase.NIGHT, state.getPhase(), "全部完成后夜晚应结算");
    }

    @Test
    @DisplayName("翻牌确认窗口 30 秒超时会自动确认并推进夜晚")
    void seerRevealAutoConfirmsOnTimeout() {
        GameState state = newState(6);
        WerewolfGameEngine engine = newEngine(state);
        engine.setSeerConfirmSeconds(3);
        List<GameEvent> events = new ArrayList<>();
        startAndEnterNight(state, engine, events, 1);

        PlayerState wolf = onlyRole(state, RoleType.WEREWOLF);
        PlayerState seer = onlyRole(state, RoleType.SEER);
        PlayerState witch = onlyRole(state, RoleType.WITCH);
        PlayerState victim = plainVillager(state, wolf.getSeatId(), seer.getSeatId(), witch.getSeatId());

        engine.applyAction(GameAction.human(ActionType.SEER_CHECK,
                seer.getSeatId(), victim.getSeatId(), null), events);
        engine.applyAction(GameAction.human(ActionType.WOLF_KILL,
                wolf.getSeatId(), victim.getSeatId(), null), events);
        engine.applyAction(GameAction.human(ActionType.WITCH_PASS,
                witch.getSeatId(), null, null), events);
        engine.applyAction(GameAction.human(ActionType.PHASE_DONE,
                wolf.getSeatId(), null, null), events);

        assertEquals(3, state.getSeerRevealSecondsLeft(), "窗口应从 3 秒开始");
        // 模拟每秒 tick
        List<GameEvent> tickEvents = new ArrayList<>();
        assertFalse(engine.tickSeerReveal(tickEvents), "第 1 秒不该结束");
        assertEquals(2, state.getSeerRevealSecondsLeft());
        engine.tickSeerReveal(tickEvents);
        assertEquals(1, state.getSeerRevealSecondsLeft());
        assertTrue(engine.tickSeerReveal(tickEvents), "第 3 秒应触发自动确认");
        assertFalse(engine.isSeerRevealPending(), "超时后窗口应关闭");

        GameEvent confirmed = lastEvent(tickEvents, GameEvent.EventType.SEER_REVEAL_CONFIRMED);
        assertNotNull(confirmed, "超时确认应广播 SEER_REVEAL_CONFIRMED");
        assertEquals(Boolean.TRUE, confirmed.getPayload().get("auto"), "超时确认 auto 必须为 true");

        // 预言家此刻已经"看完"了，补上他的完成即可推进
        engine.applyAction(GameAction.human(ActionType.PHASE_DONE,
                seer.getSeatId(), null, null), events);
        assertNotEquals(GamePhase.NIGHT, state.getPhase(), "全部完成后夜晚应结算");
    }

    @Test
    @DisplayName("夜晚超时兜底会强制关闭翻牌窗口，不会因为悬空窗口卡住")
    void nightTimeoutClosesSeerReveal() {
        GameState state = newState(6);
        WerewolfGameEngine engine = newEngine(state);
        engine.setSeerConfirmSeconds(30);
        List<GameEvent> events = new ArrayList<>();
        startAndEnterNight(state, engine, events, 1);

        PlayerState wolf = onlyRole(state, RoleType.WEREWOLF);
        PlayerState seer = onlyRole(state, RoleType.SEER);
        PlayerState victim = plainVillager(state, wolf.getSeatId(), seer.getSeatId());

        engine.applyAction(GameAction.human(ActionType.SEER_CHECK,
                seer.getSeatId(), wolf.getSeatId(), null), events);
        assertTrue(engine.isSeerRevealPending());

        // 模拟夜晚阶段 3 分钟倒计时耗尽
        engine.autoCompleteCurrentPhase(events);
        engine.nextPhase(events);

        assertFalse(engine.isSeerRevealPending(), "超时兜底必须关闭翻牌窗口");
        assertNotEquals(GamePhase.NIGHT, state.getPhase(), "超时兜底后夜晚必须推进，不能卡住");
    }

    @Test
    @DisplayName("平民/狼人看不到翻牌确认信息（隐私）")
    void seerRevealIsPrivateToSeer() {
        GameState state = newState(6);
        WerewolfGameEngine engine = newEngine(state);
        List<GameEvent> events = new ArrayList<>();
        startAndEnterNight(state, engine, events, 1);

        PlayerState wolf = onlyRole(state, RoleType.WEREWOLF);
        PlayerState seer = onlyRole(state, RoleType.SEER);

        engine.applyAction(GameAction.human(ActionType.SEER_CHECK,
                seer.getSeatId(), wolf.getSeatId(), null), events);

        // SEER_REVEAL 必须只对预言家可见
        GameEvent reveal = lastEvent(events, GameEvent.EventType.SEER_REVEAL);
        assertNotNull(reveal);
        assertTrue(reveal.getVisibleSeats().contains(seer.getSeatId()),
                "预言家应能看到翻牌结果");
        assertFalse(reveal.getVisibleSeats().contains(wolf.getSeatId()),
                "狼人绝不能看到预览家的翻牌结果");
    }

    @Test
    @DisplayName("发言可以主动结束（SPEAK_PASS）：不写入发言记录，发言权交给下一位")
    void speakCanBeEndedEarly() {
        GameState state = newState(6);
        WerewolfGameEngine engine = newEngine(state);
        List<GameEvent> events = new ArrayList<>();
        startAndEnterNight(state, engine, events, 1);
        advanceTo(state, engine, GamePhase.DISCUSSION);

        PlayerState first = state.currentSpeaker();
        assertNotNull(first, "讨论阶段必须有第一位发言人");
        int speakOrderSize = state.getSpeakOrder().size();
        int speechCountBefore = state.getSpeeches().size();

        // 当前发言人应同时拥有 SPEAK 与 SPEAK_PASS
        List<ActionType> actions = engine.availableActions(first.getSeatId());
        assertTrue(actions.contains(ActionType.SPEAK), "应有发言选项");
        assertTrue(actions.contains(ActionType.SPEAK_PASS), "应有主动结束发言选项");

        // 不是发言人的人不能替他说完
        PlayerState other = state.alivePlayers().stream()
                .filter(p -> p.getSeatId() != first.getSeatId()).findFirst().orElseThrow();
        assertRejected(state, engine, GameAction.human(ActionType.SPEAK_PASS,
                other.getSeatId(), null, null), ErrorCode.GAME_NOT_YOUR_TURN);
        assertTrue(engine.availableActions(other.getSeatId()).isEmpty(),
                "非当前发言人没有发言相关选项");

        // 主动结束
        GameActionResult passed = engine.applyAction(GameAction.human(
                ActionType.SPEAK_PASS, first.getSeatId(), null, null), events);
        assertTrue(passed.isOk(), "主动结束发言应成功：" + passed.getMessage());
        assertEquals(speechCountBefore, state.getSpeeches().size(),
                "主动结束不能写入发言记录（否则会污染复盘）");
        assertEquals(1, state.getSpeakCursor(), "发言游标应前进一位");

        PlayerState next = state.currentSpeaker();
        assertNotNull(next, "应轮到下一位发言人");
        assertNotEquals(first.getSeatId(), next.getSeatId(), "发言权应交给别人");
        assertEquals(speakOrderSize, state.getSpeakOrder().size(), "发言顺序不应被改动");

        // 应广播 reason=PASS 的 SPEAK_SKIP，以及下一位的 SPEAK_TURN
        GameEvent skip = lastEvent(events, GameEvent.EventType.SPEAK_SKIP);
        assertNotNull(skip, "应广播 SPEAK_SKIP");
        assertEquals("PASS", skip.getPayload().get("reason"), "主动结束的 reason 应为 PASS");
        GameEvent turn = lastEvent(events, GameEvent.EventType.SPEAK_TURN);
        assertNotNull(turn, "应广播下一位的 SPEAK_TURN");
        assertEquals(next.getSeatId(), turn.getPayload().get("seatId"));
    }

    @Test
    @DisplayName("发言超时跳过的 reason 是 TIMEOUT（与主动结束区分）")
    void speakTimeoutReasonIsTimeout() {
        GameState state = newState(6);
        WerewolfGameEngine engine = newEngine(state);
        List<GameEvent> events = new ArrayList<>();
        startAndEnterNight(state, engine, events, 1);
        advanceTo(state, engine, GamePhase.DISCUSSION);

        PlayerState speaker = state.currentSpeaker();
        assertNotNull(speaker);
        engine.autoCompleteCurrentPhase(events);

        GameEvent skip = lastEvent(events, GameEvent.EventType.SPEAK_SKIP);
        assertNotNull(skip, "超时应广播 SPEAK_SKIP");
        assertEquals("TIMEOUT", skip.getPayload().get("reason"));
        assertEquals(speaker.getSeatId(), skip.getPayload().get("seatId"));
        assertEquals(0, state.getSpeeches().size(), "超时跳过不能写入发言记录");
    }

    @Test
    @DisplayName("最后一位发言人主动结束后自动进入投票")
    void lastSpeakerPassEntersVoting() {
        GameState state = newState(6);
        WerewolfGameEngine engine = newEngine(state);
        List<GameEvent> events = new ArrayList<>();
        startAndEnterNight(state, engine, events, 1);
        advanceTo(state, engine, GamePhase.DISCUSSION);

        int guard = 0;
        while (state.getPhase() == GamePhase.DISCUSSION && guard++ < 20) {
            PlayerState speaker = state.currentSpeaker();
            assertNotNull(speaker, "讨论阶段必须有当前发言人");
            engine.applyAction(GameAction.human(
                    ActionType.SPEAK_PASS, speaker.getSeatId(), null, null), events);
        }
        assertEquals(GamePhase.VOTING, state.getPhase(),
                "所有发言人都主动结束后应进入投票");
        assertEquals(0, state.getSpeeches().size(), "全程主动结束不应产生任何发言记录");
    }

    @Test
    @DisplayName("狼刀少数服从多数：意见一致才生效，平票则空刀（不能先到先得）")
    void wolfKillUsesMajorityAndTiesMeanNoKill() {
        GameState state = newState(6);
        WerewolfGameEngine engine = newEngine(state);
        List<GameEvent> events = new ArrayList<>();
        startAndEnterNight(state, engine, events, 2);

        List<PlayerState> wolves = state.aliveWolves();
        assertEquals(2, wolves.size(), "6 人局 2 狼");
        List<PlayerState> villagers = state.getPlayers().stream()
                .filter(p -> p.getRole() == RoleType.VILLAGER).toList();
        assertTrue(villagers.size() >= 2, "需要至少两个平民做不同目标");

        PlayerState w1 = wolves.get(0);
        PlayerState w2 = wolves.get(1);
        PlayerState t1 = villagers.get(0);
        PlayerState t2 = villagers.get(1);

        // 两只狼各选一个目标 -> 平票 -> 空刀
        assertTrue(engine.applyAction(GameAction.human(ActionType.WOLF_KILL,
                w1.getSeatId(), t1.getSeatId(), null), events).isOk());
        assertTrue(engine.applyAction(GameAction.human(ActionType.WOLF_KILL,
                w2.getSeatId(), t2.getSeatId(), null), events).isOk());
        assertEquals(2, state.getWolfVotes().size(), "两只狼都应被记为已投票");
        assertFalse(engine.wolvesAgreedOnTarget(), "意见不一致时不应认为已达成一致");

        // 结算夜晚：无人死亡（空刀）
        markNightDone(state, engine, events);
        assertNotEquals(GamePhase.NIGHT, state.getPhase(), "夜晚应已结算");
        assertTrue(state.getLastNightDeaths().isEmpty(),
                "平票空刀：不应有人死亡，但实际死了 " + state.getLastNightDeaths());
    }

    @Test
    @DisplayName("狼刀少数服从多数：两只狼选同一目标则正常击杀")
    void wolfKillTakesEffectWhenWolvesAgree() {
        GameState state = newState(6);
        WerewolfGameEngine engine = newEngine(state);
        List<GameEvent> events = new ArrayList<>();
        startAndEnterNight(state, engine, events, 2);

        List<PlayerState> wolves = state.aliveWolves();
        PlayerState w1 = wolves.get(0);
        PlayerState w2 = wolves.get(1);
        PlayerState victim = state.getPlayers().stream()
                .filter(p -> p.getRole() == RoleType.VILLAGER).findFirst().orElseThrow();

        assertTrue(engine.applyAction(GameAction.human(ActionType.WOLF_KILL,
                w1.getSeatId(), victim.getSeatId(), null), events).isOk());
        assertTrue(engine.applyAction(GameAction.human(ActionType.WOLF_KILL,
                w2.getSeatId(), victim.getSeatId(), null), events).isOk());
        assertTrue(engine.wolvesAgreedOnTarget(), "意见一致应认为已达成一致");

        markNightDone(state, engine, events);
        assertEquals(List.of(victim.getSeatId()), state.getLastNightDeaths(),
                "意见一致时应击杀该目标");
    }

    @Test
    @DisplayName("三狼 2:1 未达成全员一致时空刀")
    void wolfKillRequiresUnanimity() {
        GameState state = newState(9);
        WerewolfGameEngine engine = newEngine(state);
        List<GameEvent> events = new ArrayList<>();
        startAndEnterNight(state, engine, events, 3);

        List<PlayerState> wolves = state.aliveWolves();
        assertEquals(3, wolves.size(), "9 人局 3 狼");
        List<PlayerState> villagers = state.getPlayers().stream()
                .filter(p -> p.getRole() == RoleType.VILLAGER).toList();
        PlayerState minority = villagers.get(0);
        PlayerState majority = villagers.get(1);

        // 先让一只狼选 A（先到先得会锁定 A），另两只狼选 B
        assertTrue(engine.applyAction(GameAction.human(ActionType.WOLF_KILL,
                wolves.get(0).getSeatId(), minority.getSeatId(), null), events).isOk());
        assertTrue(engine.applyAction(GameAction.human(ActionType.WOLF_KILL,
                wolves.get(1).getSeatId(), majority.getSeatId(), null), events).isOk());
        assertTrue(engine.applyAction(GameAction.human(ActionType.WOLF_KILL,
                wolves.get(2).getSeatId(), majority.getSeatId(), null), events).isOk());

        assertFalse(engine.wolvesAgreedOnTarget(), "2:1 未全员一致，应空刀");
        markNightDone(state, engine, events);
        assertTrue(state.getLastNightDeaths().isEmpty(),"非全员一致时空刀");
    }

    @Test
    @DisplayName("投票：不能投自己、不能投不存在的座位、不能重复投票或重复弃票")
    void voteRulesAreEnforced() {
        GameState state = newState(6);
        WerewolfGameEngine engine = newEngine(state);
        List<GameEvent> events = new ArrayList<>();
        startAndEnterNight(state, engine, events, 1);
        advanceTo(state, engine, GamePhase.VOTING);

        List<PlayerState> alive = state.alivePlayers();
        assertTrue(alive.size() >= 4, "投票阶段至少应有 4 名存活玩家");
        PlayerState voter = alive.get(0);
        PlayerState other = alive.get(1);
        PlayerState third = alive.get(2);

        assertRejected(state, engine, GameAction.human(ActionType.VOTE,
                voter.getSeatId(), voter.getSeatId(), null), ErrorCode.GAME_TARGET_INVALID);
        assertRejected(state, engine, GameAction.human(ActionType.VOTE,
                voter.getSeatId(), 99, null), ErrorCode.GAME_TARGET_INVALID);

        assertTrue(engine.applyAction(GameAction.human(ActionType.VOTE,
                voter.getSeatId(), other.getSeatId(), null), new ArrayList<>()).isOk());
        assertEquals(GamePhase.VOTING, state.getPhase(), "还有人没投票时不应结算");

        assertRejected(state, engine, GameAction.human(ActionType.VOTE,
                voter.getSeatId(), third.getSeatId(), null), ErrorCode.GAME_ALREADY_VOTED);
        assertRejected(state, engine, GameAction.human(ActionType.ABSTAIN,
                voter.getSeatId(), null, null), ErrorCode.GAME_ALREADY_VOTED);
    }

    @Test
    @DisplayName("阶段不对时任何行动都被拒绝；空行动与不存在的座位同样被拒绝")
    void actionsAreRejectedInWrongPhase() {
        GameState state = newState(6);
        WerewolfGameEngine engine = newEngine(state);
        List<GameEvent> events = new ArrayList<>();

        // 空行动
        GameActionResult nullAction = engine.applyAction(null, events);
        assertFalse(nullAction.isOk());
        assertEquals(ErrorCode.BAD_REQUEST, nullAction.getErrorCode());

        assertTrue(engine.startGame(1, events).isOk());
        assertEquals(GamePhase.STARTING, state.getPhase());

        // 发牌阶段：所有行动都非法（含身份确认，因为还没到确认阶段）
        assertRejected(state, engine, GameAction.human(ActionType.WOLF_KILL, 1, 2, null),
                ErrorCode.GAME_WRONG_PHASE);
        assertRejected(state, engine, GameAction.human(ActionType.SEER_CHECK, 2, 3, null),
                ErrorCode.GAME_WRONG_PHASE);
        assertRejected(state, engine, GameAction.human(ActionType.WITCH_PASS, 3, null, null),
                ErrorCode.GAME_WRONG_PHASE);
        assertRejected(state, engine, GameAction.human(ActionType.SPEAK, 1, null, "你好"),
                ErrorCode.GAME_WRONG_PHASE);
        assertRejected(state, engine, GameAction.human(ActionType.VOTE, 1, 2, null),
                ErrorCode.GAME_WRONG_PHASE);
        assertRejected(state, engine, GameAction.human(ActionType.ABSTAIN, 1, null, null),
                ErrorCode.GAME_WRONG_PHASE);

        // 身份确认阶段：只能确认身份，其它行动仍非法
        engine.nextPhase(events);
        assertEquals(GamePhase.CONFIRM, state.getPhase());
        assertRejected(state, engine, GameAction.human(ActionType.WOLF_KILL, 1, 2, null),
                ErrorCode.GAME_WRONG_PHASE);
        assertRejected(state, engine, GameAction.human(ActionType.VOTE, 1, 2, null),
                ErrorCode.GAME_WRONG_PHASE);
        confirmAll(state, engine, events);
        assertEquals(GamePhase.NIGHT, state.getPhase(), "全部确认后应进入夜晚");

        // 夜晚：不能发言 / 投票；不存在的座位按“玩家不存在”处理
        assertEquals(GamePhase.NIGHT, state.getPhase());
        assertRejected(state, engine, GameAction.human(ActionType.VOTE, 1, 2, null),
                ErrorCode.GAME_WRONG_PHASE);
        assertRejected(state, engine, GameAction.human(ActionType.SPEAK, 1, null, "夜晚发言"),
                ErrorCode.GAME_WRONG_PHASE);
        assertRejected(state, engine, GameAction.human(ActionType.CONFIRM_ROLE, 1, null, null),
                ErrorCode.GAME_WRONG_PHASE);
        assertRejected(state, engine, GameAction.human(ActionType.WOLF_KILL, 99, 1, null),
                ErrorCode.GAME_PLAYER_DEAD);
    }

    // ================================================== 超时兜底 ========

    @Test
    @DisplayName("超时兜底：夜晚自动结算不卡死，投票阶段全员自动弃票")
    void autoCompleteCurrentPhase_resolvesPhaseOnTimeout() {
        GameState state = newState(6);
        WerewolfGameEngine engine = newEngine(state);
        List<GameEvent> events = new ArrayList<>();
        startAndEnterNight(state, engine, events, 1);

        assertEquals(3, engine.pendingNightActors().size(), "夜晚等待狼人/预言家/女巫三人行动");

        engine.autoCompleteCurrentPhase(events);

        assertNotEquals(GamePhase.NIGHT, state.getPhase(), "超时兜底后夜晚必须结算，不能停留在 NIGHT");
        assertTrue(state.getPhase() == GamePhase.DAY
                        || state.getPhase() == GamePhase.DISCUSSION
                        || state.getPhase() == GamePhase.END,
                "结算后应进入白天/讨论/结束，实际为 " + state.getPhase());
        assertTrue(hasEvent(events, GameEvent.EventType.PHASE_CHANGE), "结算过程应产出阶段切换事件");
        assertTrue(hasEvent(events, GameEvent.EventType.DAWN)
                        || hasEvent(events, GameEvent.EventType.PEACEFUL_NIGHT),
                "夜晚结算必须产出天亮或平安夜事件");
        assertTrue(engine.pendingNightActors().isEmpty());

        // 连续兜底 + 推进不应抛异常
        engine.autoCompleteCurrentPhase(events);
        engine.nextPhase(events);
        assertNotEquals(GamePhase.NIGHT, state.getPhase());

        // 投票阶段超时：所有未投票者自动弃票并结算
        advanceTo(state, engine, GamePhase.VOTING);
        assertEquals(state.alivePlayers().size(), engine.pendingVoters().size(),
                "投票刚开始时所有存活玩家都未投票");
        engine.autoCompleteCurrentPhase(events);
        assertTrue(engine.pendingVoters().isEmpty(), "超时兜底后不应还有未投票玩家");
        assertEquals(GamePhase.CHECK_WINNER, state.getPhase(), "全员弃票后应进入结算阶段");
    }

    // ================================================== 胜负判定 ========

    @Test
    @DisplayName("狼人全部出局 -> 好人阵营胜（evaluateWinner 只读判定）")
    void evaluateWinner_reportsVillageWhenAllWolvesAreDead() {
        GameState state = newState(6);
        assignRolesForWinnerTests(state);
        WerewolfGameEngine engine = newEngine(state);

        assertFalse(state.isFinished());
        state.bySeat(1).setAlive(false);
        state.bySeat(2).setAlive(false);

        assertEquals(RoleType.Camp.VILLAGE.name(), engine.evaluateWinner());
        assertFalse(state.isFinished(), "evaluateWinner 是只读判定，不允许改状态");
    }

    @Test
    @DisplayName("存活狼人数 >= 存活好人数 -> 狼人阵营胜")
    void evaluateWinner_reportsWerewolfWhenWolvesNotFewerThanVillagers() {
        GameState state = newState(6);
        assignRolesForWinnerTests(state);
        WerewolfGameEngine engine = newEngine(state);

        assertNull(engine.evaluateWinner(), "2 狼 4 好时尚未分出胜负");

        state.bySeat(5).setAlive(false);
        state.bySeat(6).setAlive(false);

        assertEquals(2, state.aliveWolves().size());
        assertEquals(2, state.aliveVillagers().size());
        assertEquals(RoleType.Camp.WEREWOLF.name(), engine.evaluateWinner(),
                "存活狼人数 >= 存活好人数时应判定狼人胜");
        assertEquals(RoleType.Camp.WEREWOLF.name(), engine.evaluateWinner(),
                "重复判定结果必须稳定");
        assertFalse(state.isFinished(), "evaluateWinner 是只读判定，不允许改状态");
    }

    /** 固定的“1、2 号狼人；3 号预言家；4 号女巫；5、6 号平民”布局。 */
    private static void assignRolesForWinnerTests(GameState state) {
        state.bySeat(1).setRole(RoleType.WEREWOLF);
        state.bySeat(2).setRole(RoleType.WEREWOLF);
        state.bySeat(3).setRole(RoleType.SEER);
        state.bySeat(4).setRole(RoleType.WITCH);
        state.bySeat(5).setRole(RoleType.VILLAGER);
        state.bySeat(6).setRole(RoleType.VILLAGER);
    }

    // ============================================ 可用行动 / 待行动者 ====

    @Test
    @DisplayName("availableActions / pendingNightActors / pendingVoters 与阶段和角色一致")
    void availableActionsAndPendingActorsFollowPhase() {
        GameState state = newState(6);
        WerewolfGameEngine engine = newEngine(state);
        List<GameEvent> events = new ArrayList<>();
        assertTrue(engine.startGame(1, events).isOk());

        // 发牌阶段：没有可用行动
        for (PlayerState p : state.getPlayers()) {
            assertTrue(engine.availableActions(p.getSeatId()).isEmpty(), "发牌阶段不应有可用行动");
        }
        assertTrue(engine.pendingNightActors().isEmpty(), "发牌阶段没有夜间行动者");
        assertTrue(engine.availableActions(99).isEmpty(), "不存在的座位没有可用行动");

        engine.nextPhase(events);

        // 新增的身份确认阶段：所有人都应看到"确认身份"这个动作
        assertEquals(GamePhase.CONFIRM, state.getPhase());
        for (PlayerState p : state.getPlayers()) {
            assertEquals(List.of(ActionType.CONFIRM_ROLE), engine.availableActions(p.getSeatId()),
                    "身份确认阶段每个人都只能确认身份");
        }
        assertTrue(engine.pendingNightActors().isEmpty(), "身份确认阶段没有夜间行动者");

        confirmAll(state, engine, events);
        assertEquals(GamePhase.NIGHT, state.getPhase());

        PlayerState wolf = onlyRole(state, RoleType.WEREWOLF);
        PlayerState seer = onlyRole(state, RoleType.SEER);
        PlayerState witch = onlyRole(state, RoleType.WITCH);
        PlayerState villager = plainVillager(state);

        assertEquals(List.of(ActionType.WOLF_KILL, ActionType.PHASE_DONE),
                engine.availableActions(wolf.getSeatId()),
                "狼人：先出刀，再点完成");
        assertEquals(List.of(ActionType.SEER_CHECK, ActionType.PHASE_DONE),
                engine.availableActions(seer.getSeatId()),
                "预言家：先查验，再点完成");
        assertTrue(engine.availableActions(villager.getSeatId()).isEmpty(), "平民夜晚没有行动");

        List<ActionType> witchActions = engine.availableActions(witch.getSeatId());
        assertTrue(witchActions.contains(ActionType.WITCH_POISON));
        assertTrue(witchActions.contains(ActionType.WITCH_PASS));
        assertFalse(witchActions.contains(ActionType.WITCH_HEAL), "狼人还没刀人时没有解药目标");

        assertEquals(3, engine.pendingNightActors().size());
        assertTrue(engine.pendingVoters().isEmpty(), "夜晚阶段没有投票者");

        // 狼人刀人后：女巫出现解药选项，狼人不再能出刀但还可以点"完成"
        assertTrue(engine.applyAction(GameAction.human(ActionType.WOLF_KILL,
                wolf.getSeatId(), villager.getSeatId(), null), events).isOk());
        assertTrue(engine.availableActions(witch.getSeatId()).contains(ActionType.WITCH_HEAL));
        assertEquals(List.of(ActionType.PHASE_DONE), engine.availableActions(wolf.getSeatId()),
                "狼人出刀后只剩「完成」；阶段不会因为他一个人行动完就推进");
        assertEquals(3, engine.pendingActors().size(),
                "全员完成之前，夜晚不会结算（F-16）：狼人出刀后仍需点完成");
        // 剩下的"还有行动可做"的人只有预言家与女巫
        assertEquals(2, engine.actorsWithActionsLeft().stream()
                        .filter(p -> !p.isWolf()).count(),
                "狼人已经出过刀，不再有行动可做");
        assertEquals(2, engine.pendingNightActors().size());

        // 投票阶段：所有存活玩家都可以投票或弃票
        advanceTo(state, engine, GamePhase.VOTING);
        PlayerState voter = state.alivePlayers().get(0);
        assertTrue(engine.availableActions(voter.getSeatId()).contains(ActionType.VOTE));
        assertTrue(engine.availableActions(voter.getSeatId()).contains(ActionType.ABSTAIN));
        assertEquals(state.alivePlayers().size(), engine.pendingVoters().size());
    }
}
