package com.werewolf.websocket;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.werewolf.common.BizException;
import com.werewolf.common.ErrorCode;
import com.werewolf.game.domain.PlayerState;
import com.werewolf.room.RoomService;
import com.werewolf.room.entity.Room;
import com.werewolf.room.runtime.RoomRuntime;
import com.werewolf.websocket.entity.Message;
import com.werewolf.websocket.mapper.MessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 局内聊天服务（成员 C 负责模块）。
 *
 * <p>三种频道（方案第七节）：
 * <ul>
 *   <li><b>PUBLIC</b>：房间内所有玩家可见；</li>
 *   <li><b>PRIVATE</b>：私聊，仅发送者与接收者可见（receiverId 非空）；</li>
 *   <li><b>WOLF</b>：狼人频道，仅狼人阵营可见（含 AI 狼）。</li>
 * </ul>
 *
 * <p>聊天记录与 room_id 绑定，落库 messages 表。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int MAX_CONTENT_LENGTH = 500;

    private final MessageMapper messageMapper;
    private final RoomService roomService;
    private final GameEventMessenger messenger;

    public ChatService(MessageMapper messageMapper, RoomService roomService,
                       GameEventMessenger messenger) {
        this.messageMapper = messageMapper;
        this.roomService = roomService;
        this.messenger = messenger;
    }

    /** 聊天请求体（STOMP 与 REST 共用）。 */
    public static class ChatRequest {
        private String content;
        /** 私聊接收者 userId；为空表示公共聊天。 */
        private Long receiverId;
        /** PUBLIC / PRIVATE / WOLF，缺省 PUBLIC。 */
        private String type;

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public Long getReceiverId() {
            return receiverId;
        }

        public void setReceiverId(Long receiverId) {
            this.receiverId = receiverId;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }
    }

    /**
     * 处理一条聊天消息：校验权限 -> 落库 -> 按频道推送给正确的接收者。
     */
    public Message send(Long senderUserId, String roomCode, ChatRequest request) {
        if (request.getContent() == null || request.getContent().isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "消息内容不能为空");
        }
        RoomRuntime runtime = roomService.requireByCode(roomCode);
        Room room = runtime.getRoom();
        PlayerState sender = runtime.getState().byUser(senderUserId);
        if (sender == null) {
            throw BizException.of(ErrorCode.NOT_IN_ROOM);
        }
        String content = request.getContent().trim();
        if (content.length() > MAX_CONTENT_LENGTH) {
            content = content.substring(0, MAX_CONTENT_LENGTH);
        }

        Message.MessageType type = resolveType(request, runtime, sender);
        if (type == Message.MessageType.PRIVATE) {
            if(!Boolean.TRUE.equals(room.getPrivateChatEnabled()))throw BizException.of(ErrorCode.FORBIDDEN,"房主已关闭局内私聊");
            if (request.getReceiverId() == null
                    || runtime.getState().byUser(request.getReceiverId()) == null) {
                throw BizException.of(ErrorCode.GAME_TARGET_INVALID, "私聊对象不在房间内");
            }
        }

        Message message = new Message();
        message.setRoomId(room.getId());
        message.setSenderId(senderUserId);
        message.setSenderNickname(sender.getNickname());
        message.setIsAi(sender.isAi());
        message.setContent(content);
        message.setMessageType(type.name());
        message.setChannel(runtime.getState().getPhase() == com.werewolf.game.domain.GamePhase.WAITING
                ? Message.Channel.LOBBY.name() : Message.Channel.GAME.name());
        message.setReceiverId(type == Message.MessageType.PRIVATE ? request.getReceiverId() : null);
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);

        broadcast(runtime, message, sender);
        return message;
    }

    private Message.MessageType resolveType(ChatRequest request, RoomRuntime runtime,
                                            PlayerState sender) {
        String raw = request.getType() == null ? "" : request.getType().trim().toUpperCase();
        if ("WOLF".equals(raw)) {
            // 狼人频道：只有在游戏进行中且发送者是狼人才允许
            if (!sender.isWolf()) {
                throw BizException.of(ErrorCode.GAME_ROLE_NOT_ALLOWED, "你不是狼人，无法使用狼人频道");
            }
            return Message.MessageType.WOLF;
        }
        if ("PRIVATE".equals(raw) || request.getReceiverId() != null) {
            return Message.MessageType.PRIVATE;
        }
        return Message.MessageType.PUBLIC;
    }

    private void broadcast(RoomRuntime runtime, Message message, PlayerState sender) {
        String roomCode = runtime.getRoom().getRoomCode();
        switch (Message.MessageType.valueOf(message.getMessageType())) {
            case PRIVATE -> {
                // 私聊：只推给发送者与接收者
                messenger.privateChat(message.getSenderId(), roomCode, message);
                if (message.getReceiverId() != null
                        && !message.getReceiverId().equals(message.getSenderId())) {
                    messenger.privateChat(message.getReceiverId(), roomCode, message);
                }
            }
            case WOLF -> {
                // 狼人频道：推给所有存活狼人（含 AI 狼，便于前端展示“狼队讨论”）
                for (PlayerState p : runtime.getState().getPlayers()) {
                    if (p.isWolf() && !p.isAi() && p.getUserId() != null && p.getUserId() > 0) {
                        messenger.privateChat(p.getUserId(), roomCode, message);
                    }
                }
            }
            default -> messenger.chat(runtime.getRoom(), message);
        }
        log.debug("聊天消息已投递 room={} type={} sender={}", roomCode, message.getMessageType(),
                sender.getSeatId());
    }

    /** 最近聊天记录（进房时拉取历史）。 */
    public List<Message> history(Long userId, String roomCode, int limit) {
        RoomRuntime runtime = roomService.requireByCode(roomCode);
        PlayerState me = runtime.getState().byUser(userId);
        if (me == null) {
            throw BizException.of(ErrorCode.NOT_IN_ROOM);
        }
        int size = Math.min(Math.max(limit, 1), 200);
        List<Message> all = messageMapper.selectList(Wrappers.<Message>lambdaQuery()
                .eq(Message::getRoomId, runtime.getRoom().getId())
                .orderByDesc(Message::getId)
                .last("LIMIT " + size));
        List<Message> visible = new ArrayList<>();
        Set<Long> wolfUserIds = new LinkedHashSet<>();
        for (PlayerState p : runtime.getState().getPlayers()) {
            if (p.isWolf() && p.getUserId() != null) {
                wolfUserIds.add(p.getUserId());
            }
        }
        for (Message m : all) {
            switch (Message.MessageType.valueOf(m.getMessageType())) {
                case PUBLIC, SYSTEM -> visible.add(m);
                case WOLF -> {
                    if (wolfUserIds.contains(userId)) {
                        visible.add(m);
                    }
                }
                case PRIVATE -> {
                    if (userId.equals(m.getSenderId()) || userId.equals(m.getReceiverId())) {
                        visible.add(m);
                    }
                }
                default -> {
                    // 忽略未知类型
                }
            }
        }
        visible.sort((a, b) -> Long.compare(
                a.getId() == null ? 0 : a.getId(), b.getId() == null ? 0 : b.getId()));
        return visible;
    }

    /** 系统消息：座位变化、准备状态等，不落库也可（这里落库便于复盘）。 */
    public Message systemMessage(RoomRuntime runtime, String content) {
        Message message = new Message();
        message.setRoomId(runtime.getRoom().getId());
        message.setSenderId(0L);
        message.setSenderNickname("系统");
        message.setIsAi(false);
        message.setContent(content);
        message.setMessageType(Message.MessageType.SYSTEM.name());
        message.setChannel(Message.Channel.LOBBY.name());
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);
        messenger.chat(runtime.getRoom(), message);
        return message;
    }
}
