package com.werewolf.websocket;

import com.werewolf.auth.security.CurrentUser;
import com.werewolf.common.ApiResponse;
import com.werewolf.websocket.entity.Message;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 聊天 REST 接口（成员 C 负责模块）。
 *
 * <p>实时聊天走 STOMP（{@code /app/chat/{roomCode}/send}），
 * 这里提供历史记录与 HTTP 兜底发送（便于 curl 测试与自动化用例）。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /** 历史消息（已按可见性过滤）。 */
    @GetMapping("/{roomCode}/history")
    public ApiResponse<List<Message>> history(@PathVariable String roomCode,
                                             @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(chatService.history(CurrentUser.id(), roomCode, limit));
    }

    /** HTTP 发送消息（与 STOMP 走同一套校验与投递逻辑）。 */
    @PostMapping("/{roomCode}/send")
    public ApiResponse<Message> send(@PathVariable String roomCode,
                                     @RequestBody ChatService.ChatRequest request) {
        return ApiResponse.ok(chatService.send(CurrentUser.id(), roomCode, request));
    }
}
