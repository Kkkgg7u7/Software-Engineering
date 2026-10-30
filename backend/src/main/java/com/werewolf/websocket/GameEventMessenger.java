package com.werewolf.websocket;

import com.werewolf.game.domain.GameEvent;
import com.werewolf.game.dto.GameView;
import com.werewolf.room.dto.RoomView;
import com.werewolf.room.entity.Room;
import com.werewolf.websocket.entity.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 所有 WebSocket 出站消息的唯一出口。集中在这里便于：
 * <ul>
 *   <li>统一报文结构（type + data + timestamp）；</li>
 *   <li>统一隐私裁剪：私密事件只会进入对应座位的 /user/queue；</li>
 *   <li>后续替换为 Redis 广播时只需改这一个类。</li>
 * </ul>
 */
@Component
public class GameEventMessenger {

    /** 报文类型（前端 ws 层按 type 分发到 Pinia store）。 */
    public static final String TYPE_ROOM_UPDATE = "ROOM_UPDATE";
    public static final String TYPE_GAME_STATE = "GAME_STATE";
    public static final String TYPE_GAME_EVENT = "GAME_EVENT";
    public static final String TYPE_CHAT = "CHAT_MESSAGE";
    public static final String TYPE_NOTICE = "NOTICE";
    public static final String TYPE_ERROR = "ERROR";
    public static final String TYPE_KICKED = "KICKED";

    private final SimpMessagingTemplate template;

    public GameEventMessenger(SimpMessagingTemplate template) {
        this.template = template;
    }

    private Map<String, Object> envelope(String type, Object data) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        map.put("data", data);
        map.put("timestamp", System.currentTimeMillis());
        return map;
    }

    public void send(String destination, String type, Object data) {
        template.convertAndSend(destination, envelope(type, data));
    }

    public void sendToUser(Long userId, String destination, String type, Object data) {
        template.convertAndSendToUser(String.valueOf(userId), destination, envelope(type, data));
    }

    // ------------------------------------------------------------- 房间 -----

    public void roomUpdate(Room room, RoomView view) {
        send(WsDestinations.roomTopic(room.getRoomCode()), TYPE_ROOM_UPDATE, view);
    }

    /**
     * 推送"该用户自己的"房间视图到其私有队列。
     *
     * <p>{@code mySeatId} / {@code iAmReady} 这类字段因人而异，绝不能进公共主题，
     * 否则会互相覆盖（房主会看到「我」标在别人的座位上）。
     */
    public void roomUpdatePrivate(Room room, Long userId, RoomView view) {
        sendToUser(userId, WsDestinations.USER_QUEUE_ROOM, TYPE_ROOM_UPDATE, view);
    }

    public void roomListChanged() {
        send(WsDestinations.TOPIC_ROOMS, TYPE_ROOM_UPDATE, Map.of("refresh", true));
    }

    // ------------------------------------------------------------- 游戏 -----

    /** 公开游戏状态。 */
    public void gameStatePublic(Room room, GameView view) {
        send(WsDestinations.roomGame(room.getRoomCode()), TYPE_GAME_STATE, view);
    }

    /** 私有游戏状态：含本人身份、技能、可用行动。 */
    public void gameStatePrivate(Room room, Long userId, GameView view) {
        sendToUser(userId, WsDestinations.USER_QUEUE_GAME, TYPE_GAME_STATE, view);
    }

    /** 广播一个（已裁剪可见性的）游戏事件。 */
    public void gameEvent(Room room, GameEvent event) {
        send(WsDestinations.roomEvent(room.getRoomCode()), TYPE_GAME_EVENT, event);
    }

    /** 私密事件只发给指定用户。 */
    public void privateEvent(Long userId, GameEvent event) {
        sendToUser(userId, WsDestinations.USER_QUEUE_EVENT, TYPE_GAME_EVENT, event);
    }

    public void notice(Room room, String message) {
        send(WsDestinations.roomTopic(room.getRoomCode()), TYPE_NOTICE, Map.of("message", message));
    }

    public void error(Long userId, int code, String message) {
        sendToUser(userId, WsDestinations.USER_QUEUE_ERROR, TYPE_ERROR,
                Map.of("code", code, "message", message));
    }

    public void kicked(Long userId, String reason) {
        sendToUser(userId, WsDestinations.USER_QUEUE_ERROR, TYPE_KICKED,
                Map.of("message", reason));
    }

    // ------------------------------------------------------------- 聊天 -----

    /** 公共聊天：发到房间公共主题，房间内所有人都能收到。 */
    public void chat(Room room, Message message) {
        send(WsDestinations.roomChat(room.getRoomCode()), TYPE_CHAT, message);
    }

    /**
     * 点对点聊天（私聊 / 狼人频道）：只投递给指定用户。
     *
     * <p><b>安全要点</b>：这里刻意使用独立的用户队列
     * {@link WsDestinations#USER_QUEUE_CHAT}，而<b>不是</b>房间公共主题
     * {@link WsDestinations#roomChat}。
     *
     * <p>早期实现写成 {@code sendToUser(userId, roomChat(roomCode), ...)} ——
     * 把公共主题的字符串交给 {@code sendToUser}，靠 Spring 在运行时重写成
     * {@code /user/{userId}/topic/room/{code}/chat}。实测行为碰巧正确，
     * 但"公共主题名 + 私密投递"这种混用极易在后续重构中被改成真正的广播，
     * 一旦如此，同房间所有人都能抓到私聊内容。现在两条通道彻底分开。
     *
     * @param userId  接收者
     * @param roomCode 房间码（仅用于日志与前端归组）
     */
    public void privateChat(Long userId, String roomCode, Message message) {
        sendToUser(userId, WsDestinations.USER_QUEUE_CHAT, TYPE_CHAT, message);
    }

    /** 事件批量下发（引擎一次行动可能产出多个事件）。 */
    public void gameEvents(Room room, List<GameEvent> events) {
        for (GameEvent e : events) {
            gameEvent(room, e);
        }
    }
}
