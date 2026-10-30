package com.werewolf.game.domain;

/**
 * 客户端可提交的行动指令类型。所有指令都必须经 GameEngine 校验。
 */
public enum ActionType {

    /** 狼人选择击杀目标；targetId 必填。 */
    WOLF_KILL,
    /** 预言家查验目标；targetId 必填。 */
    SEER_CHECK,
    /** 女巫使用解药救治今晚被刀的人；targetId 可为空（默认救当晚被刀者）。 */
    WITCH_HEAL,
    /** 女巫使用毒药毒杀一人；targetId 必填。 */
    WITCH_POISON,
    /** 女巫本晚不使用任何药水。 */
    WITCH_PASS,
    /**
     * 确认自己的身份（身份确认阶段）。
     *
     * <p>必须由玩家本人提交，服务器不接受代他人确认，也不接受在非确认阶段提交。
     */
    CONFIRM_ROLE,
    /**
     * 结束自己在本阶段的操作（夜晚 / 投票）。
     *
     * <p><b>为什么需要它</b>：早期实现里，夜晚只要"狼人刀过 + 预言家查过 + 女巫用过一次药"
     * 就立即结算。后果是女巫用了救药之后，夜晚当场结束，她<b>再没有机会用毒药</b>——
     * 与"两瓶药各自一次机会"的规则不符，玩家体验上就是"我还没操作完就进白天了"。
     *
     * <p>现在每个阶段都必须由<b>所有相关人员分别确认"我完成了"</b>（或倒计时耗尽）
     * 才会推进，因此女巫可以先救人、再用毒、最后点完成。
     */
    PHASE_DONE,
    /**
     * 预言家确认已看过查验结果（翻牌动画结束后点"知道了"）。
     *
     * <p>查验结果需要一段翻牌动画 + 30 秒确认窗口；在预言家确认（或 30 秒走完）之前，
     * 夜晚不会推进，避免"动画还没看完就进白天了"。
     */
    SEER_CONFIRM,
    /** 白天讨论发言；speech 必填。 */
    SPEAK,
    /**
     * 结束/跳过自己的发言轮次（不发内容）。
     *
     * <p>用于"我没什么要说的，把时间让给下一位"。
     * 早期版本只能用空 {@code SPEAK}，而后端要求发言内容非空，
     * 于是当前发言人除了等满 5 分钟倒计时之外没有别的办法跳到下一位。
     */
    SPEAK_PASS,
    /** 投票放逐；targetId 必填。 */
    VOTE,
    /** 弃票。 */
    ABSTAIN,
    GUARD_PROTECT, GUARD_PASS, CUPID_LINK, SHERIFF_VOTE, SHERIFF_ABSTAIN,
    HUNTER_SHOOT, WHITE_WOLF_CARRY, SKILL_PASS, BADGE_TRANSFER, BADGE_DESTROY;

    public static ActionType from(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("行动类型不能为空");
        }
        return ActionType.valueOf(raw.trim().toUpperCase());
    }
}
