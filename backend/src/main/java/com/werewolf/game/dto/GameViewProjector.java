package com.werewolf.game.dto;

import com.werewolf.game.domain.ActionType;
import com.werewolf.game.domain.GamePhase;
import com.werewolf.game.domain.PlayerState;
import com.werewolf.game.domain.RoleType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 {@code GameState} 投影成对客户端安全的 {@link GameView}。
 *
 * <p>这是“服务器权威”的最后一道闸门：任何隐私数据（他人身份、狼人刀口、查验结果）
 * 都必须在这里被裁剪掉，绝不允许直接把 GameState 序列化下发。
 */
public final class GameViewProjector {

    private GameViewProjector() {
    }

    /**
     * @param state   引擎状态
     * @param engine  引擎实例（用于计算"本阶段还等谁"这类依赖回合内记录的派生信息）
     * @param roomName 房间名
     * @param maxPlayers 房间最大人数
     * @param werewolfCount 本局狼人数
     * @param viewerSeatId 观察者座位号；null 表示上帝视角（调试接口）
     */
    public static GameView project(com.werewolf.game.domain.GameState state,
                                   com.werewolf.game.engine.WerewolfGameEngine engine,
                                   String roomName,
                                   int maxPlayers, int werewolfCount, Integer viewerSeatId) {
        int seerConfirmTotal = engine.getSeerConfirmSeconds();
        GameView v = new GameView();
        v.setSheriffSeatId(state.getSheriffSeatId());v.setSheriffVoteRound(state.getSheriffVoteRound());
        v.setSheriffCandidates(new ArrayList<>(state.getSheriffCandidates()));v.setSkillActorSeatId(state.getSkillActorSeatId());v.setSkillKind(state.getSkillKind());
        v.setRoomId(state.getRoomId());
        v.setRoomCode(state.getRoomCode());
        v.setRoomName(roomName);
        v.setPhase(state.getPhase().name());
        v.setPhaseLabel(state.getPhase().getLabel());
        v.setRound(state.getRound());
        v.setPhaseSecondsLeft(state.getPhaseSecondsLeft());
        v.setMaxPlayers(maxPlayers);
        v.setWerewolfCount(werewolfCount);
        boolean over = state.isFinished();
        v.setGameOver(over);
        v.setWinner(state.getWinner());
        v.setWinnerLabel(winnerLabel(state.getWinner()));
        v.setDayAnnounced(state.isDayAnnounced());
        v.setLastExiledSeatId(state.getLastExiledSeatId());
        v.setLastNightDeaths(new ArrayList<>(state.getLastNightDeaths()));
        v.setAliveCount(state.aliveCount());

        PlayerState speaker = state.currentSpeaker();
        if (speaker != null) {
            v.setCurrentSpeakerSeatId(speaker.getSeatId());
            v.setCurrentSpeakerNickname(speaker.getNickname());
        }

        int confirmTarget = 0;
        for (PlayerState p : state.getPlayers()) {
            GameView.PlayerView pv = new GameView.PlayerView();
            pv.setSeatId(p.getSeatId());
            pv.setUserId(p.getUserId());
            pv.setNickname(p.getNickname());
            pv.setAvatar(p.getAvatar());
            pv.setAlive(p.isAlive());pv.setIdiotRevealed(p.getIdiotRevealed());
            pv.setReady(p.isReady());
            pv.setAi(p.isAi());
            pv.setSpeaking(speaker != null && speaker.getSeatId() == p.getSeatId());
            pv.setVoted(state.getVotes().containsKey(p.getSeatId()));
            pv.setConfirmed(p.isRoleConfirmed());
            // AI 由服务端直接视为已确认，不参与"还等谁"的统计
            if (!p.isAi()) {
                confirmTarget++;
            }
            // 只有游戏结束才公开全部身份
            if (over && p.getRole() != null) {
                pv.setRole(p.getRole().name());
            }
            v.getPlayers().add(pv);
        }
        v.setConfirmedCount(state.confirmedCount());
        v.setConfirmTargetCount(confirmTarget);
        v.setUnconfirmedSeats(state.unconfirmedSeats());

        // 阶段"完成"门（夜晚 / 投票）：公开哪些座位还没完成，便于互相等待
        List<Integer> pendingSeats = new ArrayList<>();
        for (PlayerState p : engine.pendingActors()) {
            pendingSeats.add(p.getSeatId());
        }
        v.setPendingActorSeats(pendingSeats);
        v.setActorCount(engine.actorCountOf(state.getPhase()));
        v.setPhaseDoneSeats(new ArrayList<>(state.getPhaseDoneSeats()));
        if(state.getPhase()==GamePhase.NIGHT){v.setPendingActorSeats(List.of());v.setActorCount(0);v.setPhaseDoneSeats(List.of());}

        // 投票统计（公开信息：谁被投了几票，但不公布谁投了谁）
        for(var entry:state.getVotes().entrySet())if(entry.getValue()!=null){
            double weight=state.getPhase()==GamePhase.SHERIFF_VOTING?1.0:entry.getKey().equals(state.getSheriffSeatId())?1.5:1.0;
            v.getVoteCounts().merge(String.valueOf(entry.getValue()),weight,Double::sum);
        }
        v.setVotedCount(state.getVotes().size());
        v.setAbstainCount((int) state.getVotes().values().stream().filter(x -> x == null).count());

        v.setNotices(buildNotices(state));

        if (viewerSeatId != null) {
            PlayerState me = state.bySeat(viewerSeatId);
            if (me != null && me.getRole() != null) {
                v.setMe(privateView(state, me));
                v.setIConfirmed(me.isRoleConfirmed());
                v.setIPhaseDone(state.isPhaseDone(viewerSeatId));
                // 狼队刀口协商信息：只给狼人自己看，好人绝不能拿到。
                // 注意放在 seerReveal 之前、且不受其它分支影响 —— 预言家如果
                // 是狼队阵营这个前提不成立，但多条私有字段必须互不遮蔽。
                if (me.getRole().isWolfSide()) {
                    Map<String, Integer> wolfTally = new LinkedHashMap<>();
                    for (Integer target : state.getWolfVotes().values()) {
                        if (target != null) {
                            wolfTally.merge(String.valueOf(target), 1, Integer::sum);
                        }
                    }
                    v.setWolfVoteCounts(wolfTally);
                    v.setWolvesAgreed(engine.wolvesAgreedOnTarget());
                } else if (state.getPhase().isNight()) {
                    // 非狼人在夜晚看不到任何刀口信息，显式置 null 便于前端判断权限
                    v.setWolfVoteCounts(null);
                    v.setWolvesAgreed(null);
                }
                // 翻牌确认信息只下发给预言家本人（隐私：查验结论绝不能外泄）
                boolean pending = state.isSeerRevealPending();
                v.setSeerRevealPending(pending && me.getRole() == RoleType.SEER);
                if (pending && me.getRole() == RoleType.SEER) {
                    GameView.SeerReveal reveal = new GameView.SeerReveal();
                    reveal.setTargetSeatId(state.getSeerRevealTargetSeatId());
                    reveal.setTargetNickname(state.getSeerRevealTargetNickname());
                    PlayerState target = state.getSeerRevealTargetSeatId() == null
                            ? null : state.bySeat(state.getSeerRevealTargetSeatId());
                    reveal.setTargetAvatar(target == null ? null : target.getAvatar());
                    reveal.setWolf(state.isSeerRevealIsWolf());
                    reveal.setCamp(state.isSeerRevealIsWolf()
                            ? RoleType.Camp.WEREWOLF.name() : RoleType.Camp.VILLAGE.name());
                    reveal.setSecondsLeft(state.getSeerRevealSecondsLeft());
                    reveal.setTotalSeconds(seerConfirmTotal);
                    v.setSeerReveal(reveal);
                }
            }
        }
        return v;
    }

    private static GameView.PrivateView privateView(com.werewolf.game.domain.GameState state,
                                                     PlayerState me) {
        GameView.PrivateView pv = new GameView.PrivateView();
        RoleType role = me.getRole();
        pv.setRole(role.name());
        pv.setRoleLabel(role.getLabel());
        pv.setCamp(state.belongsToLovers(me)?"LOVERS":role.getCamp().name());
        pv.setLoverSeatId(me.getLoverSeatId());
        if(role==RoleType.GUARD)pv.setLastGuardSeatId(me.getLastGuardSeatId());
        if(role==RoleType.CUPID)pv.setCupidFirstSeatId(state.getCupidFirstSeatId());
        pv.setSkill(role.getSkill());
        pv.setAssetName(role.getAssetName());
        if (role.isWolfSide()) {
            for (PlayerState p : state.aliveWolves()) {
                if (p.getSeatId() != me.getSeatId()) {
                    pv.getWolfTeammates().add(p.getSeatId());
                }
            }
            // 已出局的狼同伴也让队友知道，便于复盘沟通
            for (PlayerState p : state.getPlayers()) {
                if (p.isWolf() && !p.isAlive() && p.getSeatId() != me.getSeatId()
                        && !pv.getWolfTeammates().contains(p.getSeatId())) {
                    pv.getWolfTeammates().add(p.getSeatId());
                }
            }
        }
        if (role == RoleType.WITCH) {
            pv.setHealPotionAvailable(me.isHealPotionAvailable());pv.setWitchVictimSeatId(state.getWolfTargetSeatId());
            pv.setPoisonPotionAvailable(me.isPoisonPotionAvailable());
        }
        if (role == RoleType.SEER) {
            List<java.util.Map<String, Object>> records = new ArrayList<>();
            for (PlayerState.CheckRecord r : me.getCheckRecords()) {
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("round", r.round());
                m.put("seatId", r.targetSeatId());
                m.put("nickname", r.targetNickname());
                m.put("wolf", r.wolf());
                m.put("label", r.wolf() ? "狼人" : "好人");
                records.add(m);
            }
            pv.setCheckRecords(records);
        }
        return pv;
    }

    /** 由外部（RoomRuntime）注入“当前可行动列表”，保证与引擎一致。 */
    public static void fillActions(GameView view, PlayerState me, List<ActionType> actions,
                                   List<Integer> selectableTargets) {
        if (view.getMe() == null) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (ActionType a : actions) {
            names.add(a.name());
        }
        view.getMe().setAvailableActions(names);
        view.getMe().setSelectableTargets(selectableTargets);
    }

    private static List<String> buildNotices(com.werewolf.game.domain.GameState state) {
        List<String> notices = new ArrayList<>();
        for (com.werewolf.game.domain.GameEvent e : state.getEvents()) {
            switch (e.getEventType()) {
                case VOTE_RESULT -> {
                    Object exiled = e.getPayload().get("exiledSeatId");
                    if (exiled != null) {
                        notices.add(exiled + " 号被投票放逐");
                    } else if (Boolean.TRUE.equals(e.getPayload().get("tie"))) {
                        notices.add("投票平票，本轮无人出局");
                    }
                }
                case PEACEFUL_NIGHT -> notices.add("昨晚是平安夜");
                default -> {
                    // 其它事件通过 WebSocket 事件流下发，不进入 notices
                }
            }
        }
        if (notices.size() > 5) {
            return new ArrayList<>(notices.subList(notices.size() - 5, notices.size()));
        }
        return notices;
    }

    private static String winnerLabel(String winner) {
        if (winner == null) {
            return null;
        }
        return Arrays.stream(RoleType.Camp.values())
                .filter(c -> c.name().equals(winner))
                .map(RoleType.Camp::getLabel)
                .findFirst()
                .orElse(winner);
    }

    /** 阶段时长覆盖：把配置里的秒数写回视图，避免前端硬编码。 */
    public static void applyPhaseSeconds(GameView view, GamePhase phase, int seconds) {
        if (view.getPhase() != null && view.getPhase().equals(phase.name())) {
            view.setPhaseSecondsLeft(seconds);
        }
    }
}
