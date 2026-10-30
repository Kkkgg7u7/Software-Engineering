package com.werewolf.game.domain;

/**
 * 玩家/座位在一局游戏中的完整状态。座位号从 1 开始，与前端展示一致。
 */
public class PlayerState {
    private Integer loverSeatId;
    public Integer getLoverSeatId() { return loverSeatId; }
    public void setLoverSeatId(Integer value) { loverSeatId=value; }
    private boolean idiotRevealed = false;
    public boolean getIdiotRevealed() { return idiotRevealed; }
    public void setIdiotRevealed(boolean value) { idiotRevealed=value; }
    private Integer lastGuardSeatId;
    public Integer getLastGuardSeatId() { return lastGuardSeatId; }
    public void setLastGuardSeatId(Integer value) { lastGuardSeatId=value; }
    private String deathCause;
    public String getDeathCause() { return deathCause; }
    public void setDeathCause(String value) { deathCause=value; }
    private boolean deathSkillUsed = false;
    public boolean getDeathSkillUsed() { return deathSkillUsed; }
    public void setDeathSkillUsed(boolean value) { deathSkillUsed=value; }


    /** 座位号，1-based，整局不变。 */
    private final int seatId;
    /** users.id；AI 玩家使用负数 id（-1, -2, ...）避免与真人冲突。 */
    private final Long userId;
    /** 展示名。 */
    private final String nickname;
    /** 头像地址（AI 使用本地素材）。 */
    private final String avatar;
    /** 是否 AI NPC。 */
    private final boolean ai;
    /** 真实身份，只对本人 / 上帝视角可见。 */
    private RoleType role;
    /** 是否存活。 */
    private boolean alive = true;
    /** 是否已准备（等待阶段）。 */
    private boolean ready;

    /**
     * 是否已确认自己的身份（身份确认阶段）。
     *
     * <p>全部真人确认后才会进入第一晚；超时未确认由服务端兜底自动确认，
     * 保证对局不会因为有人挂机而卡死。
     */
    private boolean roleConfirmed;

    // ---- 女巫药水 ----
    private boolean healPotionAvailable = true;
    private boolean poisonPotionAvailable = true;

    // ---- 预言家查验结果（仅本人可见） ----
    private final java.util.List<CheckRecord> checkRecords = new java.util.ArrayList<>();

    /** 预言家一次查验记录。 */
    public record CheckRecord(int round, int targetSeatId, String targetNickname, boolean wolf) {
    }

    public PlayerState(int seatId, Long userId, String nickname, String avatar, boolean ai) {
        this.seatId = seatId;
        this.userId = userId;
        this.nickname = nickname;
        this.avatar = avatar;
        this.ai = ai;
    }

    public int getSeatId() {
        return seatId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getNickname() {
        return nickname;
    }

    public String getAvatar() {
        return avatar;
    }

    public boolean isAi() {
        return ai;
    }

    public RoleType getRole() {
        return role;
    }

    public void setRole(RoleType role) {
        this.role = role;
    }

    public boolean isAlive() {
        return alive;
    }

    public void setAlive(boolean alive) {
        this.alive = alive;
    }

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public boolean isRoleConfirmed() {
        return roleConfirmed;
    }

    public void setRoleConfirmed(boolean roleConfirmed) {
        this.roleConfirmed = roleConfirmed;
    }

    public boolean isHealPotionAvailable() {
        return healPotionAvailable;
    }

    public void setHealPotionAvailable(boolean healPotionAvailable) {
        this.healPotionAvailable = healPotionAvailable;
    }

    public boolean isPoisonPotionAvailable() {
        return poisonPotionAvailable;
    }

    public void setPoisonPotionAvailable(boolean poisonPotionAvailable) {
        this.poisonPotionAvailable = poisonPotionAvailable;
    }

    public java.util.List<CheckRecord> getCheckRecords() {
        return checkRecords;
    }

    public boolean isWolf() {
        return role != null && role.isWolfSide();
    }
}
