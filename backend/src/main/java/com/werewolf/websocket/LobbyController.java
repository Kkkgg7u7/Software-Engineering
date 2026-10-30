package com.werewolf.websocket;

import com.werewolf.auth.security.CurrentUser;
import com.werewolf.common.ApiResponse;
import com.werewolf.game.dto.GameView;
import com.werewolf.room.RoomService;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 大厅：房间列表的实时刷新。
 *
 * <p>REST 提供首次拉取，WebSocket 负责增量通知（{@code /topic/rooms} 收到 refresh 标记后
 * 前端再调用 REST，避免在消息体里塞大对象）。
 */
@RestController
@RequestMapping("/api/lobby")
public class LobbyController {

    private final RoomService roomService;

    public LobbyController(RoomService roomService) {
        this.roomService = roomService;
    }

    /** 大厅房间列表。 */
    @GetMapping("/rooms")
    public ApiResponse<List<com.werewolf.room.dto.RoomView>> rooms() {
        return ApiResponse.ok(roomService.listPublicRooms(CurrentUser.id()));
    }

    /** STOMP：客户端请求刷新房间列表（服务端回推一次快照）。 */
    @MessageMapping("/lobby/rooms")
    @SendToUser("/queue/lobby")
    public ApiResponse<List<com.werewolf.room.dto.RoomView>> roomsViaWs() {
        return ApiResponse.ok(roomService.listPublicRooms(CurrentUser.id()));
    }

    /** STOMP：重连后请求一次当前对局状态。 */
    @MessageMapping("/game/resync")
    @SendToUser(WsDestinations.USER_QUEUE_GAME)
    public ApiResponse<GameView> resync() {
        Long userId = CurrentUser.idOrNull();
        if (userId == null) {
            return ApiResponse.fail(com.werewolf.common.ErrorCode.UNAUTHORIZED);
        }
        var runtime = roomService.currentRuntime(userId);
        if (runtime == null) {
            return ApiResponse.fail(com.werewolf.common.ErrorCode.NOT_IN_ROOM);
        }
        return ApiResponse.ok(runtime.projectFor(runtime.getState().byUser(userId).getSeatId()));
    }
}
