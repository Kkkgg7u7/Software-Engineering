package com.werewolf.friendship;
import com.werewolf.auth.security.CurrentUser;
import com.werewolf.common.*;
import com.werewolf.user.UserDirectoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/friends")
public class FriendController {
    private final FriendService friends;
    public FriendController(FriendService friends){this.friends=friends;}
    public record Add(@NotNull @Positive Long targetUserId) { }
    @PostMapping("/requests") public ApiResponse<FriendService.RequestView> request(@Valid @RequestBody Add body){return ApiResponse.ok(friends.request(CurrentUser.id(),body.targetUserId()));}
    @GetMapping public ApiResponse<PageResult<UserDirectoryService.PublicUser>> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.ok(friends.friends(CurrentUser.id(),page,size));}
    @GetMapping("/requests") public ApiResponse<PageResult<FriendService.RequestView>> requests(@RequestParam(defaultValue="incoming") String direction,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.ok(friends.requests(CurrentUser.id(),direction,page,size));}
    @PostMapping("/requests/{id}/accept") public ApiResponse<FriendService.RequestView> accept(@PathVariable Long id){return ApiResponse.ok(friends.respond(CurrentUser.id(),id,true));}
    @PostMapping("/requests/{id}/reject") public ApiResponse<FriendService.RequestView> reject(@PathVariable Long id){return ApiResponse.ok(friends.respond(CurrentUser.id(),id,false));}
    @DeleteMapping("/{id}") public ApiResponse<Void> remove(@PathVariable Long id){friends.remove(CurrentUser.id(),id);return ApiResponse.ok();}
}
