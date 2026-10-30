package com.werewolf.game.engine;

import com.werewolf.common.ErrorCode;
import com.werewolf.game.domain.ActionType;
import com.werewolf.game.domain.GameAction;
import com.werewolf.game.domain.GameEvent;
import com.werewolf.game.domain.GamePhase;
import com.werewolf.game.domain.GameState;
import com.werewolf.game.domain.PlayerState;
import com.werewolf.game.domain.RoleAssigner;
import com.werewolf.game.domain.RoleType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 狼人杀核心状态机 —— <b>服务器唯一权威</b>。
 *
 * <p>设计要点（对应方案第五、八节）：
 * <ol>
 *   <li><b>纯内存、无框架依赖</b>：只依赖 domain 包，单元测试可直接 new 出来跑完整对局；</li>
 *   <li><b>所有写操作私有</b>：外部只能通过 {@link #applyAction} / {@link #nextPhase} 驱动，
 *       身份、死亡、胜负等关键状态无法被客户端或 AI 直接修改；</li>
 *   <li><b>AI 与真人同一条校验路径</b>：{@link GameAction#isFromAi()} 只影响日志，
 *       不影响任何合法性判断，AI 无法绕过规则；</li>
 *   <li><b>事件驱动</b>：每次状态变化都产出 {@link GameEvent}，由上层通过 WebSocket 广播。</li>
 * </ol>
 *
 * <p>阶段流转：
 * <pre>
 * WAITING -> STARTING -> NIGHT -> DAY -> DISCUSSION -> VOTING -> CHECK_WINNER
 *                            ^                                      |
 *                            |                                 未结束 |
 *                            +--------------------------------------+
 *                                                                   |
 *                                                                 END
 * </pre>
 */
public class WerewolfGameEngine {

    private final GameState state;

    private final Random random;

    /** 本回合已行动记录：座位号 -> 已执行的行动类型。 */
    private final Map<Integer, ActionType> actedThisRound = new LinkedHashMap<>();
    /** 本回合已投票记录（防止重复投票）。 */
    private final Map<Integer, Integer> voteCast = new LinkedHashMap<>();
    /** 本回合已发言座位。 */
    private final List<Integer> spokenThisRound = new ArrayList<>();
    /**
     * 本晚所有需要行动的角色是否都已行动完毕（狼人 + 预言家 + 女巫）。
     * 用于告诉上层"本次行动是否让夜晚进入了可结算状态"。
     */
    private boolean nightActorsDone = false;
    /**
     * 本晚是否已经结算过。
     *
     * <p><b>注意</b>：必须与 {@link #nightActorsDone} 分开。早期版本把两者合并成一个
     * 布尔值，导致"狼人行动后把标志置为 true"从而让 {@code resolveNight()} 误判为
     * "已经结算过"而直接 return，表现为夜晚永远不结算。
     */
    private boolean nightSettled = false;
    private record DeathTask(int seat, String kind) {}
    private final java.util.Deque<DeathTask> deathTasks = new java.util.ArrayDeque<>();
    private boolean deathsFromNight;


    public WerewolfGameEngine(GameState state) {
        this(state, new Random());
    }

    public WerewolfGameEngine(GameState state, Random random) {
        this.state = state;
        this.random = random;
    }

    /** 翻牌确认窗口时长（秒），由 AppProperties 注入，默认 30。 */
    private int seerConfirmSeconds = 30;

    public void setSeerConfirmSeconds(int seconds) {
        if (seconds > 0) {
            this.seerConfirmSeconds = seconds;
        }
    }

    public int getSeerConfirmSeconds() {
        return seerConfirmSeconds;
    }

    public GameState getState() {
        return state;
    }

    // ================================================================ 开局 ====

    /**
     * 开始游戏：校验配置 -> 分配角色 -> 进入 STARTING -> NIGHT。
     *
     * @param werewolfCount 狼人数量
     * @param events        产出事件
     */
    public GameActionResult startGame(int werewolfCount, List<GameEvent> events) {
        return startGame(werewolfCount, null, events);
    }

    public GameActionResult startGame(int werewolfCount, Map<String,Integer> roleCounts, List<GameEvent> events) {
        if (state.getPhase() != GamePhase.WAITING) {
            return GameActionResult.fail(ErrorCode.GAME_ALREADY_STARTED);
        }
        int total = state.getPlayers().size();
        try {
            if (roleCounts == null) {
                RoleAssigner.validateConfig(total, werewolfCount);
                RoleAssigner.assign(state.getPlayers(), werewolfCount, random);
            } else {
                RoleAssigner.assign(state.getPlayers(), roleCounts, random);
                werewolfCount = com.werewolf.game.domain.RoleConfiguration.wolves(roleCounts);
            }
        } catch (IllegalArgumentException ex) {
            return GameActionResult.fail(ErrorCode.ROOM_CONFIG_INVALID, ex.getMessage());
        }

        state.setStandard13(total==13);
        state.setCupidFirstSeatId(null);state.setCupidLinked(false);state.setSheriffSeatId(null);
        state.setSheriffElectionDone(false);state.setSheriffVoteRound(0);state.getSheriffCandidates().clear();
        state.setSkillActorSeatId(null);state.setSkillKind(null);deathTasks.clear();
        state.setRound(0);
        state.setFinished(false);
        state.setWinner(null);
        state.getVotes().clear();
        state.getWolfVotes().clear();
        state.getLastNightDeaths().clear();
        state.getSpeeches().clear();
        state.getEvents().clear();
        state.setLastExiledSeatId(null);
        state.setGuardTargetSeatId(null);state.setWolfTargetSeatId(null);state.setSeerTargetSeatId(null);
        state.setWitchUsedHeal(false);state.setWitchUsedPoison(false);state.setWitchPoisonSeatId(null);
        state.setPendingKilledSeatId(null);state.setDayAnnounced(false);state.clearSeerReveal();state.clearPhaseDone();
        state.getSpeakOrder().clear();state.setSpeakCursor(0);
        actedThisRound.clear();voteCast.clear();spokenThisRound.clear();
        nightSettled=false;nightActorsDone=false;witchPotionsUsedThisNight=0;deathsFromNight=false;

        state.setPhase(GamePhase.STARTING);
        state.setPhaseSecondsLeft(GamePhase.STARTING.getDefaultSeconds());
        events.add(GameEvent.of(GameEvent.EventType.GAME_START)
                .put("round", 0)
                .put("playerCount", total)
                .put("werewolfCount", werewolfCount));
        events.add(GameEvent.of(GameEvent.EventType.PHASE_CHANGE).put("phase", GamePhase.STARTING.name()));
        return GameActionResult.success(true);
    }

    // ========================================================== 阶段推进 ====

    /**
     * 由计时器或上层调度调用，推进到下一阶段。
     */
    public GameActionResult nextPhase(List<GameEvent> events) {
        switch (state.getPhase()) {
            case WAITING -> {
                return GameActionResult.fail(ErrorCode.GAME_WRONG_PHASE);
            }
            case STARTING -> {
                // 发牌结束后不直接进夜晚，而是先让所有人确认自己的身份
                enterConfirm(events);
                return GameActionResult.success(true);
            }
            case CONFIRM -> {
                // 确认阶段超时：由上层调用 autoCompleteCurrentPhase 兜底自动确认，
                // 这里只做最后的推进保护（正常情况下会由"最后一位确认"直接触发）
                if (!state.allConfirmed()) {
                    autoConfirmAll(events);
                }
                enterNight(events);
                return GameActionResult.success(true);
            }
            case NIGHT -> {
                // 超时未行动的玩家视为放弃：狼人随机刀、预言家空过、女巫空过
                resolveNight(events);
                return GameActionResult.success(true);
            }
            case DEATH_SKILL -> {
                completeDeathSkill(null,events);
                return GameActionResult.success(true);
            }
            case SHERIFF_VOTING -> {
                resolveSheriff(events);
                return GameActionResult.success(true);
            }
            case DAY -> {
                enterDiscussion(events);
                return GameActionResult.success(true);
            }
            case DISCUSSION -> {
                enterVoting(events);
                return GameActionResult.success(true);
            }
            case VOTING -> {
                resolveVoting(events);
                return GameActionResult.success(true);
            }
            case CHECK_WINNER -> {
                if (state.isFinished()) {
                    finish(events);
                } else {
                    enterNight(events);
                }
                return GameActionResult.success(true);
            }
            default -> {
                return GameActionResult.success();
            }
        }
    }

    /** 进入身份确认阶段：所有人都确认后才进入第一晚。 */
    private void enterConfirm(List<GameEvent> events) {
        state.setPhase(GamePhase.CONFIRM);
        state.setPhaseSecondsLeft(GamePhase.CONFIRM.getDefaultSeconds());
        // 每局开始都重置确认状态，避免上一局的确认被复用
        for (PlayerState p : state.getPlayers()) {
            p.setRoleConfirmed(false);
        }
        events.add(GameEvent.of(GameEvent.EventType.PHASE_CHANGE)
                .put("phase", GamePhase.CONFIRM.name()));
        events.add(GameEvent.of(GameEvent.EventType.ROLE_CONFIRM_START)
                .put("playerCount", state.getPlayers().size())
                .put("confirmedCount", 0)
                .put("secondsLeft", state.getPhaseSecondsLeft()));
    }

    /** 超时兜底：把还没确认的人全部标记为已确认，保证对局不被挂机的人卡住。 */
    private void autoConfirmAll(List<GameEvent> events) {
        for (PlayerState p : state.getPlayers()) {
            if (!p.isRoleConfirmed()) {
                p.setRoleConfirmed(true);
                events.add(GameEvent.of(GameEvent.EventType.ROLE_CONFIRMED)
                        .put("seatId", p.getSeatId())
                        .put("nickname", p.getNickname())
                        .put("auto", true)
                        .put("confirmedCount", state.confirmedCount())
                        .put("playerCount", state.getPlayers().size()));
            }
        }
    }

    private void enterNight(List<GameEvent> events) {
        state.setRound(state.getRound() + 1);
        state.setPhase(GamePhase.NIGHT);
        state.setPhaseSecondsLeft(GamePhase.NIGHT.getDefaultSeconds());
        state.clearPhaseDone();
        state.setGuardTargetSeatId(null);
        state.setWolfTargetSeatId(null);
        state.setSeerTargetSeatId(null);
        state.setWitchUsedHeal(false);
        state.setWitchUsedPoison(false);
        state.setWitchPoisonSeatId(null);
        state.setPendingKilledSeatId(null);
        state.setDayAnnounced(false);
        state.getWolfVotes().clear();
        state.getVotes().clear();
        actedThisRound.clear();
        voteCast.clear();
        spokenThisRound.clear();
        witchPotionsUsedThisNight = 0;
        state.clearSeerReveal();
        nightActorsDone = false;
        nightSettled = false;

        events.add(GameEvent.of(GameEvent.EventType.NIGHT_START).put("round", state.getRound()));
        events.add(GameEvent.of(GameEvent.EventType.PHASE_CHANGE).put("phase", GamePhase.NIGHT.name()));
    }

    private void enterDiscussion(List<GameEvent> events) {
        state.setPhase(GamePhase.DISCUSSION);
        state.setPhaseSecondsLeft(GamePhase.DISCUSSION.getDefaultSeconds());
        state.clearPhaseDone();
        state.getSpeakOrder().clear();
        state.setSpeakCursor(0);
        spokenThisRound.clear();
        buildSpeakOrder();

        events.add(GameEvent.of(GameEvent.EventType.PHASE_CHANGE).put("phase", GamePhase.DISCUSSION.name()));
        PlayerState speaker = state.currentSpeaker();
        if (speaker != null) {
            events.add(GameEvent.of(GameEvent.EventType.SPEAK_TURN)
                    .put("seatId", speaker.getSeatId())
                    .put("nickname", speaker.getNickname()));
        }
    }

    /** 从上一晚第一个死亡座位开始，按座位号顺序轮询存活玩家发言。 */
    private void buildSpeakOrder() {
        List<Integer> alive = new ArrayList<>();
        for (PlayerState p : state.alivePlayers()) {
            alive.add(p.getSeatId());
        }
        alive.sort(Comparator.naturalOrder());
        if (alive.isEmpty()) {
            return;
        }
        int startIndex = 0;
        if (!state.getLastNightDeaths().isEmpty()) {
            int firstDead = state.getLastNightDeaths().get(0);
            for (int i = 0; i < alive.size(); i++) {
                if (alive.get(i) > firstDead) {
                    startIndex = i;
                    break;
                }
            }
        }
        for (int i = 0; i < alive.size(); i++) {
            state.getSpeakOrder().add(alive.get((startIndex + i) % alive.size()));
        }
    }

    private void enterVoting(List<GameEvent> events) {
        state.setPhase(GamePhase.VOTING);
        state.setPhaseSecondsLeft(GamePhase.VOTING.getDefaultSeconds());
        state.clearPhaseDone();
        state.getVotes().clear();
        voteCast.clear();
        events.add(GameEvent.of(GameEvent.EventType.VOTE_START).put("round", state.getRound()));
        events.add(GameEvent.of(GameEvent.EventType.PHASE_CHANGE).put("phase", GamePhase.VOTING.name()));
    }

    private void finish(List<GameEvent> events) {
        state.setPhase(GamePhase.END);
        state.setPhaseSecondsLeft(0);
        events.add(GameEvent.of(GameEvent.EventType.GAME_OVER)
                .put("winner", state.getWinner()));
        events.add(GameEvent.of(GameEvent.EventType.PHASE_CHANGE).put("phase", GamePhase.END.name()));
    }

    // ========================================================== 行动入口 ====

    /**
     * 唯一的行动入口。真人与 AI 走完全相同的校验流程。
     */
    public GameActionResult applyAction(GameAction action, List<GameEvent> events) {
        if (action == null || action.getType() == null) {
            return GameActionResult.fail(ErrorCode.BAD_REQUEST);
        }
        PlayerState actor = state.bySeat(action.getActorSeatId());
        if (actor == null) {
            return GameActionResult.fail(ErrorCode.GAME_PLAYER_DEAD, "座位不存在");
        }
        if (!actor.isAlive() && !(state.getPhase()==GamePhase.DEATH_SKILL
                && Integer.valueOf(actor.getSeatId()).equals(state.getSkillActorSeatId()))) {
            return GameActionResult.fail(ErrorCode.GAME_PLAYER_DEAD);
        }
        return switch (action.getType()) {
            case GUARD_PROTECT, GUARD_PASS -> guard(actor,action,events);
            case CUPID_LINK -> cupid(actor,action,events);
            case SHERIFF_VOTE, SHERIFF_ABSTAIN -> sheriffVote(actor,action,events);
            case HUNTER_SHOOT, WHITE_WOLF_CARRY, SKILL_PASS, BADGE_TRANSFER, BADGE_DESTROY -> deathSkill(actor,action,events);
            case CONFIRM_ROLE -> confirmRole(actor, events);
            case PHASE_DONE -> phaseDone(actor, events);
            case SEER_CONFIRM -> seerConfirm(actor, events);
            case WOLF_KILL -> wolfKill(actor, action, events);
            case SEER_CHECK -> seerCheck(actor, action, events);
            case WITCH_HEAL -> witchHeal(actor, action, events);
            case WITCH_POISON -> witchPoison(actor, action, events);
            case WITCH_PASS -> witchPass(actor, events);
            case SPEAK -> speak(actor, action, events);
            case SPEAK_PASS -> speakPass(actor, events);
            case VOTE -> vote(actor, action, events);
            case ABSTAIN -> abstain(actor, events);
        };
    }

    // ------------------------------------------------------------ 阶段完成 --

    /**
     * 玩家声明"我这一阶段操作完了"。
     *
     * <p><b>推进策略（与需求确认一致）</b>：
     * <ul>
     *   <li>倒计时期间<b>绝不</b>自动进入下一步；</li>
     *   <li>只有<b>所有相关人员</b>都点了完成，才立即推进；</li>
     *   <li>倒计时走到 0 时，服务端给未完成者补默认操作并强制推进（防挂机）。</li>
     * </ul>
     *
     * <p>这样女巫可以"先救人 → 再毒人 → 点完成"，不会因为她用了救药就立刻进白天。
     */
    private GameActionResult phaseDone(PlayerState actor, List<GameEvent> events) {
        GamePhase phase = state.getPhase();
        if (phase != GamePhase.NIGHT && phase != GamePhase.VOTING) {
            return GameActionResult.fail(ErrorCode.GAME_WRONG_PHASE, "当前阶段不需要点完成");
        }
        if (!actor.isAlive()) {
            return GameActionResult.fail(ErrorCode.GAME_PLAYER_DEAD);
        }
        if (!pendingActors().contains(actor)) {
            return GameActionResult.fail(ErrorCode.GAME_ROLE_NOT_ALLOWED, "本阶段你没有需要完成的操作");
        }
        // 翻牌确认窗口没关时，预言家必须先看完结果 —— 服务端也要拦，
        // 不能只靠前端隐藏按钮（否则直接 POST 就能跳过动画）。
        if (state.isSeerRevealPending() && actor.getRole() == RoleType.SEER) {
            return GameActionResult.fail(ErrorCode.GAME_WRONG_PHASE,
                    "请先确认你的查验结果");
        }
        if(phase==GamePhase.NIGHT && actor.getRole()==RoleType.CUPID && !state.getCupidLinked())
            return GameActionResult.fail(ErrorCode.GAME_TARGET_INVALID,"请先选定两名情侣");
        if(phase==GamePhase.NIGHT && actor.getRole()==RoleType.GUARD && !actedThisRound.containsKey(actor.getSeatId()))
            actor.setLastGuardSeatId(null);
        boolean firstTime = !state.isPhaseDone(actor.getSeatId());
        state.markPhaseDone(actor.getSeatId());
        if (firstTime) {
            events.add(GameEvent.of(GameEvent.EventType.PHASE_DONE_BY)
                    .put("seatId", actor.getSeatId())
                    .put("nickname", actor.getNickname())
                    .put("auto", false)
                    .put("doneCount", state.getPhaseDoneSeats().size())
                    .put("actorCount", actorCountOf(phase)).privateTo(actor.getSeatId()));
        }
        // 全员完成即可推进；否则保持当前阶段，等其他人或倒计时
        if (allActorsDone()) {
            advanceAfterActorsDone(events);
            return GameActionResult.success(true);
        }
        return GameActionResult.progressed(true);
    }

    /**
     * 本阶段所有<b>需要操作</b>的人（无论是否已经点过"完成"）。
     *
     * <p>用于：
     * <ul>
     *   <li>判断"是不是全员都完成了"（全员完成才推进阶段）；</li>
     *   <li>超时兜底时知道该给谁补默认操作。</li>
     * </ul>
     *
     * <ul>
     *   <li>NIGHT：存活狼人 / 存活预言家 / 存活女巫；</li>
     *   <li>VOTING：全部存活玩家；</li>
     *   <li>其它阶段：空。</li>
     * </ul>
     */
    public List<PlayerState> phaseActorList() {
        List<PlayerState> list = new ArrayList<>();
        GamePhase phase = state.getPhase();
        if (phase == GamePhase.NIGHT) {
            for (PlayerState p : state.alivePlayers()) {
                if (isNightActor(p)) {
                    list.add(p);
                }
            }
        } else if (phase == GamePhase.VOTING) {
            list.addAll(state.alivePlayers().stream().filter(this::canVote).toList());
        }
        return list;
    }

    /**
     * 本阶段<b>还没点"完成"</b>的人（前端显示"还等谁"）。
     *
     * <p>与"还有行动可做"是两个概念：狼人出刀之后已经没有行动可做，
     * 但在点"完成"之前依然属于"还在等的人"。
     */
    public List<PlayerState> pendingActors() {
        List<PlayerState> list = new ArrayList<>();
        for (PlayerState p : phaseActorList()) {
            if (!state.isPhaseDone(p.getSeatId())) {
                list.add(p);
            }
        }
        return list;
    }

    /**
     * 本阶段<b>还有具体行动可做</b>的人（供超时兜底与 AI 调度）。
     *
     * <p>与 {@link #pendingActors()} 的区别：狼人出刀后就不再出现在这里，
     * 但在他点"完成"之前仍然出现在 {@code pendingActors()} 里。
     */
    public List<PlayerState> actorsWithActionsLeft() {
        List<PlayerState> list = new ArrayList<>();
        GamePhase phase = state.getPhase();
        if (phase == GamePhase.NIGHT) {
            for (PlayerState p : state.alivePlayers()) {
                if (state.isPhaseDone(p.getSeatId())) {
                    continue;
                }
                if (p.isWolf()) {
                    if (!actedThisRound.containsKey(p.getSeatId())) {
                        list.add(p);
                    }
                } else if (p.getRole() == RoleType.SEER) {
                    if (!actedThisRound.containsKey(p.getSeatId())) {
                        list.add(p);
                    }
                } else if (p.getRole()==RoleType.GUARD || (p.getRole()==RoleType.CUPID && !state.getCupidLinked())) {
                    if(!actedThisRound.containsKey(p.getSeatId()))list.add(p);
                } else if (p.getRole() == RoleType.WITCH) {
                    // 女巫只要还有药可下就还有行动
                    if (p.isHealPotionAvailable() || p.isPoisonPotionAvailable()) {
                        list.add(p);
                    }
                }
            }
        } else if (phase == GamePhase.VOTING) {
            for (PlayerState p : state.alivePlayers()) {
                if (canVote(p) && !voteCast.containsKey(p.getSeatId())) {
                    list.add(p);
                }
            }
        }
        return list;
    }

    /** 本阶段的"相关人员"总数，用于前端显示 x/y。 */
    public int actorCountOf(GamePhase phase) {
        GamePhase saved = state.getPhase();
        if (phase != saved) {
            // 仅用于计算，不改真实状态
            return switch (phase) {
                case VOTING -> state.aliveCount();
                case NIGHT -> {
                    int n = 0;
                    for (PlayerState p : state.alivePlayers()) {
                        if (isNightActor(p)) {
                            n++;
                        }
                    }
                    yield n;
                }
                default -> 0;
            };
        }
        return phaseActorList().size();
    }

    /** 是否所有相关人员都已完成本阶段（阶段可以推进了）。 */
    public boolean allActorsDone() {
        return pendingActors().isEmpty();
    }

    /** 全员完成后推进阶段（夜晚要先结算）。 */
    private void advanceAfterActorsDone(List<GameEvent> events) {
        if (state.getPhase() == GamePhase.NIGHT && !nightSettled) {
            resolveNight(events);
            return;
        }
        nextPhase(events);
    }

    /**
     * 玩家确认自己的身份。
     *
     * <p>规则：
     * <ul>
     *   <li>只能在 CONFIRM 阶段提交；</li>
     *   <li>只能确认自己（由 {@code applyAction} 用提交者的座位号解析 actor）；</li>
     *   <li>重复确认是幂等的，不报错也不重复计票；</li>
     *   <li><b>最后一个确认的人提交后，立刻进入第一晚</b>，不需要额外等待倒计时。</li>
     * </ul>
     */
    private GameActionResult confirmRole(PlayerState actor, List<GameEvent> events) {
        if (state.getPhase() != GamePhase.CONFIRM) {
            return GameActionResult.fail(ErrorCode.GAME_WRONG_PHASE, "当前不在身份确认阶段");
        }
        if (actor.getRole() == null) {
            return GameActionResult.fail(ErrorCode.GAME_WRONG_PHASE, "你还没有拿到身份");
        }
        boolean firstTime = !actor.isRoleConfirmed();
        actor.setRoleConfirmed(true);
        if (firstTime) {
            events.add(GameEvent.of(GameEvent.EventType.ROLE_CONFIRMED)
                    .put("seatId", actor.getSeatId())
                    .put("nickname", actor.getNickname())
                    .put("auto", false)
                    .put("confirmedCount", state.confirmedCount())
                    .put("playerCount", state.getPlayers().size()));
        }
        if (state.allConfirmed()) {
            enterNight(events);
            return GameActionResult.success(true);
        }
        return GameActionResult.progressed(true);
    }

    // ------------------------------------------------------------ 夜晚行动 --

    private GameActionResult wolfKill(PlayerState actor, GameAction action, List<GameEvent> events) {
        if (state.getPhase() != GamePhase.NIGHT) {
            return GameActionResult.fail(ErrorCode.GAME_WRONG_PHASE);
        }
        if (!actor.isWolf()) {
            return GameActionResult.fail(ErrorCode.GAME_ROLE_NOT_ALLOWED);
        }
        if (actedThisRound.containsKey(actor.getSeatId()) || state.isPhaseDone(actor.getSeatId())) {
            return GameActionResult.fail(ErrorCode.GAME_ALREADY_ACTED);
        }
        if (action.getTargetSeatId() == null) {
            return GameActionResult.fail(ErrorCode.GAME_TARGET_INVALID);
        }
        PlayerState target = state.bySeat(action.getTargetSeatId());
        if (target == null || !target.isAlive()) {
            return GameActionResult.fail(ErrorCode.GAME_TARGET_INVALID);
        }
        if (target.isWolf()) {
            return GameActionResult.fail(ErrorCode.GAME_TARGET_INVALID, "不能击杀狼人同伴");
        }
        actedThisRound.put(actor.getSeatId(), ActionType.WOLF_KILL);
        state.getWolfVotes().put(actor.getSeatId(), target.getSeatId());
        // 所有存活狼人选择同一目标才产生刀口。
        // 平票（含两只狼各选一个目标）视为"未达成一致"，本晚空刀。
        state.setWolfTargetSeatId(tallyWolfTarget());

        Integer[] wolfSeats = new Integer[state.getPlayers().size()];
        java.util.List<Integer> wolves = new ArrayList<>();
        for (PlayerState p : state.getPlayers()) {
            if (p.isWolf()) {
                wolves.add(p.getSeatId());
            }
        }
        events.add(GameEvent.of(GameEvent.EventType.ROLE_SKILL)
                .put("role", RoleType.WEREWOLF.name())
                .put("effect", "KILL")
                .put("actorSeatId", actor.getSeatId())
                .put("targetSeatId", target.getSeatId())
                .put("visibleTo", "WEREWOLF")
                .visibleTo(wolves.toArray(wolfSeats)));

        if (allWolvesActed()) {
            nightActorsDone = true;
        }
        // 女巫可能已经行动过/已出局：此时狼人的最后一刀就应触发夜晚结算。
        // 返回 progressed 而不是 success(...)：阶段未切换时不能重置夜晚倒计时。
        if (maybeResolveNight(events)) {
            return GameActionResult.success(true);
        }
        return GameActionResult.progressed(nightActorsDone);
    }

    /**
     * 统计狼人刀口：<b>全员一致</b>。
     *
     * <p>规则（F-21）：
     * <ul>
     *   <li>所有存活狼人各自选择目标，得票最高者成为最终刀口；</li>
     *   <li><b>平票 = 未达成一致 → 本晚空刀</b>（返回 {@code null}）。</li>
     * </ul>
     *
     * <p><b>为什么必须显式判平票</b>：早期实现用
     * {@code if (count > bestCount)} 比较，平票时保留<b>先遍历到的那个</b>目标 ——
     * 而遍历顺序就是投票顺序，于是两只狼各选一个目标时，<b>先点的那只狼说了算</b>，
     * 后点的狼等于没投票。这不是多数决，是"先到先得"。
     *
     * @return 最终刀口座位号；平票或无人选择时返回 {@code null}（空刀）
     */
    private Integer tallyWolfTarget() {
        List<PlayerState> wolves=state.aliveWolves();
        if(wolves.isEmpty() || state.getWolfVotes().size()!=wolves.size())return null;
        Integer agreed=null;
        for(PlayerState wolf:wolves){Integer target=state.getWolfVotes().get(wolf.getSeatId());
            if(target==null)return null;if(agreed==null)agreed=target;else if(!agreed.equals(target))return null;}
        return agreed;
    }

    /** 狼人本轮是否已就刀口达成一致（供前端提示"狼队意见不一致"）。 */
    public boolean wolvesAgreedOnTarget() {
        return state.getWolfVotes().size() >= state.aliveWolves().size()
                && tallyWolfTarget() != null;
    }

    private boolean allWolvesActed() {
        for (PlayerState p : state.aliveWolves()) {
            if (!actedThisRound.containsKey(p.getSeatId())) {
                return false;
            }
        }
        return true;
    }

    private GameActionResult seerCheck(PlayerState actor, GameAction action, List<GameEvent> events) {
        if (state.getPhase() != GamePhase.NIGHT) {
            return GameActionResult.fail(ErrorCode.GAME_WRONG_PHASE);
        }
        if (actor.getRole() != RoleType.SEER) {
            return GameActionResult.fail(ErrorCode.GAME_ROLE_NOT_ALLOWED);
        }
        if (actedThisRound.containsKey(actor.getSeatId())) return GameActionResult.fail(ErrorCode.GAME_ALREADY_ACTED);
        if (state.isPhaseDone(actor.getSeatId())) {
            return GameActionResult.fail(ErrorCode.GAME_ALREADY_ACTED);
        }
        if (action.getTargetSeatId() == null) {
            return GameActionResult.fail(ErrorCode.GAME_TARGET_INVALID);
        }
        PlayerState target = state.bySeat(action.getTargetSeatId());
        if (target == null || !target.isAlive()) {
            return GameActionResult.fail(ErrorCode.GAME_TARGET_INVALID);
        }
        if (target.getSeatId() == actor.getSeatId()) {
            return GameActionResult.fail(ErrorCode.GAME_TARGET_INVALID, "不能查验自己");
        }
        boolean alreadyChecked = actor.getCheckRecords().stream()
                .anyMatch(r -> r.targetSeatId() == target.getSeatId());
        if (alreadyChecked) {
            return GameActionResult.fail(ErrorCode.GAME_TARGET_INVALID, "同一玩家不能重复查验");
        }

        actedThisRound.put(actor.getSeatId(), ActionType.SEER_CHECK);
        state.setSeerTargetSeatId(target.getSeatId());
        boolean wolf = target.isWolf();
        actor.getCheckRecords().add(new PlayerState.CheckRecord(
                state.getRound(), target.getSeatId(), target.getNickname(), wolf));

        events.add(GameEvent.of(GameEvent.EventType.SEER_RESULT)
                .put("actorSeatId", actor.getSeatId())
                .put("targetSeatId", target.getSeatId())
                .put("targetNickname", target.getNickname())
                .put("wolf", wolf)
                .put("effect", "CHECK")
                .privateTo(actor.getSeatId()));

        // 开启翻牌确认窗口（默认 30 秒）：预言家看完翻牌动画并点"知道了"，
        // 或者倒计时走完，夜晚才会继续。只看 SEER_RESULT 事件的话，
        // 前端动画可能还没播完夜晚就已经推进了。
        state.startSeerReveal(target.getSeatId(), target.getNickname(), wolf,
                seerConfirmSeconds);
        events.add(GameEvent.of(GameEvent.EventType.SEER_REVEAL)
                .put("actorSeatId", actor.getSeatId())
                .put("targetSeatId", target.getSeatId())
                .put("targetNickname", target.getNickname())
                .put("targetAvatar", target.getAvatar())
                .put("wolf", wolf)
                .put("camp", wolf ? RoleType.Camp.WEREWOLF.name() : RoleType.Camp.VILLAGE.name())
                .put("secondsLeft", state.getSeerRevealSecondsLeft())
                .privateTo(actor.getSeatId()));

        // 翻牌窗口未关闭前不结算（maybeResolveNight 内部也会再判一次）
        if (maybeResolveNight(events)) {
            return GameActionResult.success(true);
        }
        return GameActionResult.progressed(nightActorsDone);
    }

    /**
     * 预言家确认已看过翻牌结果。
     *
     * <p>只有查验者本人能确认；确认后如果其他人都已完成，夜晚会立即推进。
     */
    private GameActionResult seerConfirm(PlayerState actor, List<GameEvent> events) {
        if (state.getPhase() != GamePhase.NIGHT) {
            return GameActionResult.fail(ErrorCode.GAME_WRONG_PHASE, "当前不在夜晚阶段");
        }
        if (actor.getRole() != RoleType.SEER) {
            return GameActionResult.fail(ErrorCode.GAME_ROLE_NOT_ALLOWED, "你不是预言家");
        }
        if (!state.isSeerRevealPending()) {
            return GameActionResult.fail(ErrorCode.GAME_WRONG_PHASE, "当前没有待确认的查验结果");
        }
        closeSeerReveal(actor, false, events);
        if (allActorsDone()) {
            advanceAfterActorsDone(events);
            return GameActionResult.success(true);
        }
        return GameActionResult.progressed(true);
    }

    /** 预言家是否正在等待确认翻牌结果（供测试与前端判断）。 */
    public boolean isSeerRevealPending() {
        return state.isSeerRevealPending();
    }

    /** 关闭翻牌窗口并广播确认事件（超时兜底也会调用）。 */
    public void forceCloseSeerReveal(List<GameEvent> events) {
        if (!state.isSeerRevealPending()) {
            return;
        }
        PlayerState seer = state.firstAliveWithRole(RoleType.SEER);
        if (seer != null) {
            closeSeerReveal(seer, true, events);
        } else {
            state.clearSeerReveal();
        }
    }

    /** 关闭翻牌窗口并广播确认事件。 */
    private void closeSeerReveal(PlayerState actor, boolean auto, List<GameEvent> events) {
        Integer target = state.getSeerRevealTargetSeatId();
        boolean wolf = state.isSeerRevealIsWolf();
        state.clearSeerReveal();
        events.add(GameEvent.of(GameEvent.EventType.SEER_REVEAL_CONFIRMED)
                .put("actorSeatId", actor.getSeatId())
                .put("targetSeatId", target)
                .put("wolf", wolf)
                .put("auto", auto)
                .privateTo(actor.getSeatId()));
    }

    /**
     * 翻牌确认窗口每秒递减（由 RoomRuntime 的 tick 调用）。
     *
     * @return 是否在本次调用中因为倒计时走完而自动确认
     */
    public boolean tickSeerReveal(List<GameEvent> events) {
        if (!state.isSeerRevealPending()) {
            return false;
        }
        if (!state.tickSeerReveal()) {
            return false;
        }
        PlayerState seer = state.firstAliveWithRole(RoleType.SEER);
        if (seer != null) {
            closeSeerReveal(seer, true, events);
        } else {
            state.clearSeerReveal();
        }
        return true;
    }

    private GameActionResult witchHeal(PlayerState actor, GameAction action, List<GameEvent> events) {
        GameActionResult basic = checkWitch(actor);
        if (!basic.isOk()) {
            return basic;
        }
        if (!actor.isHealPotionAvailable()) {
            return GameActionResult.fail(ErrorCode.GAME_POTION_USED, "解药已经用过了");
        }
        Integer victim = state.getWolfTargetSeatId();
        if (victim == null) {
            return GameActionResult.fail(ErrorCode.GAME_TARGET_INVALID, "今晚没有人被狼人击杀");
        }
        if(victim==actor.getSeatId() && state.getRound()!=1)
            return GameActionResult.fail(ErrorCode.GAME_TARGET_INVALID,"女巫仅首夜可自救");
        PlayerState victimPlayer = state.bySeat(victim);
        if (victimPlayer == null || !victimPlayer.isAlive()) {
            return GameActionResult.fail(ErrorCode.GAME_TARGET_INVALID);
        }
        actedThisRound.put(actor.getSeatId(), ActionType.WITCH_HEAL);
        witchPotionsUsedThisNight++;
        state.setWitchUsedHeal(true);
        actor.setHealPotionAvailable(false);
        events.add(GameEvent.of(GameEvent.EventType.WITCH_ACTION)
                .put("actorSeatId", actor.getSeatId())
                .put("effect", "HEAL")
                .put("targetSeatId", victim)
                .privateTo(actor.getSeatId()));
        // 用了救药后本晚继续：女巫还可以用毒药，或者自己点"完成"收手（F-16）。
        // 这里刻意不做任何自动推进，避免"我还没操作完就进白天了"。
        if (allActorsDone()) {
            advanceAfterActorsDone(events);
            return GameActionResult.success(true);
        }
        return GameActionResult.progressed(true);
    }

    private GameActionResult witchPoison(PlayerState actor, GameAction action, List<GameEvent> events) {
        GameActionResult basic = checkWitch(actor);
        if (!basic.isOk()) {
            return basic;
        }
        if (!actor.isPoisonPotionAvailable()) {
            return GameActionResult.fail(ErrorCode.GAME_POTION_USED, "毒药已经用过了");
        }
        if (action.getTargetSeatId() == null) {
            return GameActionResult.fail(ErrorCode.GAME_TARGET_INVALID);
        }
        PlayerState target = state.bySeat(action.getTargetSeatId());
        if (target == null || !target.isAlive()) {
            return GameActionResult.fail(ErrorCode.GAME_TARGET_INVALID);
        }
        if (target.getSeatId() == actor.getSeatId()) {
            return GameActionResult.fail(ErrorCode.GAME_TARGET_INVALID, "不能毒杀自己");
        }
        actedThisRound.put(actor.getSeatId(), ActionType.WITCH_POISON);
        witchPotionsUsedThisNight++;
        state.setWitchUsedPoison(true);
        state.setWitchPoisonSeatId(target.getSeatId());
        actor.setPoisonPotionAvailable(false);
        events.add(GameEvent.of(GameEvent.EventType.WITCH_ACTION)
                .put("actorSeatId", actor.getSeatId())
                .put("effect", "POISON")
                .put("targetSeatId", target.getSeatId())
                .privateTo(actor.getSeatId()));
        // 毒药通常是一晚的最后一步：用完即视为本晚结束
        if (!actor.isHealPotionAvailable()) state.markPhaseDone(actor.getSeatId());
        if (allActorsDone()) {
            advanceAfterActorsDone(events);
            return GameActionResult.success(true);
        }
        return GameActionResult.progressed(true);
    }

    /** 女巫本晚已使用的药水数（最多 2：解药 + 毒药各一次）。 */
    private int witchPotionsUsedThisNight = 0;

    private GameActionResult witchPass(PlayerState actor, List<GameEvent> events) {
        GameActionResult basic = checkWitch(actor);
        if (!basic.isOk()) {
            return basic;
        }
        // 点"不使用" = 放弃剩余药水并结束本晚操作
        state.markPhaseDone(actor.getSeatId());
        events.add(GameEvent.of(GameEvent.EventType.WITCH_ACTION)
                .put("actorSeatId", actor.getSeatId())
                .put("effect", "PASS")
                .privateTo(actor.getSeatId()));
        if (allActorsDone()) {
            advanceAfterActorsDone(events);
            return GameActionResult.success(true);
        }
        return GameActionResult.progressed(true);
    }

    private GameActionResult checkWitch(PlayerState actor) {
        if (state.getPhase() != GamePhase.NIGHT) {
            return GameActionResult.fail(ErrorCode.GAME_WRONG_PHASE);
        }
        if (actor.getRole() != RoleType.WITCH) {
            return GameActionResult.fail(ErrorCode.GAME_ROLE_NOT_ALLOWED);
        }
        if (!actor.isAlive()) {
            return GameActionResult.fail(ErrorCode.GAME_PLAYER_DEAD);
        }
        if (state.isPhaseDone(actor.getSeatId())) {
            return GameActionResult.fail(ErrorCode.GAME_ALREADY_ACTED, "你本晚已经结束操作");
        }
        // 女巫一晚最多两瓶药（解药 + 毒药各一次）；"两次机会"是需求明确要求的规则
        if (witchPotionsUsedThisNight >= 2) {
            return GameActionResult.fail(ErrorCode.GAME_ALREADY_ACTED, "本晚两瓶药都已使用");
        }
        return GameActionResult.success();
    }

    /**
     * 夜晚是否所有人都行动完毕。
     *
     * <p><b>推进策略（F-16 修复）</b>：必须 {@link #allActorsDone()} 为真才结算 ——
     * 也就是存活狼人全部出刀、存活预言家已查验、
     * <b>存活女巫两瓶药都用完或主动点了"完成"</b>。
     *
     * <p>早期实现只要求"女巫用过一次药"，于是她用掉救药后夜晚当场结束，
     * <b>再没有机会用毒药</b>。现在由女巫自己决定何时收手。
     */
    private boolean maybeResolveNight(List<GameEvent> events) {
        if (state.getPhase() != GamePhase.NIGHT || nightSettled) {
            return false;
        }
        if (!allActorsDone()) {
            return false;
        }
        // 预言家的翻牌确认窗口没关之前不结算：
        // 否则动画还没播完，夜晚就已经进入白天了。
        if (state.isSeerRevealPending()) {
            return false;
        }
        resolveNight(events);
        return true;
    }

    /** 该玩家在夜晚阶段是否有任务（决定能否点"完成"）。 */
    private boolean isNightActor(PlayerState p) {
        return p.isWolf() || p.getRole() == RoleType.SEER || p.getRole() == RoleType.WITCH
            || p.getRole()==RoleType.GUARD || (p.getRole()==RoleType.CUPID && state.getRound()==1 && !state.getCupidLinked());
    }

    /** 该玩家在夜晚阶段是否有任务（决定能否点"完成"）。 */
    public boolean isNightActorOf(int seatId) {
        PlayerState p = state.bySeat(seatId);
        return p != null && isNightActor(p);
    }

    /** 某个狼人本轮是否已经参与过选择。
     *
     * <p>狼人一旦提交 {@code WOLF_KILL} 就会被记入 {@code actedThisRound}，
     * 之后不再出现在"待行动"列表里；重复提交会被 {@code GAME_ALREADY_ACTED} 拦下。
     */
    private boolean wolfHasChosen(PlayerState wolf) {
        return actedThisRound.containsKey(wolf.getSeatId());
    }

    private int seatOfRole(RoleType role) {
        PlayerState p = state.firstAliveWithRole(role);
        return p == null ? -1 : p.getSeatId();
    }

    /** 夜晚结算：解药优先于狼刀，毒药独立生效。 */
    private void resolveNight(List<GameEvent> events) {
        if (state.getPhase() != GamePhase.NIGHT || nightSettled) {
            return;
        }
        nightSettled = true;
        nightActorsDone = true;
        state.getLastNightDeaths().clear();

        Map<Integer,String> causes=new LinkedHashMap<>();
        Integer wolfTarget=state.getWolfTargetSeatId();
        if(wolfTarget!=null){boolean guarded=wolfTarget.equals(state.getGuardTargetSeatId());
            if(guarded==state.isWitchUsedHeal())causes.put(wolfTarget,"WOLF");}
        if(state.isWitchUsedPoison() && state.getWitchPoisonSeatId()!=null)causes.put(state.getWitchPoisonSeatId(),"POISON");
        // Record simultaneous primary deaths first; poison suppresses hunter even if also bitten.
        for(var item:causes.entrySet())markDead(state.bySeat(item.getKey()),item.getValue(),events);
        for(var item:causes.entrySet())triggerDeath(state.bySeat(item.getKey()),events);
        state.getLastNightDeaths().sort(Comparator.naturalOrder());
        if(state.getLastNightDeaths().isEmpty())events.add(GameEvent.of(GameEvent.EventType.PEACEFUL_NIGHT).put("round",state.getRound()));
        else events.add(GameEvent.of(GameEvent.EventType.DAWN).put("round",state.getRound()).put("deaths",new ArrayList<>(state.getLastNightDeaths())));
        deathsFromNight=true;
        advanceDeathTasks(events);
    }

    // ------------------------------------------------------------ 白天行动 --

    private GameActionResult speak(PlayerState actor, GameAction action, List<GameEvent> events) {
        if (state.getPhase() != GamePhase.DISCUSSION) {
            return GameActionResult.fail(ErrorCode.GAME_WRONG_PHASE);
        }
        PlayerState current = state.currentSpeaker();
        if (current == null) {
            return GameActionResult.fail(ErrorCode.GAME_NOT_YOUR_TURN);
        }
        if (current.getSeatId() != actor.getSeatId()) {
            return GameActionResult.fail(ErrorCode.GAME_NOT_YOUR_TURN,
                    "当前应由 " + current.getSeatId() + " 号发言");
        }
        String content = action.getSpeech() == null ? "" : action.getSpeech().trim();
        if (content.isEmpty()) {
            return GameActionResult.fail(ErrorCode.BAD_REQUEST, "发言内容不能为空");
        }
        if (content.length() > 500) {
            content = content.substring(0, 500);
        }
        state.addSpeech(new GameState.SpeechRecord(state.getRound(), actor.getSeatId(),
                actor.getNickname(), content, actor.isAi(), state.getPhase()));
        spokenThisRound.add(actor.getSeatId());
        events.add(GameEvent.of(GameEvent.EventType.SPEECH)
                .put("round", state.getRound())
                .put("seatId", actor.getSeatId())
                .put("nickname", actor.getNickname())
                .put("content", content)
                .put("ai", actor.isAi()));

        state.setSpeakCursor(state.getSpeakCursor() + 1);
        PlayerState next = state.currentSpeaker();
        if (next == null) {
            enterVoting(events);
            return GameActionResult.success(true);
        }
        events.add(GameEvent.of(GameEvent.EventType.SPEAK_TURN)
                .put("seatId", next.getSeatId())
                .put("nickname", next.getNickname()));
        return GameActionResult.success(false);
    }

    /**
     * 结束自己的发言轮次（不说内容）。
     *
     * <p>与 {@link #speak} 的区别：不写入 speeches、不发 SPEECH 事件，
     * 只广播 SPEAK_SKIP 并把发言权交给下一位；下一位拿满新的 5 分钟。
     */
    private GameActionResult speakPass(PlayerState actor, List<GameEvent> events) {
        if (state.getPhase() != GamePhase.DISCUSSION) {
            return GameActionResult.fail(ErrorCode.GAME_WRONG_PHASE);
        }
        PlayerState current = state.currentSpeaker();
        if (current == null) {
            return GameActionResult.fail(ErrorCode.GAME_NOT_YOUR_TURN);
        }
        if (current.getSeatId() != actor.getSeatId()) {
            return GameActionResult.fail(ErrorCode.GAME_NOT_YOUR_TURN,
                    "当前应由 " + current.getSeatId() + " 号发言");
        }
        spokenThisRound.add(actor.getSeatId());
        events.add(GameEvent.of(GameEvent.EventType.SPEAK_SKIP)
                .put("round", state.getRound())
                .put("seatId", actor.getSeatId())
                .put("nickname", actor.getNickname())
                .put("reason", "PASS"));

        state.setSpeakCursor(state.getSpeakCursor() + 1);
        PlayerState next = state.currentSpeaker();
        if (next == null) {
            enterVoting(events);
            return GameActionResult.success(true);
        }
        events.add(GameEvent.of(GameEvent.EventType.SPEAK_TURN)
                .put("seatId", next.getSeatId())
                .put("nickname", next.getNickname()));
        return GameActionResult.success(false);
    }

    private GameActionResult vote(PlayerState actor, GameAction action, List<GameEvent> events) {
        if (state.getPhase() != GamePhase.VOTING) {
            return GameActionResult.fail(ErrorCode.GAME_WRONG_PHASE);
        }
        if (!canVote(actor)) return GameActionResult.fail(ErrorCode.GAME_ROLE_NOT_ALLOWED,"翻牌白痴已失去投票权");
        if (voteCast.containsKey(actor.getSeatId())) {
            return GameActionResult.fail(ErrorCode.GAME_ALREADY_VOTED);
        }
        if (action.getTargetSeatId() == null) {
            return GameActionResult.fail(ErrorCode.GAME_TARGET_INVALID);
        }
        PlayerState target = state.bySeat(action.getTargetSeatId());
        if (target == null || !target.isAlive()) {
            return GameActionResult.fail(ErrorCode.GAME_TARGET_INVALID);
        }
        if (target.getSeatId() == actor.getSeatId()) {
            return GameActionResult.fail(ErrorCode.GAME_TARGET_INVALID, "不能投票给自己");
        }
        voteCast.put(actor.getSeatId(), target.getSeatId());
        state.getVotes().put(actor.getSeatId(), target.getSeatId());
        events.add(GameEvent.of(GameEvent.EventType.VOTE_CAST)
                .put("actorSeatId", actor.getSeatId())
                .put("actorNickname", actor.getNickname()));

        if (allAliveVoted()) {
            resolveVoting(events);
            return GameActionResult.success(true);
        }
        return GameActionResult.success(false);
    }

    private GameActionResult abstain(PlayerState actor, List<GameEvent> events) {
        if (state.getPhase() != GamePhase.VOTING) {
            return GameActionResult.fail(ErrorCode.GAME_WRONG_PHASE);
        }
        if (!canVote(actor)) return GameActionResult.fail(ErrorCode.GAME_ROLE_NOT_ALLOWED,"翻牌白痴已失去投票权");
        if (voteCast.containsKey(actor.getSeatId())) {
            return GameActionResult.fail(ErrorCode.GAME_ALREADY_VOTED);
        }
        voteCast.put(actor.getSeatId(), null);
        state.getVotes().put(actor.getSeatId(), null);
        events.add(GameEvent.of(GameEvent.EventType.VOTE_CAST)
                .put("actorSeatId", actor.getSeatId())
                .put("actorNickname", actor.getNickname())
                .put("abstain", true));
        if (allAliveVoted()) {
            resolveVoting(events);
            return GameActionResult.success(true);
        }
        return GameActionResult.success(false);
    }

    private boolean allAliveVoted() {
        for (PlayerState p : state.alivePlayers()) {
            if (canVote(p) && !voteCast.containsKey(p.getSeatId())) {
                return false;
            }
        }
        return true;
    }

    /** 投票结算：最高票放逐；平票则本轮无人出局（简化规则，前端会提示 PK 平票）。 */
    private void resolveVoting(List<GameEvent> events) {
        Map<Integer, Integer> counter = new LinkedHashMap<>();
        for (var entry : state.getVotes().entrySet()) {
            if(entry.getValue()!=null)counter.merge(entry.getValue(),
                entry.getKey().equals(state.getSheriffSeatId())?3:2,Integer::sum);
        }
        Integer exiled = null;
        boolean tie = false;
        int best = 0;
        for (Map.Entry<Integer, Integer> e : counter.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                exiled = e.getKey();
                tie = false;
            } else if (e.getValue() == best) {
                tie = true;
            }
        }
        Map<String, Object> tally = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> e : counter.entrySet()) {
            tally.put(String.valueOf(e.getKey()), e.getValue()/2.0);
        }
        long abstainCount = state.getVotes().values().stream().filter(v -> v == null).count();
        GameEvent result = GameEvent.of(GameEvent.EventType.VOTE_RESULT)
                .put("tally", tally)
                .put("abstainCount", (int) abstainCount)
                .put("tie", tie);

        if (exiled != null && !tie) {
            PlayerState target = state.bySeat(exiled);
            if (target != null && target.isAlive()) {
                if(target.getRole()==RoleType.IDIOT && !target.getIdiotRevealed()){
                    target.setIdiotRevealed(true);state.setLastExiledSeatId(null);
                    result.put("idiotSeatId",exiled);
                    events.add(GameEvent.of(GameEvent.EventType.IDIOT_REVEALED).put("seatId",exiled));
                }else{
                    state.setLastExiledSeatId(exiled);
                    result.put("exiledSeatId",exiled).put("exiledNickname",target.getNickname()).put("votes",best/2.0);
                    kill(target,"VOTE",events);
                }
            }
        } else {
            state.setLastExiledSeatId(null);

        }
        events.add(result);

        deathsFromNight=false;
        advanceDeathTasks(events);
    }


    // ========================================================== 胜负判定 ====

    /**
     * 胜负规则（服务器权威）：
     * <ul>
     *   <li>狼人全部出局 -> 好人阵营胜；</li>
     *   <li>存活狼人数 >= 存活好人数 -> 狼人阵营胜（屠边简化版：狼人达成人数压制）。</li>
     * </ul>
     *
     * @return 是否已分出胜负（内部会写入 state.winner/finished）
     */
    private boolean checkWinnerInternal() {
        String winner=evaluateWinner();
        if(winner==null)return false;
        state.setFinished(true);state.setWinner(winner);return true;
    }

    public String evaluateWinner() {
        if(state.mixedLoversAlive() && state.aliveCount()==2)return RoleType.Camp.LOVERS.name();
        int wolves=state.aliveWolves().size(),villagers=state.aliveVillagers().size();
        if(wolves==0)return RoleType.Camp.VILLAGE.name();
        if(wolves>=villagers && !state.mixedLoversAlive())return RoleType.Camp.WEREWOLF.name();
        return null;
    }

    // ============================================== 供 AI / 前端读取视图 ====

    /** 某座位当前可执行的行动类型（AI 与前端按钮共用同一份规则）。 */
    public List<ActionType> availableActions(int seatId) {
        PlayerState p = state.bySeat(seatId);
        List<ActionType> list = new ArrayList<>();
        if(p!=null && state.getPhase()==GamePhase.DEATH_SKILL && Integer.valueOf(seatId).equals(state.getSkillActorSeatId())){
            if("HUNTER".equals(state.getSkillKind()))return List.of(ActionType.HUNTER_SHOOT,ActionType.SKILL_PASS);
            if("WHITE_WOLF_KING".equals(state.getSkillKind()))return List.of(ActionType.WHITE_WOLF_CARRY,ActionType.SKILL_PASS);
            return List.of(ActionType.BADGE_TRANSFER,ActionType.BADGE_DESTROY);
        }
        if (p == null || !p.isAlive() || state.getPhase().isFinished()) {
            return list;
        }
        switch (state.getPhase()) {
            case CONFIRM -> {
                // 身份确认：尚未确认的玩家都可以点"我已确认身份"
                if (!p.isRoleConfirmed()) {
                    list.add(ActionType.CONFIRM_ROLE);
                }
            }
            case NIGHT -> {
                if (p.isWolf() && !actedThisRound.containsKey(seatId) && !state.isPhaseDone(seatId)) {
                    list.add(ActionType.WOLF_KILL);
                }
                if(p.getRole()==RoleType.SEER && state.isSeerRevealPending())list.add(ActionType.SEER_CONFIRM);
                if (p.getRole() == RoleType.SEER && !actedThisRound.containsKey(seatId)) {
                    list.add(ActionType.SEER_CHECK);
                }
                if(p.getRole()==RoleType.GUARD && !state.isPhaseDone(seatId) && !actedThisRound.containsKey(seatId)){
                    list.add(ActionType.GUARD_PROTECT);list.add(ActionType.GUARD_PASS);
                }
                if(p.getRole()==RoleType.CUPID && state.getRound()==1 && !state.getCupidLinked() && !state.isPhaseDone(seatId))list.add(ActionType.CUPID_LINK);
                if (p.getRole() == RoleType.WITCH && !state.isPhaseDone(seatId)) {
                    // 女巫的"两次机会"：两瓶药在同一晚可以分别使用，
                    // 因此这里按"药水是否还在"给选项，而不是按"本轮是否行动过"。
                    if (p.isHealPotionAvailable() && state.getWolfTargetSeatId() != null && (state.getRound()==1 || state.getWolfTargetSeatId()!=seatId)) {
                        list.add(ActionType.WITCH_HEAL);
                    }
                    if (p.isPoisonPotionAvailable()) {
                        list.add(ActionType.WITCH_POISON);
                    }
                    // 还有药但不想用了，可以点"不使用"收手
                    if (!state.isPhaseDone(seatId)
                            && (p.isHealPotionAvailable() || p.isPoisonPotionAvailable())) {
                        list.add(ActionType.WITCH_PASS);
                    }
                }
                // 本阶段有任务且还没点完成的人，都可以点"完成"
                if (!state.isPhaseDone(seatId) && isNightActor(p)) {
                    // 翻牌确认窗口没关时，预言家先看完结果再说，不能直接点完成
                    boolean blockedByReveal = state.isSeerRevealPending()
                            && p.getRole() == RoleType.SEER;
                    if (!blockedByReveal) {
                        list.add(ActionType.PHASE_DONE);
                    }
                }
            }
            case DISCUSSION -> {
                PlayerState current = state.currentSpeaker();
                if (current != null && current.getSeatId() == seatId) {
                    list.add(ActionType.SPEAK);
                    // 允许"我没什么要说的"直接跳过，不必等满 5 分钟倒计时
                    list.add(ActionType.SPEAK_PASS);
                }
            }
            case SHERIFF_VOTING -> {
                if(!state.getVotes().containsKey(seatId)){list.add(ActionType.SHERIFF_VOTE);list.add(ActionType.SHERIFF_ABSTAIN);}
            }
            case VOTING -> {
                if (!canVote(p)) break;
                if (!voteCast.containsKey(seatId)) {
                    list.add(ActionType.VOTE);
                    list.add(ActionType.ABSTAIN);
                } else if (!state.isPhaseDone(seatId)) {
                    // 已投票的人还可以点"完成"，表示自己这一轮不再改票
                    list.add(ActionType.PHASE_DONE);
                }
            }
            default -> {
                // 其余阶段无可用行动
            }
        }
        return list;
    }

    /** 夜晚还需要行动的角色（供 RoomRuntime 调度 AI）。 */
    public List<PlayerState> pendingNightActors() {
        List<PlayerState> pending = new ArrayList<>();
        if (state.getPhase() != GamePhase.NIGHT) {
            return pending;
        }
        for (PlayerState p : state.alivePlayers()) {
            if (actedThisRound.containsKey(p.getSeatId())) {
                continue;
            }
            if (isNightActor(p) && !state.isPhaseDone(p.getSeatId())) {
                pending.add(p);
            }
        }
        return pending;
    }

    /** 投票阶段还没投票的座位（供调度 AI 与超时自动弃票）。 */
    public List<PlayerState> pendingVoters() {
        List<PlayerState> pending = new ArrayList<>();
        if (state.getPhase() != GamePhase.VOTING) {
            return pending;
        }
        for (PlayerState p : state.alivePlayers()) {
            if (canVote(p) && !voteCast.containsKey(p.getSeatId())) {
                pending.add(p);
            }
        }
        return pending;
    }

    /** 超时兜底：把某人标记为"本阶段已完成"，并广播一条 auto=true 的事件。 */
    private void markAutoDone(PlayerState p, List<GameEvent> events) {
        if (state.isPhaseDone(p.getSeatId())) {
            return;
        }
        state.markPhaseDone(p.getSeatId());
        events.add(GameEvent.of(GameEvent.EventType.PHASE_DONE_BY)
                .put("seatId", p.getSeatId())
                .put("nickname", p.getNickname())
                .put("auto", true)
                .put("doneCount", state.getPhaseDoneSeats().size())
                .put("actorCount", actorCountOf(state.getPhase())).privateTo(p.getSeatId()));
    }

    /** 超时自动兜底：给所有未行动的人补一个合法默认行动（狼人随机刀、其余空过）。 */
    public void autoCompleteCurrentPhase(List<GameEvent> events) {
        if(state.getPhase()==GamePhase.DEATH_SKILL || state.getPhase()==GamePhase.SHERIFF_VOTING)return;
        if(state.getPhase()==GamePhase.NIGHT && state.getRound()==1 && !state.getCupidLinked()){
            PlayerState cupid=state.firstAliveWithRole(RoleType.CUPID);
            if(cupid!=null){List<PlayerState> candidates=new ArrayList<>(state.alivePlayers());java.util.Collections.shuffle(candidates,random);
                if(state.getCupidFirstSeatId()==null)cupid(cupid,GameAction.ai(ActionType.CUPID_LINK,cupid.getSeatId(),candidates.get(0).getSeatId(),null,"timeout"),events);
                for(PlayerState p:candidates)if(!Integer.valueOf(p.getSeatId()).equals(state.getCupidFirstSeatId())){
                    cupid(cupid,GameAction.ai(ActionType.CUPID_LINK,cupid.getSeatId(),p.getSeatId(),null,"timeout"),events);break;}}
        }
        if (state.getPhase() == GamePhase.CONFIRM) {
            // 身份确认超时：未确认的人自动确认，保证对局不被挂机者卡死
            autoConfirmAll(events);
            return;
        }
        if (state.getPhase() == GamePhase.NIGHT) {
            // 倒计时耗尽：给还没行动的狼人随机刀、预言家随机查验、
            // 女巫视为"放弃剩余药水"，全部标记完成，随后由调用方推进阶段。
            for (PlayerState p : new ArrayList<>(actorsWithActionsLeft())) {
                if (p.isWolf()) {
                    List<PlayerState> candidates = new ArrayList<>();
                    for (PlayerState t : state.alivePlayers()) {
                        if (!t.isWolf()) {
                            candidates.add(t);
                        }
                    }
                    if (!candidates.isEmpty()) {
                        Integer existing=state.getWolfVotes().values().stream().findFirst().orElse(null);
                        PlayerState target = existing==null?candidates.get(random.nextInt(candidates.size())):state.bySeat(existing);
                        applyAction(GameAction.ai(ActionType.WOLF_KILL, p.getSeatId(),
                                target.getSeatId(), null, "timeout-random"), events);
                    }
                } else if (p.getRole() == RoleType.SEER) {
                    List<PlayerState> candidates = new ArrayList<>();
                    for (PlayerState t : state.alivePlayers()) {
                        boolean checked = p.getCheckRecords().stream()
                                .anyMatch(r -> r.targetSeatId() == t.getSeatId());
                        if (t.getSeatId() != p.getSeatId() && !checked) {
                            candidates.add(t);
                        }
                    }
                    if (!candidates.isEmpty()) {
                        PlayerState target = candidates.get(random.nextInt(candidates.size()));
                        applyAction(GameAction.ai(ActionType.SEER_CHECK, p.getSeatId(),
                                target.getSeatId(), null, "timeout-random"), events);
                    }
                } else if(p.getRole()==RoleType.GUARD){
                    p.setLastGuardSeatId(null);markAutoDone(p,events);
                } else if (p.getRole() == RoleType.WITCH) {
                    // 女巫超时 = 放弃剩余药水：直接标记完成，不再消耗药水
                    markAutoDone(p, events);
                } else {
                    markAutoDone(p, events);
                }
            }
            // 所有还有行动的人都处理完了：若还有人没点完成，由调用方的
            // nextPhase 强制推进（超时兜底），所以这里只尝试结算。
            if (maybeResolveNight(events)) {
                return;
            }
            // 兜底 1：翻牌确认窗口还开着（预言家没点"知道了"）——超时视为自动确认，
            // 否则夜晚会因为一个悬空的窗口而卡到下一个倒计时周期。
            if (state.isSeerRevealPending()) {
                PlayerState seer = state.firstAliveWithRole(RoleType.SEER);
                if (seer != null) {
                    closeSeerReveal(seer, true, events);
                } else {
                    state.clearSeerReveal();
                }
            }
            // 兜底 2：给剩下没点完成的人补上完成标记，再结算一次
            for (PlayerState p : new ArrayList<>(pendingActors())) {
                markAutoDone(p, events);
            }
            maybeResolveNight(events);
        } else if (state.getPhase() == GamePhase.VOTING) {
            for (PlayerState p : pendingVoters()) {
                applyAction(GameAction.ai(ActionType.ABSTAIN, p.getSeatId(), null, null,
                        "timeout-abstain"), events);
            }
            // 全员弃票后所有票都在 voteCast 里了，交给调用方 nextPhase 结算
        } else if (state.getPhase() == GamePhase.DISCUSSION) {
            // 讨论超时：只跳过当前发言人并推进到下一位。
            // 注意这里直接用 SPEAK_SKIP 事件推进，而不是伪造一条 AI 发言——
            // 否则超时提示会作为真实发言进入 speeches 与 game_records.detail_json，污染复盘。
            PlayerState current = state.currentSpeaker();
            if (current != null) {
                events.add(GameEvent.of(GameEvent.EventType.SPEAK_SKIP)
                        .put("round", state.getRound())
                        .put("seatId", current.getSeatId())
                        .put("nickname", current.getNickname())
                        .put("reason", "TIMEOUT"));
                state.setSpeakCursor(state.getSpeakCursor() + 1);
                PlayerState next = state.currentSpeaker();
                if (next == null) {
                    enterVoting(events);
                } else {
                    events.add(GameEvent.of(GameEvent.EventType.SPEAK_TURN)
                            .put("seatId", next.getSeatId())
                            .put("nickname", next.getNickname()));
                }
            }
        }
    }
    private boolean canVote(PlayerState p){return p.isAlive() && !p.getIdiotRevealed();}

    private GameActionResult guard(PlayerState actor,GameAction action,List<GameEvent> events){
        if(state.getPhase()!=GamePhase.NIGHT)return GameActionResult.fail(ErrorCode.GAME_WRONG_PHASE);
        if(actor.getRole()!=RoleType.GUARD)return GameActionResult.fail(ErrorCode.GAME_ROLE_NOT_ALLOWED);
        if(actedThisRound.containsKey(actor.getSeatId()) || state.isPhaseDone(actor.getSeatId()))return GameActionResult.fail(ErrorCode.GAME_ALREADY_ACTED);
        Integer target=action.getType()==ActionType.GUARD_PASS?null:action.getTargetSeatId();
        if(action.getType()==ActionType.GUARD_PROTECT && (target==null || state.bySeat(target)==null || !state.bySeat(target).isAlive()
                || target.equals(actor.getLastGuardSeatId())))return GameActionResult.fail(ErrorCode.GAME_TARGET_INVALID,"不可连续守护同一玩家");
        state.setGuardTargetSeatId(target);actor.setLastGuardSeatId(target);actedThisRound.put(actor.getSeatId(),action.getType());
        events.add(GameEvent.of(GameEvent.EventType.GUARD_ACTION).put("targetSeatId",target).privateTo(actor.getSeatId()));
        return GameActionResult.progressed(true);
    }

    // Two selections, both private to Cupid until the complete pair is committed.
    private GameActionResult cupid(PlayerState actor,GameAction action,List<GameEvent> events){
        if(state.getPhase()!=GamePhase.NIGHT || state.getRound()!=1)return GameActionResult.fail(ErrorCode.GAME_WRONG_PHASE);
        if(actor.getRole()!=RoleType.CUPID)return GameActionResult.fail(ErrorCode.GAME_ROLE_NOT_ALLOWED);
        if(state.getCupidLinked())return GameActionResult.fail(ErrorCode.GAME_ALREADY_ACTED);
        Integer target=action.getTargetSeatId();
        if(target==null || state.bySeat(target)==null || !state.bySeat(target).isAlive() || target.equals(state.getCupidFirstSeatId()))return GameActionResult.fail(ErrorCode.GAME_TARGET_INVALID);
        if(state.getCupidFirstSeatId()==null){state.setCupidFirstSeatId(target);return GameActionResult.progressed(false);}
        PlayerState first=state.bySeat(state.getCupidFirstSeatId()),second=state.bySeat(target);
        first.setLoverSeatId(target);second.setLoverSeatId(first.getSeatId());state.setCupidLinked(true);
        actedThisRound.put(actor.getSeatId(),ActionType.CUPID_LINK);state.markPhaseDone(actor.getSeatId());
        events.add(GameEvent.of(GameEvent.EventType.CUPID_LINKED).put("lovers",List.of(first.getSeatId(),target))
                .privateTo(actor.getSeatId(),first.getSeatId(),target));
        if(maybeResolveNight(events))return GameActionResult.success(true);
        return GameActionResult.progressed(true);
    }

    private void markDead(PlayerState p,String cause,List<GameEvent> events){
        if(p==null || !p.isAlive())return;
        p.setAlive(false);p.setDeathCause(cause);
        if(state.getPhase()==GamePhase.NIGHT && !state.getLastNightDeaths().contains(p.getSeatId()))state.getLastNightDeaths().add(p.getSeatId());
        // Night cause is private (publishing POISON would expose the witch's choice).
        events.add(GameEvent.of(GameEvent.EventType.PLAYER_DIED).put("seatId",p.getSeatId()).put("nickname",p.getNickname())
                .put("cause",state.getPhase()==GamePhase.NIGHT?"NIGHT":cause));
    }

    private void kill(PlayerState p,String cause,List<GameEvent> events){
        if(p==null || !p.isAlive())return;
        markDead(p,cause,events);triggerDeath(p,events);
    }

    private void triggerDeath(PlayerState p,List<GameEvent> events){
        if(p==null)return;
        if(!p.getDeathSkillUsed()){
            if(p.getRole()==RoleType.HUNTER && !"POISON".equals(p.getDeathCause()) && !"LOVER".equals(p.getDeathCause()))
                deathTasks.addLast(new DeathTask(p.getSeatId(),"HUNTER"));
            if(p.getRole()==RoleType.WHITE_WOLF_KING && "VOTE".equals(p.getDeathCause()))
                deathTasks.addLast(new DeathTask(p.getSeatId(),"WHITE_WOLF_KING"));
            p.setDeathSkillUsed(true);
        }
        if(Integer.valueOf(p.getSeatId()).equals(state.getSheriffSeatId()) && deathTasks.stream().noneMatch(t->t.seat()==p.getSeatId() && t.kind().equals("BADGE")))
            deathTasks.addLast(new DeathTask(p.getSeatId(),"BADGE"));
        if(p.getLoverSeatId()!=null)kill(state.bySeat(p.getLoverSeatId()),"LOVER",events);
    }

    private void phase(GamePhase phase,List<GameEvent> events){
        state.setPhase(phase);state.setPhaseSecondsLeft(phase.getDefaultSeconds());state.clearPhaseDone();
        events.add(GameEvent.of(GameEvent.EventType.PHASE_CHANGE).put("phase",phase.name()));
    }

    private void advanceDeathTasks(List<GameEvent> events){
        while(!deathTasks.isEmpty()){
            DeathTask task=deathTasks.removeFirst();
            if(task.kind().equals("BADGE") && !Integer.valueOf(task.seat()).equals(state.getSheriffSeatId()))continue;
            state.setSkillActorSeatId(task.seat());state.setSkillKind(task.kind());phase(GamePhase.DEATH_SKILL,events);
            events.add(GameEvent.of(GameEvent.EventType.DEATH_SKILL_START).put("seatId",task.seat()).put("kind",task.kind()));
            return;
        }
        state.setSkillActorSeatId(null);state.setSkillKind(null);
        if(checkWinnerInternal()){finish(events);return;}
        if(!deathsFromNight){phase(GamePhase.CHECK_WINNER,events);return;}
        if(state.getStandard13() && !state.getSheriffElectionDone()){
            state.getSheriffCandidates().clear();for(PlayerState p:state.alivePlayers())state.getSheriffCandidates().add(p.getSeatId());
            state.setSheriffVoteRound(1);state.getVotes().clear();phase(GamePhase.SHERIFF_VOTING,events);return;
        }
        enterDawnDiscussion(events);
    }

    private void enterDawnDiscussion(List<GameEvent> events){
        if(state.getLastNightDeaths().isEmpty())enterDiscussion(events);
        else{phase(GamePhase.DAY,events);state.setDayAnnounced(true);}
    }

    private GameActionResult deathSkill(PlayerState actor,GameAction action,List<GameEvent> events){
        if(state.getPhase()!=GamePhase.DEATH_SKILL)return GameActionResult.fail(ErrorCode.GAME_WRONG_PHASE);
        if(!Integer.valueOf(actor.getSeatId()).equals(state.getSkillActorSeatId()))return GameActionResult.fail(ErrorCode.GAME_ROLE_NOT_ALLOWED);
        String kind=state.getSkillKind();ActionType type=action.getType();
        boolean badge="BADGE".equals(kind);
        if(badge && type!=ActionType.BADGE_TRANSFER && type!=ActionType.BADGE_DESTROY)return GameActionResult.fail(ErrorCode.GAME_ROLE_NOT_ALLOWED);
        if(!badge && type!=ActionType.SKILL_PASS && !("HUNTER".equals(kind) && type==ActionType.HUNTER_SHOOT)
                && !("WHITE_WOLF_KING".equals(kind) && type==ActionType.WHITE_WOLF_CARRY))return GameActionResult.fail(ErrorCode.GAME_ROLE_NOT_ALLOWED);
        Integer target=(type==ActionType.SKILL_PASS || type==ActionType.BADGE_DESTROY)?null:action.getTargetSeatId();
        if(type!=ActionType.SKILL_PASS && type!=ActionType.BADGE_DESTROY && (target==null || state.bySeat(target)==null || !state.bySeat(target).isAlive()))return GameActionResult.fail(ErrorCode.GAME_TARGET_INVALID);
        completeDeathSkill(target,events);return GameActionResult.success(true);
    }

    private void completeDeathSkill(Integer target,List<GameEvent> events){
        if("BADGE".equals(state.getSkillKind())){
            state.setSheriffSeatId(target);events.add(GameEvent.of(GameEvent.EventType.BADGE_CHANGED).put("seatId",target));
        }else if(target!=null)kill(state.bySeat(target),"SKILL",events);
        advanceDeathTasks(events);
    }

    private GameActionResult sheriffVote(PlayerState actor,GameAction action,List<GameEvent> events){
        if(state.getPhase()!=GamePhase.SHERIFF_VOTING)return GameActionResult.fail(ErrorCode.GAME_WRONG_PHASE);
        if(state.getVotes().containsKey(actor.getSeatId()))return GameActionResult.fail(ErrorCode.GAME_ALREADY_VOTED);
        Integer target=action.getType()==ActionType.SHERIFF_ABSTAIN?null:action.getTargetSeatId();
        if(action.getType()==ActionType.SHERIFF_VOTE && (target==null || !state.getSheriffCandidates().contains(target)))return GameActionResult.fail(ErrorCode.GAME_TARGET_INVALID);
        state.getVotes().put(actor.getSeatId(),target);
        if(state.getVotes().size()==state.aliveCount()){resolveSheriff(events);return GameActionResult.success(true);}
        return GameActionResult.progressed(false);
    }

    private void resolveSheriff(List<GameEvent> events){
        Map<Integer,Integer> counts=new LinkedHashMap<>();
        for(Integer target:state.getVotes().values())if(target!=null)counts.merge(target,1,Integer::sum);
        int best=counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<Integer> tied=counts.entrySet().stream().filter(x->x.getValue()==best).map(Map.Entry::getKey).toList();
        events.add(GameEvent.of(GameEvent.EventType.SHERIFF_RESULT).put("round",state.getSheriffVoteRound()).put("tally",counts).put("tied",tied));
        state.getVotes().clear();
        if(tied.size()>1 && state.getSheriffVoteRound()==1){
            state.setSheriffVoteRound(2);state.getSheriffCandidates().clear();state.getSheriffCandidates().addAll(tied);
            phase(GamePhase.SHERIFF_VOTING,events);return;
        }
        state.setSheriffSeatId(tied.size()==1?tied.get(0):null);state.setSheriffElectionDone(true);state.getSheriffCandidates().clear();
        events.add(GameEvent.of(GameEvent.EventType.BADGE_CHANGED).put("seatId",state.getSheriffSeatId()));
        enterDawnDiscussion(events);
    }

}
