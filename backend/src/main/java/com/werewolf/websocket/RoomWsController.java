package com.werewolf.websocket;

import com.werewolf.ai.AiService;
import com.werewolf.auth.security.UserPrincipal;
import com.werewolf.common.BizException;
import com.werewolf.common.ErrorCode;
import com.werewolf.game.GameService;
import com.werewolf.game.domain.PlayerState;
import com.werewolf.game.dto.ActionRequest;
import com.werewolf.room.RoomEventPublisher;
import com.werewolf.room.RoomService;
import com.werewolf.room.runtime.RoomRuntime;
import com.werewolf.websocket.entity.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;

/**
 * 房间/游戏相关的 STOMP 入口。
 *
 * <pre>
 * 客户端发送                       服务器行为
 * /app/room/{code}/action     ->  交给 GameEngine 校验并执行，然后广播新状态
 * /app/room/{code}/ready      ->  修改准备状态并广播房间面板
 * /app/room/{code}/sync       ->  按请求者座位补发一次全量状态（断线重连）
 * /app/room/{code}/leave      ->  主动离开房间
 * </pre>
 *
 * <p>注意：所有失败都会通过 {@code /user/queue/error} 返回，
 * <b>不会</b>改变任何游戏状态（服务器权威原则）。
 */
@Controller
public class RoomWsController {

    private static final Logger log = LoggerFactory.getLogger(RoomWsController.class);

    private final GameService gameService;
    private final RoomService roomService;
    private final ChatService chatService;
    private final GameEventMessenger messenger;
    private final RoomEventPublisher roomEventPublisher;
    private final AiService aiService;

    public RoomWsController(GameService gameService, RoomService roomService, ChatService chatService,
                            GameEventMessenger messenger, RoomEventPublisher roomEventPublisher,
                            AiService aiService) {
        this.gameService = gameService;
        this.roomService = roomService;
        this.chatService = chatService;
        this.messenger = messenger;
        this.roomEventPublisher = roomEventPublisher;
        this.aiService = aiService;
    }

    @MessageMapping("/room/{roomCode}/action")
    public void action(@DestinationVariable String roomCode, @Payload ActionRequest request,
                       SimpMessageHeaderAccessor accessor) {
        Long userId = requireUserId(accessor);
        try {
            RoomRuntime runtime = gameService.applyAction(userId, request);
            log.debug("行动成功 room={} user={} action={}", roomCode, userId, request.getAction());
            // 每次成功行动后补发一次本人视角状态，前端无需自行推断
            pushStateTo(runtime, userId);
        } catch (BizException ex) {
            log.info("行动被拒绝 room={} user={} action={} reason={}", roomCode, userId,
                    request.getAction(), ex.getMessage());
            messenger.error(userId, ex.getErrorCode().getCode(), ex.getMessage());
        }
    }

    @MessageMapping("/room/{roomCode}/vote-skip")
    public void voteSkip(@DestinationVariable String roomCode, SimpMessageHeaderAccessor accessor) {
        Long userId = requireUserId(accessor);
        try {
            RoomRuntime runtime = roomService.requireRuntimeOf(userId);
            PlayerState me = runtime.getState().byUser(userId);
            if (me == null) {
                throw BizException.of(ErrorCode.NOT_IN_ROOM);
            }
            runtime.voteSkip(me.getSeatId());
        } catch (BizException ex) {
            messenger.error(userId, ex.getErrorCode().getCode(), ex.getMessage());
        }
    }

    @MessageMapping("/room/{roomCode}/ready")
    public void ready(@DestinationVariable String roomCode, @Payload Map<String, Object> payload,
                      SimpMessageHeaderAccessor accessor) {
        Long userId = requireUserId(accessor);
        try {
            boolean ready = payload.get("ready") == null
                    || Boolean.parseBoolean(String.valueOf(payload.get("ready")));
            roomService.setReady(userId, ready);
            RoomRuntime runtime = roomService.requireRuntimeOf(userId);
            broadcastRoom(runtime);
        } catch (BizException ex) {
            messenger.error(userId, ex.getErrorCode().getCode(), ex.getMessage());
        }
    }

    @MessageMapping("/room/{roomCode}/sync")
    public void sync(@DestinationVariable String roomCode, SimpMessageHeaderAccessor accessor) {
        Long userId = requireUserId(accessor);
        try {
            RoomRuntime runtime = roomService.requireByCode(roomCode);
            PlayerState me = runtime.getState().byUser(userId);
            if (me != null) {
                runtime.setOnline(me.getSeatId(), true);
            }
            broadcastRoom(runtime);
            pushStateTo(runtime, userId);
        } catch (BizException ex) {
            messenger.error(userId, ex.getErrorCode().getCode(), ex.getMessage());
        }
    }

    @MessageMapping("/room/{roomCode}/heartbeat")
    public void heartbeat(@DestinationVariable String roomCode, SimpMessageHeaderAccessor accessor) {
        Long userId = requireUserId(accessor);
        RoomRuntime runtime = roomService.requireRuntimeOf(userId);
        PlayerState me = runtime.getState().byUser(userId);
        if (me != null) {
            runtime.setOnline(me.getSeatId(), true);
        }
    }

    @MessageMapping("/room/{roomCode}/leave")
    public void leave(@DestinationVariable String roomCode, SimpMessageHeaderAccessor accessor) {
        Long userId = requireUserId(accessor);
        try {
            RoomRuntime runtime = roomService.requireRuntimeOf(userId);
            roomService.leaveRoom(userId);
            roomService.listPublicRooms(userId);
            messenger.roomListChanged();
            log.info("用户 {} 通过 WebSocket 离开房间 {}", userId, runtime.getRoom().getRoomCode());
        } catch (BizException ex) {
            messenger.error(userId, ex.getErrorCode().getCode(), ex.getMessage());
        }
    }

    /** 前端连接建立后请求一次“我是谁、我能做什么”。 */
    @MessageMapping("/room/{roomCode}/me")
    public void me(@DestinationVariable String roomCode, SimpMessageHeaderAccessor accessor) {
        Long userId = requireUserId(accessor);
        try {
            RoomRuntime runtime = roomService.requireByCode(roomCode);
            pushStateTo(runtime, userId);
        } catch (BizException ex) {
            messenger.error(userId, ex.getErrorCode().getCode(), ex.getMessage());
        }
    }

    @MessageMapping("/chat/{roomCode}/send")
    public void chat(@DestinationVariable String roomCode,
                     @Payload ChatService.ChatRequest request,
                     SimpMessageHeaderAccessor accessor) {
        Long userId = requireUserId(accessor);
        try {
            Message message = chatService.send(userId, roomCode, request);
            log.debug("聊天已投递 id={} type={}", message.getId(), message.getMessageType());
        } catch (BizException ex) {
            messenger.error(userId, ex.getErrorCode().getCode(), ex.getMessage());
        }
    }

    // ------------------------------------------------------------- 工具 -----

    private void pushStateTo(RoomRuntime runtime, Long userId) {
        PlayerState me = runtime.getState().byUser(userId);
        if (me == null) {
            return;
        }
        messenger.gameStatePrivate(runtime.getRoom(), userId, runtime.projectFor(me.getSeatId()));
    }

    /**
     * 广播房间状态。
     *
     * <p>实际实现见 {@link com.werewolf.room.RoomEventPublisher}：
     * 公共主题只发不含个人视角的公开快照，每个玩家自己的视图走
     * {@code /user/queue/room}。这样各人的 {@code mySeatId} / {@code iAmReady}
     * 不会互相覆盖（F-12）。
     */
    private void broadcastRoom(RoomRuntime runtime) {
        roomEventPublisher.broadcast(runtime);
    }

    private Long requireUserId(SimpMessageHeaderAccessor accessor) {
        Principal principal = accessor.getUser();
        if (principal instanceof UserPrincipal up) {
            return up.getId();
        }
        throw BizException.of(ErrorCode.UNAUTHORIZED);
    }

    @MessageExceptionHandler(BizException.class)
    @SendToUser(WsDestinations.USER_QUEUE_ERROR)
    public Map<String, Object> handleBiz(BizException ex) {
        return Map.of("code", ex.getErrorCode().getCode(), "message", ex.getMessage());
    }

    @MessageExceptionHandler(Exception.class)
    @SendToUser(WsDestinations.USER_QUEUE_ERROR)
    public Map<String, Object> handleOther(Exception ex) {
        log.error("WebSocket 处理异常", ex);
        return Map.of("code", ErrorCode.INTERNAL_ERROR.getCode(),
                "message", ErrorCode.INTERNAL_ERROR.getMessage());
    }
}
