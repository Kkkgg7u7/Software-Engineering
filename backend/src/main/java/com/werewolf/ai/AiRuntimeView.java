package com.werewolf.ai;

import com.werewolf.game.domain.ActionType;
import com.werewolf.game.domain.GameState;
import com.werewolf.game.domain.PlayerState;

import java.util.List;

/**
 * AI 侧看到的“对局只读视图”。
 *
 * <p>由 {@code RoomRuntime} 实现。AI 模块只依赖这个接口，不反向依赖房间实现，
 * 保证引擎/AI 可以在单元测试里脱离 WebSocket 运行。
 */
public interface AiRuntimeView {

    GameState state();

    /** 某座位当前可执行的行动（来自引擎，AI 与前端共用）。 */
    List<ActionType> legalActions(int seatId);

    /** 某行动下合法的目标集合（AI 输出必须落在此集合内）。 */
    List<Integer> legalTargets(PlayerState actor, List<ActionType> actions);
}
