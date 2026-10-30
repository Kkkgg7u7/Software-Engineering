package com.werewolf.ai;

import com.werewolf.ai.dto.AiContext;
import com.werewolf.ai.dto.AiDecision;
import com.werewolf.game.domain.ActionType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 规则 AI —— 零外部依赖的兜底实现（方案第十六节“API 调用失败时使用简单规则 AI 作为 fallback”）。
 *
 * <p>策略：
 * <ul>
 *   <li><b>狼人</b>：优先刀“疑似神职”的座位（发言多、被查过、威胁大的），避开狼同伴；</li>
 *   <li><b>预言家</b>：查验没查过的座位，优先查“发言少/跟票”的可疑目标；</li>
 *   <li><b>女巫</b>：第一晚被刀且自己是首夜时倾向救人；有明确狼人信息时用毒；</li>
 *   <li><b>投票</b>：好人投“被怀疑最多”的座位；狼人投“好人阵营里威胁最大”的座位，避免投同伴；</li>
 *   <li><b>发言</b>：根据身份与公开日志拼装一段带理由的发言，绝不泄露他人身份。</li>
 * </ul>
 */
@Component
public class RuleAgent implements AiAgent {

    private final Random random = new Random();

    @Override
    public AiDecision decide(AiContext ctx, List<ActionType> allowedActions,
                             List<Integer> selectableTargets) {
        if (allowedActions == null || allowedActions.isEmpty()) {
            return AiDecision.rule(ActionType.WITCH_PASS, null, null, "无可用行动");
        }
        for(ActionType action:List.of(ActionType.GUARD_PROTECT,ActionType.CUPID_LINK,ActionType.SHERIFF_VOTE,
                ActionType.HUNTER_SHOOT,ActionType.WHITE_WOLF_CARRY,ActionType.BADGE_TRANSFER)){
            if(allowedActions.contains(action) && !selectableTargets.isEmpty()){
                List<Integer> choices=selectableTargets.stream().filter(x->!x.equals(ctx.getLoverSeatId())).toList();
                if(choices.isEmpty())choices=selectableTargets;
                return AiDecision.rule(action,choices.get(random.nextInt(choices.size())),null,"从合法目标中选择，避免伤害自己的情侣");
            }
        }
        if (allowedActions.contains(ActionType.WOLF_KILL)) {
            Integer target = ctx.getWolfSuggestedTarget()!=null && selectableTargets.contains(ctx.getWolfSuggestedTarget())?ctx.getWolfSuggestedTarget():pickWolfTarget(ctx, selectableTargets);
            return AiDecision.rule(ActionType.WOLF_KILL, target, null,
                    "选择击杀 " + target + " 号：其公开发言威胁度较高");
        }
        if (allowedActions.contains(ActionType.SEER_CHECK)) {
            Integer target = pickCheckTarget(ctx, selectableTargets);
            return AiDecision.rule(ActionType.SEER_CHECK, target, null,
                    "查验 " + target + " 号：公开信息不足，需要确认阵营");
        }
        if (allowedActions.contains(ActionType.WITCH_HEAL) && shouldHeal(ctx)) {
            Integer victim = ctx.getWitchVictimSeatId()!=null?ctx.getWitchVictimSeatId():selectableTargets.isEmpty()?null:selectableTargets.get(0);
            return AiDecision.rule(ActionType.WITCH_HEAL, victim, null,
                    "使用解药：" + victim + " 号被狼人袭击，且局势尚早");
        }
        if (allowedActions.contains(ActionType.WITCH_POISON)) {
            Integer target = pickPoisonTarget(ctx, selectableTargets);
            if (target != null) {
                return AiDecision.rule(ActionType.WITCH_POISON, target, null,
                        "使用毒药：" + target + " 号发言存在明显矛盾");
            }
        }
        if (allowedActions.contains(ActionType.WITCH_HEAL)) {
            return AiDecision.rule(ActionType.WITCH_HEAL,
                    ctx.getWitchVictimSeatId(), null, "使用解药");
        }
        if (allowedActions.contains(ActionType.SPEAK)) {
            String speech = buildSpeech(ctx);
            return AiDecision.rule(ActionType.SPEAK, null, speech, "按公开信息组织发言");
        }
        if (allowedActions.contains(ActionType.VOTE)) {
            Integer target = pickVoteTarget(ctx, selectableTargets);
            return AiDecision.rule(ActionType.VOTE, target, null,
                    "投票给 " + target + " 号：其行为与发言存在矛盾");
        }
        for(ActionType action:List.of(ActionType.WITCH_PASS,ActionType.GUARD_PASS,ActionType.SKILL_PASS,
                ActionType.BADGE_DESTROY,ActionType.SHERIFF_ABSTAIN,ActionType.PHASE_DONE))
            if(allowedActions.contains(action))return AiDecision.rule(action,null,null,"结束当前操作");
        // 兜底：不行动
        ActionType fallback = allowedActions.contains(ActionType.ABSTAIN)
                ? ActionType.ABSTAIN : allowedActions.get(0);
        return AiDecision.rule(fallback, null, null, "本回合放弃行动");
    }

    // ---------------------------------------------------------- 夜晚决策 ----

    private Integer pickWolfTarget(AiContext ctx, List<Integer> targets) {
        List<Integer> candidates = exclude(ctx, targets, ctx.getWolfTeammates());
        if (candidates.isEmpty()) {
            return targets.isEmpty() ? null : targets.get(0);
        }
        // 威胁度打分：发言次数多、且没被狼人刀过的优先
        Map<Integer, Integer> threat = new LinkedHashMap<>();
        for (String line : ctx.getSpeechLog()) {
            Integer seat = seatOfLine(line);
            if (seat != null) {
                threat.merge(seat, 1, Integer::sum);
            }
        }
        return candidates.stream()
                .max(Comparator.comparingInt(s -> threat.getOrDefault(s, 0) + random.nextInt(3)))
                .orElse(candidates.get(0));
    }

    private Integer pickCheckTarget(AiContext ctx, List<Integer> targets) {
        if (targets.isEmpty()) {
            return null;
        }
        List<Integer> checked = new ArrayList<>();
        for (String c : ctx.getMyChecks()) {
            Integer seat = seatOfLine(c);
            if (seat != null) {
                checked.add(seat);
            }
        }
        List<Integer> candidates = exclude(ctx, targets, checked);
        if (candidates.isEmpty()) {
            candidates = new ArrayList<>(targets);
        }
        return candidates.get(random.nextInt(candidates.size()));
    }

    private boolean shouldHeal(AiContext ctx) {
        if (!Boolean.TRUE.equals(ctx.getHealPotionAvailable())) {
            return false;
        }
        // 第一晚必救（信息最少，避免好人被白刀）；后续有查验信息时更倾向于留药
        if (ctx.getRound() <= 1) {
            return true;
        }
        return !ctx.getMyChecks().isEmpty() && random.nextBoolean();
    }

    private Integer pickPoisonTarget(AiContext ctx, List<Integer> targets) {
        if (!Boolean.TRUE.equals(ctx.getPoisonPotionAvailable()) || targets.isEmpty()) {
            return null;
        }
        // 只有当自己掌握“确凿狼人信息”或进入残局时才用毒，避免毒错好人
        for (String check : ctx.getMyChecks()) {
            if (check.contains("狼人")) {
                Integer seat = seatOfLine(check);
                if (seat != null && targets.contains(seat)) {
                    return seat;
                }
            }
        }
        if (ctx.getAliveSeats().size() <= 4 && random.nextInt(3) == 0) {
            return targets.get(random.nextInt(targets.size()));
        }
        return null;
    }

    // ---------------------------------------------------------- 白天决策 ----

    private Integer pickVoteTarget(AiContext ctx, List<Integer> targets) {
        if (targets.isEmpty()) {
            return null;
        }
        boolean iAmWolf = "WEREWOLF".equals(ctx.getMyRole()) || "WHITE_WOLF_KING".equals(ctx.getMyRole());
        List<Integer> candidates = iAmWolf
                ? exclude(ctx, targets, ctx.getWolfTeammates())
                : new ArrayList<>(targets);
        if (candidates.isEmpty()) {
            candidates = exclude(ctx, targets, ctx.getWolfTeammates());
        }
        if (candidates.isEmpty()) {
            candidates = new ArrayList<>(targets);
        }
        // 被公开怀疑最多的人优先
        Map<Integer, Integer> suspicion = new LinkedHashMap<>();
        for (String line : ctx.getEventLog()) {
            if (line.contains("怀疑")) {
                Integer seat = seatOfLine(line);
                if (seat != null) {
                    suspicion.merge(seat, 1, Integer::sum);
                }
            }
        }
        for (String line : ctx.getSpeechLog()) {
            if (line.contains("怀疑") || line.contains("投票")) {
                Integer seat = seatOfLine(line);
                if (seat != null) {
                    suspicion.merge(seat, 1, Integer::sum);
                }
            }
        }
        return candidates.stream()
                .max(Comparator.comparingInt(s -> suspicion.getOrDefault(s, 0) + random.nextInt(2)))
                .orElse(candidates.get(0));
    }

    private String buildSpeech(AiContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("我是 ").append(ctx.getMySeatId()).append(" 号。");
        switch (ctx.getMyRole() == null ? "" : ctx.getMyRole()) {
            case "SEER" -> {
                sb.append("我是预言家。");
                if (ctx.getMyChecks().isEmpty()) {
                    sb.append("昨晚的查验结果我会在合适的时候公布，先听大家的发言。");
                } else {
                    sb.append("我的查验结果：").append(String.join("；", ctx.getMyChecks())).append("。");
                }
            }
            case "WITCH" -> sb.append("我手里有药，昨晚的刀口信息我会结合发言判断。");
            case "WEREWOLF" -> {
                sb.append("我是好人。从发言看，");
                sb.append(suspicionLine(ctx));
            }
            default -> {
                sb.append("我是平民，只能靠发言和投票找狼。");
                sb.append(suspicionLine(ctx));
            }
        }
        sb.append(" 我的建议是先聚焦 ").append(suggestFocus(ctx)).append(" 号。");
        return sb.toString();
    }

    private String suspicionLine(AiContext ctx) {
        List<Integer> alive = aliveSeats(ctx);
        if (alive.isEmpty()) {
            return "目前信息太少。";
        }
        List<Integer> others = exclude(ctx, alive, List.of(ctx.getMySeatId()));
        if (others.isEmpty()) {
            return "目前信息太少。";
        }
        int focus = others.get(random.nextInt(others.size()));
        return focus + " 号的发言逻辑不太自洽，我表示怀疑。";
    }

    private int suggestFocus(AiContext ctx) {
        List<Integer> alive = aliveSeats(ctx);
        List<Integer> others = exclude(ctx, alive, List.of(ctx.getMySeatId()));
        if (others.isEmpty()) {
            return ctx.getMySeatId();
        }
        return others.get(random.nextInt(others.size()));
    }

    // ------------------------------------------------------------ 工具 -----

    private List<Integer> aliveSeats(AiContext ctx) {
        List<Integer> seats = new ArrayList<>();
        for (String line : ctx.getAliveSeats()) {
            Integer seat = seatOfLine(line);
            if (seat != null) {
                seats.add(seat);
            }
        }
        return seats;
    }

    private List<Integer> exclude(AiContext ctx, List<Integer> source, List<Integer> excluded) {
        List<Integer> result = new ArrayList<>();
        for (Integer s : source) {
            if (excluded == null || !excluded.contains(s)) {
                result.add(s);
            }
        }
        return result;
    }

    /** 从 “3 号玩家: 我怀疑4号” 这类文本里抽出第一个座位号。 */
    private Integer seatOfLine(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c >= '0' && c <= '9') {
                int j = i;
                while (j < line.length() && Character.isDigit(line.charAt(j))) {
                    j++;
                }
                try {
                    return Integer.parseInt(line.substring(i, j));
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
            i++;
        }
        return null;
    }
}
