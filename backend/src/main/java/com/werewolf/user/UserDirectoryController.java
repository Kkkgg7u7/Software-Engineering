package com.werewolf.user;
import com.werewolf.common.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserDirectoryController {
    private final UserDirectoryService directory;
    public UserDirectoryController(UserDirectoryService directory) {this.directory=directory;}
    @GetMapping("/search")
    public ApiResponse<PageResult<UserDirectoryService.PublicUser>> search(@RequestParam String q,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(directory.search(q,page,size));
    }
    @GetMapping("/{id}")
    public ApiResponse<UserDirectoryService.PublicUser> profile(@PathVariable Long id) {return ApiResponse.ok(directory.profile(id));}
}
