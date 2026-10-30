package com.werewolf.community;
import com.werewolf.auth.security.CurrentUser;
import com.werewolf.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/api/posts")
public class CommunityController {
 private final CommunityService service;public CommunityController(CommunityService s){service=s;}
 public record Create(@NotBlank String clientRequestId,String content,@Size(max=9) List<@NotBlank String> mediaIds){}
 public record Comment(@NotBlank String clientRequestId,@NotBlank String content){}
 @GetMapping public ApiResponse<CommunityService.Feed<CommunityService.View>> feed(@RequestParam(required=false) Long authorId,@RequestParam(required=false) Long beforeId,@RequestParam(defaultValue="12")int limit){return ApiResponse.ok(service.feed(CurrentUser.id(),authorId,beforeId,limit));}
 @GetMapping("/{id}") public ApiResponse<CommunityService.View> get(@PathVariable Long id){return ApiResponse.ok(service.get(CurrentUser.id(),id));}
 @PostMapping public ApiResponse<CommunityService.View> create(@Valid @RequestBody Create b){return ApiResponse.ok(service.create(CurrentUser.id(),b.clientRequestId(),b.content(),b.mediaIds()));}
 @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable Long id){service.delete(CurrentUser.id(),id);return ApiResponse.ok();}
 @PutMapping("/{id}/like") public ApiResponse<CommunityService.View> like(@PathVariable Long id){return ApiResponse.ok(service.like(CurrentUser.id(),id,true));}
 @DeleteMapping("/{id}/like") public ApiResponse<CommunityService.View> unlike(@PathVariable Long id){return ApiResponse.ok(service.like(CurrentUser.id(),id,false));}
 @GetMapping("/{id}/comments") public ApiResponse<CommunityService.Feed<CommunityService.CommentView>> comments(@PathVariable Long id,@RequestParam(required=false)Long beforeId,@RequestParam(defaultValue="20")int limit){return ApiResponse.ok(service.comments(CurrentUser.id(),id,beforeId,limit));}
 @PostMapping("/{id}/comments") public ApiResponse<CommunityService.CommentView> comment(@PathVariable Long id,@Valid @RequestBody Comment b){return ApiResponse.ok(service.comment(CurrentUser.id(),id,b.clientRequestId(),b.content()));}
 @DeleteMapping("/{postId}/comments/{id}") public ApiResponse<Void> deleteComment(@PathVariable Long postId,@PathVariable Long id){service.deleteComment(CurrentUser.id(),postId,id);return ApiResponse.ok();}
}
