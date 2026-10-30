package com.werewolf.game.domain;

import java.util.Optional;

/**
 * 第一版支持的角色（方案 P0：狼人、预言家、女巫、平民）。
 *
 * <p>约定：
 * <ul>
 *   <li>{@link #isWolfSide()} 决定胜负判定中的阵营；</li>
 *   <li>{@link #getAssetName()} 对应前端 frontend/public/assets/roles/*.svg 命名；</li>
 *   <li>新增角色只需在此枚举 + {@code RoleAssigner} + 前端图标表登记，不改动状态机主干。</li>
 * </ul>
 */
public enum RoleType {

    WEREWOLF("狼人", Camp.WEREWOLF, true, 1, "夜晚与同伴共同猎杀一名玩家"),
    WHITE_WOLF_KING("白狼王", Camp.WEREWOLF, true, 1, "被投票放逐时可带走一名玩家"),
    HUNTER("猎人", Camp.VILLAGE, false, 1, "死亡时可开枪；被毒或殉情时不能开枪"),
    GUARD("守卫", Camp.VILLAGE, false, 1, "每晚守护一人，不可连续守护同一人；同守同救死亡"),
    IDIOT("白痴", Camp.VILLAGE, false, 1, "首次被投票放逐时翻牌免死，之后失去投票权"),
    CUPID("爱神", Camp.VILLAGE, false, 1, "首夜连接两名情侣，一方死亡另一方殉情"),
    SEER("预言家", Camp.VILLAGE, false, 1, "夜晚查验一名玩家的阵营"),
    WITCH("女巫", Camp.VILLAGE, false, 1, "拥有一瓶解药和一瓶毒药，各只能使用一次"),
    VILLAGER("村民", Camp.VILLAGE, false, 1, "没有技能，依靠发言与投票找出狼人");

    /** 阵营。 */
    public enum Camp {
        WEREWOLF("狼人阵营"),
        VILLAGE("好人阵营"), LOVERS("情侣阵营");

        private final String label;

        Camp(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    private final String label;
    private final Camp camp;
    private final boolean wolfSide;
    private final int defaultCount;
    private final String skill;

    RoleType(String label, Camp camp, boolean wolfSide, int defaultCount, String skill) {
        this.label = label;
        this.camp = camp;
        this.wolfSide = wolfSide;
        this.defaultCount = defaultCount;
        this.skill = skill;
    }

    public String getLabel() {
        return label;
    }

    public Camp getCamp() {
        return camp;
    }

    public boolean isWolfSide() {
        return wolfSide;
    }

    public int getDefaultCount() {
        return defaultCount;
    }

    public String getSkill() {
        return skill;
    }

    /** 前端素材文件名（小写下划线）。 */
    public String getAssetName() {
        return name().toLowerCase();
    }

    public static Optional<RoleType> from(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String v = raw.trim().toUpperCase();
        for (RoleType r : values()) {
            if (r.name().equals(v)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
