package com.werewolf.websocket;

/**
 * WebSocket 目的地常量。前后端必须保持一致，改动同步更新 docs/WEBSOCKET.md。
 *
 * <pre>
 * 订阅 / 发送（客户端 -> 服务器，前缀 /app）
 *   /app/room/{roomCode}/action        游戏行动（夜晚技能、投票、发言）
 *   /app/room/{roomCode}/vote-skip     投票阶段弃票
 *   /app/room/{roomCode}/ready         准备 / 取消准备
 *   /app/room/{roomCode}/sync          请求全量状态补发（断线重连 / 刷新恢复）
 *   /app/room/{roomCode}/me            请求一次"我是谁、我能做什么"
 *   /app/room/{roomCode}/heartbeat     心跳（保活 + 标记在线）
 *   /app/room/{roomCode}/leave         主动离开房间
 *   /app/chat/{roomCode}/send          发送聊天（公共 / 私聊 / 狼人频道）
 *   /app/lobby/rooms                   请求大厅房间列表快照
 *   /app/game/resync                   重连后请求当前对局状态
 *
 * 推送（服务器 -> 客户端）
 *   /topic/rooms                       大厅房间列表变化（refresh 标记）
 *   /topic/room/{roomCode}             房间座位 / 准备状态 / 系统提示
 *   /topic/room/{roomCode}/game        游戏公开状态
 *   /topic/room/{roomCode}/event       游戏事件（特效 / 日志）
 *   /topic/room/{roomCode}/chat        聊天消息（公共频道）
 *   /user/queue/game                   当前用户的私有游戏状态（含本人身份与可选行动）
 *   /user/queue/event                  当前用户的私密事件（查验结果、女巫用药）
 *   /user/queue/error                  操作失败 / 被踢提示
 *   /user/queue/lobby                  大厅列表快照（STOMP 请求的响应）
 *   /user/queue/room                   当前用户的房间视图
 *   /user/queue/chat                   私聊 / 狼人频道
 * </pre>
 */
public final class WsDestinations {

    private WsDestinations() {
    }

    public static final String APP_PREFIX = "/app";
    public static final String BROKER_PREFIX = "/topic";
    public static final String QUEUE_PREFIX = "/queue";
    public static final String USER_PREFIX = "/user";

    public static final String ENDPOINT = "/ws";
    public static final String NATIVE_ENDPOINT = "/ws-native";

    public static final String TOPIC_ROOMS = "/topic/rooms";

    public static String roomTopic(String roomCode) {
        return "/topic/room/" + roomCode;
    }

    public static String roomGame(String roomCode) {
        return "/topic/room/" + roomCode + "/game";
    }

    public static String roomEvent(String roomCode) {
        return "/topic/room/" + roomCode + "/event";
    }

    /**
     * 公共聊天主题：房间内所有人都能收到。
     *
     * <p><b>只用于公共频道。</b>私聊与狼人频道绝不能发到这里 ——
     * 否则同房间任何客户端都能抓到本该保密的内容。
     */
    public static String roomChat(String roomCode) {
        return "/topic/room/" + roomCode + "/chat";
    }

    /**
     * 点对点聊天用的<b>用户私有队列</b>。
     *
     * <p>与 {@link #roomChat(String)} 是两条完全不同的通道：
     * <ul>
     *   <li>{@code roomChat} 是广播主题，谁订阅谁收到；</li>
     *   <li>{@code userChat} 只投递给指定用户（由
     *       {@code SimpMessagingTemplate#convertAndSendToUser} 按 userId 路由）。</li>
     * </ul>
     *
     * <p>早期实现把 {@code roomChat(...)} 的字符串直接传给 {@code sendToUser}，
     * 依赖 Spring 在运行时把它重写成 {@code /user/{userId}/topic/...}。
     * 结果碰巧是对的，但代码里"公共主题名字"与"私密投递"混在一起，
     * 极易被后人改成真正的广播而造成私聊泄露。这里显式区分开。
     */
    public static final String USER_QUEUE_CHAT = "/queue/chat";

    public static final String USER_QUEUE_GAME = "/queue/game";
    public static final String USER_QUEUE_EVENT = "/queue/event";
    public static final String USER_QUEUE_ERROR = "/queue/error";
    /**
     * 当前用户自己的房间视图（含 {@code mySeatId} / {@code iAmReady} 等"因人而异"的字段）。
     *
     * <p><b>为什么必须走用户队列</b>：这些字段对每个玩家都不同。
     * 早期实现把它们一起广播到公共主题 {@code /topic/room/{code}}，
     * 导致客户端最终收到的是"最后一个玩家"的视角 ——
     * 表现为房主看到「我」标记落在别人座位上，同时这也是隐私泄露。
     * 公共主题现在只发不含任何个人视角的公开快照。
     */
    public static final String USER_QUEUE_ROOM = "/queue/room";
}
