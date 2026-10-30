package com.werewolf.game;

import com.werewolf.ai.AiService;
import com.werewolf.auth.security.CurrentUser;
import com.werewolf.common.ApiResponse;
import com.werewolf.game.domain.ActionType;
import com.werewolf.game.domain.GamePhase;
import com.werewolf.game.domain.RoleType;
import com.werewolf.game.dto.ActionRequest;
import com.werewolf.game.dto.GameView;
import com.werewolf.room.RoomService;
import com.werewolf.room.runtime.RoomRuntime;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 游戏接口：开始游戏、提交行动、查询状态。
 *
 * <p>HTTP 与 WebSocket 两条通道都能提交行动，且走同一套 GameEngine 校验；
 * WebSocket 更适合实时操作（无需等待响应），HTTP 便于调试与自动化测试。
 */
@RestController
@RequestMapping("/api/game")
public class GameController {

    private final GameService gameService;
    private final RoomService roomService;
    private final AiService aiService;

    public GameController(GameService gameService, RoomService roomService, AiService aiService) {
        this.gameService = gameService;
        this.roomService = roomService;
        this.aiService = aiService;
    }

    /** 房主开始游戏。 */
    @PostMapping("/start")
    public ApiResponse<GameView> start() {
        Long userId = CurrentUser.id();
        RoomRuntime runtime = gameService.startGame(userId);
        return ApiResponse.ok(runtime.projectFor(runtime.getState().byUser(userId).getSeatId()));
    }

    /** 提交一次行动（夜晚技能 / 发言 / 投票）。 */
    @PostMapping("/action")
    public ApiResponse<GameView> action(@Valid @RequestBody ActionRequest request) {
        Long userId = CurrentUser.id();
        RoomRuntime runtime = gameService.applyAction(userId, request);
        return ApiResponse.ok(runtime.projectFor(runtime.getState().byUser(userId).getSeatId()));
    }

    /** 当前用户视角的完整游戏状态（含本人身份与可用行动）。 */
    @GetMapping("/state")
    public ApiResponse<GameView> state() {
        return ApiResponse.ok(gameService.state(CurrentUser.id()));
    }

    /** 按房间码查询状态（观战/重连）。 */
    @GetMapping("/state/{roomCode}")
    public ApiResponse<GameView> stateByCode(@PathVariable String roomCode) {
        return ApiResponse.ok(gameService.stateByCode(roomCode, CurrentUser.id()));
    }

    /** 公开发言记录。 */
    @GetMapping("/speeches")
    public ApiResponse<List<Map<String, Object>>> speeches() {
        return ApiResponse.ok(gameService.speeches(CurrentUser.id()));
    }

    /** 本局结束后回到等待状态，供“再来一局”。 */
    @PostMapping("/reset")
    public ApiResponse<Void> reset() {
        Long userId = CurrentUser.id();
        RoomRuntime runtime = roomService.requireRuntimeOf(userId);
        if (!userId.equals(runtime.getRoom().getCreatedBy())) {
            return ApiResponse.fail(com.werewolf.common.ErrorCode.NOT_HOST);
        }
        runtime.resetToWaiting();
        return ApiResponse.ok();
    }

    /** 元数据：角色、阶段、行动枚举与 AI 提供方，前端用于渲染图例与提示。 */
    @GetMapping("/meta")
    public ApiResponse<Map<String, Object>> meta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        List<Map<String, Object>> roles = Arrays.stream(RoleType.values()).map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.name());
            m.put("label", r.getLabel());
            m.put("camp", r.getCamp().name());
            m.put("campLabel", r.getCamp().getLabel());
            m.put("skill", r.getSkill());
            m.put("maxCount",com.werewolf.game.domain.RoleConfiguration.maxCount(r,13));
            m.put("asset", "/assets/roles/" + r.getAssetName() + "-card.svg?art=night-v1");
            m.put("icon", "/assets/roles/" + r.getAssetName() + "-icon.svg?art=night-v1");
            m.put("token", "/assets/roles/" + r.getAssetName() + "-token.svg?art=night-v1");
            return m;
        }).toList();
        List<Map<String, Object>> phases = Arrays.stream(GamePhase.values()).map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name());
            m.put("label", p.getLabel());
            m.put("isNight", p.isNight());
            return m;
        }).toList();
        meta.put("roles", roles);
        Map<String,Object> defaults=new LinkedHashMap<>();
        for(int n=4;n<=13;n++){
            Map<String,Object> byWolves=new LinkedHashMap<>();
            for(int w=1;w<=com.werewolf.game.domain.RoleAssigner.maxWerewolfCount(n);w++){
                if(n==13 && w!=4)continue;
                byWolves.put(String.valueOf(w),com.werewolf.game.domain.RoleConfiguration.defaults(n,w));
            }
            defaults.put(String.valueOf(n),byWolves);
        }
        meta.put("roleDefaults",defaults);
        meta.put("phases", phases);
        meta.put("actions", Arrays.stream(ActionType.values()).map(Enum::name).toList());
        meta.put("aiProvider", aiService.providerDescription());
        meta.put("llmEnabled", aiService.isLlmEnabled());
        meta.put("cardBack", "/assets/roles/hidden-back.svg?art=night-v1");
        // 开局约束：前端据此做表单联动校验，避免提交非法配置
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("minPlayers", 4);
        rules.put("maxPlayers", 13);
        rules.put("minWerewolves", 1);
        rules.put("maxWerewolvesOf", Map.of(
                "4", com.werewolf.room.RoomService.maxWerewolfCount(4),
                "5", com.werewolf.room.RoomService.maxWerewolfCount(5),
                "6", com.werewolf.room.RoomService.maxWerewolfCount(6),
                "8", com.werewolf.room.RoomService.maxWerewolfCount(8),
                "9", com.werewolf.room.RoomService.maxWerewolfCount(9),
                "12", com.werewolf.room.RoomService.maxWerewolfCount(12), "13", 4));
        rules.put("requiredSpecialRoles", List.of("SEER", "WITCH"));
        rules.put("werewolfRule", "狼人阵营人数须少于好人，避免按现有胜负规则开局即获胜；自定义牌组可关闭任何特殊身份");
        rules.put("customRolesSupported",true);
        rules.put("standard13Deck",com.werewolf.game.domain.RoleAssigner.buildDeck(13,4));
        rules.put("winnerRule", "狼人全灭则好人胜；存活狼人数 >= 存活好人数且无存活跨阵营情侣则狼人胜；跨阵营情侣两人独活胜；死亡技能完成后判定");
        meta.put("rules", rules);
        meta.put("effects", Map.of(
                "WOLF_KILL", "/assets/roles/werewolf-icon.svg?art=night-v1",
                "SEER_CHECK", "/assets/roles/seer-icon.svg?art=night-v1",
                "WITCH_HEAL", "/assets/roles/witch-icon.svg?art=night-v1",
                "WITCH_POISON", "/assets/roles/witch-icon.svg?art=night-v1",
                "VOTE", "/assets/roles/hunter-icon.svg?art=night-v1",
                "DAWN", "/assets/art/scenes/day.jpg",
                "WIN_GOOD", "/assets/roles/villager-card.svg?art=night-v1",
                "WIN_WEREWOLF", "/assets/roles/werewolf-card.svg?art=night-v1"));
        return ApiResponse.ok(meta);
    }
}
