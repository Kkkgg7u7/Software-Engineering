package com.werewolf.game.domain;

/**
 * 一次行动请求。真人玩家由 REST/STOMP 提交，AI NPC 由 AiService 提交。
 *
 * <p><b>服务器权威原则</b>：这个对象只是“请求”，是否合法完全由
 * {@code WerewolfGameEngine} 判定；AI 与真人走完全相同的校验路径。
 */
public class GameAction {

    private final ActionType type;
    /** 行动发起者座位号。 */
    private final int actorSeatId;
    /** 目标座位号，可为空（弃票 / 女巫空过）。 */
    private final Integer targetSeatId;
    /** 发言内容（SPEAK 时必填）。 */
    private final String speech;
    /** 行动理由，仅用于日志与 AI 调试，不影响规则。 */
    private final String reason;
    /** 是否来自 AI。 */
    private final boolean fromAi;

    private GameAction(ActionType type, int actorSeatId, Integer targetSeatId, String speech,
                       String reason, boolean fromAi) {
        this.type = type;
        this.actorSeatId = actorSeatId;
        this.targetSeatId = targetSeatId;
        this.speech = speech;
        this.reason = reason;
        this.fromAi = fromAi;
    }

    public static GameAction human(ActionType type, int actorSeatId, Integer target, String speech) {
        return new GameAction(type, actorSeatId, target, speech, null, false);
    }

    public static GameAction ai(ActionType type, int actorSeatId, Integer target, String speech,
                                String reason) {
        return new GameAction(type, actorSeatId, target, speech, reason, true);
    }

    public ActionType getType() {
        return type;
    }

    public int getActorSeatId() {
        return actorSeatId;
    }

    public Integer getTargetSeatId() {
        return targetSeatId;
    }

    public String getSpeech() {
        return speech;
    }

    public String getReason() {
        return reason;
    }

    public boolean isFromAi() {
        return fromAi;
    }

    @Override
    public String toString() {
        return "GameAction{" + type + ", actor=" + actorSeatId + ", target=" + targetSeatId
                + ", ai=" + fromAi + '}';
    }
}
