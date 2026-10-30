package com.werewolf.ai;

import com.werewolf.ai.dto.AiContext;
import com.werewolf.ai.dto.AiDecision;
import com.werewolf.config.AiProperties;
import com.werewolf.game.domain.ActionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LLM 驱动的 AI 决策器（OpenAI 兼容 /chat/completions 协议）。
 *
 * <p>设计约束（方案第八、十七节）：
 * <ol>
 *   <li><b>只喂公开信息</b>：上下文来自 {@link AiContextBuilder}，不含他人身份；</li>
 *   <li><b>严格 JSON 输出</b>：{@code speech / action / targetId / reason}，
 *       解析失败或 action 不在允许集合内一律判为非法，交由 {@link RuleAgent} 接管；</li>
 *   <li><b>绝不修改状态</b>：本类只返回 {@link AiDecision}（建议），
 *       最终是否生效由 {@code WerewolfGameEngine} 校验。</li>
 * </ol>
 */
@Component
public class LlmAgent implements AiAgent {

    private static final Logger log = LoggerFactory.getLogger(LlmAgent.class);

    private final AiProperties properties;
    private final AiJsonParser parser;
    private final RuleAgent ruleAgent;
    private final ChatCompletionClient client;

    public LlmAgent(AiProperties properties, AiJsonParser parser, RuleAgent ruleAgent,
                    ChatCompletionClient client) {
        this.properties = properties;
        this.parser = parser;
        this.ruleAgent = ruleAgent;
        this.client = client;
    }

    @Override
    public AiDecision decide(AiContext ctx, List<ActionType> allowedActions,
                             List<Integer> selectableTargets) {
        AiDecision fallback = ruleAgent.decide(ctx, allowedActions, selectableTargets);
        if (!properties.isLlmUsable()) {
            return AiDecision.fallback(fallback.action(), fallback.targetSeatId(), fallback.speech(),
                    "未配置 LLM API Key，使用规则 AI");
        }
        try {
            String system = AiPrompt.systemPrompt();
            String user = AiPrompt.userPrompt(ctx, allowedActions, selectableTargets);
            String raw = client.complete(system, user);
            if (raw == null || raw.isBlank()) {
                return AiDecision.fallback(fallback.action(), fallback.targetSeatId(),
                        fallback.speech(), "LLM 返回空内容，使用规则 AI");
            }
            AiDecision parsed = parser.parse(raw, allowedActions, selectableTargets);
            if (parsed == null) {
                log.info("LLM 输出不合法，切换规则 AI（内容未记录）");
                return AiDecision.fallback(fallback.action(), fallback.targetSeatId(),
                        fallback.speech(), "LLM 输出格式非法，已由规则 AI 接管");
            }
            return parsed;
        } catch (Exception ex) {
            log.warn("LLM 调用失败，切换规则 AI: {}", ex.getClass().getSimpleName());
            return AiDecision.fallback(fallback.action(), fallback.targetSeatId(), fallback.speech(),
                    "LLM 调用失败(" + ex.getClass().getSimpleName() + ")，使用规则 AI");
        }
    }

    private String abbreviate(String raw) {
        return raw.length() <= 200 ? raw : raw.substring(0, 200) + "...";
    }
}
