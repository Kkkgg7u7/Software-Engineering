package com.werewolf.ai;

import com.werewolf.ai.dto.AiContext;
import com.werewolf.ai.dto.AiDecision;
import com.werewolf.game.domain.ActionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RuleAgent} 纯单元测试（不启动 Spring）。
 *
 * <p>规则 AI 是“零外部依赖”的兜底实现：即使没有配置任何 LLM API Key，
 * 一局游戏也必须能被 AI NPC 填满。因此这里重点验证三件事：
 * <ol>
 *   <li>夜晚（狼刀 / 查验 / 女巫用药）、投票、发言三种场景都能给出
 *       {@link AiDecision#isValid()} 为 true 的决策；</li>
 *   <li>狼人不会把同伴当成击杀 / 投票目标；</li>
 *   <li>药水不可用时不会返回 WITCH_HEAL / WITCH_POISON。</li>
 * </ol>
 *
 * <p>注意：规则 AI 只产出“建议”，最终合法性仍由 WerewolfGameEngine 判定。
 */
class RuleAgentTest {

    private final RuleAgent agent = new RuleAgent();

    private static AiContext context(int mySeatId, String role) {
        AiContext ctx = new AiContext();
        ctx.setMySeatId(mySeatId);
        ctx.setMyNickname("AI·月影");
        ctx.setMyRole(role);
        ctx.setRound(1);
        ctx.setPhase("夜晚");
        ctx.setTask("测试任务");
        ctx.setAliveSeats(List.of(
                "1 号 甲(AI)", "2 号 乙(AI)", "3 号 丙(AI)",
                "4 号 丁(AI)", "5 号 戊(AI)", "6 号 己(AI)"));
        ctx.setDeadSeats(List.of());
        ctx.setSpeechLog(List.of());
        ctx.setEventLog(List.of());
        ctx.setLastVoteSummary("");
        return ctx;
    }

    // ========================================================== 夜晚 =====

    @Test
    @DisplayName("狼人夜里不会把同伴当成击杀目标")
    void wolfKill_neverTargetsTeammate() {
        AiContext ctx = context(1, "WEREWOLF");
        ctx.setWolfTeammates(List.of(6));
        ctx.setWolfTeammatesAlive(List.of(6));
        ctx.setAllowedActions(List.of("WOLF_KILL"));
        ctx.setSelectableTargets(List.of(2, 3, 6));

        AiDecision decision = agent.decide(ctx, List.of(ActionType.WOLF_KILL), List.of(2, 3, 6));

        assertTrue(decision.isValid(), "狼人夜晚决策必须有效：" + decision.reason());
        assertEquals(ActionType.WOLF_KILL, decision.action());
        assertNotNull(decision.targetSeatId(), "WOLF_KILL 必须有目标");
        assertNotEquals(6, decision.targetSeatId().intValue(), "不能击杀狼人同伴");
        assertTrue(List.of(2, 3, 6).contains(decision.targetSeatId()), "目标必须落在可选集合内");
        assertEquals(AiDecision.Source.RULE_ONLY, decision.source(), "规则 AI 不产生 LLM 调用");
        assertFalse(decision.usedLlm());
    }

    @Test
    @DisplayName("预言家夜里从可选集合中挑一个目标")
    void seerCheck_returnsTargetFromSelectable() {
        AiContext ctx = context(2, "SEER");
        ctx.setMyChecks(List.of("第 1 晚查验 3 号(丙) 是 好人"));
        ctx.setAllowedActions(List.of("SEER_CHECK"));
        ctx.setSelectableTargets(List.of(1, 3, 4));

        AiDecision decision = agent.decide(ctx, List.of(ActionType.SEER_CHECK), List.of(1, 3, 4));

        assertTrue(decision.isValid(), "预言家查验决策必须有效");
        assertEquals(ActionType.SEER_CHECK, decision.action());
        assertNotNull(decision.targetSeatId());
        assertTrue(List.of(1, 3, 4).contains(decision.targetSeatId()));
        assertTrue(decision.reason().contains("查验"), "理由里应说明是查验：" + decision.reason());
    }

    @Test
    @DisplayName("女巫首夜有解药且有人被刀 -> 用解药救人")
    void witchHealsOnFirstNight() {
        AiContext ctx = context(3, "WITCH");
        ctx.setHealPotionAvailable(true);
        ctx.setPoisonPotionAvailable(true);
        ctx.setAllowedActions(List.of("WITCH_HEAL", "WITCH_POISON", "WITCH_PASS"));
        ctx.setSelectableTargets(List.of(5));

        AiDecision decision = agent.decide(ctx,
                List.of(ActionType.WITCH_HEAL, ActionType.WITCH_POISON, ActionType.WITCH_PASS),
                List.of(5));

        assertTrue(decision.isValid(), "女巫决策必须有效");
        assertEquals(ActionType.WITCH_HEAL, decision.action(), "首夜应当救人");
        assertEquals(5, decision.targetSeatId().intValue(), "解药目标应是当晚被刀者");
    }

    @Test
    @DisplayName("女巫两瓶药都用完时只会选择 WITCH_PASS（引擎此时也只下发 WITCH_PASS）")
    void witchWithoutPotions_neverReturnsHealOrPoison() {
        AiContext ctx = context(3, "WITCH");
        ctx.setHealPotionAvailable(false);
        ctx.setPoisonPotionAvailable(false);
        ctx.setRound(4);
        ctx.setAllowedActions(List.of("WITCH_PASS"));
        ctx.setSelectableTargets(List.of());

        AiDecision decision = agent.decide(ctx, List.of(ActionType.WITCH_PASS), List.of());

        assertTrue(decision.isValid(), "无药可用的女巫必须给出一个合法决策（空过）");
        assertEquals(ActionType.WITCH_PASS, decision.action());
        assertNotEquals(ActionType.WITCH_HEAL, decision.action(), "解药已用完，不能返回 WITCH_HEAL");
        assertNotEquals(ActionType.WITCH_POISON, decision.action(), "毒药已用完，不能返回 WITCH_POISON");
        assertNull(decision.targetSeatId());
    }

    @Test
    @DisplayName("女巫拿到“查验为狼人”的信息时会用毒（决策文本格式：座位号在最前）")
    void witchPoisonsCheckedWolf() {
        AiContext ctx = context(3, "WITCH");
        ctx.setHealPotionAvailable(false);
        ctx.setPoisonPotionAvailable(true);
        ctx.setRound(3);
        // RuleAgent 用 seatOfLine 取“文本里第一个数字”作为座位号，因此这里给出以座位号开头的记录
        ctx.setMyChecks(List.of("4 号 丁 是 狼人"));
        ctx.setAllowedActions(List.of("WITCH_POISON", "WITCH_PASS"));
        ctx.setSelectableTargets(List.of(1, 2, 4, 5));

        AiDecision decision = agent.decide(ctx,
                List.of(ActionType.WITCH_POISON, ActionType.WITCH_PASS), List.of(1, 2, 4, 5));

        assertTrue(decision.isValid(), "毒药决策必须带目标才有效");
        assertEquals(ActionType.WITCH_POISON, decision.action(), "掌握确凿狼人信息时应当用毒");
        assertEquals(4, decision.targetSeatId().intValue());
    }

    // ========================================================== 投票 =====

    @Test
    @DisplayName("狼人投票不会投同伴；好人投票目标合法")
    void vote_avoidsTeammatesForWolf() {
        AiContext wolfCtx = context(1, "WEREWOLF");
        wolfCtx.setWolfTeammates(List.of(6));
        wolfCtx.setWolfTeammatesAlive(List.of(6));
        wolfCtx.setPhase("投票");
        wolfCtx.setAllowedActions(List.of("VOTE", "ABSTAIN"));
        wolfCtx.setSelectableTargets(List.of(2, 3, 6));

        AiDecision wolfVote = agent.decide(wolfCtx,
                List.of(ActionType.VOTE, ActionType.ABSTAIN), List.of(2, 3, 6));

        assertTrue(wolfVote.isValid(), "狼人投票决策必须有效");
        assertEquals(ActionType.VOTE, wolfVote.action());
        assertNotNull(wolfVote.targetSeatId());
        assertNotEquals(6, wolfVote.targetSeatId().intValue(), "狼人不能投自己的同伴");
        assertTrue(List.of(2, 3, 6).contains(wolfVote.targetSeatId()));

        AiContext villagerCtx = context(2, "VILLAGER");
        villagerCtx.setPhase("投票");
        villagerCtx.setAllowedActions(List.of("VOTE", "ABSTAIN"));
        villagerCtx.setSelectableTargets(List.of(1, 3, 4));

        AiDecision villagerVote = agent.decide(villagerCtx,
                List.of(ActionType.VOTE, ActionType.ABSTAIN), List.of(1, 3, 4));

        assertTrue(villagerVote.isValid(), "好人投票决策必须有效");
        assertEquals(ActionType.VOTE, villagerVote.action());
        assertTrue(List.of(1, 3, 4).contains(villagerVote.targetSeatId()));
    }

    // ========================================================== 发言 =====

    @Test
    @DisplayName("发言内容非空，并且包含自己的座位号")
    void speak_returnsNonEmptySpeechWithOwnSeat() {
        AiContext ctx = context(2, "VILLAGER");
        ctx.setPhase("讨论");
        ctx.setAllowedActions(List.of("SPEAK"));
        ctx.setSelectableTargets(List.of());

        AiDecision decision = agent.decide(ctx, List.of(ActionType.SPEAK), List.of());

        assertTrue(decision.isValid(), "发言决策必须带非空 speech");
        assertEquals(ActionType.SPEAK, decision.action());
        assertNotNull(decision.speech());
        assertFalse(decision.speech().isBlank(), "发言内容不能为空");
        assertTrue(decision.speech().contains("我是 " + ctx.getMySeatId() + " 号"),
                "发言应自报座位号，实际：" + decision.speech());
        assertTrue(decision.speech().contains("2"), "发言应包含自己的座位号数字");
    }

    @Test
    @DisplayName("狼人发言同样是非空且自报座位号的合法决策")
    void wolfSpeech_isValidAndSelfIdentified() {
        AiContext ctx = context(1, "WEREWOLF");
        ctx.setWolfTeammates(List.of(2, 3, 4, 5, 6));
        ctx.setWolfTeammatesAlive(List.of(2, 3, 4, 5, 6));
        ctx.setPhase("讨论");
        ctx.setAllowedActions(List.of("SPEAK"));

        AiDecision decision = agent.decide(ctx, List.of(ActionType.SPEAK), List.of());

        assertTrue(decision.isValid());
        assertEquals(ActionType.SPEAK, decision.action());
        assertNotNull(decision.speech());
        assertFalse(decision.speech().isBlank());
        assertTrue(decision.speech().contains("我是 1 号"));
    }

    @Test
    @DisplayName("狼人规则 AI 的公开发言不能泄露私有的狼同伴名单")
    void wolfSpeech_doesNotRevealTeammates() {
        AiContext ctx = context(1, "WEREWOLF");
        ctx.setWolfTeammates(List.of(5, 6));
        ctx.setWolfTeammatesAlive(List.of(5, 6));
        ctx.setPhase("讨论");
        ctx.setAllowedActions(List.of("SPEAK"));

        AiDecision decision = agent.decide(ctx, List.of(ActionType.SPEAK), List.of());

        assertTrue(decision.isValid());
        assertEquals(ActionType.SPEAK, decision.action());
        assertNotNull(decision.speech());
        assertFalse(decision.speech().contains("内部"), "公开发言不能包含内部提示：" + decision.speech());
        assertFalse(decision.speech().contains("同伴"), "公开发言不能说明狼同伴：" + decision.speech());
        assertFalse(decision.speech().contains("[5, 6]"), "公开发言不能带出私有名单：" + decision.speech());
    }

    // ======================================================= 兜底分支 ====

    @Test
    @DisplayName("没有任何可用行动时返回空过决策，而不是抛异常")
    void noAllowedActions_returnsNonBlockingDecision() {
        AiContext ctx = context(4, "VILLAGER");
        ctx.setAllowedActions(List.of());

        AiDecision decision = agent.decide(ctx, List.of(), List.of());

        assertNotNull(decision);
        assertTrue(decision.isValid(), "兜底决策也要满足 isValid");
        assertEquals(ActionType.WITCH_PASS, decision.action(), "无可用行动时返回“不行动”");
        assertNull(decision.targetSeatId());
    }

    @Test
    @DisplayName("只有弃票可用时返回 ABSTAIN 而不是 VOTE")
    void abstainPreferredWhenVoteNotAllowed() {
        AiContext ctx = context(4, "VILLAGER");
        ctx.setPhase("投票");
        ctx.setAllowedActions(List.of("ABSTAIN"));

        AiDecision decision = agent.decide(ctx, List.of(ActionType.ABSTAIN), List.of());

        assertTrue(decision.isValid());
        assertEquals(ActionType.ABSTAIN, decision.action());
    }
}
