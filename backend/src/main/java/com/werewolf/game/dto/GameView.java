package com.werewolf.game.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 游戏状态视图 —— 服务器下发前已经做过可见性裁剪。
 *
 * <p><b>安全约定</b>：本对象在游戏进行中<b>绝不</b>包含他人身份。
 * 只有 {@code gameOver=true} 时才在 {@link PlayerView#role} 中回填真实身份用于复盘。
 */
public class GameView {
    private Integer sheriffSeatId;
    public Integer getSheriffSeatId() { return sheriffSeatId; }
    public void setSheriffSeatId(Integer value) { sheriffSeatId=value; }
    private int sheriffVoteRound = 0;
    public int getSheriffVoteRound() { return sheriffVoteRound; }
    public void setSheriffVoteRound(int value) { sheriffVoteRound=value; }
    private java.util.List<Integer> sheriffCandidates = new java.util.ArrayList<>();
    public java.util.List<Integer> getSheriffCandidates() { return sheriffCandidates; }
    public void setSheriffCandidates(java.util.List<Integer> value) { sheriffCandidates=value; }
    private Integer skillActorSeatId;
    public Integer getSkillActorSeatId() { return skillActorSeatId; }
    public void setSkillActorSeatId(Integer value) { skillActorSeatId=value; }
    private String skillKind;
    public String getSkillKind() { return skillKind; }
    public void setSkillKind(String value) { skillKind=value; }


    private Long roomId;
    private String roomCode;
    private String roomName;
    private String phase;
    private String phaseLabel;
    private int round;
    private int phaseSecondsLeft;
    private int maxPlayers;
    private int werewolfCount;
    private boolean gameOver;
    private String winner;
    private String winnerLabel;
    private boolean dayAnnounced;

    private Integer currentSpeakerSeatId;
    private String currentSpeakerNickname;
    private Integer lastExiledSeatId;
    private List<Integer> lastNightDeaths = new ArrayList<>();

    // ---- 身份确认阶段（GamePhase.CONFIRM）----
    /** 已确认身份的玩家数。 */
    private int confirmedCount;
    /** 需要确认身份的总人数（真人；AI 由服务端视为已确认，不计入）。 */
    private int confirmTargetCount;
    /** 尚未确认身份的真人座位号，用于提示"还等谁"。 */
    private List<Integer> unconfirmedSeats = new ArrayList<>();
    /**
     * 我是否已确认身份。
     *
     * <p>注意：字段名必须以 {@code i} 开头，必须用 {@code @JsonProperty} 固定 JSON 名。
     * 否则 Jackson 按 JavaBean 规则把 {@code getIConfirmed()} 推断成 {@code confirmed}，
     * 与前端声明的 {@code iConfirmed} 不一致（同 F-03 的 iAmHost 问题）。
     */
    @com.fasterxml.jackson.annotation.JsonProperty("iConfirmed")
    private Boolean iConfirmed;

    // ---- 阶段"完成"门（夜晚 / 投票）----
    /** 本阶段还需要操作的座位号（前端显示"还等谁"）。 */
    private List<Integer> pendingActorSeats = new ArrayList<>();
    /** 本阶段需要操作的总人数。 */
    private int actorCount;
    /** 本阶段已点"完成"的座位号。 */
    private List<Integer> phaseDoneSeats = new ArrayList<>();
    /** 我是否已经点过"完成"。 */
    @com.fasterxml.jackson.annotation.JsonProperty("iPhaseDone")
    private Boolean iPhaseDone;

    // ---- 狼队刀口协商（只对狼人本人下发，绝不外泄给好人）----
    /**
     * 狼队刀口票数：{@code "座位号" -> 票数}。
     *
     * <p>早期实现里服务端用"先到先得"决定刀口，后点的狼等于没投票、也看不到队友选了什么。
     * 现在改为<b>少数服从多数 + 平票空刀</b>，并把票数告诉狼人自己，便于协商。
     */
    private Map<String, Integer> wolfVoteCounts;
    /**
     * 狼队当前是否已就刀口达成一致。
     *
     * <p>false = 平票或还没投完；平票会导致本晚空刀。
     */
    private Boolean wolvesAgreed;

    // ---- 预言家翻牌确认（只对预言家本人下发）----
    /** 是否正在等待预言家确认查验结果。 */
    private Boolean seerRevealPending;
    /** 翻牌展示的对象与结论。 */
    private SeerReveal seerReveal;

    /** 翻牌动画所需的全部信息。 */
    public static class SeerReveal {
        private Integer targetSeatId;
        private String targetNickname;
        private String targetAvatar;
        /** 是否狼人（true = 红牌，false = 蓝牌）。 */
        private boolean wolf;
        /** 阵营英文名：WEREWOLF / VILLAGE。 */
        private String camp;
        /** 剩余确认秒数。 */
        private int secondsLeft;
        /** 总时长，前端用来画进度。 */
        private int totalSeconds;

        public Integer getTargetSeatId() {
            return targetSeatId;
        }

        public void setTargetSeatId(Integer targetSeatId) {
            this.targetSeatId = targetSeatId;
        }

        public String getTargetNickname() {
            return targetNickname;
        }

        public void setTargetNickname(String targetNickname) {
            this.targetNickname = targetNickname;
        }

        public String getTargetAvatar() {
            return targetAvatar;
        }

        public void setTargetAvatar(String targetAvatar) {
            this.targetAvatar = targetAvatar;
        }

        public boolean isWolf() {
            return wolf;
        }

        public void setWolf(boolean wolf) {
            this.wolf = wolf;
        }

        public String getCamp() {
            return camp;
        }

        public void setCamp(String camp) {
            this.camp = camp;
        }

        public int getSecondsLeft() {
            return secondsLeft;
        }

        public void setSecondsLeft(int secondsLeft) {
            this.secondsLeft = secondsLeft;
        }

        public int getTotalSeconds() {
            return totalSeconds;
        }

        public void setTotalSeconds(int totalSeconds) {
            this.totalSeconds = totalSeconds;
        }
    }

    private List<PlayerView> players = new ArrayList<>();
    private Map<String, Double> voteCounts = new LinkedHashMap<>();
    private int votedCount;
    private int aliveCount;
    private int abstainCount;

    /** 仅当前请求者可见的私有信息。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private PrivateView me;

    /** 房间内最近的系统提示（例如“3 号被放逐”）。 */
    private List<String> notices = new ArrayList<>();

    public static class PlayerView {
    private boolean idiotRevealed = false;
    public boolean getIdiotRevealed() { return idiotRevealed; }
    public void setIdiotRevealed(boolean value) { idiotRevealed=value; }

        private int seatId;
        private Long userId;
        private String nickname;
        private String avatar;
        private boolean alive;
        private boolean ready;
        private boolean ai;
        private boolean speaking;
        private boolean voted;
        /** 仅 gameOver 时非空。 */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String role;
        /** 是否已确认身份（身份确认阶段显示用）。 */
        private boolean confirmed;

        public int getSeatId() {
            return seatId;
        }

        public void setSeatId(int seatId) {
            this.seatId = seatId;
        }

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public String getNickname() {
            return nickname;
        }

        public void setNickname(String nickname) {
            this.nickname = nickname;
        }

        public String getAvatar() {
            return avatar;
        }

        public void setAvatar(String avatar) {
            this.avatar = avatar;
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

        public boolean isAi() {
            return ai;
        }

        public void setAi(boolean ai) {
            this.ai = ai;
        }

        public boolean isSpeaking() {
            return speaking;
        }

        public void setSpeaking(boolean speaking) {
            this.speaking = speaking;
        }

        public boolean isVoted() {
            return voted;
        }

        public void setVoted(boolean voted) {
            this.voted = voted;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public boolean isConfirmed() {
            return confirmed;
        }

        public void setConfirmed(boolean confirmed) {
            this.confirmed = confirmed;
        }
    }

    /** 私有视图：只有本人（或上帝视角调试接口）能拿到。 */
    public static class PrivateView {
    private Integer loverSeatId;
    public Integer getLoverSeatId() { return loverSeatId; }
    public void setLoverSeatId(Integer value) { loverSeatId=value; }
    private Integer lastGuardSeatId;
    public Integer getLastGuardSeatId() { return lastGuardSeatId; }
    public void setLastGuardSeatId(Integer value) { lastGuardSeatId=value; }
    private Integer cupidFirstSeatId;
    public Integer getCupidFirstSeatId() { return cupidFirstSeatId; }
    public void setCupidFirstSeatId(Integer value) { cupidFirstSeatId=value; }
    private Integer witchVictimSeatId;
    public Integer getWitchVictimSeatId() { return witchVictimSeatId; }
    public void setWitchVictimSeatId(Integer value) { witchVictimSeatId=value; }

        private String role;
        private String roleLabel;
        private String camp;
        private String skill;
        private String assetName;
        /** 狼人同伴座位（仅狼人可见）。 */
        private List<Integer> wolfTeammates = new ArrayList<>();
        private Boolean healPotionAvailable;
        private Boolean poisonPotionAvailable;
        private List<Map<String, Object>> checkRecords = new ArrayList<>();
        private List<String> availableActions = new ArrayList<>();
        /** 当前可选的合法目标座位。 */
        private List<Integer> selectableTargets = new ArrayList<>();

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getRoleLabel() {
            return roleLabel;
        }

        public void setRoleLabel(String roleLabel) {
            this.roleLabel = roleLabel;
        }

        public String getCamp() {
            return camp;
        }

        public void setCamp(String camp) {
            this.camp = camp;
        }

        public String getSkill() {
            return skill;
        }

        public void setSkill(String skill) {
            this.skill = skill;
        }

        public String getAssetName() {
            return assetName;
        }

        public void setAssetName(String assetName) {
            this.assetName = assetName;
        }

        public List<Integer> getWolfTeammates() {
            return wolfTeammates;
        }

        public void setWolfTeammates(List<Integer> wolfTeammates) {
            this.wolfTeammates = wolfTeammates;
        }

        public Boolean getHealPotionAvailable() {
            return healPotionAvailable;
        }

        public void setHealPotionAvailable(Boolean healPotionAvailable) {
            this.healPotionAvailable = healPotionAvailable;
        }

        public Boolean getPoisonPotionAvailable() {
            return poisonPotionAvailable;
        }

        public void setPoisonPotionAvailable(Boolean poisonPotionAvailable) {
            this.poisonPotionAvailable = poisonPotionAvailable;
        }

        public List<Map<String, Object>> getCheckRecords() {
            return checkRecords;
        }

        public void setCheckRecords(List<Map<String, Object>> checkRecords) {
            this.checkRecords = checkRecords;
        }

        public List<String> getAvailableActions() {
            return availableActions;
        }

        public void setAvailableActions(List<String> availableActions) {
            this.availableActions = availableActions;
        }

        public List<Integer> getSelectableTargets() {
            return selectableTargets;
        }

        public void setSelectableTargets(List<Integer> selectableTargets) {
            this.selectableTargets = selectableTargets;
        }
    }

    public Long getRoomId() {
        return roomId;
    }

    public void setRoomId(Long roomId) {
        this.roomId = roomId;
    }

    public String getRoomCode() {
        return roomCode;
    }

    public void setRoomCode(String roomCode) {
        this.roomCode = roomCode;
    }

    public String getRoomName() {
        return roomName;
    }

    public void setRoomName(String roomName) {
        this.roomName = roomName;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public String getPhaseLabel() {
        return phaseLabel;
    }

    public void setPhaseLabel(String phaseLabel) {
        this.phaseLabel = phaseLabel;
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

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public void setMaxPlayers(int maxPlayers) {
        this.maxPlayers = maxPlayers;
    }

    public int getWerewolfCount() {
        return werewolfCount;
    }

    public void setWerewolfCount(int werewolfCount) {
        this.werewolfCount = werewolfCount;
    }

    public boolean isGameOver() {
        return gameOver;
    }

    public void setGameOver(boolean gameOver) {
        this.gameOver = gameOver;
    }

    public String getWinner() {
        return winner;
    }

    public void setWinner(String winner) {
        this.winner = winner;
    }

    public String getWinnerLabel() {
        return winnerLabel;
    }

    public void setWinnerLabel(String winnerLabel) {
        this.winnerLabel = winnerLabel;
    }

    public boolean isDayAnnounced() {
        return dayAnnounced;
    }

    public void setDayAnnounced(boolean dayAnnounced) {
        this.dayAnnounced = dayAnnounced;
    }

    public Integer getCurrentSpeakerSeatId() {
        return currentSpeakerSeatId;
    }

    public void setCurrentSpeakerSeatId(Integer currentSpeakerSeatId) {
        this.currentSpeakerSeatId = currentSpeakerSeatId;
    }

    public String getCurrentSpeakerNickname() {
        return currentSpeakerNickname;
    }

    public void setCurrentSpeakerNickname(String currentSpeakerNickname) {
        this.currentSpeakerNickname = currentSpeakerNickname;
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

    public void setLastNightDeaths(List<Integer> lastNightDeaths) {
        this.lastNightDeaths = lastNightDeaths;
    }

    public int getConfirmedCount() {
        return confirmedCount;
    }

    public void setConfirmedCount(int confirmedCount) {
        this.confirmedCount = confirmedCount;
    }

    public int getConfirmTargetCount() {
        return confirmTargetCount;
    }

    public void setConfirmTargetCount(int confirmTargetCount) {
        this.confirmTargetCount = confirmTargetCount;
    }

    public List<Integer> getUnconfirmedSeats() {
        return unconfirmedSeats;
    }

    public void setUnconfirmedSeats(List<Integer> unconfirmedSeats) {
        this.unconfirmedSeats = unconfirmedSeats;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("iConfirmed")
    public Boolean getIConfirmed() {
        return iConfirmed;
    }

    public void setIConfirmed(Boolean iConfirmed) {
        this.iConfirmed = iConfirmed;
    }

    public List<Integer> getPendingActorSeats() {
        return pendingActorSeats;
    }

    public void setPendingActorSeats(List<Integer> pendingActorSeats) {
        this.pendingActorSeats = pendingActorSeats;
    }

    public int getActorCount() {
        return actorCount;
    }

    public void setActorCount(int actorCount) {
        this.actorCount = actorCount;
    }

    public List<Integer> getPhaseDoneSeats() {
        return phaseDoneSeats;
    }

    public void setPhaseDoneSeats(List<Integer> phaseDoneSeats) {
        this.phaseDoneSeats = phaseDoneSeats;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("iPhaseDone")
    public Boolean getIPhaseDone() {
        return iPhaseDone;
    }

    public void setIPhaseDone(Boolean iPhaseDone) {
        this.iPhaseDone = iPhaseDone;
    }

    public Boolean getSeerRevealPending() {
        return seerRevealPending;
    }

    public void setSeerRevealPending(Boolean seerRevealPending) {
        this.seerRevealPending = seerRevealPending;
    }

    public SeerReveal getSeerReveal() {
        return seerReveal;
    }

    public void setSeerReveal(SeerReveal seerReveal) {
        this.seerReveal = seerReveal;
    }

    public Map<String, Integer> getWolfVoteCounts() {
        return wolfVoteCounts;
    }

    public void setWolfVoteCounts(Map<String, Integer> wolfVoteCounts) {
        this.wolfVoteCounts = wolfVoteCounts;
    }

    public Boolean getWolvesAgreed() {
        return wolvesAgreed;
    }

    public void setWolvesAgreed(Boolean wolvesAgreed) {
        this.wolvesAgreed = wolvesAgreed;
    }

    public List<PlayerView> getPlayers() {
        return players;
    }

    public void setPlayers(List<PlayerView> players) {
        this.players = players;
    }

    public Map<String, Double> getVoteCounts() {
        return voteCounts;
    }

    public void setVoteCounts(Map<String, Double> voteCounts) {
        this.voteCounts = voteCounts;
    }

    public int getVotedCount() {
        return votedCount;
    }

    public void setVotedCount(int votedCount) {
        this.votedCount = votedCount;
    }

    public int getAliveCount() {
        return aliveCount;
    }

    public void setAliveCount(int aliveCount) {
        this.aliveCount = aliveCount;
    }

    public int getAbstainCount() {
        return abstainCount;
    }

    public void setAbstainCount(int abstainCount) {
        this.abstainCount = abstainCount;
    }

    public PrivateView getMe() {
        return me;
    }

    public void setMe(PrivateView me) {
        this.me = me;
    }

    public List<String> getNotices() {
        return notices;
    }

    public void setNotices(List<String> notices) {
        this.notices = notices;
    }
}
