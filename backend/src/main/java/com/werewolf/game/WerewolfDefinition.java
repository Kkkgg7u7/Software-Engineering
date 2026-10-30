package com.werewolf.game;
import com.werewolf.platform.GameDefinition;
import com.werewolf.room.RoomService;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class WerewolfDefinition implements GameDefinition {
 private final com.werewolf.room.runtime.WerewolfGameEngineFactory engines;
 private final com.werewolf.room.runtime.GameBroadcaster broadcaster;
 private final com.werewolf.ai.AiService ai;
 private final com.werewolf.config.AppProperties properties;
 public WerewolfDefinition(com.werewolf.room.runtime.WerewolfGameEngineFactory engines,com.werewolf.room.runtime.GameBroadcaster broadcaster,com.werewolf.ai.AiService ai,com.werewolf.config.AppProperties properties){this.engines=engines;this.broadcaster=broadcaster;this.ai=ai;this.properties=properties;}
 public com.werewolf.room.runtime.RoomRuntime createRuntime(com.werewolf.room.entity.Room room){
  var state=new com.werewolf.game.domain.GameState(room.getId(),room.getRoomCode());
  return new com.werewolf.room.runtime.RoomRuntime(room,engines.create(state),broadcaster,ai,properties.getPhase());
 }

 public Metadata metadata(){return new Metadata("WEREWOLF","狼人杀","月圆之夜，用发言寻找真相。与朋友一起体验隐藏身份、阵营推理和实时对局。","阵营推理","AVAILABLE","/assets/art/scenes/game.jpg",List.of(4,6,8,12,13),4,13,List.of("服务器统一分配隐藏身份，按个人权限展示。","狼人全员一致才击杀；守卫不可连守、同守同救死亡；女巫仅首夜可自救，可同夜双药。","13人标准局：白狼王1、狼人3、预言家、女巫、猎人、守卫、白痴、爱神各1、平民3；警长选举产生。","猎人被毒或殉情不开枪；白狼王仅被放逐时带人；警长1.5票；跨阵营情侣两人独活获胜。"),"/lobby");}
 public void validateConfiguration(Map<String,Object> c){
  int players=((Number)c.get("maxPlayers")).intValue(),bots=((Number)c.get("aiCount")).intValue();
  if(c.containsKey("roleCounts")){
   try{
    @SuppressWarnings("unchecked") var counts=(Map<String,Integer>)c.get("roleCounts");
    com.werewolf.game.domain.RoleConfiguration.validate(players,counts);
    if(bots<0||bots>=players)throw new IllegalArgumentException("AI 数量必须在 0～"+(players-1)+" 之间");
   }catch(IllegalArgumentException ex){throw com.werewolf.common.BizException.of(com.werewolf.common.ErrorCode.ROOM_CONFIG_INVALID,ex.getMessage());}
  }else RoomService.validateConfig(players,((Number)c.get("werewolfCount")).intValue(),bots);
 }
}
