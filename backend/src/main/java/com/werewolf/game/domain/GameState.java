package com.werewolf.game.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一局游戏的完整内存状态。由 GameEngine 独占写权限，其它模块只读。
 *
 * <p>方案第十节：进行中的游戏状态保存在内存，游戏结束后再落库 game_records。
 */
public class GameState {
    private boolean standard13 = false;
    public boolean getStandard13() { return standard13; }
    public void setStandard13(boolean value) { standard13=value; }
    private Integer guardTargetSeatId;
    public Integer getGuardTargetSeatId() { return guardTargetSeatId; }
    public void setGuardTargetSeatId(Integer value) { guardTargetSeatId=value; }
    private Integer cupidFirstSeatId;
    public Integer getCupidFirstSeatId() { return cupidFirstSeatId; }
    public void setCupidFirstSeatId(Integer value) { cupidFirstSeatId=value; }
    private boolean cupidLinked = false;
    public boolean getCupidLinked() { return cupidLinked; }
    public void setCupidLinked(boolean value) { cupidLinked=value; }
    private Integer sheriffSeatId;
    public Integer getSheriffSeatId() { return sheriffSeatId; }
    public void setSheriffSeatId(Integer value) { sheriffSeatId=value; }
    private boolean sheriffElectionDone = false;
    public boolean getSheriffElectionDone() { return sheriffElectionDone; }
    public void setSheriffElectionDone(boolean value) { sheriffElectionDone=value; }
    private int sheriffVoteRound = 0;
    public int getSheriffVoteRound() { return sheriffVoteRound; }
    public void setSheriffVoteRound(int value) { sheriffVoteRound=value; }
    private Integer skillActorSeatId;
    public Integer getSkillActorSeatId() { return skillActorSeatId; }
    public void setSkillActorSeatId(Integer value) { skillActorSeatId=value; }
    private String skillKind;
    public String getSkillKind() { return skillKind; }
    public void setSkillKind(String value) { skillKind=value; }


    private final java.util.Set<Integer> sheriffCandidates = new java.util.LinkedHashSet<>();
    public java.util.Set<Integer> getSheriffCandidates(){return sheriffCandidates;}
    public boolean mixedLoversAlive(){
        for(PlayerState p:alivePlayers()) if(p.getLoverSeatId()!=null){PlayerState q=bySeat(p.getLoverSeatId());
            if(q!=null && q.isAlive() && p.isWolf()!=q.isWolf())return true;}
        return false;
    }
    public boolean belongsToLovers(PlayerState p){return p!=null && p.getLoverSeatId()!=null && bySeat(p.getLoverSeatId())!=null && p.isWolf()!=bySeat(p.getLoverSeatId()).isWolf();}
    private final Long roomId;
    private final String roomCode;

    private final List<PlayerState> players = new ArrayList<>();

    private GamePhase phase = GamePhase.WAITING;
    /** 第几轮（夜晚编号），从 1 开始。 */
    private int round = 0;

    /** 阶段剩余秒数，前端倒计时用。 */
    private int phaseSecondsLeft = 0;

    // ---- 夜晚临时数据 ----
    /** 狼人本晚的击杀目标座位号，未决定为 null。 */
    private Integer wolfTargetSeatId;
    /** 狼人内部投票明细：座位号 -> 目标座位号。 */
    private final Map<Integer, Integer> wolfVotes = new LinkedHashMap<>();
    /** 预言家本晚查验目标。 */
    private Integer seerTargetSeatId;
    /** 女巫本晚是否使用解药。 */
    private boolean witchUsedHeal;
    /** 女巫本晚是否使用毒药。 */
    private boolean witchUsedPoison;
    /** 女巫毒杀目标。 */
    private Integer witchPoisonSeatId;
    /** 本晚已被狼人击杀的座位（结算前暂存）。 */
    private Integer pendingKilledSeatId;

    // ---- 白天/投票数据 ----
    /** 讨论发言顺序（存活座位号）。 */
    private final List<Integer> speakOrder = new ArrayList<>();
    /** 当前该谁发言（speakOrder 下标）。 */
    private int speakCursor = 0;
    /** 投票明细：投票人座位号 -> 被投座位号（弃票为 null）。 */
    private final Map<Integer, Integer> votes = new LinkedHashMap<>();
    /** 上一轮被放逐的座位号，用于前端展示与 AI 推理。 */
    private Integer lastExiledSeatId;
    /** 上一晚死亡座位号列表。 */
    private final List<Integer> lastNightDeaths = new ArrayList<>();
    /** 当前白天是否已公布死讯。 */
    private boolean dayAnnounced;

    /** 事件流（供前端回放 / AI 读取公开信息）。 */
    private final List<GameEvent> events = new ArrayList<>();
    /** 公共发言记录（AI 与真人共用，AI 只读取公开信息）。 */
    private final List<SpeechRecord> speeches = new ArrayList<>();

    // ---- 胜负 ----
    private boolean finished;
    private String winner;

    public record SpeechRecord(int round, int seatId, String nickname, String content, boolean ai,
                               GamePhase phase) {
    }

    public GameState(Long roomId, String roomCode) {
        this.roomId = roomId;
        this.roomCode = roomCode;
    }

    // ---------------------------------------------------------------- 查询 --

    public PlayerState bySeat(int seatId) {
        for (PlayerState p : players) {
            if (p.getSeatId() == seatId) {
                return p;
            }
        }
        return null;
    }

    public PlayerState byUserId(Long userId) {
        if (userId == null) {
            return null;
        }
        for (PlayerState p : players) {
            if (userId.equals(p.getUserId())) {
                return p;
            }
        }
        return null;
    }

    /** 别名：按 userId 查座位（业务层习惯写法）。 */
    public PlayerState byUser(Long userId) {
        return byUserId(userId);
    }

    /** 别名：按座位号查玩家。 */
    public PlayerState bySeatId(int seatId) {
        return bySeat(seatId);
    }

    public List<PlayerState> alivePlayers() {
        List<PlayerState> list = new ArrayList<>();
        for (PlayerState p : players) {
            if (p.isAlive()) {
                list.add(p);
            }
        }
        return list;
    }

    public List<PlayerState> aliveWolves() {
        List<PlayerState> list = new ArrayList<>();
        for (PlayerState p : players) {
            if (p.isAlive() && p.isWolf()) {
                list.add(p);
            }
        }
        return list;
    }

    public List<PlayerState> aliveVillagers() {
        List<PlayerState> list = new ArrayList<>();
        for (PlayerState p : players) {
            if (p.isAlive() && !p.isWolf()) {
                list.add(p);
            }
        }
        return list;
    }

    public PlayerState aliveWolfNotSelf(int seatId) {
        for (PlayerState p : aliveWolves()) {
            if (p.getSeatId() != seatId) {
                return p;
            }
        }
        return null;
    }

    public PlayerState firstAliveWithRole(RoleType role) {
        for (PlayerState p : players) {
            if (p.isAlive() && p.getRole() == role) {
                return p;
            }
        }
        return null;
    }

    public boolean hasAliveRole(RoleType role) {
        return firstAliveWithRole(role) != null;
    }

    public PlayerState currentSpeaker() {
        if (phase != GamePhase.DISCUSSION || speakCursor >= speakOrder.size()) {
            return null;
        }
        return bySeat(speakOrder.get(speakCursor));
    }

    public void addEvent(GameEvent event) {
        events.add(event);
    }

    public void addSpeech(SpeechRecord record) {
        speeches.add(record);
    }

    public int aliveCount() {
        return alivePlayers().size();
    }

    // ------------------------------------------------ 阶段"我完成了"标记 ----

    /**
     * 本阶段已点"完成"的座位号。
     *
     * <p>每次阶段切换都会清空。夜晚 / 投票阶段用它来判断"是不是所有人都操作完了"，
     * 只有全员完成（或倒计时耗尽）才推进阶段，避免有人还没操作就进入下一步。
     */
    private final Set<Integer> phaseDoneSeats = new LinkedHashSet<>();

    public Set<Integer> getPhaseDoneSeats() {
        return phaseDoneSeats;
    }

    public boolean isPhaseDone(int seatId) {
        return phaseDoneSeats.contains(seatId);
    }

    public void markPhaseDone(int seatId) {
        phaseDoneSeats.add(seatId);
    }

    public void clearPhaseDone() {
        phaseDoneSeats.clear();
    }

    // ------------------------------------------- 预言家翻牌确认（30 秒）----

    /**
     * 预言家查验后的翻牌确认窗口。
     *
     * <p>查验出结果时开启，持续 {@code seerConfirmSeconds}（默认 30 秒）；
     * 预言家点"知道了"或倒计时走完即关闭。窗口未关闭前夜晚不会推进。
     */
    private boolean seerRevealPending;
    /** 被查验的座位号。 */
    private Integer seerRevealTargetSeatId;
    /** 查验结论：是否狼人。 */
    private boolean seerRevealIsWolf;
    /** 被查验者昵称（前端动画上显示）。 */
    private String seerRevealTargetNickname;
    /** 翻牌确认窗口剩余秒数。 */
    private int seerRevealSecondsLeft;

    public boolean isSeerRevealPending() {
        return seerRevealPending;
    }

    public void startSeerReveal(int targetSeatId, String targetNickname, boolean isWolf, int seconds) {
        this.seerRevealPending = true;
        this.seerRevealTargetSeatId = targetSeatId;
        this.seerRevealTargetNickname = targetNickname;
        this.seerRevealIsWolf = isWolf;
        this.seerRevealSecondsLeft = seconds;
    }

    public void clearSeerReveal() {
        this.seerRevealPending = false;
        this.seerRevealTargetSeatId = null;
        this.seerRevealTargetNickname = null;
        this.seerRevealIsWolf = false;
        this.seerRevealSecondsLeft = 0;
    }

    public Integer getSeerRevealTargetSeatId() {
        return seerRevealTargetSeatId;
    }

    public String getSeerRevealTargetNickname() {
        return seerRevealTargetNickname;
    }

    public boolean isSeerRevealIsWolf() {
        return seerRevealIsWolf;
    }

    public int getSeerRevealSecondsLeft() {
        return seerRevealSecondsLeft;
    }

    /** 每秒调用一次；返回 true 表示倒计时刚刚走完。 */
    public boolean tickSeerReveal() {
        if (!seerRevealPending) {
            return false;
        }
        seerRevealSecondsLeft--;
        return seerRevealSecondsLeft <= 0;
    }

    // ------------------------------------------------------ 身份确认状态 ----

    /** 已确认身份的玩家数。 */
    public int confirmedCount() {
        int n = 0;
        for (PlayerState p : players) {
            if (p.isRoleConfirmed()) {
                n++;
            }
        }
        return n;
    }

    /**
     * 是否所有人都已确认身份。
     *
     * <p>只统计需要确认的人：真人玩家必须确认；AI 由服务端直接视为已确认，
     * 否则会因为"AI 不会点确认"而永远开不了局。
     */
    public boolean allConfirmed() {
        for (PlayerState p : players) {
            if (p.isAi()) {
                continue;
            }
            if (!p.isRoleConfirmed()) {
                return false;
            }
        }
        return true;
    }

    /** 尚未确认身份的真人座位号（用于前端提示"还等谁"）。 */
    public List<Integer> unconfirmedSeats() {
        List<Integer> list = new ArrayList<>();
        for (PlayerState p : players) {
            if (!p.isAi() && !p.isRoleConfirmed()) {
                list.add(p.getSeatId());
            }
        }
        return list;
    }

    // ------------------------------------------------------- getters/setters --

    public Long getRoomId() {
        return roomId;
    }

    public String getRoomCode() {
        return roomCode;
    }

    public List<PlayerState> getPlayers() {
        return players;
    }

    public GamePhase getPhase() {
        return phase;
    }

    public void setPhase(GamePhase phase) {
        this.phase = phase;
    }

    public int getRound() {
        return round;
    }

    public void setRound(int round) {
        this.round = round;
    }

    public int getPhaseSecondsLeft() {
        return phaseSecondsLeft;
    }

    public void setPhaseSecondsLeft(int phaseSecondsLeft) {
        this.phaseSecondsLeft = phaseSecondsLeft;
    }

    public Integer getWolfTargetSeatId() {
        return wolfTargetSeatId;
    }

    public void setWolfTargetSeatId(Integer wolfTargetSeatId) {
        this.wolfTargetSeatId = wolfTargetSeatId;
    }

    public Map<Integer, Integer> getWolfVotes() {
        return wolfVotes;
    }

    public Integer getSeerTargetSeatId() {
        return seerTargetSeatId;
    }

    public void setSeerTargetSeatId(Integer seerTargetSeatId) {
        this.seerTargetSeatId = seerTargetSeatId;
    }

    public boolean isWitchUsedHeal() {
        return witchUsedHeal;
    }

    public void setWitchUsedHeal(boolean witchUsedHeal) {
        this.witchUsedHeal = witchUsedHeal;
    }

    public boolean isWitchUsedPoison() {
        return witchUsedPoison;
    }

    public void setWitchUsedPoison(boolean witchUsedPoison) {
        this.witchUsedPoison = witchUsedPoison;
    }

    public Integer getWitchPoisonSeatId() {
        return witchPoisonSeatId;
    }

    public void setWitchPoisonSeatId(Integer witchPoisonSeatId) {
        this.witchPoisonSeatId = witchPoisonSeatId;
    }

    public Integer getPendingKilledSeatId() {
        return pendingKilledSeatId;
    }

    public void setPendingKilledSeatId(Integer pendingKilledSeatId) {
        this.pendingKilledSeatId = pendingKilledSeatId;
    }

    public List<Integer> getSpeakOrder() {
        return speakOrder;
    }

    public int getSpeakCursor() {
        return speakCursor;
    }

    public void setSpeakCursor(int speakCursor) {
        this.speakCursor = speakCursor;
    }

    public Map<Integer, Integer> getVotes() {
        return votes;
    }

    public Integer getLastExiledSeatId() {
        return lastExiledSeatId;
    }

    public void setLastExiledSeatId(Integer lastExiledSeatId) {
        this.lastExiledSeatId = lastExiledSeatId;
    }

    public List<Integer> getLastNightDeaths() {
        return lastNightDeaths;
    }

    public boolean isDayAnnounced() {
        return dayAnnounced;
    }

    public void setDayAnnounced(boolean dayAnnounced) {
        this.dayAnnounced = dayAnnounced;
    }

    public List<GameEvent> getEvents() {
        return events;
    }

    public List<SpeechRecord> getSpeeches() {
        return speeches;
    }

    public boolean isFinished() {
        return finished;
    }

    public void setFinished(boolean finished) {
        this.finished = finished;
    }

    public String getWinner() {
        return winner;
    }

    public void setWinner(String winner) {
        this.winner = winner;
    }
}
