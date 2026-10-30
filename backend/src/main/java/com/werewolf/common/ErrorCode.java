package com.werewolf.common;

/**
 * 业务错误码。前后端共用，新增时同步更新 docs/API.md 与前端 src/api/errorCode.ts。
 */
public enum ErrorCode {

    // 通用 1xxx
    BAD_REQUEST(1000, "请求参数不合法"),
    UNAUTHORIZED(1001, "未登录或登录已过期"),
    FORBIDDEN(1002, "没有权限执行该操作"),
    NOT_FOUND(1003, "资源不存在"),
    INTERNAL_ERROR(1004, "服务器内部错误"),
    RATE_LIMITED(1005, "操作过于频繁，请稍后再试"),

    // 用户 / 认证 2xxx
    USERNAME_TAKEN(2000, "用户名已被注册"),
    NICKNAME_TAKEN(2001, "昵称已被占用"),
    USER_NOT_FOUND(2002, "用户不存在"),
    PASSWORD_WRONG(2003, "用户名或密码错误"),
    ACCOUNT_DISABLED(2004, "账号已被禁用"),
    SESSION_REPLACED(2005, "该账号已在其他设备登录，你已下线。请重新登录（多人游戏请各自注册账号）"),

    // 房间 3xxx
    ROOM_NOT_FOUND(3000, "房间不存在或已解散"),
    ROOM_PASSWORD_WRONG(3001, "房间密码错误"),
    ROOM_FULL(3002, "房间人数已满"),
    ROOM_NOT_WAITING(3003, "房间已开始游戏，无法加入"),
    NOT_IN_ROOM(3004, "你不在该房间中"),
    NOT_HOST(3005, "只有房主可以执行该操作"),
    ALREADY_IN_ROOM(3006, "你已在其它房间中"),
    PLAYER_NOT_READY(3007, "还有玩家未准备"),
    ROOM_CONFIG_INVALID(3008, "房间配置不合法(角色数量与人数不匹配)"),

    // 游戏 4xxx
    GAME_NOT_FOUND(4000, "当前房间没有进行中的游戏"),
    GAME_ALREADY_STARTED(4001, "游戏已经开始"),
    GAME_NOT_YOUR_TURN(4002, "还没轮到你发言"),
    GAME_WRONG_PHASE(4003, "当前阶段不允许该操作"),
    GAME_ROLE_NOT_ALLOWED(4004, "你的角色不能执行该操作"),
    GAME_PLAYER_DEAD(4005, "该玩家已出局"),
    GAME_TARGET_INVALID(4006, "目标不合法"),
    GAME_ALREADY_ACTED(4007, "本回合你已经行动过了"),
    GAME_POTION_USED(4008, "该药水已经用过了"),
    GAME_ALREADY_VOTED(4009, "你已经投过票了"),

    // AI 5xxx
    AI_UNAVAILABLE(5000, "AI 服务暂不可用，已切换为规则 AI"),
    AI_OUTPUT_INVALID(5001, "AI 输出格式不合法，已被规则 AI 接管");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
