package com.werewolf.game.domain;

/**
 * 游戏阶段状态机（方案第五节）：
 *
 * <pre>
 * WAITING -> STARTING -> CONFIRM -> NIGHT -> DAY -> DISCUSSION -> VOTING -> CHECK_WINNER
 *                          ^                                                    |
 *                          |                                                未结束
 *                          +----------------------------------------------------+
 *                                                                               |
 *                                                                              END
 * </pre>
 */
public enum GamePhase {

    /** 房间等待中，未开始游戏。 */
    WAITING("等待开始", 0),
    /** 开局动画：发牌、角色分配完成，等待前端播完开场特效。 */
    STARTING("发牌中", 5),
    /**
     * 身份确认：每个人都看过并确认了自己的身份，全部确认后才进入第一晚。
     *
     * <p>为什么单独设一个阶段：狼人杀的身份一旦发下去就无法更改，
     * 如果有人在"低头看牌"的时候界面还没渲染出来，就会在第一晚毫无准备地行动。
     * 单独确认一次可以保证所有人都真的看到了自己的身份再开始。
     */
    CONFIRM("身份确认", 60),
    /** 夜晚：狼人刀人 -> 预言家查验 -> 女巫用药。 */
    NIGHT("夜晚", 180),
    /** 天亮：公布死讯。 */
    DAY("白天", 20),
    SHERIFF_VOTING("警长选举", 90),
    DEATH_SKILL("死亡技能与警徽", 30),
    /**
     * 讨论：按顺序发言，<b>每人</b> 5 分钟倒计时。
     *
     * <p>注意这里的 300 秒是"单个发言人的时间"，不是整轮讨论的总时长。
     */
    DISCUSSION("讨论", 300),
    /** 投票：每人 3 分钟倒计时。 */
    VOTING("投票", 180),
    /** 结算：判定胜负。 */
    CHECK_WINNER("结算", 3),
    /** 结束。 */
    END("游戏结束", 0);

    private final String label;
    private final int defaultSeconds;

    GamePhase(String label, int defaultSeconds) {
        this.label = label;
        this.defaultSeconds = defaultSeconds;
    }

    public String getLabel() {
        return label;
    }

    public int getDefaultSeconds() {
        return defaultSeconds;
    }

    /** 是否是夜间阶段（只有狼人/预言家/女巫能看到夜间面板）。 */
    public boolean isNight() {
        return this == NIGHT;
    }

    /** 阶段是否已结束（不可再操作）。 */
    public boolean isFinished() {
        return this == END;
    }

    /** 是否允许发起讨论发言。 */
    public boolean canSpeak() {
        return this == DISCUSSION;
    }
}
