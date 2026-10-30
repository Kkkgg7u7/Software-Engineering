package com.werewolf.ai;

import com.werewolf.ai.dto.AiContext;
import com.werewolf.ai.dto.AiDecision;
import com.werewolf.game.domain.ActionType;

import java.util.List;

/**
 * AI 决策器接口。两种实现：
 * <ul>
 *   <li>{@link LlmAgent}：调用 LLM，产出更自然的发言；</li>
 *   <li>{@link RuleAgent}：纯规则，零外部依赖（断网 / 无 API Key / 调用超限时的兜底）。</li>
 * </ul>
 */
public interface AiAgent {

    AiDecision decide(AiContext context, List<ActionType> allowedActions, List<Integer> selectableTargets);
}
