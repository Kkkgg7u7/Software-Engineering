package com.werewolf.voice;

import com.werewolf.common.*;
import com.werewolf.room.runtime.*;
import com.werewolf.websocket.GameEventMessenger;
import com.werewolf.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.*;

/** Authenticated signaling only. Audio uses browser RTCPeerConnection, never this server. */
@Service
public class VoiceService {
 private static final long LEASE_MS=30000;
 private final RoomManager rooms;
 private final GameEventMessenger messages;
 private final UserMapper users;
 private final List<Map<String,Object>> iceServers;
 private final Map<Long,Member> members=new HashMap<>();
 private static class Member {
  long userId,lastSeen,windowStart,authVersion; int signals; String roomCode,clientId,sessionId,nickname; boolean muted;
 }
 public VoiceService(RoomManager rooms,GameEventMessenger messages,UserMapper users,ObjectMapper json,
   @Value("${jiujiu.voice.ice-servers:[]}") String iceJson){
  this.rooms=rooms;this.messages=messages;this.users=users;
  try {
   JsonNode parsed=json.readTree(iceJson);if(!parsed.isArray()||parsed.size()>8)throw new IllegalArgumentException();
   List<Map<String,Object>> configs=new ArrayList<>();
   for(JsonNode server:parsed){
    JsonNode urls=server.path("urls");List<String> list=new ArrayList<>();
    if(urls.isTextual())list.add(urls.asText());else if(urls.isArray())urls.forEach(x->list.add(x.asText()));
    if(list.isEmpty()||list.size()>8||list.stream().anyMatch(x->x.length()>512||!x.matches("^(stun|stuns|turn|turns):[^\\s]+$")))throw new IllegalArgumentException();
    Map<String,Object> config=new LinkedHashMap<>();config.put("urls",list);
    for(String key:List.of("username","credential"))if(server.has(key)){if(!server.get(key).isTextual()||server.get(key).asText().length()>1024)throw new IllegalArgumentException();config.put(key,server.get(key).asText());}
    configs.add(config);
   }
   iceServers=List.copyOf(configs);
  }catch(Exception e){throw new IllegalArgumentException("jiujiu.voice.ice-servers 必须是合法的 WebRTC ICE 配置 JSON（配置内容已隐藏）");}
 }
 private RoomRuntime requireMember(String code,Long uid){
  var room=rooms.byCode(code==null?"":code.toUpperCase(Locale.ROOT));
  if(room==null||uid==null||room.getState().byUser(uid)==null)throw BizException.of(ErrorCode.NOT_IN_ROOM);
  return room;
 }
 private boolean enabled(RoomRuntime room){return Boolean.TRUE.equals(room.getRoom().getVoiceEnabled());}
 private Member requireSession(String code,Long uid,String session){
  var room=requireMember(code,uid);
  Member member=members.get(uid);
  if(!enabled(room)||member==null||!member.roomCode.equals(room.getRoom().getRoomCode())||!member.sessionId.equals(session)||System.currentTimeMillis()-member.lastSeen>LEASE_MS)
   throw BizException.of(ErrorCode.FORBIDDEN,"语音会话已结束，请重新加入");
  return member;
 }
 private Map<String,Object> view(Member member){return Map.of("userId",member.userId,"sessionId",member.sessionId,"nickname",member.nickname,"muted",member.muted);}
 private List<Map<String,Object>> peers(String code){return members.values().stream().filter(m->m.roomCode.equals(code)).sorted(Comparator.comparingLong(m->m.userId)).map(this::view).toList();}
 private Map<String,Object> snapshot(RoomRuntime room){return Map.of("roomCode",room.getRoom().getRoomCode(),"enabled",enabled(room),"members",peers(room.getRoom().getRoomCode()));}
 public synchronized Map<String,Object> state(String code,Long uid){sweep();return snapshot(requireMember(code,uid));}
 public synchronized Map<String,Object> join(String code,Long uid,String clientId){
  var room=requireMember(code,uid);if(!enabled(room))throw BizException.of(ErrorCode.FORBIDDEN,"房主尚未开启语音");
  if(clientId==null||!clientId.matches("[A-Za-z0-9_-]{16,80}"))throw BizException.of(ErrorCode.BAD_REQUEST,"语音客户端标识不合法");
  sweep();String normalized=room.getRoom().getRoomCode();Member old=members.get(uid);
  if(old!=null&&old.roomCode.equals(normalized)&&old.clientId.equals(clientId)){old.lastSeen=System.currentTimeMillis();return joined(room,old);}
  if(old!=null)remove(old,"语音已在另一个页面加入");
  var player=room.getState().byUser(uid);Member member=new Member();member.userId=uid;member.roomCode=normalized;member.nickname=player.getNickname();
  member.clientId=clientId;member.sessionId=UUID.randomUUID().toString();member.lastSeen=System.currentTimeMillis();member.windowStart=member.lastSeen;
  member.authVersion=users.selectById(uid).getAuthVersion();members.put(uid,member);broadcast(normalized);
  return joined(room,member);
 }
 private Map<String,Object> joined(RoomRuntime room,Member member){Map<String,Object> result=new LinkedHashMap<>(snapshot(room));result.put("sessionId",member.sessionId);result.put("iceServers",iceServers);result.put("leaseSeconds",30);return result;}
 public synchronized Map<String,Object> heartbeat(String code,Long uid,String session,boolean muted){
  Member member=requireSession(code,uid,session);member.lastSeen=System.currentTimeMillis();boolean changed=member.muted!=muted;member.muted=muted;if(changed)broadcast(member.roomCode);return snapshot(requireMember(code,uid));
 }
 public synchronized void leave(String code,Long uid,String session){
  // An old tab may leave after replacement; it must never remove the replacement session.
  Member member=members.get(uid);if(member!=null&&member.roomCode.equalsIgnoreCase(code)&&member.sessionId.equals(session))remove(member,"已退出语音");
 }
 public synchronized void signal(String code,Long uid,VoiceController.Signal signal){
  Member sender=requireSession(code,uid,signal.sessionId());
  if(signal.targetUserId()==null||signal.targetUserId().equals(uid))throw BizException.of(ErrorCode.BAD_REQUEST);
  Member target=requireSession(code,signal.targetUserId(),signal.targetSessionId());
  String type=signal.type();JsonNode payload=signal.payload();
  if(type==null||!Set.of("OFFER","ANSWER","ICE").contains(type)||payload==null||!payload.isObject())throw BizException.of(ErrorCode.BAD_REQUEST);
  if(payload.toString().length()>(type.equals("ICE")?4096:32768))throw BizException.of(ErrorCode.BAD_REQUEST,"语音信令过大");
  if(type.equals("ICE")){
   if(!payload.path("candidate").isTextual()||payload.path("candidate").asText().length()>2048)throw BizException.of(ErrorCode.BAD_REQUEST);
  }else if(!payload.path("sdp").isTextual()||!payload.path("type").asText().equals(type.toLowerCase(Locale.ROOT))||!payload.path("sdp").asText().startsWith("v=0"))throw BizException.of(ErrorCode.BAD_REQUEST);
  long now=System.currentTimeMillis();if(now-sender.windowStart>10000){sender.windowStart=now;sender.signals=0;}
  if(++sender.signals>240)throw BizException.of(ErrorCode.RATE_LIMITED);
  messages.sendToUser(target.userId,"/queue/voice","VOICE_SIGNAL",Map.of("roomCode",sender.roomCode,"fromUserId",uid,"fromSessionId",sender.sessionId,"targetSessionId",target.sessionId,"type",type,"payload",payload));
 }
 private void broadcast(String code){var room=rooms.byCode(code);if(room==null)return;var state=snapshot(room);for(var member:new ArrayList<>(members.values()))if(member.roomCode.equals(code))messages.sendToUser(member.userId,"/queue/voice","VOICE_MEMBERS",state);}
 private void remove(Member member,String reason){members.remove(member.userId);messages.sendToUser(member.userId,"/queue/voice","VOICE_CLOSED",Map.of("roomCode",member.roomCode,"sessionId",member.sessionId,"reason",reason));broadcast(member.roomCode);}
 @Scheduled(fixedDelay=5000)
 public synchronized void sweep(){
  long now=System.currentTimeMillis();
  for(var member:new ArrayList<>(members.values())){
   var room=rooms.byCode(member.roomCode);var user=users.selectById(member.userId);
   if(room==null||!enabled(room)||room.getState().byUser(member.userId)==null||now-member.lastSeen>LEASE_MS||user==null||user.getAuthVersion()!=member.authVersion)remove(member,"房间、登录或语音会话已结束");
  }
 }
}
