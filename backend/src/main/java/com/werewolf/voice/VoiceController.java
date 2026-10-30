package com.werewolf.voice;
import com.werewolf.auth.security.CurrentUser;
import com.werewolf.common.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/rooms/{code}/voice")
public class VoiceController {
 private final VoiceService voice;
 public VoiceController(VoiceService voice){this.voice=voice;}
 public record Join(String clientId){} public record Session(String sessionId,boolean muted){}
 public record Signal(String sessionId,Long targetUserId,String targetSessionId,String type,JsonNode payload){}
 @GetMapping public ApiResponse<?> state(@PathVariable String code){return ApiResponse.ok(voice.state(code,CurrentUser.id()));}
 @PostMapping("/join") public ApiResponse<?> join(@PathVariable String code,@RequestBody Join body){return ApiResponse.ok(voice.join(code,CurrentUser.id(),body.clientId()));}
 @PostMapping("/heartbeat") public ApiResponse<?> beat(@PathVariable String code,@RequestBody Session body){return ApiResponse.ok(voice.heartbeat(code,CurrentUser.id(),body.sessionId(),body.muted()));}
 @PostMapping("/leave") public ApiResponse<?> leave(@PathVariable String code,@RequestBody Session body){voice.leave(code,CurrentUser.id(),body.sessionId());return ApiResponse.ok();}
 @PostMapping("/signal") public ApiResponse<?> signal(@PathVariable String code,@RequestBody Signal body){voice.signal(code,CurrentUser.id(),body);return ApiResponse.ok();}
}
