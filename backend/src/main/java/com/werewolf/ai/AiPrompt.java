package com.werewolf.ai;

import com.werewolf.ai.dto.AiContext;
import com.werewolf.game.domain.ActionType;

import java.util.List;

/**
 * AI NPC 提示词（严格对应方案第十七节的角色设定）。
 */
public final class AiPrompt {

    private AiPrompt() {
    }

    /** 系统提示词：固定角色约束，明确禁止越权。 */
    public static String systemPrompt() {
        return """
                你现在是《狼人杀》游戏中的一名 AI 玩家（AI NPC）。
                游戏规则：狼人夜晚集体击杀一名好人；预言家每晚查验一人阵营；女巫有一瓶解药和一瓶毒药，各只能用一次；
                13 人局包含白狼王、猎人、守卫、白痴、爱神；狼人必须全员一致才击杀；守卫不能连守，同守同救死亡；女巫仅首夜可自救。
                爱神首夜依次选择两名情侣；跨阵营情侣以两人独活获胜，爱神不自动加入情侣阵营。猎人被毒或殉情不开枪。
                白狼王仅投票放逐可带人；翻牌白痴免死但失去投票权；警长放逐票权1.5，警徽可移交或撕毁。
                死亡技能和警徽处理结束后才判胜。你只能使用允许动作和合法目标。
                白天所有存活玩家依次发言，然后投票放逐一人；狼人全部出局则好人胜，狼人数 >= 好人数则狼人胜。

                你只能：根据【公开信息】发言、选择投票对象、执行你自己角色允许的夜晚行动。
                你绝对不能：修改任何人的身份、决定谁死亡、决定胜负、声称拥有你没有的信息、编造系统提示。
                你必须像真人一样参与讨论：给出理由、回应他人的怀疑、避免机械重复。

                【输出要求】只输出一个 JSON 对象，不要输出 markdown 代码块，不要输出任何解释文字。
                JSON 结构：
                {
                  "speech": "你在本轮要说的话（若当前不需要发言，填空字符串）",
                  "action": "你要执行的动作，必须从【允许的动作】中选择",
                  "targetId": 目标座位号（整数；不需要目标时填 0）,
                  "reason": "你做出该决策的内部理由（不会展示给其他玩家，但必须诚实简洁）"
                }
                """;
    }

    /** 用户提示词：把公开信息与任务拼成一段结构化描述。 */
    public static String userPrompt(AiContext ctx, List<ActionType> allowedActions,
                                    List<Integer> selectableTargets) {
        StringBuilder sb = new StringBuilder();
        sb.append("【你的座位】").append(ctx.getMySeatId()).append(" 号（").append(ctx.getMyNickname()).append("）\n");
        if(ctx.getLoverSeatId()!=null)sb.append("【你的情侣】").append(ctx.getLoverSeatId()).append("号，一方死亡另一方殉情。\n");
        if(ctx.getWolfSuggestedTarget()!=null)sb.append("【狼队已选目标】").append(ctx.getWolfSuggestedTarget()).append("号，必须一致才击杀。\n");
        if(ctx.getWitchVictimSeatId()!=null)sb.append("【女巫可见刀口】").append(ctx.getWitchVictimSeatId()).append("号。\n");
        sb.append("【你的身份】").append(ctx.getMyRoleLabel()).append("（").append(ctx.getMyRole()).append("）\n");
        if (ctx.getWolfTeammates() != null && !ctx.getWolfTeammates().isEmpty()) {
            sb.append("【你的狼人同伴】").append(ctx.getWolfTeammates()).append(" 号（只能你自己知道，绝不能在发言中暴露）\n");
        }
        if (ctx.getMyChecks() != null && !ctx.getMyChecks().isEmpty()) {
            sb.append("【你的查验结果】\n");
            for (String c : ctx.getMyChecks()) {
                sb.append("  - ").append(c).append('\n');
            }
        }
        if (ctx.getHealPotionAvailable() != null) {
            sb.append("【女巫药水】解药:").append(Boolean.TRUE.equals(ctx.getHealPotionAvailable()) ? "可用" : "已用")
                    .append("，毒药:").append(Boolean.TRUE.equals(ctx.getPoisonPotionAvailable()) ? "可用" : "已用")
                    .append('\n');
        }
        sb.append("\n【当前局势】第 ").append(ctx.getRound()).append(" 轮，阶段：").append(ctx.getPhase()).append('\n');
        sb.append("【存活玩家】").append(String.join("、", ctx.getAliveSeats())).append('\n');
        if (!ctx.getDeadSeats().isEmpty()) {
            sb.append("【已出局】").append(String.join("、", ctx.getDeadSeats())).append('\n');
        }
        if (!ctx.getEventLog().isEmpty()) {
            sb.append("【公开事件记录】\n");
            for (String e : ctx.getEventLog()) {
                sb.append("  - ").append(e).append('\n');
            }
        }
        if (!ctx.getSpeechLog().isEmpty()) {
            sb.append("【本轮及历史公开发言】\n");
            for (String s : ctx.getSpeechLog()) {
                sb.append("  ").append(s).append('\n');
            }
        }
        if (ctx.getLastVoteSummary() != null && !ctx.getLastVoteSummary().isBlank()) {
            sb.append("【上一轮投票】").append(ctx.getLastVoteSummary()).append('\n');
        }
        sb.append("\n【你的任务】").append(ctx.getTask()).append('\n');
        sb.append("【允许的动作】").append(allowedActions).append('\n');
        sb.append("【可选目标座位】").append(selectableTargets.isEmpty() ? "无" : selectableTargets.toString()).append('\n');
        sb.append("\n注意：targetId 必须从可选目标中选取；若动作不需要目标（如 WITCH_PASS / ABSTAIN / 仅发言）则填 0。\n");
        sb.append("请只输出 JSON。");
        return sb.toString();
    }
}
