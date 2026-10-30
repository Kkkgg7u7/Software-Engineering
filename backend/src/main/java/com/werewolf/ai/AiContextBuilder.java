package com.werewolf.ai;

import com.werewolf.ai.dto.AiContext;
import com.werewolf.game.domain.ActionType;
import com.werewolf.game.domain.GameEvent;
import com.werewolf.game.domain.GamePhase;
import com.werewolf.game.domain.GameState;
import com.werewolf.game.domain.PlayerState;
import com.werewolf.game.domain.RoleType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 GameState 投影成 AI 的上下文。
 *
 * <p><b>安全红线</b>：这里只读取公开信息 + AI 自己角色的合法私有信息
 * （自己的身份、狼同伴、自己的查验结果、自己的药水）。
 * 任何“他人身份”“狼刀结果”“女巫用药”都不会进入 Prompt。
 */
@Component
public class AiContextBuilder {

    public AiContext build(GameState state, PlayerState me, List<ActionType> allowedActions,
                           List<Integer> selectableTargets) {
        AiContext ctx = new AiContext();
        ctx.setMySeatId(me.getSeatId());
        ctx.setMyNickname(me.getNickname());
        RoleType role = me.getRole();
        ctx.setMyRole(role == null ? "VILLAGER" : role.name());
        ctx.setMyRoleLabel(role == null ? "平民" : role.getLabel());
        ctx.setRound(state.getRound());
        ctx.setPhase(state.getPhase().getLabel());
        ctx.setTask(describeTask(state, me, allowedActions));

        List<String> alive = new ArrayList<>();
        List<String> dead = new ArrayList<>();
        for (PlayerState p : state.getPlayers()) {
            String label = p.getSeatId() + " 号 " + p.getNickname() + (p.isAi() ? "(AI)" : "");
            if (p.isAlive()) {
                alive.add(label);
            } else {
                dead.add(label);
            }
        }
        ctx.setAliveSeats(alive);
        ctx.setDeadSeats(dead);
        ctx.setAllowedActions(allowedActions.stream().map(Enum::name).toList());
        ctx.setSelectableTargets(selectableTargets);

        ctx.setLoverSeatId(me.getLoverSeatId());
        if (role!=null && role.isWolfSide()) {
            ctx.setWolfSuggestedTarget(state.getWolfVotes().values().stream().findFirst().orElse(null));
            List<Integer> teammates = new ArrayList<>();
            List<Integer> aliveTeammates = new ArrayList<>();
            for (PlayerState p : state.getPlayers()) {
                if (p.isWolf() && p.getSeatId() != me.getSeatId()) {
                    teammates.add(p.getSeatId());
                    if (p.isAlive()) {
                        aliveTeammates.add(p.getSeatId());
                    }
                }
            }
            ctx.setWolfTeammates(teammates);
            ctx.setWolfTeammatesAlive(aliveTeammates);
        }
        if (role == RoleType.SEER) {
            List<String> checks = new ArrayList<>();
            for (PlayerState.CheckRecord r : me.getCheckRecords()) {
                checks.add("第 " + r.round() + " 晚查验 " + r.targetSeatId() + " 号("
                        + r.targetNickname() + ") 是 " + (r.wolf() ? "狼人" : "好人"));
            }
            ctx.setMyChecks(checks);
        }
        if (role == RoleType.WITCH) {
            ctx.setWitchVictimSeatId(state.getWolfTargetSeatId());
            ctx.setHealPotionAvailable(me.isHealPotionAvailable());
            ctx.setPoisonPotionAvailable(me.isPoisonPotionAvailable());
        }

        // 公开发言（最多最近 24 条，避免 prompt 过长）
        List<GameState.SpeechRecord> speeches = state.getSpeeches();
        int from = Math.max(0, speeches.size() - 24);
        for (int i = from; i < speeches.size(); i++) {
            GameState.SpeechRecord s = speeches.get(i);
            ctx.getSpeechLog().add("第" + s.round() + "轮 " + s.seatId() + "号(" + s.nickname() + ")："
                    + s.content());
        }

        // 公开事件
        for (GameEvent e : state.getEvents()) {
            String line = describeEvent(state, e);
            if (line != null) {
                ctx.getEventLog().add(line);
            }
        }
        if (ctx.getEventLog().size() > 24) {
            ctx.setEventLog(new ArrayList<>(
                    ctx.getEventLog().subList(ctx.getEventLog().size() - 24, ctx.getEventLog().size())));
        }
        ctx.setLastVoteSummary(describeLastVote(state));
        return ctx;
    }

    /** 当前该 AI 要完成什么任务 —— 直接决定发言风格。 */
    private String describeTask(GameState state, PlayerState me, List<ActionType> actions) {
        if (actions.contains(ActionType.WOLF_KILL)) {
            return "现在是夜晚，你是狼人，需要和同伴一起选择今晚击杀的目标（targetId 必填），不要击杀狼同伴。";
        }
        if (actions.contains(ActionType.SEER_CHECK)) {
            return "现在是夜晚，你是预言家，需要选择今晚查验的玩家（targetId 必填）。";
        }
        if (actions.contains(ActionType.WITCH_HEAL) || actions.contains(ActionType.WITCH_POISON)
                || actions.contains(ActionType.WITCH_PASS)) {
            return "现在是夜晚，你是女巫。你可以用解药救今晚被狼人击杀的人（WITCH_HEAL），"
                    + "或用毒药毒杀一名玩家（WITCH_POISON），或保留药水（WITCH_PASS）。"
                    + "解药和毒药各只能用一次，请谨慎决定。";
        }
        if (actions.contains(ActionType.SPEAK)) {
            return "轮到你发言了。请结合公开信息给出你的分析、怀疑对象与理由，"
                    + "像真人一样自然（80~200 字），action 填 SPEAK。";
        }
        if (actions.contains(ActionType.VOTE)) {
            return "现在是投票阶段，请选择你要放逐的玩家（targetId 必填），并在 speech 里简短说明理由。";
        }
        return "当前无需行动。";
    }

    /** 只描述公开可见的事件。 */
    private String describeEvent(GameState state, GameEvent e) {
        return switch (e.getEventType()) {
            case PEACEFUL_NIGHT -> "第" + e.getPayload().getOrDefault("round", "?") + "晚是平安夜，无人死亡";
            case DAWN -> "第" + e.getPayload().getOrDefault("round", "?") + "晚有人死亡："
                    + e.getPayload().getOrDefault("deaths", List.of());
            case PLAYER_DIED -> (e.getPayload().get("seatId") + " 号("
                    + e.getPayload().getOrDefault("nickname", "") + ") 出局，原因 "
                    + ("VOTE".equals(e.getPayload().get("cause")) ? "被投票放逐" : "夜间死亡"));
            case VOTE_RESULT -> {
                Object exiled = e.getPayload().get("exiledSeatId");
                if (exiled != null) {
                    yield "投票结果：" + exiled + " 号被放逐（得票 "
                            + e.getPayload().getOrDefault("votes", "?") + "）";
                }
                yield Boolean.TRUE.equals(e.getPayload().get("tie")) ? "投票平票，无人出局" : null;
            }
            case GAME_OVER -> "游戏结束，获胜方：" + e.getPayload().get("winner");
            default -> null;
        };
    }

    private String describeLastVote(GameState state) {
        if (state.getLastExiledSeatId() != null) {
            return "上一轮被放逐的是 " + state.getLastExiledSeatId() + " 号";
        }
        return "";
    }

    /** 是否到了需要 AI 决策的阶段。 */
    public boolean needsDecision(GamePhase phase) {
        return phase == GamePhase.NIGHT || phase == GamePhase.DISCUSSION || phase == GamePhase.VOTING;
    }
}
