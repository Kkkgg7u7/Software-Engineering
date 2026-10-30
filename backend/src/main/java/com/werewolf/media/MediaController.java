package com.werewolf.media;
import com.werewolf.auth.security.CurrentUser;
import com.werewolf.common.ApiResponse;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/media")
public class MediaController {
    private final MediaService media;
    public MediaController(MediaService media){this.media=media;}
    @PostMapping(value="/images",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<MediaService.ImageView> upload(@RequestPart("file") MultipartFile file,@RequestParam(defaultValue="PRIVATE") String purpose){return ApiResponse.ok(media.upload(CurrentUser.id(),file,purpose));}
    @GetMapping("/{id}/content")
    public ResponseEntity<Resource> content(@PathVariable String id){return response(media.content(id,CurrentUser.id(),false),id,false);}
    @GetMapping("/avatars/{id}")
    public ResponseEntity<Resource> avatar(@PathVariable String id){return response(media.content(id,null,true),id,true);}
    private ResponseEntity<Resource> response(Resource resource,String id,boolean publicAvatar){return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header("X-Content-Type-Options","nosniff").header("Cache-Control",publicAvatar?"public, max-age=3600":"private, no-store").header("Content-Disposition","inline; filename=\""+id+".png\"").body(resource);}
}
