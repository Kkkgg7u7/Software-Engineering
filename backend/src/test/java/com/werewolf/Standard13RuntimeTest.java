package com.werewolf;
import com.werewolf.game.domain.*;
import com.werewolf.game.engine.*;
import com.werewolf.room.entity.Room;
import com.werewolf.room.runtime.*;
import com.werewolf.ai.*;
import com.werewolf.config.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

class Standard13RuntimeTest {
 private final GameBroadcaster silent=new GameBroadcaster(){
  public void updateSeatMapping(Long id,Map<Integer,Long> users){}
  public void broadcastGameState(Room room,com.werewolf.game.dto.GameView view,java.util.function.IntFunction<com.werewolf.game.dto.GameView> perSeat,Map<Integer,Long> users){}
  public void broadcastEvents(Room room,List<GameEvent> events,Map<Integer,Long> users){}
  public void sendPrivateEvent(Room room,int seat,GameEvent event,Map<Integer,Long> users){}
  public void broadcastNotice(Room room,String message){}
  public void kickSeat(Room room,int seat,Map<Integer,Long> users){}
 };
 @Test void botsCompleteMultipleStandardGamesThroughTheRealRuntime(){
  var properties=new AiProperties();properties.setEnabled(false);
  for(int seed=0;seed<12;seed++){
   var state=new GameState((long)seed,"STD"+seed);for(int i=1;i<=13;i++)state.getPlayers().add(new PlayerState(i,-(long)i,"bot"+i,null,true));
   var room=new Room();room.setId((long)seed);room.setRoomCode("STD"+seed);room.setRoomName("standard13");room.setMaxPlayers(13);room.setWerewolfCount(4);
   var phase=new AppProperties.Phase();
   var ai=new AiService(properties,new AiContextBuilder(),new RuleAgent(),null){
    @Override public long thinkDelay(Random random){return 0;}
   };
   var runtime=new RoomRuntime(room,new WerewolfGameEngine(state,new Random(seed)),silent,ai,phase);
   assertTrue(runtime.startGame().isOk());boolean electionSeen=false;
   for(int tick=0;tick<3500 && state.getPhase()!=GamePhase.END;tick++){
    if(state.getPhase()==GamePhase.SHERIFF_VOTING)electionSeen=true;
    runtime.tick();
   }
   assertEquals(GamePhase.END,state.getPhase(),"seed "+seed+" stalled at "+state.getPhase()+" round "+state.getRound());
   assertNotNull(state.getWinner());assertTrue(electionSeen,"first dawn election must run");
   assertTrue(state.getCupidLinked());
  }
 }
 @Test void nightTimeoutDoesNotSkipQueuedHunterSkill(){
  var state=new GameState(1L,"TIMEOUT");var room=new Room();room.setId(1L);room.setRoomCode("TIMEOUT");room.setRoomName("timeout");room.setMaxPlayers(6);room.setWerewolfCount(1);
  for(int i=1;i<=6;i++)state.getPlayers().add(new PlayerState(i,(long)i,"human"+i,null,false));
  var engine=new WerewolfGameEngine(state,new Random(1));
  var runtime=new RoomRuntime(room,engine,silent,null,new AppProperties.Phase());assertTrue(runtime.startGame().isOk());
  state.bySeat(1).setRole(RoleType.WEREWOLF);state.bySeat(2).setRole(RoleType.HUNTER);for(int i=3;i<=6;i++)state.bySeat(i).setRole(RoleType.VILLAGER);
  state.setPhase(GamePhase.NIGHT);state.setRound(1);state.setWolfTargetSeatId(2);state.setPhaseSecondsLeft(1);
  state.getWolfVotes().put(1,2);state.markPhaseDone(1);
  runtime.tick();assertEquals(GamePhase.DEATH_SKILL,state.getPhase());assertEquals(2,state.getSkillActorSeatId());assertEquals(30,state.getPhaseSecondsLeft());
 }
 @Test void pendingModelReplyDoesNotBlockTickAndIsCancelledOnReset(){
  var state=new GameState(90L,"ASYNCAA");var room=new Room();room.setId(90L);room.setRoomCode("ASYNCAA");room.setRoomName("async");room.setMaxPlayers(4);room.setWerewolfCount(1);
  for(int i=1;i<=4;i++)state.getPlayers().add(new PlayerState(i,-(long)i,"bot"+i,null,true));
  var futures=new ArrayList<java.util.concurrent.CompletableFuture<com.werewolf.ai.dto.AiDecision>>();
  var ai=new AiService(new AiProperties(),new AiContextBuilder(),new RuleAgent(),null){
   @Override public long thinkDelay(Random r){return 0;}
   @Override public boolean mayCallLlm(int used){return true;}
   @Override public java.util.concurrent.CompletableFuture<com.werewolf.ai.dto.AiDecision> request(AiRuntimeView r,PlayerState p,List<ActionType>a,int used){var future=new java.util.concurrent.CompletableFuture<com.werewolf.ai.dto.AiDecision>();futures.add(future);return future;}
  };
  var runtime=new RoomRuntime(room,new WerewolfGameEngine(state,new Random(1)),silent,ai,new AppProperties.Phase());assertTrue(runtime.startGame().isOk());
  long start=System.nanoTime();for(int i=0;i<200&&futures.isEmpty();i++)runtime.tick();assertFalse(futures.isEmpty());assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<500);
  state.setSheriffSeatId(2);state.bySeat(1).setLoverSeatId(2);runtime.resetToWaiting();assertTrue(futures.stream().allMatch(java.util.concurrent.CompletableFuture::isCancelled));assertNull(state.getSheriffSeatId());assertNull(state.bySeat(1).getLoverSeatId());assertEquals(GamePhase.WAITING,state.getPhase());ai.shutdown();
 }


 @Test void customDecksCompleteAndReplayThroughTheRealAiRuntime(){
  var decks=List.of(Map.of("WEREWOLF",1,"VILLAGER",3),Map.of("WHITE_WOLF_KING",2,"HUNTER",2,"IDIOT",2,"VILLAGER",2),Map.of("WEREWOLF",2,"WHITE_WOLF_KING",2,"HUNTER",2,"IDIOT",2,"SEER",1,"WITCH",1,"GUARD",1,"CUPID",1,"VILLAGER",1));
  for(var counts:decks)for(int seed=0;seed<6;seed++){
   int n=counts.values().stream().mapToInt(Integer::intValue).sum();var state=new GameState(500L,"CUSTOM");
   for(int seat=1;seat<=n;seat++)state.getPlayers().add(new PlayerState(seat,-(long)seat,"bot"+seat,null,true));
   var room=new Room();room.setId(500L);room.setRoomCode("CUSTOM");room.setRoomName("custom");room.setMaxPlayers(n);room.setWerewolfCount(RoleConfiguration.wolves(counts));room.setRoleCounts(RoleConfiguration.validate(n,counts));
   var props=new AiProperties();props.setEnabled(false);var ai=new AiService(props,new AiContextBuilder(),new RuleAgent(),null){@Override public long thinkDelay(Random random){return 0;}};
   var runtime=new RoomRuntime(room,new WerewolfGameEngine(state,new Random(seed)),silent,ai,new AppProperties.Phase());
   for(int replay=0;replay<2;replay++){
    assertTrue(runtime.startGame().isOk());
    var actual=new HashMap<String,Integer>();for(var p:state.getPlayers())actual.merge(p.getRole().name(),1,Integer::sum);assertEquals(counts,actual);
    for(int tick=0;tick<5000 && state.getPhase()!=GamePhase.END;tick++)runtime.tick();
    assertEquals(GamePhase.END,state.getPhase(),"custom "+n+" seed "+seed+" replay "+replay+" stalled at "+state.getPhase());assertNotNull(state.getWinner());
    runtime.resetToWaiting();assertEquals(room.getRoleCounts(),RoleConfiguration.validate(n,counts));assertTrue(state.getPlayers().stream().allMatch(p->p.getRole()==null));assertNull(state.getWinner());
   }
   ai.shutdown();
  }
 }
}
