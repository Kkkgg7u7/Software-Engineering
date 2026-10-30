package com.werewolf.room;

import com.werewolf.auth.security.CurrentUser;
import com.werewolf.common.ApiResponse;
import com.werewolf.room.dto.CreateRoomRequest;
import com.werewolf.room.dto.JoinRoomRequest;
import com.werewolf.room.dto.RoomView;
import com.werewolf.room.dto.UpdateRoomRequest;
import com.werewolf.room.runtime.RoomRuntime;
import com.werewolf.websocket.GameEventMessenger;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 房间接口（成员 A 负责模块）。
 */
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomService roomService;
    private final GameEventMessenger messenger;
    private final RoomAccessPolicy access;

    public RoomController(RoomService roomService, GameEventMessenger messenger, RoomAccessPolicy access) {
        this.access = access;
        this.roomService = roomService;
        this.messenger = messenger;
    }

    private void requireCurrent(Long uid,String code){var current=roomService.currentRoom(uid);if(current==null||!current.getRoomCode().equalsIgnoreCase(code))throw com.werewolf.common.BizException.of(com.werewolf.common.ErrorCode.NOT_IN_ROOM);}

    /** 创建房间。 */
    @PostMapping
    public ApiResponse<RoomView> create(@Valid @RequestBody CreateRoomRequest request) {
        Long userId = CurrentUser.id();
        RoomRuntime runtime = roomService.createRoom(userId, request);
        messenger.roomListChanged();
        return ApiResponse.ok(runtime.roomView(userId));
    }

    /** 加入房间（按房间码）。 */
    @PostMapping("/join")
    public ApiResponse<RoomView> join(@RequestBody JoinRoomRequest request) {
        Long userId = CurrentUser.id();
        RoomRuntime runtime = roomService.joinRoom(userId, request.getRoomCode(), request.getPassword());
        messenger.roomListChanged();
        return ApiResponse.ok(runtime.roomView(userId));
    }

    /** 快速匹配：加入兼容的等待房间，若无则创建新的公开房间。 */
    @PostMapping("/match")
    public ApiResponse<RoomView> match(@RequestBody(required=false) com.werewolf.room.dto.MatchRoomRequest options) {
        Long userId = CurrentUser.id();
        RoomRuntime runtime = options==null?roomService.matchRoom(userId):roomService.matchRoom(userId,options);
        messenger.roomListChanged();
        return ApiResponse.ok(runtime.roomView(userId));
    }

    /** 公开房间列表（大厅）。 */
    @GetMapping("/public")
    public ApiResponse<List<RoomView>> publicRooms() {
        return ApiResponse.ok(roomService.listPublicRooms(CurrentUser.id()));
    }

    /** 当前所在房间（用于刷新页面后恢复）。 */
    @GetMapping("/current")
    public ApiResponse<RoomView> current() {
        return ApiResponse.ok(roomService.currentRoom(CurrentUser.id()));
    }

    /** 房间详情。 */
    @GetMapping("/{roomCode}")
    public ApiResponse<RoomView> detail(@PathVariable String roomCode) {
        RoomRuntime runtime = roomService.requireByCode(roomCode);
        access.requireDetailsAccess(runtime, CurrentUser.id());
        return ApiResponse.ok(runtime.roomView(CurrentUser.id()));
    }

    /** 修改房间配置（仅房主、等待阶段）。 */
    @PutMapping("/{roomCode}")
    public ApiResponse<RoomView> update(@PathVariable String roomCode,
                                        @Valid @RequestBody UpdateRoomRequest request) {
        Long userId = CurrentUser.id();
        requireCurrent(userId,roomCode);
        RoomRuntime runtime = roomService.updateRoom(userId, request);
        messenger.roomListChanged();
        return ApiResponse.ok(runtime.roomView(userId));
    }

    /** 准备 / 取消准备。 */
    @PostMapping("/{roomCode}/ready")
    public ApiResponse<RoomView> ready(@PathVariable String roomCode,
                                       @RequestBody(required = false) Map<String, Object> body) {
        Long userId = CurrentUser.id();
        boolean ready = body == null || body.get("ready") == null
                || Boolean.parseBoolean(String.valueOf(body.get("ready")));
        requireCurrent(userId,roomCode);
        roomService.setReady(userId, ready);
        RoomRuntime runtime = roomService.requireByCode(roomCode);
        // 广播"不含个人视角"的公共快照；本次操作者的私有视图随 HTTP 响应返回，
        // 其余玩家的私有视图由各自的 /user/queue/room 推送。
        // 注意：绝不能把某个人的私有视图广播到公共主题 —— 那是跨用户串视角的 bug。
        messenger.roomUpdate(runtime.getRoom(), runtime.roomView(null));
        return ApiResponse.ok(runtime.roomView(userId));
    }

    /** 退出房间（房主退出则解散）。 */
    @PostMapping("/{roomCode}/leave")
    public ApiResponse<Void> leave(@PathVariable String roomCode) {
        requireCurrent(CurrentUser.id(),roomCode);
        roomService.leaveRoom(CurrentUser.id());
        messenger.roomListChanged();
        return ApiResponse.ok();
    }

    /** 房主踢人。 */
    @DeleteMapping("/{roomCode}/seats/{seatId}")
    public ApiResponse<Void> kick(@PathVariable String roomCode, @PathVariable Integer seatId) {
        requireCurrent(CurrentUser.id(),roomCode);
        roomService.kick(CurrentUser.id(), seatId);
        messenger.roomListChanged();
        return ApiResponse.ok();
    }

    /** 房主解散房间。 */
    @DeleteMapping("/{roomCode}")
    public ApiResponse<Void> dissolve(@PathVariable String roomCode) {
        Long userId = CurrentUser.id();
        RoomRuntime runtime = roomService.requireByCode(roomCode);
        if (!userId.equals(runtime.getRoom().getCreatedBy())) {
            return ApiResponse.fail(com.werewolf.common.ErrorCode.NOT_HOST);
        }
        roomService.dissolve(runtime, "房主主动解散");
        messenger.roomListChanged();
        return ApiResponse.ok();
    }
}
