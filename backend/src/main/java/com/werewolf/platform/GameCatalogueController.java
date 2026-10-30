package com.werewolf.platform;
import com.werewolf.common.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/api/games")
public class GameCatalogueController {
 private final GameRegistry games;public GameCatalogueController(GameRegistry g){games=g;}
 @GetMapping public ApiResponse<List<GameDefinition.Metadata>> list(){return ApiResponse.ok(games.list());}
 @GetMapping("/{type}") public ApiResponse<GameDefinition.Metadata> detail(@PathVariable String type){return ApiResponse.ok(games.require(type).metadata());}
}
