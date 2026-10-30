package com.werewolf.game.engine;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调试用：确认 4 人局（1 狼 1 预言家 1 女巫 1 平民）夜晚能正常结算。
 */
class NightResolveDebugTest {

    private GameState newState(int players) {
        GameState state = new GameState(1L, "TEST01");
        for (int i = 1; i <= players; i++) {
            state.getPlayers().add(new PlayerState(i, (long) i, "P" + i, null, false));
        }
        return state;
    }

    @Test
    @DisplayName("4 人局：狼刀 + 查验 + 女巫PASS 之后夜晚应立刻结算")
    void nightResolvesAfterAllRolesAct() {
        GameState state = newState(4);
        WerewolfGameEngine engine = new WerewolfGameEngine(state, new Random(7));
        List<GameEvent> events = new ArrayList<>();

        assertTrue(engine.startGame(1, events).isOk());
        assertEquals(GamePhase.STARTING, state.getPhase());

        // 发牌后先进入身份确认阶段，所有人都要确认
        assertTrue(engine.nextPhase(events).isOk());
        assertEquals(GamePhase.CONFIRM, state.getPhase());
        for (PlayerState p : state.getPlayers()) {
            if (!p.isRoleConfirmed()) {
                engine.applyAction(GameAction.human(
                        ActionType.CONFIRM_ROLE, p.getSeatId(), null, null), events);
            }
        }
        assertEquals(GamePhase.NIGHT, state.getPhase(), "全部确认后应进入第一晚");

        PlayerState wolf = state.aliveWolves().get(0);
        PlayerState seer = state.firstAliveWithRole(RoleType.SEER);
        PlayerState witch = state.firstAliveWithRole(RoleType.WITCH);
        PlayerState villager = state.getPlayers().stream()
                .filter(p -> p.getRole() == RoleType.VILLAGER).findFirst().orElseThrow();

        System.out.println("wolf=" + wolf.getSeatId() + " seer=" + seer.getSeatId()
                + " witch=" + witch.getSeatId() + " villager=" + villager.getSeatId());

        GameActionResult kill = engine.applyAction(
                GameAction.human(ActionType.WOLF_KILL, wolf.getSeatId(), villager.getSeatId(), null),
                events);
        System.out.println("wolfKill ok=" + kill.isOk() + " advanced=" + kill.isPhaseAdvanced()
                + " msg=" + kill.getMessage());
        assertEquals(GamePhase.NIGHT, state.getPhase());

        GameActionResult check = engine.applyAction(
                GameAction.human(ActionType.SEER_CHECK, seer.getSeatId(), wolf.getSeatId(), null),
                events);
        System.out.println("seerCheck ok=" + check.isOk() + " advanced=" + check.isPhaseAdvanced()
                + " msg=" + check.getMessage());
        assertEquals(GamePhase.NIGHT, state.getPhase());
        System.out.println("seer records = " + seer.getCheckRecords());

        GameActionResult pass = engine.applyAction(
                GameAction.human(ActionType.WITCH_PASS, witch.getSeatId(), null, null), events);
        System.out.println("witchPass ok=" + pass.isOk() + " advanced=" + pass.isPhaseAdvanced()
                + " msg=" + pass.getMessage());
        System.out.println("phase after witch pass = " + state.getPhase()
                + " deaths = " + state.getLastNightDeaths());

        // 狼人与预言家还没点"完成"，夜晚此刻不应结算
        assertEquals(GamePhase.NIGHT, state.getPhase(),
                "还有行动者没点完成，夜晚不能提前结算（F-16）");
        markNightDone(state, engine, events);

        assertEquals(GamePhase.DAY, state.getPhase(),
                "全员完成（含女巫点「不使用」）后夜晚应结算，进入 DAY 公布死讯");
        assertEquals(List.of(villager.getSeatId()), state.getLastNightDeaths());
        assertTrue(events.stream().anyMatch(e -> e.getEventType() == GameEvent.EventType.DAWN));
    }

    /**
     * 让所有还没点"完成"的夜间行动者点一次完成（新规则要求，见 F-16）。
     *
     * <p>预言家查验后会开启 30 秒翻牌确认窗口（F-18），要先确认才能继续。
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

    @Test
    @DisplayName("狼人行动不得重置夜晚倒计时（D-12 回归）：未收齐行动时 phaseAdvanced 必须为 false")
    void wolfKillDoesNotAdvancePhase() {
        GameState state = newState(5);
        WerewolfGameEngine engine = new WerewolfGameEngine(state, new Random(11));
        List<GameEvent> events = new ArrayList<>();

        assertTrue(engine.startGame(1, events).isOk());
        assertTrue(engine.nextPhase(events).isOk());
        // 适配新增的身份确认阶段
        for (PlayerState p : state.getPlayers()) {
            if (!p.isRoleConfirmed()) {
                engine.applyAction(GameAction.human(
                        ActionType.CONFIRM_ROLE, p.getSeatId(), null, null), events);
            }
        }
        assertEquals(GamePhase.NIGHT, state.getPhase());

        PlayerState wolf = state.aliveWolves().get(0);
        PlayerState seer = state.firstAliveWithRole(RoleType.SEER);
        PlayerState victim = state.getPlayers().stream()
                .filter(p -> p.getRole() == RoleType.VILLAGER).findFirst().orElseThrow();

        GameActionResult kill = engine.applyAction(
                GameAction.human(ActionType.WOLF_KILL, wolf.getSeatId(), victim.getSeatId(), null),
                events);
        assertTrue(kill.isOk());
        assertFalse(kill.isPhaseAdvanced(),
                "只有一个狼人时狼刀后仍有预言家/女巫未行动，不能报告阶段已切换（否则倒计时会被重置）");
        assertTrue(kill.isActionProgressed(), "狼人行动已完成，应报告行动进度");

        GameActionResult check = engine.applyAction(
                GameAction.human(ActionType.SEER_CHECK, seer.getSeatId(), wolf.getSeatId(), null),
                events);
        assertTrue(check.isOk());
        assertFalse(check.isPhaseAdvanced(), "女巫尚未行动，阶段不能切换");
        assertEquals(GamePhase.NIGHT, state.getPhase());
    }

    @Test
    @DisplayName("女巫出局后夜晚不再等待女巫（D-11 回归）：7 人局，狼刀 + 查验即结算")
    void nightResolvesWithoutWitch() {
        // 用 7 人局（2 狼 + 预言家 + 女巫 + 3 平民）：女巫出局后好人仍有 5 人，
        // 狼人刀掉一个平民后是 2 狼 vs 3 好人，不会立刻达成"狼数 >= 好人数"结束对局，
        // 这样可以专门断言"夜晚本身能否在没有女巫的情况下正常结算"。
        GameState state = newState(7);
        WerewolfGameEngine engine = new WerewolfGameEngine(state, new Random(23));
        List<GameEvent> events = new ArrayList<>();

        assertTrue(engine.startGame(2, events).isOk());
        assertTrue(engine.nextPhase(events).isOk());
        for (PlayerState p : state.getPlayers()) {
            if (!p.isRoleConfirmed()) {
                engine.applyAction(GameAction.human(
                        ActionType.CONFIRM_ROLE, p.getSeatId(), null, null), events);
            }
        }
        assertEquals(GamePhase.NIGHT, state.getPhase());

        List<PlayerState> wolves = state.aliveWolves();
        PlayerState seer = state.firstAliveWithRole(RoleType.SEER);
        PlayerState witch = state.firstAliveWithRole(RoleType.WITCH);
        PlayerState victim = state.getPlayers().stream()
                .filter(p -> p.getRole() == RoleType.VILLAGER).findFirst().orElseThrow();

        // 直接让女巫出局（模拟上一轮被放逐 / 被毒），验证夜晚不会白等 30 秒超时
        witch.setAlive(false);

        for (int i = 0; i < wolves.size(); i++) {
            PlayerState wolf = wolves.get(i);
            GameActionResult kill = engine.applyAction(
                    GameAction.human(ActionType.WOLF_KILL, wolf.getSeatId(), victim.getSeatId(), null),
                    events);
            assertTrue(kill.isOk(), "狼人应能完成刀人");
            if (i < wolves.size() - 1) {
                assertFalse(kill.isPhaseAdvanced(), "还有狼人未行动时不能结算");
            }
        }
        // 最后一只狼行动后，夜晚只剩预言家未行动
        GameActionResult check = engine.applyAction(
                GameAction.human(ActionType.SEER_CHECK, seer.getSeatId(), wolves.get(0).getSeatId(), null),
                events);

        assertTrue(check.isOk());
        // 新规则：查验完之后还需要所有行动者点"完成"才结算
        assertEquals(GamePhase.NIGHT, state.getPhase(), "未点完成前夜晚不结算");
        markNightDone(state, engine, events);

        assertEquals(GamePhase.DAY, state.getPhase(),
                "女巫已出局时，狼人+预言家都完成后夜晚应立即结算（不必等倒计时超时）");
        assertEquals(List.of(victim.getSeatId()), state.getLastNightDeaths());
    }
}
