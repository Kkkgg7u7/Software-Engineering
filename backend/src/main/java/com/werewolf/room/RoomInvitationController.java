package com.werewolf.room;
import com.werewolf.auth.security.CurrentUser;
import com.werewolf.common.*;
import com.werewolf.conversation.ConversationService;
import com.werewolf.room.entity.Room;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/rooms")
public class RoomInvitationController {
 private final RoomService rooms;private final ConversationService conversations;
 public RoomInvitationController(RoomService r,ConversationService c){rooms=r;conversations=c;}
 public record Invite(@Positive Long conversationId,@Positive Long targetUserId,@NotBlank String clientMessageId){}
 @PostMapping("/{code}/invite") public ApiResponse<ConversationService.MessageView> invite(@PathVariable String code,@Valid @RequestBody Invite b){Long uid=CurrentUser.id();var runtime=rooms.requireByCode(code);if(runtime.getState().byUser(uid)==null)throw BizException.of(ErrorCode.NOT_IN_ROOM);var room=runtime.getRoom();if(!Boolean.TRUE.equals(room.getAllowInvites()))throw BizException.of(ErrorCode.FORBIDDEN,"房主已关闭房间邀请");if(!Room.Status.WAITING.name().equals(room.getGameStatus()))throw BizException.of(ErrorCode.ROOM_NOT_WAITING);return ApiResponse.ok(conversations.gameInvite(uid,b.conversationId(),b.targetUserId(),b.clientMessageId(),room.getRoomCode(),room.getRoomName()));}
}
