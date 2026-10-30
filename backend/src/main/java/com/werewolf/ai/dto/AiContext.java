package com.werewolf.ai.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 交给大模型的上下文 —— <b>只包含公开信息</b>。
 *
 * <p>这是 AI 不能作弊的关键：对手身份、狼刀结果、女巫药水等私有状态
 * 一律不进入 Prompt，模型即使“想作弊”也没有信息可作弊。
 */
public class AiContext {
    private Integer loverSeatId;
    public Integer getLoverSeatId() { return loverSeatId; }
    public void setLoverSeatId(Integer value) { loverSeatId=value; }
    private Integer wolfSuggestedTarget;
    public Integer getWolfSuggestedTarget() { return wolfSuggestedTarget; }
    public void setWolfSuggestedTarget(Integer value) { wolfSuggestedTarget=value; }
    private Integer witchVictimSeatId;
    public Integer getWitchVictimSeatId() { return witchVictimSeatId; }
    public void setWitchVictimSeatId(Integer value) { witchVictimSeatId=value; }


    private int mySeatId;
    private String myNickname;
    private String myRole;
    private String myRoleLabel;
    /** 狼人同伴座位（仅当自己是狼人时填充，属于合法私有信息）。 */
    private List<Integer> wolfTeammates = new ArrayList<>();
    /** 预言家已查验结果（仅当自己是预言家时填充）。 */
    private List<String> myChecks = new ArrayList<>();
    private Boolean healPotionAvailable;
    private Boolean poisonPotionAvailable;

    private int round;
    private String phase;
    private String task;
    private List<Integer> selectableTargets = new ArrayList<>();
    private List<String> allowedActions = new ArrayList<>();
    /** 存活玩家：[1] 张三(AI) */
    private List<String> aliveSeats = new ArrayList<>();
    private List<String> deadSeats = new ArrayList<>();
    private List<Integer> wolfTeammatesAlive = new ArrayList<>();

    /** 公开发言记录，按时间顺序。 */
    private List<String> speechLog = new ArrayList<>();
    /** 公开事件日志（谁被放逐、平安夜等）。 */
    private List<String> eventLog = new ArrayList<>();
    /** 上一轮投票统计（公开）。 */
    private String lastVoteSummary = "";

    public int getMySeatId() {
        return mySeatId;
    }

    public void setMySeatId(int mySeatId) {
        this.mySeatId = mySeatId;
    }

    public String getMyNickname() {
        return myNickname;
    }

    public void setMyNickname(String myNickname) {
        this.myNickname = myNickname;
    }

    public String getMyRole() {
        return myRole;
    }

    public void setMyRole(String myRole) {
        this.myRole = myRole;
    }

    public String getMyRoleLabel() {
        return myRoleLabel;
    }

    public void setMyRoleLabel(String myRoleLabel) {
        this.myRoleLabel = myRoleLabel;
    }

    public List<Integer> getWolfTeammates() {
        return wolfTeammates;
    }

    public void setWolfTeammates(List<Integer> wolfTeammates) {
        this.wolfTeammates = wolfTeammates;
    }

    public List<String> getMyChecks() {
        return myChecks;
    }

    public void setMyChecks(List<String> myChecks) {
        this.myChecks = myChecks;
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

    public int getRound() {
        return round;
    }

    public void setRound(int round) {
        this.round = round;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public String getTask() {
        return task;
    }

    public void setTask(String task) {
        this.task = task;
    }

    public List<Integer> getSelectableTargets() {
        return selectableTargets;
    }

    public void setSelectableTargets(List<Integer> selectableTargets) {
        this.selectableTargets = selectableTargets;
    }

    public List<String> getAllowedActions() {
        return allowedActions;
    }

    public void setAllowedActions(List<String> allowedActions) {
        this.allowedActions = allowedActions;
    }

    public List<String> getAliveSeats() {
        return aliveSeats;
    }

    public void setAliveSeats(List<String> aliveSeats) {
        this.aliveSeats = aliveSeats;
    }

    public List<String> getDeadSeats() {
        return deadSeats;
    }

    public void setDeadSeats(List<String> deadSeats) {
        this.deadSeats = deadSeats;
    }

    public List<Integer> getWolfTeammatesAlive() {
        return wolfTeammatesAlive;
    }

    public void setWolfTeammatesAlive(List<Integer> wolfTeammatesAlive) {
        this.wolfTeammatesAlive = wolfTeammatesAlive;
    }

    public List<String> getSpeechLog() {
        return speechLog;
    }

    public void setSpeechLog(List<String> speechLog) {
        this.speechLog = speechLog;
    }

    public List<String> getEventLog() {
        return eventLog;
    }

    public void setEventLog(List<String> eventLog) {
        this.eventLog = eventLog;
    }

    public String getLastVoteSummary() {
        return lastVoteSummary;
    }

    public void setLastVoteSummary(String lastVoteSummary) {
        this.lastVoteSummary = lastVoteSummary;
    }
}
