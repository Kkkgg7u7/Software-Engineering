package com.werewolf.room;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.werewolf.ai.BotRoster;
import com.werewolf.common.BizException;
import com.werewolf.common.ErrorCode;
import com.werewolf.game.domain.GamePhase;
import com.werewolf.game.domain.PlayerState;
import com.werewolf.game.domain.RoleAssigner;
import com.werewolf.game.domain.RoleConfiguration;
import com.werewolf.game.domain.RoleType;
import com.werewolf.game.entity.GameRecord;
import com.werewolf.game.mapper.GameRecordMapper;
import com.werewolf.room.dto.CreateRoomRequest;
import com.werewolf.room.dto.RoomView;
import com.werewolf.room.dto.UpdateRoomRequest;
import com.werewolf.room.entity.Room;
import com.werewolf.room.entity.RoomPlayer;
import com.werewolf.room.mapper.RoomMapper;
import com.werewolf.room.mapper.RoomPlayerMapper;
import com.werewolf.room.runtime.GameEndHandler;
import com.werewolf.room.runtime.RoomManager;
import com.werewolf.room.runtime.RoomRuntime;
import com.werewolf.user.entity.User;
import com.werewolf.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 房间系统（成员 A 负责模块）。
 *
 * <p>职责边界：
 * <ul>
 *   <li>本类只负责“房间生命周期 + 座位管理 + 落库”，游戏规则一律交给 GameEngine；</li>
 *   <li>房间的实时状态在 {@link RoomRuntime}（内存），rooms 表只保存配置与状态标记。</li>
 * </ul>
 */
@Service
public class RoomService implements GameEndHandler {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final RoomMapper roomMapper;
    private final RoomPlayerMapper roomPlayerMapper;
    private final GameRecordMapper gameRecordMapper;
    private final UserMapper userMapper;
    private final RoomManager roomManager;
    private final BotRoster botRoster;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final RoomEventPublisher roomEventPublisher;
    private final SecureRandom random = new SecureRandom();
    private final com.werewolf.platform.GameRegistry games;

    public RoomService(RoomMapper roomMapper, RoomPlayerMapper roomPlayerMapper,
                       GameRecordMapper gameRecordMapper, UserMapper userMapper,
                       RoomManager roomManager, BotRoster botRoster, PasswordEncoder passwordEncoder,
                       ObjectMapper objectMapper, RoomEventPublisher roomEventPublisher, com.werewolf.platform.GameRegistry games) {
        this.games=games;
        this.roomMapper = roomMapper;
        this.roomPlayerMapper = roomPlayerMapper;
        this.gameRecordMapper = gameRecordMapper;
        this.userMapper = userMapper;
        this.roomManager = roomManager;
        this.botRoster = botRoster;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
        this.roomEventPublisher = roomEventPublisher;
    }

    // ============================================================ 创建房间 ====

    /** 把自己注册为“对局结束回调”，避免 RoomManager 反向依赖 Service 造成循环依赖。 */
    @PostConstruct
    void registerGameEndHandler() {
        roomManager.setGameEndHandler(this);
    }

    @Transactional
    public synchronized RoomRuntime createRoom(Long userId, CreateRoomRequest request) {
        User user = requireUser(userId);
        RoomRuntime current = roomManager.findByUserId(userId);
        if (current != null) {
            throw BizException.of(ErrorCode.ALREADY_IN_ROOM,
                    "你已在房间 " + current.getRoom().getRoomCode() + " 中");
        }
        int maxPlayers = request.getMaxPlayers() == null ? 8 : request.getMaxPlayers();
        int werewolfCount = request.getWerewolfCount() == null ? 2 : request.getWerewolfCount();
        int aiCount = request.getAiCount() == null ? 0 : request.getAiCount();
        boolean allowAi=request.getAllowAi()!=null?request.getAllowAi():aiCount>0;
        if(!allowAi)aiCount=0;
        Map<String,Integer> counts = request.getRoleCounts();
        if (counts != null) {
            counts = validateRoles(maxPlayers, counts);
            werewolfCount = RoleConfiguration.wolves(counts);
        }
        validateRoomConfiguration(request.getGameType()==null?"WEREWOLF":request.getGameType(),maxPlayers,werewolfCount,aiCount,counts);

        Room room = new Room();
        room.setRoomCode(generateRoomCode());
        room.setGameType(request.getGameType()==null?"WEREWOLF":request.getGameType());
        room.setAllowInvites(request.getAllowInvites()==null||request.getAllowInvites());
        room.setVoiceEnabled(Boolean.TRUE.equals(request.getVoiceEnabled()));
        room.setPrivateChatEnabled(request.getPrivateChatEnabled()==null||request.getPrivateChatEnabled());
        room.setRoomName(request.getRoomName().trim());
        room.setPassword(encodePassword(request.getPassword()));
        room.setRoleCounts(counts);
        room.setMaxPlayers(maxPlayers);
        room.setWerewolfCount(werewolfCount);
        room.setAiCount(aiCount);
        // 指定 AI 数量即允许入座；显式关闭时数量归零，避免显示虚假的 AI。
        room.setAllowAi(allowAi);
        room.setPublicRoom(request.getPublicRoom() == null ? Boolean.TRUE : request.getPublicRoom());
        room.setGameStatus(Room.Status.WAITING.name());
        room.setCreatedBy(userId);
        room.setCreatedAt(LocalDateTime.now());
        try {
            roomMapper.insert(room);
        } catch (DuplicateKeyException ex) {
            room.setRoomCode(generateRoomCode());
            roomMapper.insert(room);
        }

        RoomRuntime runtime = roomManager.create(room);
        runtime.addPlayer(user.getId(), user.getNickname(), user.getAvatar());
        persistSeats(runtime);
        // 房主创建时就可以带上 AI NPC，方便一个人也能开局演示
        addBots(runtime, aiCount);
        return runtime;
    }

    // ============================================================ 加入房间 ====

    @Transactional
    public synchronized RoomRuntime joinRoom(Long userId, String roomCode, String password) {
        User user = requireUser(userId);
        RoomRuntime runtime = roomManager.requireByCode(normalizeCode(roomCode));
        Room room = runtime.getRoom();

        PlayerState already = runtime.getState().byUser(userId);
        if (already != null) {
            // 重复加入视为重连：直接返回当前房间，但也要补一次广播
            // （可能之前掉线了，其他人需要看到"他回来了"）
            runtime.setOnline(already.getSeatId(), true);
            roomEventPublisher.broadcast(runtime);
            return runtime;
        }
        RoomRuntime current = roomManager.findByUserId(userId);
        if (current != null && !current.getRoom().getId().equals(room.getId())) {
            throw BizException.of(ErrorCode.ALREADY_IN_ROOM,
                    "你已在房间 " + current.getRoom().getRoomCode() + " 中，请先退出");
        }
        if (Room.Status.PLAYING.name().equals(room.getGameStatus())) {
            throw BizException.of(ErrorCode.ROOM_NOT_WAITING);
        }
        if (room.getPassword() != null && !room.getPassword().isBlank()) {
            if (password == null || !passwordEncoder.matches(password, room.getPassword())) {
                throw BizException.of(ErrorCode.ROOM_PASSWORD_WRONG);
            }
        }
        if (runtime.isFull()) {
            throw BizException.of(ErrorCode.ROOM_FULL);
        }
        PlayerState added = runtime.addPlayer(user.getId(), user.getNickname(), user.getAvatar());
        persistSeats(runtime);
        // 关键：必须通知房间里已有的玩家"有人进来了"。
        // 早期实现漏了这一步，导致房主看不到新玩家（座位一直空着，只能刷新页面）。
        roomEventPublisher.broadcast(runtime);
        if (added != null) {
            roomEventPublisher.notice(runtime, added.getNickname() + " 加入了房间");
        }
        log.info("用户 {} 加入房间 {}，座位 {}", user.getNickname(), room.getRoomCode(),
                added == null ? "?" : added.getSeatId());
        return runtime;
    }

    /**
     * 一键匹配。匹配与手动创建、加入共用锁，避免并发请求选中同一空位。
     * 只自动加入无密码、公开、等待中、4 人 1 狼且没有 AI 的房间；房主仍可手动建自定义房。
     */
    @Transactional
    public synchronized RoomRuntime matchRoom(Long userId) { return matchRoom(userId,new com.werewolf.room.dto.MatchRoomRequest(null,null,null,null)); }

    @Transactional
    public synchronized RoomRuntime matchRoom(Long userId,com.werewolf.room.dto.MatchRoomRequest options) {
        games.require(options.type()).validateConfiguration(Map.of("maxPlayers",options.capacity(),"werewolfCount",options.wolves(),"aiCount",0));
        // 重复点击匹配或刷新后的重试，直接回到当前房间。
        requireUser(userId);
        RoomRuntime current = roomManager.findByUserId(userId);
        if (current != null) {
            return current;
        }

        List<RoomRuntime> candidates = roomManager.publicWaitingRooms().stream()
                .filter(runtime -> {
                    Room room = runtime.getRoom();
                    return (room.getPassword() == null || room.getPassword().isBlank())
                            && options.type().equals(room.getGameType())
                            && Integer.valueOf(options.capacity()).equals(room.getMaxPlayers())
                            && options.privateChat()==Boolean.TRUE.equals(room.getPrivateChatEnabled())
                            && !Boolean.TRUE.equals(room.getAllowAi())
                            && Integer.valueOf(options.wolves()).equals(room.getWerewolfCount())
                            && room.effectiveRoleCounts().equals(RoleConfiguration.defaults(options.capacity(),options.wolves()))
                            && Integer.valueOf(0).equals(room.getAiCount());
                })
                // 先填满已有房间，人数相同时优先等待更久的房间。
                .sorted(Comparator.comparingInt((RoomRuntime runtime) -> runtime.humanCount())
                        .reversed()
                        .thenComparingLong(RoomRuntime::getCreatedAt))
                .toList();
        for (RoomRuntime candidate : candidates) {
            try {
                return joinRoom(userId, candidate.getRoom().getRoomCode(), null);
            } catch (BizException ex) {
                // 候选房可能刚被手动加入、开始或解散，继续找下一个。
                if (ex.getErrorCode() != ErrorCode.ROOM_FULL
                        && ex.getErrorCode() != ErrorCode.ROOM_NOT_WAITING
                        && ex.getErrorCode() != ErrorCode.ROOM_NOT_FOUND
                        && ex.getErrorCode() != ErrorCode.ROOM_PASSWORD_WRONG) {
                    throw ex;
                }
            }
        }

        CreateRoomRequest request = new CreateRoomRequest();
        request.setRoomName("快速匹配房");
        request.setGameType(options.type());
        request.setPrivateChatEnabled(options.privateChat());
        request.setMaxPlayers(options.capacity());
        request.setWerewolfCount(options.wolves());
        request.setAiCount(0);
        request.setAllowAi(false);
        request.setPublicRoom(true);
        return createRoom(userId, request);
    }

    /** 退出房间；房主退出则解散房间（简化规则，避免房主转移的复杂度）。 */
    @Transactional
    public synchronized void leaveRoom(Long userId) {
        RoomRuntime runtime = roomManager.findByUserId(userId);
        if (runtime == null) {
            throw BizException.of(ErrorCode.NOT_IN_ROOM);
        }
        PlayerState player = runtime.getState().byUser(userId);
        boolean isHost = runtime.getRoom().getCreatedBy() != null
                && runtime.getRoom().getCreatedBy().equals(userId);
        runtime.removePlayer(userId);
        if (isHost) {
            dissolve(runtime, "房主已退出，房间解散");
            return;
        }
        persistSeats(runtime);
        // 通知还留在房间里的玩家（早期实现只更新数据库，别人的座位网格不会变）
        if (player != null) {
            roomEventPublisher.notice(runtime, player.getNickname() + " 离开了房间");
        }
        roomEventPublisher.broadcast(runtime);
    }

    @Transactional
    public synchronized void dissolve(RoomRuntime runtime, String reason) {
        Room room = runtime.getRoom();
        room.setGameStatus(Room.Status.ENDED.name());
        roomMapper.updateById(room);
        roomPlayerMapper.delete(Wrappers.<RoomPlayer>lambdaQuery()
                .eq(RoomPlayer::getRoomId, room.getId()));
        roomManager.destroy(room.getId());
        roomEventPublisher.reconcileVoice();
        log.info("房间解散: {} ({})", room.getRoomCode(), reason);
    }

    // ============================================================ 座位操作 ====

    public synchronized void setReady(Long userId, boolean ready) {
        RoomRuntime runtime = requireRuntimeOf(userId);
        if (runtime.isGameStarted() && runtime.getState().getPhase() != GamePhase.END) {
            throw BizException.of(ErrorCode.ROOM_NOT_WAITING, "游戏进行中无法修改准备状态");
        }
        PlayerState player = runtime.getState().byUser(userId);
        if (player == null) {
            throw BizException.of(ErrorCode.NOT_IN_ROOM);
        }
        player.setReady(ready);
        persistSeats(runtime);
        // 广播：其他人需要立刻看到"某人已准备"，而本人需要拿到自己的 iAmReady
        roomEventPublisher.broadcast(runtime);
    }

    /** 房主修改房间配置。 */
    @Transactional
    public synchronized RoomRuntime updateRoom(Long userId, UpdateRoomRequest request) {
        RoomRuntime runtime = requireRuntimeOf(userId);
        Room room = runtime.getRoom();
        requireHost(runtime, userId);
        if (runtime.isGameStarted() && runtime.getState().getPhase() != GamePhase.END) {
            throw BizException.of(ErrorCode.ROOM_NOT_WAITING, "游戏进行中无法修改房间配置");
        }
        int maxPlayers = request.getMaxPlayers() == null ? room.getMaxPlayers() : request.getMaxPlayers();
        int werewolfCount = request.getWerewolfCount() == null
                ? room.getWerewolfCount() : request.getWerewolfCount();
        int aiCount = request.getAiCount() == null ? room.getAiCount() : request.getAiCount();
        boolean allowAi=request.getAllowAi()!=null?request.getAllowAi()
                :request.getAiCount()!=null&&aiCount>0||Boolean.TRUE.equals(room.getAllowAi());
        if(aiCount<0)throw BizException.of(ErrorCode.ROOM_CONFIG_INVALID,"AI 数量不能为负");
        if(!allowAi)aiCount=0;
        int currentHumans = runtime.humanCount();
        if(aiCount+currentHumans>maxPlayers)throw BizException.of(ErrorCode.ROOM_CONFIG_INVALID,"AI 与真人总数不能超过房间最大人数");
        if (maxPlayers < currentHumans) {
            throw BizException.of(ErrorCode.ROOM_CONFIG_INVALID,
                    "房间人数不能小于当前真人玩家数(" + currentHumans + ")");
        }
        Map<String,Integer> counts = Boolean.TRUE.equals(request.getResetRoleCounts()) ? null
                : request.getRoleCounts()!=null ? request.getRoleCounts() : room.getRoleCounts();
        if (counts != null) {
            counts=validateRoles(maxPlayers,counts);
            werewolfCount=RoleConfiguration.wolves(counts);
            if(request.getRoleCounts()==null && request.getWerewolfCount()!=null && request.getWerewolfCount()!=werewolfCount)
                throw BizException.of(ErrorCode.ROOM_CONFIG_INVALID,"请通过身份配置修改狼人阵营人数，或恢复默认配置");
        }
        validateRoomConfiguration(room.getGameType(),maxPlayers,werewolfCount,aiCount,counts);

        if (request.getRoomName() != null && !request.getRoomName().isBlank()) {
            room.setRoomName(request.getRoomName().trim());
        }
        room.setRoleCounts(counts);
        room.setMaxPlayers(maxPlayers);
        room.setWerewolfCount(werewolfCount);
        room.setAllowAi(allowAi);
        room.setAiCount(aiCount);
        if(request.getAllowInvites()!=null)room.setAllowInvites(request.getAllowInvites());
        if(request.getVoiceEnabled()!=null)room.setVoiceEnabled(request.getVoiceEnabled());
        if(request.getPrivateChatEnabled()!=null)room.setPrivateChatEnabled(request.getPrivateChatEnabled());
        if (request.getPublicRoom() != null) {
            room.setPublicRoom(request.getPublicRoom());
        }
        if (Boolean.TRUE.equals(request.getClearPassword())) {
            room.setPassword(null);
        } else if (request.getPassword() != null && !request.getPassword().isBlank()) {
            room.setPassword(passwordEncoder.encode(request.getPassword()));
        }
        roomMapper.updateById(room);
        // AI 数量变化：按需增删 AI 座位
        int currentBots = runtime.getState().getPlayers().size() - runtime.humanCount();
        if (Boolean.TRUE.equals(room.getAllowAi()) && aiCount > currentBots) {
            addBots(runtime, aiCount - currentBots);
        } else if (aiCount < currentBots) {
            removeBots(runtime, currentBots - aiCount);
        }
        room.setAiCount(aiCount);
        roomMapper.updateById(room);
        persistSeats(runtime);
        runtime.touch();
        // 配置变了（人数/狼数/密码/是否公开），房间内所有人 + 大厅都要看到
        roomEventPublisher.broadcast(runtime);
        return runtime;
    }

    /** 房主踢人。 */
    public synchronized void kick(Long hostUserId, Integer seatId) {
        RoomRuntime runtime = requireRuntimeOf(hostUserId);
        requireHost(runtime, hostUserId);
        PlayerState target = runtime.getState().bySeat(seatId);
        if (target == null) {
            throw BizException.of(ErrorCode.GAME_TARGET_INVALID, "座位不存在");
        }
        if (target.getUserId() != null && target.getUserId().equals(hostUserId)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "房主不能踢出自己");
        }
        if (target.isAi()) {
            removeBots(runtime, 1);
            Room room = runtime.getRoom();
            room.setAiCount(Math.max(0, room.getAiCount() - 1));
            roomMapper.updateById(room);
            roomEventPublisher.broadcast(runtime);
            return;
        }
        runtime.kickSeat(target.getSeatId());
        runtime.removePlayer(target.getUserId());
        persistSeats(runtime);
        roomEventPublisher.notice(runtime, target.getNickname() + " 被房主移出房间");
        roomEventPublisher.broadcast(runtime);
    }

    private void addBots(RoomRuntime runtime, int count) {
        Room room = runtime.getRoom();
        if (!Boolean.TRUE.equals(room.getAllowAi())) {
            return;
        }
        for (int i = 0; i < count; i++) {
            int seat = runtime.nextFreeSeat();
            if (seat < 0) {
                break;
            }
            String name = botRoster.nextName();
            runtime.addBot(seat, name, botRoster.nextAvatar());
        }
        persistSeats(runtime);
        roomEventPublisher.broadcast(runtime);
    }

    private void removeBots(RoomRuntime runtime, int count) {
        List<PlayerState> bots = new ArrayList<>();
        for (PlayerState p : runtime.getState().getPlayers()) {
            if (p.isAi()) {
                bots.add(p);
            }
        }
        // 从后往前删，保持座位编号连续
        bots.sort((a, b) -> Integer.compare(b.getSeatId(), a.getSeatId()));
        for (int i = 0; i < Math.min(count, bots.size()); i++) {
            runtime.getState().getPlayers().remove(bots.get(i));
        }
        renumberSeats(runtime);
        persistSeats(runtime);
    }

    // ============================================================ 开局/结算 ==

    /** 房主开始游戏。 */
    public synchronized RoomRuntime startGame(Long userId) {
        RoomRuntime runtime = requireRuntimeOf(userId);
        requireHost(runtime, userId);
        Room room = runtime.getRoom();
        if (runtime.isGameStarted() && runtime.getState().getPhase() != GamePhase.END) {
            throw BizException.of(ErrorCode.GAME_ALREADY_STARTED);
        }
        int total = runtime.getState().getPlayers().size();
        if (total != room.getMaxPlayers()) throw BizException.of(ErrorCode.ROOM_CONFIG_INVALID,
                "身份配置为 " + room.getMaxPlayers() + " 人，当前入座 " + total + " 人，请补齐座位或调整本局人数");
        validateRoles(total, room.effectiveRoleCounts());
        // 收集"未准备"的玩家，一次性把所有名字告诉房主，
        // 避免他只看到一个名字、改完再点一次又报另一个。
        java.util.List<String> unready = new java.util.ArrayList<>();
        for (PlayerState p : runtime.getState().getPlayers()) {
            // 房主不需要"准备"：他是发起开局的人，再让自己准备一次纯属多余的仪式感，
            // 而且前端房主操作条里原本没有准备按钮，会导致房主把自己卡在"未准备"上。
            if (p.isAi() || p.getUserId() == null || p.getUserId().equals(room.getCreatedBy())) {
                continue;
            }
            if (!p.isReady()) {
                unready.add(p.getNickname());
            }
        }
        if (!unready.isEmpty()) {
            throw BizException.of(ErrorCode.PLAYER_NOT_READY,
                    "还有玩家未准备：" + String.join("、", unready));
        }
        if (runtime.getState().getPhase() == GamePhase.END) runtime.resetToWaiting();
        var result = runtime.startGame();
        if (!result.isOk()) {
            throw new BizException(result.getErrorCode(), result.getMessage());
        }
        persistSeats(runtime);
        log.info("房间 {} 开始游戏：{} 人，{} 狼", room.getRoomCode(), total, room.getWerewolfCount());
        return runtime;
    }

    /** 把一局结果写入 game_records，并更新用户战绩。 */
    @Transactional
    public void saveGameRecord(RoomRuntime runtime) {
        Room room = runtime.getRoom();
        var state = runtime.getState();
        GameRecord record = new GameRecord();
        record.setRoomId(room.getId());
        record.setRoomCode(room.getRoomCode());
        record.setWinner(state.getWinner());
        record.setRounds(state.getRound());
        record.setResult(winnerText(state.getWinner()));
        record.setCreatedAt(LocalDateTime.now());
        Map<String, Object> detail = new LinkedHashMap<>();
        List<Map<String, Object>> players = new ArrayList<>();
        for (PlayerState p : state.getPlayers()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seatId", p.getSeatId());
            m.put("nickname", p.getNickname());
            m.put("role", p.getRole() == null ? null : p.getRole().name());
            m.put("alive", p.isAlive());
            m.put("ai", p.isAi());
            players.add(m);
        }
        detail.put("players", players);
        detail.put("speeches", state.getSpeeches());
        try {
            record.setDetailJson(objectMapper.writeValueAsString(detail));
        } catch (Exception ex) {
            record.setDetailJson("{}");
        }
        gameRecordMapper.insert(record);

        // 更新真人玩家战绩
        for (PlayerState p : state.getPlayers()) {
            if (p.isAi() || p.getUserId() == null || p.getUserId() <= 0) {
                continue;
            }
            User user = userMapper.selectById(p.getUserId());
            if (user == null) {
                continue;
            }
            boolean win = p.getRole() != null && (state.belongsToLovers(p) ? "LOVERS".equals(state.getWinner()) : p.getRole().getCamp().name().equals(state.getWinner()));
            var stats = Wrappers.<User>lambdaUpdate().eq(User::getId, user.getId())
                    .setSql("total_games = COALESCE(total_games, 0) + 1");
            if (win) { stats.setSql("win_games = COALESCE(win_games, 0) + 1"); }
            userMapper.update(null, stats);
        }
    }

    /**
     * 结算文案。
     *
     * <p>注意：{@code GameState.winner} 存的是 {@link RoleType.Camp} 的枚举名（"WEREWOLF" / "VILLAGE"），
     * 不是中文。早期实现拿中文字面量去比较，导致狼人获胜时也被写成「好人阵营胜利」。
     */
    private String winnerText(String winner) {
        if (winner == null) {
            return "对局未产生胜方";
        }
        if (RoleType.Camp.WEREWOLF.name().equals(winner)) {
            return "狼人阵营胜利";
        }
        if (RoleType.Camp.VILLAGE.name().equals(winner)) {
            return "好人阵营胜利";
        }
        return "LOVERS".equals(winner)?"情侣阵营胜利":winner;
    }

    // ============================================================== 查询 ====

    public RoomRuntime requireRuntimeOf(Long userId) {
        RoomRuntime runtime = roomManager.findByUserId(userId);
        if (runtime == null) {
            throw BizException.of(ErrorCode.NOT_IN_ROOM);
        }
        return runtime;
    }

    public RoomRuntime requireByCode(String code) {
        return roomManager.requireByCode(normalizeCode(code));
    }

    /** 公开房间列表（大厅）。 */
    public List<RoomView> listPublicRooms(Long viewerUserId) {
        List<RoomView> list = new ArrayList<>();
        for (RoomRuntime runtime : roomManager.publicWaitingRooms()) {
            list.add(runtime.roomView(viewerUserId));
        }
        return list;
    }

    /** 当前用户所在房间。 */
    public RoomView currentRoom(Long userId) {
        RoomRuntime runtime = roomManager.findByUserId(userId);
        return runtime == null ? null : runtime.roomView(userId);
    }

    /** 当前用户所在房间的运行时（可能为 null）。 */
    public RoomRuntime currentRuntime(Long userId) {
        return roomManager.findByUserId(userId);
    }

    // ============================================================== 工具 ====

    private void requireHost(RoomRuntime runtime, Long userId) {
        if (runtime.getRoom().getCreatedBy() == null
                || !runtime.getRoom().getCreatedBy().equals(userId)) {
            throw BizException.of(ErrorCode.NOT_HOST);
        }
    }

    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    private String encodePassword(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return passwordEncoder.encode(raw);
    }

    private String normalizeCode(String code) {
        if (code == null) {
            throw BizException.of(ErrorCode.ROOM_NOT_FOUND);
        }
        return code.trim().toUpperCase();
    }

    private String generateRoomCode() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * 校验 人数 / 狼人数 / AI 数 的组合是否可开局。
     *
     * <p>狼人上限同时受两条约束（与 {@link RoleAssigner#maxWerewolfCount} 保持一致）：
     * <ol>
     *   <li>必须少于总人数的一半（否则狼人开局即达成人数压制）；</li>
     *   <li>必须为预言家和女巫各留 1 个座位（两者都是 P0 角色，4 人局也必须存在）。</li>
     * </ol>
     */
    public static void validateConfig(int maxPlayers, int werewolfCount, int aiCount) {
        if (maxPlayers < 4 || maxPlayers > 13) {
            throw BizException.of(ErrorCode.ROOM_CONFIG_INVALID, "房间人数必须在 4~13 之间");
        }
        if(maxPlayers==13 && werewolfCount!=4)throw BizException.of(ErrorCode.ROOM_CONFIG_INVALID,"13 人标准局固定为 4 狼（含白狼王）");
        int maxWolves = RoleAssigner.maxWerewolfCount(maxPlayers);
        if (werewolfCount < 1 || werewolfCount > maxWolves) {
            throw BizException.of(ErrorCode.ROOM_CONFIG_INVALID,
                    "狼人数量必须在 1~" + maxWolves + " 之间（需为预言家和女巫留出座位，且少于人数的一半）");
        }
        if (aiCount < 0 || aiCount > maxPlayers - 1) {
            throw BizException.of(ErrorCode.ROOM_CONFIG_INVALID,
                    "AI 数量必须在 0~" + (maxPlayers - 1) + " 之间（至少留 1 个真人座位）");
        }
        // 开局时 AI 占用的也是真实座位，因此只需保证"总座位能放下 狼人+预言家+女巫"
        if (maxPlayers < werewolfCount + RoleAssigner.REQUIRED_SPECIAL_ROLES) {
            throw BizException.of(ErrorCode.ROOM_CONFIG_INVALID,
                    "座位不足：需要为预言家和女巫各留 1 个座位，"
                            + maxPlayers + " 人局最多 " + (maxPlayers - RoleAssigner.REQUIRED_SPECIAL_ROLES)
                            + " 只狼");
        }
    }

    private Map<String,Integer> validateRoles(int total,Map<String,Integer> counts) {
        try { return RoleConfiguration.validate(total,counts); }
        catch(IllegalArgumentException ex) { throw BizException.of(ErrorCode.ROOM_CONFIG_INVALID,ex.getMessage()); }
    }

    private void validateRoomConfiguration(String type,int total,int wolves,int bots,Map<String,Integer> counts) {
        Map<String,Object> config=new LinkedHashMap<>();
        config.put("maxPlayers",total);config.put("werewolfCount",wolves);config.put("aiCount",bots);
        if(counts!=null)config.put("roleCounts",counts);
        games.require(type).validateConfiguration(config);
    }

    /** 暴露给前端/测试：给定人数的狼人数量上限。 */
    public static int maxWerewolfCount(int maxPlayers) {
        return RoleAssigner.maxWerewolfCount(maxPlayers);
    }

    /** 座位变化后重新编号（AI 删除后可能出现空洞）。 */
    private void renumberSeats(RoomRuntime runtime) {
        List<PlayerState> players = runtime.getState().getPlayers();
        players.sort((a, b) -> Integer.compare(a.getSeatId(), b.getSeatId()));
        // PlayerState.seatId 是 final，只能通过重建对象实现重排
        List<PlayerState> rebuilt = new ArrayList<>();
        int seat = 1;
        for (PlayerState p : players) {
            if (p.getSeatId() == seat) {
                rebuilt.add(p);
            } else {
                PlayerState clone = new PlayerState(seat, p.getUserId(), p.getNickname(),
                        p.getAvatar(), p.isAi());
                clone.setReady(p.isReady());
                clone.setAlive(p.isAlive());
                clone.setRole(p.getRole());
                rebuilt.add(clone);
            }
            seat++;
        }
        players.clear();
        players.addAll(rebuilt);
    }

    /** 把当前座位快照写入 room_players（真实身份只在结束后回写）。 */
    private void persistSeats(RoomRuntime runtime) {
        Room room = runtime.getRoom();
        roomPlayerMapper.delete(Wrappers.<RoomPlayer>lambdaQuery()
                .eq(RoomPlayer::getRoomId, room.getId()));
        boolean revealRoles = runtime.getState().getPhase() == GamePhase.END;
        for (PlayerState p : runtime.getState().getPlayers()) {
            RoomPlayer rp = new RoomPlayer();
            rp.setRoomId(room.getId());
            rp.setUserId(p.getUserId() == null ? 0L : p.getUserId());
            rp.setNickname(p.getNickname());
            rp.setSeatId(p.getSeatId());
            rp.setIsAi(p.isAi());
            rp.setIsAlive(p.isAlive());
            rp.setReady(p.isReady());
            rp.setRole(revealRoles && p.getRole() != null ? p.getRole().name() : null);
            rp.setJoinedAt(LocalDateTime.now());
            roomPlayerMapper.insert(rp);
        }
    }

    /** 定时任务可能会用到：导出调试信息。 */
    public Map<String, Object> debugInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("activeRooms", roomManager.size());
        List<Map<String, Object>> rooms = new ArrayList<>();
        for (RoomRuntime runtime : roomManager.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("roomCode", runtime.getRoom().getRoomCode());
            m.put("phase", runtime.getState().getPhase().name());
            m.put("players", runtime.getState().getPlayers().size());
            rooms.add(m);
        }
        info.put("rooms", rooms);
        return info;
    }

    /** 供 WebSocket 层在游戏结束时触发落库（由 RoomManager 回调）。 */
    @Override
    public void onGameEnd(Room room, RoomRuntime runtime) {
        try {
            persistSeats(runtime);
            saveGameRecord(runtime);
        } catch (Exception ex) {
            log.error("保存对局记录失败 roomCode={}", room.getRoomCode(), ex);
        }
    }
}
