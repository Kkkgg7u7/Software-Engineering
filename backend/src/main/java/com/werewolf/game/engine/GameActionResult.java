package com.werewolf.game.engine;

import com.werewolf.common.ErrorCode;

/**
 * 引擎执行结果。引擎永不抛异常给调用方（除非是编程错误），
 * 而是返回 ok=false + errorCode，由上层决定如何提示（真人返回错误，AI 走 fallback）。
 */
public class GameActionResult {

    private final boolean ok;
    private final ErrorCode errorCode;
    private final String message;
    /**
     * 本次行动是否<b>真正切换了阶段</b>。
     *
     * <p>必须与"本回合行动是否收集完毕"区分开：
     * 早期实现复用同一个标志，导致狼人/预言家行动后上层误以为阶段已切换，
     * 从而重置了夜晚倒计时（夜晚被拉长到 60~90 秒）。
     */
    private final boolean phaseAdvanced;
    /** 本次行动是否让"本回合所有必要行动已收集完毕"（用于 UI 提示，不用于阶段调度）。 */
    private final boolean actionProgressed;

    private GameActionResult(boolean ok, ErrorCode errorCode, String message, boolean phaseAdvanced,
                             boolean actionProgressed) {
        this.ok = ok;
        this.errorCode = errorCode;
        this.message = message;
        this.phaseAdvanced = phaseAdvanced;
        this.actionProgressed = actionProgressed;
    }

    public static GameActionResult success(boolean phaseAdvanced) {
        return new GameActionResult(true, null, "ok", phaseAdvanced, phaseAdvanced);
    }

    /** 行动成功但阶段未切换；{@code actionProgressed} 表示本回合行动是否已收齐。 */
    public static GameActionResult progressed(boolean actionProgressed) {
        return new GameActionResult(true, null, "ok", false, actionProgressed);
    }

    public static GameActionResult success() {
        return success(false);
    }

    public static GameActionResult fail(ErrorCode code, String message) {
        return new GameActionResult(false, code, message == null ? code.getMessage() : message,
                false, false);
    }

    public static GameActionResult fail(ErrorCode code) {
        return fail(code, null);
    }

    public boolean isOk() {
        return ok;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public String getMessage() {
        return message;
    }

    /** 是否因为本次行动触发了阶段切换（用于上层决定是否重新调度 AI 与重置倒计时）。 */
    public boolean isPhaseAdvanced() {
        return phaseAdvanced;
    }

    /** 本回合的必要行动是否已收集完毕（仅用于提示，不触发阶段调度）。 */
    public boolean isActionProgressed() {
        return actionProgressed;
    }
}
