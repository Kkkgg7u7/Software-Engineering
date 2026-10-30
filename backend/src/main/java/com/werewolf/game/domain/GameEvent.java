package com.werewolf.game.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 引擎产生的一条事件。前端 EffectManager 依据 eventType 播放对应的预生成 AI 素材特效。
 *
 * <p>方案第九节：
 * <pre>
 * GameEngine 产生事件 -> WebSocket -> Vue EffectManager 播放动画
 * </pre>
 */
public class GameEvent {

    /** 事件类型，前端一一对应一张特效素材。 */
    public enum EventType {
        /** 游戏开始，发牌。 */
        GAME_START,
        /** 阶段切换（phase 字段给出新阶段）。 */
        PHASE_CHANGE,
        /** 进入身份确认阶段。 */
        ROLE_CONFIRM_START,
        /** 某位玩家确认了身份（auto=true 表示超时自动确认）。 */
        ROLE_CONFIRMED,
        /** 某位玩家在本阶段点了"完成"（auto=true 表示超时自动完成）。 */
        PHASE_DONE_BY,
        /** 预言家查验出结果，进入翻牌确认窗口（只发给预言家本人）。 */
        SEER_REVEAL,
        /** 预言家确认看过结果（auto=true 表示 30 秒超时自动确认）。 */
        SEER_REVEAL_CONFIRMED,
        /** 夜晚开始。 */
        NIGHT_START,
        /** 狼人技能特效（不暴露目标给非狼玩家）。 */
        ROLE_SKILL,
        /** 预言家查验结果（仅查验者本人可见）。 */
        SEER_RESULT,
        /** 女巫用药。 */
        WITCH_ACTION,
        /** 天亮，公布死讯。 */
        DAWN,
        /** 平安夜。 */
        PEACEFUL_NIGHT,
        /** 白天讨论开始 / 轮到某人发言。 */
        SPEAK_TURN,
        /** 一条发言。 */
        SPEECH,
        /**
         * 跳过某人的发言（本人点"结束发言"或倒计时超时）。
         *
         * <p>不写入发言记录，避免污染复盘。载荷里的 {@code reason} 区分来源：
         * {@code PASS} = 本人主动结束，{@code TIMEOUT} = 5 分钟倒计时走完。
         */
        SPEAK_SKIP,
        /** 投票开始。 */
        VOTE_START,
        /** 某人投票（只广播投票人，不广播被投者，避免信息泄露）。 */
        VOTE_CAST,
        /** 投票结果，公布被放逐者或平票。 */
        VOTE_RESULT,
        /** 玩家出局。 */
        PLAYER_DIED,
        /** 游戏结束，公布胜负。 */
        GAME_OVER, GUARD_ACTION, CUPID_LINKED, IDIOT_REVEALED, SHERIFF_RESULT, DEATH_SKILL_START, BADGE_CHANGED;

        public static EventType from(String raw) {
            return EventType.valueOf(raw.trim().toUpperCase());
        }
    }

    private final EventType eventType;
    /** 事件数据；不同事件类型字段不同，见 docs/WEBSOCKET.md。 */
    private final Map<String, Object> payload = new LinkedHashMap<>();
    private final long timestamp = System.currentTimeMillis();
    /**
     * 可见座位白名单。
     *
     * <ul>
     *   <li>{@code null} 且 {@link #broadcastToAll} 为 true：全体可见；</li>
     *   <li>非空：仅这些座位能看到 payload 细节（例如狼刀只有狼人知道目标）；</li>
     *   <li>{@code broadcastToAll=false}：事件本身只发给白名单座位（例如预言家查验结果）。</li>
     * </ul>
     */
    private java.util.Set<Integer> visibleSeats;
    private boolean broadcastToAll = true;

    public GameEvent(EventType eventType) {
        this.eventType = eventType;
    }

    public static GameEvent of(EventType type) {
        return new GameEvent(type);
    }

    public GameEvent put(String key, Object value) {
        payload.put(key, value);
        return this;
    }

    /** 仅这些座位能看到事件细节，但事件的通知仍广播给所有人（前端只知道“有人行动了”）。 */
    public GameEvent visibleTo(Integer... seats) {
        this.visibleSeats = new java.util.HashSet<>(java.util.Arrays.asList(seats));
        return this;
    }

    /** 完全私密：事件只发给这些座位。 */
    public GameEvent privateTo(Integer... seats) {
        this.visibleSeats = new java.util.HashSet<>(java.util.Arrays.asList(seats));
        this.broadcastToAll = false;
        return this;
    }

    public java.util.Set<Integer> getVisibleSeats() {
        return visibleSeats;
    }

    public boolean isBroadcastToAll() {
        return broadcastToAll;
    }

    /** 该座位能否看到 payload 细节。 */
    public boolean visibleFor(int seatId) {
        return visibleSeats == null || visibleSeats.contains(seatId);
    }

    public EventType getEventType() {
        return eventType;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public long getTimestamp() {
        return timestamp;
    }

    /** 该事件是否属于“私密事件”，需要按座位单独下发。 */
    public boolean isPrivate() {
        return !broadcastToAll;
    }
}
