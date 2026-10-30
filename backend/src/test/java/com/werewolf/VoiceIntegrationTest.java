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
@SpringBootTest(properties="werewolf.test.voice-isolation=true") @AutoConfigureMockMvc @ActiveProfiles("h2")
class VoiceIntegrationTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired RoomService rooms;@Autowired RoomManager manager;
 record User(long id,String token){}
 @AfterEach void clear(){for(var r:manager.all())rooms.dissolve(r,"platform test cleanup");}
 JsonNode call(String method,String path,String token,Object body)throws Exception{var req=MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method),path);if(token!=null)req.header("Authorization","Bearer "+token);if(body!=null)req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));return json.readTree(mvc.perform(req).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));}
 JsonNode ok(String method,String path,User u,Object body)throws Exception{var r=call(method,path,u.token(),body);assertEquals(0,r.path("code").asInt(),r.toString());return r.path("data");}
 User user()throws Exception{String name="gp"+Long.toUnsignedString(System.nanoTime(),36);var r=call("POST","/api/auth/register",null,Map.of("username",name,"nickname",name,"password","demo1234"));assertEquals(0,r.path("code").asInt());return new User(r.path("data").path("user").path("id").asLong(),r.path("data").path("token").asText());}
 void friend(User a,User b)throws Exception{long id=ok("POST","/api/friends/requests",a,Map.of("targetUserId",b.id())).path("id").asLong();ok("POST","/api/friends/requests/"+id+"/accept",b,null);}
 JsonNode create(User u,Map<String,Object> options)throws Exception{var data=new HashMap<String,Object>(Map.of("roomName","platform room","maxPlayers",4,"werewolfCount",1,"aiCount",0,"allowAi",false));data.putAll(options);return ok("POST","/api/rooms",u,data);}

 @Autowired com.werewolf.voice.VoiceService voice;
 String path(String code){return "/api/rooms/"+code+"/voice";}
 String joinVoice(String code,User user,String client)throws Exception{return ok("POST",path(code)+"/join",user,Map.of("clientId",client)).path("sessionId").asText();}
 @Test void disabledVoiceAndPrivateRoomRejectOutsiders()throws Exception{
  var host=user();var outsider=user();String code=create(host,Map.of("publicRoom",false)).path("roomCode").asText();
  assertEquals(1002,call("POST",path(code)+"/join",host.token(),Map.of("clientId","voice-client-0001")).path("code").asInt());
  ok("PUT","/api/rooms/"+code,host,Map.of("voiceEnabled",true));
  assertEquals(3004,call("GET",path(code),outsider.token(),null).path("code").asInt());
  assertEquals(3004,call("POST",path(code)+"/join",outsider.token(),Map.of("clientId","voice-client-0002")).path("code").asInt());
  assertEquals(1001,call("GET",path(code),null,null).path("code").asInt());
 }
 @Test void membershipMuteReplacementAndOldLeaveAreSafe()throws Exception{
  var host=user();String code=create(host,Map.of("voiceEnabled",true)).path("roomCode").asText();
  String one=joinVoice(code,host,"voice-client-0001");assertEquals(one,joinVoice(code,host,"voice-client-0001"));
  var state=ok("POST",path(code)+"/heartbeat",host,Map.of("sessionId",one,"muted",true));assertTrue(state.path("members").get(0).path("muted").asBoolean());
  String two=joinVoice(code,host,"voice-client-0002");assertNotEquals(one,two);
  ok("POST",path(code)+"/leave",host,Map.of("sessionId",one));assertEquals(two,ok("GET",path(code),host,null).path("members").get(0).path("sessionId").asText());
  assertEquals(1002,call("POST",path(code)+"/heartbeat",host.token(),Map.of("sessionId",one)).path("code").asInt());
  ok("PUT","/api/rooms/"+code,host,Map.of("voiceEnabled",false));assertTrue(ok("GET",path(code),host,null).path("members").isEmpty());
 }
 @Test void signalRequiresBothCurrentSessionsInSameRoomAndValidPayload()throws Exception{
  var a=user();var b=user();var c=user();String code=create(a,Map.of("voiceEnabled",true)).path("roomCode").asText();
  ok("POST","/api/rooms/join",b,Map.of("roomCode",code));String other=create(c,Map.of("voiceEnabled",true)).path("roomCode").asText();
  String sa=joinVoice(code,a,"voice-client-0001"),sb=joinVoice(code,b,"voice-client-0002"),sc=joinVoice(other,c,"voice-client-0003");
  var signal=new HashMap<String,Object>(Map.of("sessionId",sa,"targetUserId",b.id(),"targetSessionId",sb,"type","OFFER","payload",Map.of("type","offer","sdp","v=0\r\n")));
  ok("POST",path(code)+"/signal",a,signal);signal.put("targetUserId",c.id());signal.put("targetSessionId",sc);
  assertNotEquals(0,call("POST",path(code)+"/signal",a.token(),signal).path("code").asInt());
  signal.put("targetUserId",b.id());signal.put("targetSessionId",sb);signal.put("payload",Map.of("type","answer","sdp","bad"));assertEquals(1000,call("POST",path(code)+"/signal",a.token(),signal).path("code").asInt());
  signal.put("payload",Map.of("type","offer","sdp","v=0"+"x".repeat(33000)));assertEquals(1000,call("POST",path(code)+"/signal",a.token(),signal).path("code").asInt());
  ok("POST","/api/rooms/"+code+"/leave",b,null);signal.put("payload",Map.of("type","offer","sdp","v=0"));assertNotEquals(0,call("POST",path(code)+"/signal",a.token(),signal).path("code").asInt());assertEquals(1,ok("GET",path(code),a,null).path("members").size());
 }
 @Test void expiredLeasesAreRemovedAndCannotSignal()throws Exception{
  var a=user();String code=create(a,Map.of("voiceEnabled",true)).path("roomCode").asText();String session=joinVoice(code,a,"voice-client-0001");
  var map=(Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(voice,"members");org.springframework.test.util.ReflectionTestUtils.setField(map.get(a.id()),"lastSeen",System.currentTimeMillis()-31000);
  assertEquals(1002,call("POST",path(code)+"/heartbeat",a.token(),Map.of("sessionId",session)).path("code").asInt());voice.sweep();assertTrue(ok("GET",path(code),a,null).path("members").isEmpty());
 }
}
