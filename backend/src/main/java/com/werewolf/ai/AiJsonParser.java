package com.werewolf.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.werewolf.ai.dto.AiDecision;
import com.werewolf.game.domain.ActionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LLM 输出解析器 —— <b>AI 安全的关键一环</b>。
 *
 * <p>规则：
 * <ol>
 *   <li>容忍模型加 markdown 代码块、前后废话，尽力提取第一个 JSON 对象；</li>
 *   <li>action 必须落在【允许的动作】集合内，否则判为非法；</li>
 *   <li>需要目标的动作，targetId 必须落在【可选目标】内，否则判为非法；</li>
 *   <li>任何非法都返回 null，调用方切换到规则 AI —— 即“AI 不能绕过 GameEngine”。</li>
 * </ol>
 */
@Component
public class AiJsonParser {

    private static final Logger log = LoggerFactory.getLogger(AiJsonParser.class);

    private final ObjectMapper objectMapper;

    public AiJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AiDecision parse(String raw, List<ActionType> allowedActions, List<Integer> selectableTargets) {
        String json = extractJson(raw);
        if (json == null) {
            return null;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception ex) {
            log.debug("JSON 解析失败: {}", ex.getMessage());
            return null;
        }
        if (node == null || !node.isObject()) {
            return null;
        }
        String actionRaw = text(node, "action");
        String speech = text(node, "speech");
        String reason = text(node, "reason");
        Integer targetId = intOrNull(node.get("targetId"));

        ActionType action = null;
        if (actionRaw != null && !actionRaw.isBlank()) {
            try {
                action = ActionType.from(actionRaw);
            } catch (IllegalArgumentException ex) {
                log.debug("LLM 返回未知动作: {}", actionRaw);
                return null;
            }
        }
        // 模型没给动作时，用允许集合里的合理默认值
        if (action == null || !allowedActions.contains(action)) {
            log.debug("LLM 动作 {} 不在允许集合 {} 内", action, allowedActions);
            return null;
        }
        if (requiresTarget(action)) {
            if (targetId == null || targetId == 0 || !selectableTargets.contains(targetId)) {
                log.debug("LLM 目标 {} 不在可选集合 {} 内", targetId, selectableTargets);
                return null;
            }
        } else {
            targetId = null;
        }
        if (action == ActionType.SPEAK && (speech == null || speech.isBlank())) {
            return null;
        }
        return AiDecision.llm(action, targetId, speech, reason == null ? "LLM 决策" : reason);
    }

    private boolean requiresTarget(ActionType action) {
        return switch (action) {
            case WOLF_KILL, SEER_CHECK, WITCH_POISON, VOTE, WITCH_HEAL, GUARD_PROTECT, CUPID_LINK, SHERIFF_VOTE, HUNTER_SHOOT, WHITE_WOLF_CARRY, BADGE_TRANSFER -> true;
            default -> false;
        };
    }

    /** 从可能带 markdown / 前后缀的文本中抽出第一个完整 JSON 对象。 */
    public String extractJson(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                s = s.substring(firstNewline + 1, lastFence).trim();
            }
        }
        int start = s.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        return v.isTextual() ? v.asText() : v.toString();
    }

    private Integer intOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isInt() || node.isLong()) {
            return node.asInt();
        }
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.asText().trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }
}
