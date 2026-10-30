package com.werewolf.ai.dto;

import com.werewolf.game.domain.ActionType;

/**
 * AI 的一次决策结果。
 *
 * <p>对应方案第十七节的严格 JSON：
 * <pre>
 * { "speech": "...", "action": "...", "targetId": 0, "reason": "..." }
 * </pre>
 *
 * <p>注意：这只是<b>建议</b>。{@code RoomRuntime} 会把它包装成 GameAction 交给
 * {@code WerewolfGameEngine} 校验，非法决策会被直接拒绝（AI 无法绕过规则）。
 */
public record AiDecision(ActionType action,
                         Integer targetSeatId,
                         String speech,
                         String reason,
                         Source source,
                         boolean usedLlm) {

    public enum Source {
        /** 大模型返回并通过了格式校验。 */
        LLM,
        /** 大模型不可用或输出非法，由规则 AI 接管。 */
        RULE_FALLBACK,
        /** 纯规则 AI（未配置 API Key 或达到调用上限）。 */
        RULE_ONLY
    }

    public static AiDecision llm(ActionType action, Integer target, String speech, String reason) {
        return new AiDecision(action, target, speech, reason, Source.LLM, true);
    }

    public static AiDecision fallback(ActionType action, Integer target, String speech, String reason) {
        return new AiDecision(action, target, speech, reason, Source.RULE_FALLBACK, false);
    }

    public static AiDecision rule(ActionType action, Integer target, String speech, String reason) {
        return new AiDecision(action, target, speech, reason, Source.RULE_ONLY, false);
    }

    /** 是否具备可提交的基本要素（最终合法性仍由引擎判定）。 */
    public boolean isValid() {
        if (action == null) {
            return false;
        }
        return switch (action) {
            case WOLF_KILL, SEER_CHECK, WITCH_POISON, VOTE, GUARD_PROTECT, CUPID_LINK, SHERIFF_VOTE, HUNTER_SHOOT, WHITE_WOLF_CARRY, BADGE_TRANSFER -> targetSeatId != null;
            case SPEAK -> speech != null && !speech.isBlank();
            default -> true;
        };
    }
}
