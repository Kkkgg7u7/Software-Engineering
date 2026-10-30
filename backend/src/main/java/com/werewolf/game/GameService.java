package com.werewolf.game;

import com.werewolf.common.BizException;
import com.werewolf.common.ErrorCode;
import com.werewolf.game.domain.ActionType;
import com.werewolf.game.domain.GamePhase;
import com.werewolf.game.domain.PlayerState;
import com.werewolf.game.dto.ActionRequest;
import com.werewolf.game.dto.GameView;
import com.werewolf.game.engine.GameActionResult;
import com.werewolf.room.RoomService;
import com.werewolf.room.runtime.RoomRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 游戏对外服务：玩家行动的<b>唯一</b>入口 + 状态查询。
 *
 * <p>数据流（方案第十五节）：
 * <pre>
 * 玩家操作 -> Vue -> HTTP/WebSocket -> GameService
 *         -> RoomRuntime -> WerewolfGameEngine 校验 -> 修改状态
 *         -> WebSocket 广播 -> 所有玩家界面更新
 * </pre>
 */
@Service
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    private final RoomService roomService;
    private final com.werewolf.room.RoomAccessPolicy access;

    public GameService(RoomService roomService, com.werewolf.room.RoomAccessPolicy access) {
        this.access = access;
        this.roomService = roomService;
    }

    /** 开始游戏（仅房主）。 */
    public RoomRuntime startGame(Long userId) {
        RoomRuntime runtime = roomService.startGame(userId);
        runtime.broadcastState();
        return runtime;
    }

    /**
     * 提交一次玩家行动。
     *
     * @throws BizException 行动非法时抛出，前端弹出提示且状态不变
     */
    public RoomRuntime applyAction(Long userId, ActionRequest request) {
        RoomRuntime runtime = roomService.requireRuntimeOf(userId);
        PlayerState me = runtime.getState().byUser(userId);
        if (me == null) {
            throw BizException.of(ErrorCode.NOT_IN_ROOM);
        }
        if (runtime.getState().getPhase() == GamePhase.WAITING) {
            throw BizException.of(ErrorCode.GAME_NOT_FOUND, "游戏还没有开始");
        }
        ActionType type;
        try {
            type = ActionType.from(request.getAction());
        } catch (IllegalArgumentException ex) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "未知的行动类型: " + request.getAction());
        }
        GameActionResult result = runtime.applyHumanAction(me.getSeatId(), type,
                request.getTargetSeatId(), request.getSpeech());
        if (!result.isOk()) {
            log.info("玩家行动被拒绝 user={} seat={} action={} target={} reason={}",
                    userId, me.getSeatId(), type, request.getTargetSeatId(), result.getMessage());
            throw new BizException(result.getErrorCode(), result.getMessage());
        }
        return runtime;
    }

    /** 当前用户视角的游戏状态。 */
    public GameView state(Long userId) {
        RoomRuntime runtime = roomService.requireRuntimeOf(userId);
        PlayerState me = runtime.getState().byUser(userId);
        Integer seatId = me == null ? null : me.getSeatId();
        return runtime.projectFor(seatId);
    }

    /** 房间码取游戏状态（观战/重连用）。 */
    public GameView stateByCode(String roomCode, Long userId) {
        RoomRuntime runtime = roomService.requireByCode(roomCode);
        access.requireDetailsAccess(runtime, userId);
        PlayerState me = runtime.getState().byUser(userId);
        Integer seatId = me == null ? null : me.getSeatId();
        return runtime.projectFor(seatId);
    }

    /**
     * 上帝视角（仅用于本地调试 / 演示大屏）。
     * 生产环境应通过配置开关关闭，避免泄露身份。
     */
    public GameView godView(String roomCode) {
        RoomRuntime runtime = roomService.requireByCode(roomCode);
        return runtime.projectFor(null);
    }

    /** 公开发言记录（复盘与前端侧栏）。 */
    public List<Map<String, Object>> speeches(Long userId) {
        RoomRuntime runtime = roomService.requireRuntimeOf(userId);
        List<Map<String, Object>> list = new ArrayList<>();
        for (var s : runtime.getState().getSpeeches()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("round", s.round());
            m.put("seatId", s.seatId());
            m.put("nickname", s.nickname());
            m.put("content", s.content());
            m.put("ai", s.ai());
            list.add(m);
        }
        return list;
    }
}
