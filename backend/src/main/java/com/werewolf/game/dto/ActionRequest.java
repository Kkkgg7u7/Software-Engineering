package com.werewolf.game.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 玩家行动请求（REST 与 STOMP 共用同一份校验规则）。
 *
 * <p>客户端只能“请求”行动，是否合法完全由 GameEngine 判定。
 */
public class ActionRequest {

    /** 行动类型：WOLF_KILL / SEER_CHECK / WITCH_HEAL / WITCH_POISON / WITCH_PASS / SPEAK / VOTE / ABSTAIN。 */
    @NotBlank(message = "行动类型不能为空")
    private String action;

    /** 目标座位号；弃票/空过时可为空。 */
    private Integer targetSeatId;

    /** 发言内容（SPEAK 必填）。 */
    private String speech;

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Integer getTargetSeatId() {
        return targetSeatId;
    }

    public void setTargetSeatId(Integer targetSeatId) {
        this.targetSeatId = targetSeatId;
    }

    public String getSpeech() {
        return speech;
    }

    public void setSpeech(String speech) {
        this.speech = speech;
    }
}
