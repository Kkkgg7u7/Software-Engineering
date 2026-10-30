package com.werewolf.platform;
import com.werewolf.common.*;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class GameRegistry {
 private final Map<String,GameDefinition> modules=new LinkedHashMap<>();
 public GameRegistry(List<GameDefinition> definitions){for(var d:definitions)if(modules.putIfAbsent(d.metadata().type(),d)!=null)throw new IllegalStateException("Duplicate game type: "+d.metadata().type());}
 public GameDefinition require(String type){var d=modules.get(type);if(d==null)throw BizException.of(ErrorCode.BAD_REQUEST,"未注册的游戏类型");if(!"AVAILABLE".equals(d.metadata().status()))throw BizException.of(ErrorCode.BAD_REQUEST,"游戏暂不可用");return d;}
 public List<GameDefinition.Metadata> list(){return modules.values().stream().map(GameDefinition::metadata).toList();}
}
