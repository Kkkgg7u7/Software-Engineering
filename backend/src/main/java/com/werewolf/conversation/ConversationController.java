package com.werewolf.conversation;
import com.werewolf.auth.security.CurrentUser;
import com.werewolf.common.*;
import com.werewolf.user.UserDirectoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/api")
public class ConversationController {
 private final ConversationService service;
 public ConversationController(ConversationService s){service=s;}
 public record Direct(@NotNull @Positive Long targetUserId){}
 public record Send(@NotBlank String clientMessageId,@NotBlank String contentType,String content,String mediaId){}
 public record Read(Long messageId){}
 public record Name(@NotBlank String name){}
 public record Join(@NotBlank String code){}
 public record Invite(@NotNull @Positive Long targetUserId,@NotBlank String clientMessageId){}
 @GetMapping("/conversations") public ApiResponse<PageResult<ConversationService.View>> list(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return ApiResponse.ok(service.list(CurrentUser.id(),page,size));}
 @PostMapping("/conversations/direct") public ApiResponse<ConversationService.View> direct(@Valid @RequestBody Direct b){return ApiResponse.ok(service.direct(CurrentUser.id(),b.targetUserId()));}
 @GetMapping("/conversations/{id}") public ApiResponse<ConversationService.View> get(@PathVariable Long id){return ApiResponse.ok(service.get(CurrentUser.id(),id));}
 @GetMapping("/conversations/{id}/messages") public ApiResponse<ConversationService.History> history(@PathVariable Long id,@RequestParam(required=false)Long beforeId,@RequestParam(defaultValue="30")int limit){return ApiResponse.ok(service.history(CurrentUser.id(),id,beforeId,limit));}
 @PostMapping("/conversations/{id}/messages") public ApiResponse<ConversationService.MessageView> send(@PathVariable Long id,@Valid @RequestBody Send b){return ApiResponse.ok(service.send(CurrentUser.id(),id,b.clientMessageId(),b.contentType(),b.content(),b.mediaId()));}
 @PostMapping("/conversations/{id}/read") public ApiResponse<Void> read(@PathVariable Long id,@RequestBody Read b){service.read(CurrentUser.id(),id,b.messageId());return ApiResponse.ok();}
 @PostMapping("/groups") public ApiResponse<ConversationService.View> create(@Valid @RequestBody Name b){return ApiResponse.ok(service.createGroup(CurrentUser.id(),b.name()));}
 @PostMapping("/groups/join") public ApiResponse<ConversationService.View> join(@Valid @RequestBody Join b){return ApiResponse.ok(service.join(CurrentUser.id(),b.code()));}
 @GetMapping("/groups/{id}/members") public ApiResponse<List<UserDirectoryService.PublicUser>> members(@PathVariable Long id){return ApiResponse.ok(service.groupMembers(CurrentUser.id(),id));}
 @PutMapping("/groups/{id}") public ApiResponse<ConversationService.View> rename(@PathVariable Long id,@Valid @RequestBody Name b){return ApiResponse.ok(service.rename(CurrentUser.id(),id,b.name()));}
 @PostMapping("/groups/{id}/leave") public ApiResponse<Void> leave(@PathVariable Long id){service.leave(CurrentUser.id(),id);return ApiResponse.ok();}
 @PostMapping("/groups/{id}/invite") public ApiResponse<ConversationService.MessageView> invite(@PathVariable Long id,@Valid @RequestBody Invite b){return ApiResponse.ok(service.invite(CurrentUser.id(),id,b.targetUserId(),b.clientMessageId()));}
}
