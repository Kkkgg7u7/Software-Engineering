package com.werewolf.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.werewolf.ai.dto.AiDecision;
import com.werewolf.game.domain.ActionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiJsonParser} 单元测试（直接 new，不启动 Spring）。
 *
 * <p>这是“AI 不能绕过 GameEngine”的第一道闸门，因此要求：
 * <ul>
 *   <li>能容忍 markdown 代码块、前后废话，尽力抽出第一个 JSON 对象；</li>
 *   <li>action 必须落在【允许的动作】集合内；</li>
 *   <li>需要目标的动作，targetId 必须落在【可选目标】集合内；</li>
 *   <li>任何不合法一律返回 null，由调用方切换到规则 AI。</li>
 * </ul>
 */
class AiJsonParserTest {

    private final AiJsonParser parser = new AiJsonParser(new ObjectMapper());

    // ================================================== 正常解析路径 ====

    @Test
    @DisplayName("标准 JSON 能解析")
    void parsesPlainJson() {
        String raw = "{\"action\":\"WOLF_KILL\",\"targetId\":3,\"speech\":\"\",\"reason\":\"刀 3 号\"}";

        AiDecision decision = parser.parse(raw, List.of(ActionType.WOLF_KILL), List.of(2, 3));

        assertNotNull(decision);
        assertEquals(ActionType.WOLF_KILL, decision.action());
        assertEquals(3, decision.targetSeatId().intValue());
        assertEquals("刀 3 号", decision.reason());
        assertTrue(decision.usedLlm(), "能过校验的决策来自 LLM");
        assertEquals(AiDecision.Source.LLM, decision.source());
        assertTrue(decision.isValid());
    }

    @Test
    @DisplayName("被 ```json 代码块包裹的 JSON 能解析")
    void parsesJsonInsideMarkdownFence() {
        String raw = "```json\n{\"action\":\"VOTE\",\"targetId\":2,\"reason\":\"投 2 号\"}\n```";

        AiDecision decision = parser.parse(raw, List.of(ActionType.VOTE), List.of(2, 4));

        assertNotNull(decision, "markdown 代码块必须被容忍");
        assertEquals(ActionType.VOTE, decision.action());
        assertEquals(2, decision.targetSeatId().intValue());
    }

    @Test
    @DisplayName("前有解释、后有废话的 JSON 能解析")
    void parsesJsonWithSurroundingNoise() {
        String raw = "好的，我的决策如下：{\"action\":\"SEER_CHECK\",\"targetId\":4} 希望对你有所帮助。";

        AiDecision decision = parser.parse(raw, List.of(ActionType.SEER_CHECK), List.of(3, 4));

        assertNotNull(decision);
        assertEquals(ActionType.SEER_CHECK, decision.action());
        assertEquals(4, decision.targetSeatId().intValue());
    }

    @Test
    @DisplayName("字符串内部的花括号不会破坏对象提取")
    void toleratesBracesInsideStrings() {
        String raw = "{\"speech\":\"他说 {别慌} 然后走了\",\"action\":\"SPEAK\"}";

        AiDecision decision = parser.parse(raw, List.of(ActionType.SPEAK), List.of());

        assertNotNull(decision);
        assertEquals(ActionType.SPEAK, decision.action());
        assertNotNull(decision.speech());
        assertTrue(decision.speech().contains("{别慌}"), "发言内容应完整保留：" + decision.speech());
    }

    @Test
    @DisplayName("extractJson 能抽出第一个完整对象，也能识别“没有 JSON”")
    void extractJson_behavesAsDocumented() {
        assertEquals("{\"a\":1}", parser.extractJson("前缀 {\"a\":1} 后缀"));
        assertEquals("{\"a\":{\"b\":2}}", parser.extractJson("{\"a\":{\"b\":2}}"));
        assertNull(parser.extractJson("这里没有任何 JSON"));
        assertNull(parser.extractJson(null));
        assertNull(parser.extractJson("{\"a\":1"), "缺少右括号时不应抽出半截对象");
    }

    // ================================================== 非法输入路径 ====

    @Test
    @DisplayName("action 不在允许集合内 -> null")
    void rejectsActionOutsideAllowedSet() {
        String raw = "{\"action\":\"VOTE\",\"targetId\":2}";

        assertNull(parser.parse(raw, List.of(ActionType.WOLF_KILL), List.of(2)));
    }

    @Test
    @DisplayName("未知 action 枚举值 -> null")
    void rejectsUnknownAction() {
        assertNull(parser.parse("{\"action\":\"FLY_AWAY\",\"targetId\":2}",
                List.of(ActionType.WOLF_KILL), List.of(2)));
        assertNull(parser.parse("{\"action\":\"\",\"targetId\":2}",
                List.of(ActionType.WOLF_KILL), List.of(2)));
    }

    @Test
    @DisplayName("需要目标的动作：targetId 不在可选集合内 -> null")
    void rejectsTargetOutsideSelectableSet() {
        assertNull(parser.parse("{\"action\":\"WOLF_KILL\",\"targetId\":9}",
                List.of(ActionType.WOLF_KILL), List.of(2, 3)));
        assertNull(parser.parse("{\"action\":\"WOLF_KILL\"}",
                List.of(ActionType.WOLF_KILL), List.of(2, 3)), "缺少 targetId 也要拒绝");
    }

    @Test
    @DisplayName("不需要目标的动作：targetId=0 或缺失都能成功")
    void acceptsMissingOrZeroTargetForTargetlessActions() {
        AiDecision pass = parser.parse("{\"action\":\"WITCH_PASS\",\"targetId\":0}",
                List.of(ActionType.WITCH_PASS), List.of());

        assertNotNull(pass, "WITCH_PASS 不需要目标，targetId=0 应被容忍");
        assertEquals(ActionType.WITCH_PASS, pass.action());
        assertNull(pass.targetSeatId(), "不需要目标的动作应把 targetId 归一化为 null");
        assertTrue(pass.isValid());

        AiDecision abstain = parser.parse("{\"action\":\"ABSTAIN\"}",
                List.of(ActionType.ABSTAIN), List.of());

        assertNotNull(abstain, "ABSTAIN 缺少 targetId 应被容忍");
        assertEquals(ActionType.ABSTAIN, abstain.action());
        assertNull(abstain.targetSeatId());
        assertTrue(abstain.isValid());
    }

    @Test
    @DisplayName("SPEAK 必须带非空发言，否则 -> null")
    void speakRequiresNonBlankSpeech() {
        assertNull(parser.parse("{\"action\":\"SPEAK\",\"speech\":\"   \"}",
                List.of(ActionType.SPEAK), List.of()));
        assertNull(parser.parse("{\"action\":\"SPEAK\"}",
                List.of(ActionType.SPEAK), List.of()));

        AiDecision ok = parser.parse("{\"action\":\"SPEAK\",\"speech\":\"我是 3 号，我怀疑 5 号\"}",
                List.of(ActionType.SPEAK), List.of());
        assertNotNull(ok);
        assertEquals(ActionType.SPEAK, ok.action());
        assertTrue(ok.speech().contains("3 号"));
    }

    @Test
    @DisplayName("WITCH_HEAL 在当前解析器里属于“需要目标”的动作（必须显式给 targetId）")
    void witchHealRequiresExplicitTargetId() {
        // 记录当前实现的真实约定：requiresTarget(WITCH_HEAL) == true，
        // 与引擎侧“targetId 可空（默认救当晚被刀者）”的语义不同，详见交付说明。
        assertNull(parser.parse("{\"action\":\"WITCH_HEAL\"}",
                List.of(ActionType.WITCH_HEAL), List.of(5)));

        AiDecision ok = parser.parse("{\"action\":\"WITCH_HEAL\",\"targetId\":5}",
                List.of(ActionType.WITCH_HEAL), List.of(5));
        assertNotNull(ok);
        assertEquals(ActionType.WITCH_HEAL, ok.action());
        assertEquals(5, ok.targetSeatId().intValue());
    }

    @Test
    @DisplayName("非法 JSON / 非对象 / null -> null")
    void rejectsBrokenJson() {
        assertNull(parser.parse("这根本不是 JSON", List.of(ActionType.WOLF_KILL), List.of(2)));
        assertNull(parser.parse("{action: WOLF_KILL}", List.of(ActionType.WOLF_KILL), List.of(2)));
        assertNull(parser.parse("{\"action\":\"WOLF_KILL\",\"targetId\":2",
                List.of(ActionType.WOLF_KILL), List.of(2)), "缺少右括号应判为非法");
        assertNull(parser.parse("[1,2,3]", List.of(ActionType.WOLF_KILL), List.of(2)),
                "顶层不是对象应判为非法");
        assertNull(parser.parse("", List.of(ActionType.WOLF_KILL), List.of(2)));
        assertNull(parser.parse(null, List.of(ActionType.WOLF_KILL), List.of(2)));
    }

    @Test
    @DisplayName("reason 缺省时填充默认理由，且不产生 LLM 之外的状态")
    void fillsDefaultReasonWhenMissing() {
        AiDecision decision = parser.parse("{\"action\":\"ABSTAIN\"}",
                List.of(ActionType.ABSTAIN), List.of());

        assertNotNull(decision);
        assertNotNull(decision.reason());
        assertFalse(decision.reason().isBlank());
    }
}
