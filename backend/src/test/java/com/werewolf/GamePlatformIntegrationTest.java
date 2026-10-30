package com.werewolf;
import com.fasterxml.jackson.databind.*;
import com.werewolf.room.RoomService;
import com.werewolf.room.runtime.RoomManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties="werewolf.test.platform-isolation=true") @AutoConfigureMockMvc @ActiveProfiles("h2")
class GamePlatformIntegrationTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired RoomService rooms;@Autowired RoomManager manager;
 record User(long id,String token){}
 @AfterEach void clear(){for(var r:manager.all())rooms.dissolve(r,"platform test cleanup");}
 JsonNode call(String method,String path,String token,Object body)throws Exception{var req=MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method),path);if(token!=null)req.header("Authorization","Bearer "+token);if(body!=null)req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));return json.readTree(mvc.perform(req).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));}
 JsonNode ok(String method,String path,User u,Object body)throws Exception{var r=call(method,path,u.token(),body);assertEquals(0,r.path("code").asInt(),r.toString());return r.path("data");}
 User user()throws Exception{String name="gp"+Long.toUnsignedString(System.nanoTime(),36);var r=call("POST","/api/auth/register",null,Map.of("username",name,"nickname",name,"password","demo1234"));assertEquals(0,r.path("code").asInt());return new User(r.path("data").path("user").path("id").asLong(),r.path("data").path("token").asText());}
 void friend(User a,User b)throws Exception{long id=ok("POST","/api/friends/requests",a,Map.of("targetUserId",b.id())).path("id").asLong();ok("POST","/api/friends/requests/"+id+"/accept",b,null);}
 JsonNode create(User u,Map<String,Object> options)throws Exception{var data=new HashMap<String,Object>(Map.of("roomName","platform room","maxPlayers",4,"werewolfCount",1,"aiCount",0,"allowAi",false));data.putAll(options);return ok("POST","/api/rooms",u,data);}
 @Test void catalogueAndRoomMetadataUseRegisteredGame()throws Exception{var a=user();var games=ok("GET","/api/games",a,null);assertEquals(1,games.size());assertEquals("WEREWOLF",games.get(0).path("type").asText());assertEquals("AVAILABLE",games.get(0).path("status").asText());assertEquals(1001,call("GET","/api/games",null,null).path("code").asInt());assertEquals(1000,call("POST","/api/rooms",a.token(),Map.of("roomName","invalid","gameType","UNKNOWN")).path("code").asInt());var room=create(a,Map.of("allowInvites",false,"privateChatEnabled",false));assertEquals("WEREWOLF",room.path("gameType").asText());assertFalse(room.path("allowInvites").asBoolean());assertFalse(room.path("configuration").path("privateChatEnabled").asBoolean());assertFalse(room.has("password"));assertTrue(room.has("createdAt"));}
 @Test void configuredMatchJoinsOnlyExactCompatibleRoom()throws Exception{var host=user();var other=user();var guest=user();var standard=create(other,Map.of());var eight=create(host,Map.of("maxPlayers",8,"werewolfCount",2,"privateChatEnabled",false));var matched=ok("POST","/api/rooms/match",guest,Map.of("gameType","WEREWOLF","maxPlayers",8,"werewolfCount",2,"privateChatEnabled",false));assertEquals(eight.path("roomCode"),matched.path("roomCode"));assertNotEquals(standard.path("roomCode"),matched.path("roomCode"));assertEquals(matched.path("roomCode"),ok("POST","/api/rooms/match",guest,Map.of("maxPlayers",4,"werewolfCount",1)).path("roomCode"));var fresh=user();var newRoom=ok("POST","/api/rooms/match",fresh,Map.of("maxPlayers",6,"werewolfCount",1,"privateChatEnabled",true));assertEquals(6,newRoom.path("maxPlayers").asInt());assertEquals(1000,call("POST","/api/rooms/match",user().token(),Map.of("gameType","UNKNOWN")).path("code").asInt());}
 @Test void invitationsGoToPrivateAndGroupConversationsWithoutBypassingPassword()throws Exception{var a=user();var b=user();var c=user();friend(a,b);var room=create(a,Map.of("password","secret","publicRoom",false));String code=room.path("roomCode").asText();var card=ok("POST","/api/rooms/"+code+"/invite",a,Map.of("targetUserId",b.id(),"clientMessageId","room-invite1"));assertEquals("GAME_INVITE",card.path("contentType").asText());assertEquals(code,card.path("relatedCode").asText());assertFalse(card.toString().contains("secret"));assertEquals(card.path("id"),ok("POST","/api/rooms/"+code+"/invite",a,Map.of("targetUserId",b.id(),"clientMessageId","room-invite1")).path("id"));assertNotEquals(0,call("POST","/api/rooms/join",b.token(),Map.of("roomCode",code)).path("code").asInt());ok("POST","/api/rooms/join",b,Map.of("roomCode",code,"password","secret"));var group=ok("POST","/api/groups",a,Map.of("name","invite test"));long gid=group.path("id").asLong();ok("POST","/api/groups/join",b,Map.of("code",group.path("code").asText()));var groupCard=ok("POST","/api/rooms/"+code+"/invite",a,Map.of("conversationId",gid,"clientMessageId","group-room01"));assertEquals(gid,groupCard.path("conversationId").asLong());assertEquals(1,ok("GET","/api/conversations/"+gid+"/messages",b,null).path("items").size());assertNotEquals(0,call("POST","/api/rooms/"+code+"/invite",c.token(),Map.of("targetUserId",b.id(),"clientMessageId","outsider-01")).path("code").asInt());var unrelatedGroup=ok("POST","/api/groups",c,Map.of("name","private group"));assertEquals(1002,call("POST","/api/rooms/"+code+"/invite",a.token(),Map.of("conversationId",unrelatedGroup.path("id").asLong(),"clientMessageId","group-forge1")).path("code").asInt());ok("PUT","/api/rooms/"+code,a,Map.of("allowInvites",false));assertEquals(1002,call("POST","/api/rooms/"+code+"/invite",a.token(),Map.of("targetUserId",b.id(),"clientMessageId","disabled-01")).path("code").asInt());}
 @Test void privateRoomChatToggleAndMutationPathAreEnforced()throws Exception{var a=user();var b=user();var room=create(a,Map.of("privateChatEnabled",false));String code=room.path("roomCode").asText();ok("POST","/api/rooms/join",b,Map.of("roomCode",code));var request=Map.of("content","private hi","type","PRIVATE","receiverId",b.id());assertEquals(1002,call("POST","/api/chat/"+code+"/send",a.token(),request).path("code").asInt());ok("POST","/api/chat/"+code+"/send",a,Map.of("content","public hi","type","PUBLIC"));assertNotEquals(0,call("PUT","/api/rooms/ZZZZZZ",a.token(),Map.of("roomName","wrong path")).path("code").asInt());assertNotEquals(0,call("POST","/api/rooms/ZZZZZZ/leave",b.token(),null).path("code").asInt());assertEquals(code,ok("GET","/api/rooms/current",b,null).path("roomCode").asText());ok("PUT","/api/rooms/"+code,a,Map.of("privateChatEnabled",true));ok("POST","/api/chat/"+code+"/send",a,request);}
 @Test void standard13RoomStartsOnlyWithFullDeckAndRejectsForgedSkill()throws Exception{
  var host=user();var room=create(host,Map.of("maxPlayers",13,"werewolfCount",4));String code=room.path("roomCode").asText();
  assertNotEquals(0,call("POST","/api/game/start",host.token(),null).path("code").asInt());
  assertNotEquals(0,call("PUT","/api/rooms/"+code,host.token(),Map.of("werewolfCount",3)).path("code").asInt());
  ok("PUT","/api/rooms/"+code,host,Map.of("allowAi",true,"aiCount",12));
  var state=ok("POST","/api/game/start",host,null);assertEquals(13,state.path("players").size());assertTrue(state.path("me").has("role"));
  for(var player:state.path("players"))assertFalse(player.has("role"));
  assertNotEquals(0,call("POST","/api/game/action",host.token(),Map.of("action","HUNTER_SHOOT","actorSeatId",2,"targetSeatId",1)).path("code").asInt());
  var meta=ok("GET","/api/game/meta",host,null);assertEquals(13,meta.path("rules").path("maxPlayers").asInt());assertEquals(13,meta.path("rules").path("standard13Deck").size());
 }
 @Test void standard13SupportsCreatingTwelveBotsDirectly()throws Exception{
 var host=user();var room=create(host,Map.of("maxPlayers",13,"werewolfCount",4,"allowAi",true,"aiCount",12));
 assertEquals(13,room.path("playerCount").asInt());assertEquals(12,room.path("aiCount").asInt());ok("POST","/api/game/start",host,null);
 }
 @Test void soloHostCanAddBotsToAnAiDisabledRoomAndStart()throws Exception{
  for(int capacity:List.of(4,13)){
   var host=user();var room=create(host,Map.of("maxPlayers",capacity,"werewolfCount",capacity==13?4:1));String code=room.path("roomCode").asText();assertFalse(room.path("allowAi").asBoolean());
   var updated=ok("PUT","/api/rooms/"+code,host,Map.of("aiCount",capacity-1));
   assertTrue(updated.path("allowAi").asBoolean());assertEquals(capacity-1,updated.path("aiCount").asInt());assertEquals(capacity,updated.path("playerCount").asInt());
   int bots=0;for(var seat:updated.path("seats"))if(seat.path("ai").asBoolean()){bots++;assertTrue(seat.path("ready").asBoolean());}assertEquals(capacity-1,bots);
   assertEquals(capacity,ok("POST","/api/game/start",host,null).path("players").size());
  }
 }
 @Test void positiveCreateCountEnablesAiWhenSwitchIsOmitted()throws Exception{
  var host=user();var room=ok("POST","/api/rooms",host,Map.of("roomName","solo create","maxPlayers",4,"werewolfCount",1,"aiCount",3));
  assertTrue(room.path("allowAi").asBoolean());assertEquals(4,room.path("playerCount").asInt());ok("POST","/api/game/start",host,null);
 }
 @Test void explicitAiDisableClearsCountAndRemovesBotSeats()throws Exception{
  var host=user();var room=create(host,Map.of("aiCount",3,"allowAi",false));assertEquals(0,room.path("aiCount").asInt());assertEquals(1,room.path("playerCount").asInt());String code=room.path("roomCode").asText();
  ok("PUT","/api/rooms/"+code,host,Map.of("aiCount",3));var disabled=ok("PUT","/api/rooms/"+code,host,Map.of("allowAi",false));assertFalse(disabled.path("allowAi").asBoolean());assertEquals(0,disabled.path("aiCount").asInt());assertEquals(1,disabled.path("playerCount").asInt());
 }
 @Test void botCountCannotExceedSeatsLeftForCurrentHumans()throws Exception{
  var host=user();var guest=user();var room=create(host,Map.of());String code=room.path("roomCode").asText();ok("POST","/api/rooms/join",guest,Map.of("roomCode",code));
  assertEquals(3008,call("PUT","/api/rooms/"+code,host.token(),Map.of("aiCount",3)).path("code").asInt());var unchanged=ok("GET","/api/rooms/"+code,host,null);assertEquals(0,unchanged.path("aiCount").asInt());assertEquals(2,unchanged.path("playerCount").asInt());assertFalse(unchanged.path("allowAi").asBoolean());
 }


 @Autowired com.werewolf.room.mapper.RoomMapper roomMapper;
 @Test void customRolesAreHostOnlyPersistedAndDistributedExactly()throws Exception{
  var host=user();var guest=user();var room=create(host,Map.of("maxPlayers",6,"aiCount",4,"allowAi",true));String code=room.path("roomCode").asText();ok("POST","/api/rooms/join",guest,Map.of("roomCode",code));
  var roles=Map.of("WEREWOLF",2,"HUNTER",2,"VILLAGER",2);
  assertEquals(3005,call("PUT","/api/rooms/"+code,guest.token(),Map.of("roleCounts",roles)).path("code").asInt());
  var edited=ok("PUT","/api/rooms/"+code,host,Map.of("roleCounts",roles));assertEquals(2,edited.path("werewolfCount").asInt());
  var seen=ok("GET","/api/rooms/"+code,guest,null);assertEquals(edited.path("roleCounts"),seen.path("roleCounts"));assertEquals(edited.path("roleCounts"),seen.path("configuration").path("roleCounts"));
  var stored=roomMapper.selectById(room.path("id").asLong());assertEquals(edited.path("roleCounts"),json.valueToTree(stored.getRoleCounts()));
  ok("POST","/api/rooms/"+code+"/ready",guest,Map.of("ready",true));var view=ok("POST","/api/game/start",host,null);
  var actual=new HashMap<String,Integer>();for(var p:rooms.requireByCode(code).getState().getPlayers())actual.merge(p.getRole().name(),1,Integer::sum);assertEquals(roles,actual);
  for(var p:view.path("players"))assertFalse(p.has("role"));
  assertEquals(3003,call("PUT","/api/rooms/"+code,host.token(),Map.of("roleCounts",Map.of("WEREWOLF",1,"VILLAGER",5))).path("code").asInt());
 }
 @Test void invalidCustomCountsLeaveConfigurationAndSeatsUntouched()throws Exception{
  var host=user();var room=create(host,Map.of());String code=room.path("roomCode").asText();
  for(var roles:List.of(Map.of("WEREWOLF",1,"VILLAGER",2),Map.of("WEREWOLF",1,"VILLAGER",4),Map.of("WEREWOLF",0,"VILLAGER",4),Map.of("WEREWOLF",4),Map.of("WEREWOLF",1,"SEER",2,"VILLAGER",1),Map.of("WEREWOLF",1,"VILLAGER",4,"HUNTER",-1),Map.of("ALIEN",4))){
   assertEquals(3008,call("PUT","/api/rooms/"+code,host.token(),Map.of("aiCount",3,"roomName","must not mutate","roleCounts",roles)).path("code").asInt());
   var after=ok("GET","/api/rooms/"+code,host,null);assertEquals(room.path("roleCounts"),after.path("roleCounts"));assertEquals(room.path("roomName"),after.path("roomName"));assertEquals(1,after.path("playerCount").asInt());
  }
  for(Object bad:List.of(1.5,"1",true))assertEquals(1000,call("PUT","/api/rooms/"+code,host.token(),Map.of("roleCounts",Map.of("WEREWOLF",bad,"VILLAGER",3))).path("code").asInt());
 }
 @Test void customThirteenMayDepartFromPresetAndResetRestoresOriginalDeck()throws Exception{
  var host=user();var roles=Map.of("WEREWOLF",3,"VILLAGER",10);
  var room=create(host,Map.of("maxPlayers",13,"roleCounts",roles,"aiCount",12,"allowAi",true));String code=room.path("roomCode").asText();assertEquals(3,room.path("werewolfCount").asInt());
  ok("POST","/api/game/start",host,null);var runtime=rooms.requireByCode(code);assertEquals(3,runtime.getState().aliveWolves().size());
  runtime.resetToWaiting();var restored=ok("PUT","/api/rooms/"+code,host,Map.of("resetRoleCounts",true,"werewolfCount",4));assertEquals(1,restored.path("roleCounts").path("WHITE_WOLF_KING").asInt());assertEquals(1,restored.path("roleCounts").path("CUPID").asInt());assertNull(roomMapper.selectById(room.path("id").asLong()).getRoleCounts());
  ok("POST","/api/game/start",host,null);assertEquals(4,runtime.getState().aliveWolves().size());
 }
 @Test void matchDoesNotJoinCustomDeckWithSamePlayerAndWolfCounts()throws Exception{
  var host=user();var guest=user();var custom=create(host,Map.of("roleCounts",Map.of("WEREWOLF",1,"VILLAGER",3)));
  var matched=ok("POST","/api/rooms/match",guest,Map.of("maxPlayers",4,"werewolfCount",1));assertNotEquals(custom.path("roomCode"),matched.path("roomCode"));
 }
 @Test void startRequiresAllConfiguredSeatsAndFailedRestartPreservesEndedGame()throws Exception{
  var host=user();var room=create(host,Map.of("maxPlayers",6,"aiCount",3,"allowAi",true,"roleCounts",Map.of("WEREWOLF",1,"VILLAGER",5)));String code=room.path("roomCode").asText();
  assertEquals(3008,call("POST","/api/game/start",host.token(),null).path("code").asInt());assertEquals("WAITING",ok("GET","/api/rooms/"+code,host,null).path("status").asText());
  ok("PUT","/api/rooms/"+code,host,Map.of("aiCount",5));ok("POST","/api/game/start",host,null);
  var runtime=rooms.requireByCode(code);var events=new ArrayList<com.werewolf.game.domain.GameEvent>();
  for(int i=0;i<100 && runtime.getState().getPhase()!=com.werewolf.game.domain.GamePhase.END;i++){
   runtime.getEngine().autoCompleteCurrentPhase(events);if(runtime.getState().getPhase()!=com.werewolf.game.domain.GamePhase.END)runtime.getEngine().nextPhase(events);
  }
  assertEquals(com.werewolf.game.domain.GamePhase.END,runtime.getState().getPhase());
  String winner=runtime.getState().getWinner();runtime.getState().getPlayers().removeIf(p->p.isAi());
  assertEquals(3008,call("POST","/api/game/start",host.token(),null).path("code").asInt());assertEquals(winner,runtime.getState().getWinner());assertEquals(com.werewolf.game.domain.GamePhase.END,runtime.getState().getPhase());
 }
 @Test void customConfigurationSurvivesCompletedGameResetWithNoStateResidue()throws Exception{
  var host=user();var room=create(host,Map.of("aiCount",3,"allowAi",true,"roleCounts",Map.of("WHITE_WOLF_KING",1,"HUNTER",2,"VILLAGER",1)));String code=room.path("roomCode").asText();
  ok("POST","/api/game/start",host,null);var runtime=rooms.requireByCode(code);var state=runtime.getState();
  var events=new ArrayList<com.werewolf.game.domain.GameEvent>();
  for(int i=0;i<100 && state.getPhase()!=com.werewolf.game.domain.GamePhase.END;i++){runtime.getEngine().autoCompleteCurrentPhase(events);if(state.getPhase()!=com.werewolf.game.domain.GamePhase.END)runtime.getEngine().nextPhase(events);}
  assertEquals(com.werewolf.game.domain.GamePhase.END,state.getPhase());
  ok("POST","/api/game/reset",host,null);assertFalse(state.isFinished());assertNull(state.getWinner());assertTrue(state.getWolfVotes().isEmpty());assertTrue(state.getPhaseDoneSeats().isEmpty());assertFalse(state.isSeerRevealPending());assertNull(state.getSkillActorSeatId());
  for(var p:state.getPlayers()){assertNull(p.getRole());assertNull(p.getLoverSeatId());assertTrue(p.getCheckRecords().isEmpty());}
  var after=ok("GET","/api/rooms/"+code,host,null);assertEquals(room.path("roleCounts"),after.path("roleCounts"));
  ok("POST","/api/game/start",host,null);assertEquals(2,state.getPlayers().stream().filter(p->p.getRole()==com.werewolf.game.domain.RoleType.HUNTER).count());
 }
}
