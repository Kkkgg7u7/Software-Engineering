package com.werewolf;
import com.werewolf.game.domain.*;
import com.werewolf.game.engine.*;
import com.werewolf.game.dto.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Standard13EngineTest {
 GameState s; WerewolfGameEngine e; List<GameEvent> events;
 void setup(RoleType... roles){s=new GameState(1L,"ABCDEF");e=new WerewolfGameEngine(s,new Random(7));events=new ArrayList<>();
  for(int i=0;i<roles.length;i++){var p=new PlayerState(i+1,(long)i+1,"p"+i,null,false);p.setRole(roles[i]);s.getPlayers().add(p);}
  s.setPhase(GamePhase.NIGHT);s.setRound(1);
 }
 void ok(ActionType a,int seat,Integer target){var result=e.applyAction(GameAction.human(a,seat,target,null),events);assertTrue(result.isOk(),a+":"+result.getMessage());}
 @Test void standardDeckIsExactAndReset(){var deck=RoleAssigner.buildDeck(13,4);assertEquals(13,deck.size());assertEquals(3,Collections.frequency(deck,RoleType.WEREWOLF));assertEquals(3,Collections.frequency(deck,RoleType.VILLAGER));
  for(var r:List.of(RoleType.WHITE_WOLF_KING,RoleType.SEER,RoleType.WITCH,RoleType.HUNTER,RoleType.GUARD,RoleType.IDIOT,RoleType.CUPID))assertEquals(1,Collections.frequency(deck,r));
  assertThrows(IllegalArgumentException.class,()->RoleAssigner.validateConfig(13,3));
  setup(deck.toArray(RoleType[]::new));s.setPhase(GamePhase.WAITING);assertTrue(e.startGame(4,events).isOk());assertTrue(s.getStandard13());
 }
 @Test void threeAgainstOneIsNotUnanimous(){setup(RoleType.WEREWOLF,RoleType.WEREWOLF,RoleType.WEREWOLF,RoleType.WHITE_WOLF_KING,RoleType.SEER,RoleType.WITCH,RoleType.VILLAGER,RoleType.VILLAGER,RoleType.VILLAGER);
  ok(ActionType.WOLF_KILL,1,5);ok(ActionType.WOLF_KILL,2,5);ok(ActionType.WOLF_KILL,3,5);assertNull(s.getWolfTargetSeatId());ok(ActionType.WOLF_KILL,4,6);assertFalse(e.wolvesAgreedOnTarget());assertNull(s.getWolfTargetSeatId());e.nextPhase(events);assertTrue(s.bySeat(5).isAlive());assertTrue(s.bySeat(6).isAlive());
 }
 @Test void guardAloneSavesButGuardAndHealKills(){for(boolean heal:List.of(false,true)){setup(RoleType.WEREWOLF,RoleType.GUARD,RoleType.WITCH,RoleType.SEER,RoleType.VILLAGER,RoleType.VILLAGER);
   ok(ActionType.GUARD_PROTECT,2,5);ok(ActionType.WOLF_KILL,1,5);if(heal)ok(ActionType.WITCH_HEAL,3,null);e.nextPhase(events);assertEquals(!heal,s.bySeat(5).isAlive());}
 }
 @Test void guardCannotRepeatButPassResets(){setup(RoleType.WEREWOLF,RoleType.GUARD,RoleType.SEER,RoleType.WITCH,RoleType.VILLAGER,RoleType.VILLAGER);
  ok(ActionType.GUARD_PROTECT,2,2);e.nextPhase(events);s.setPhase(GamePhase.CHECK_WINNER);e.nextPhase(events);
  assertFalse(e.applyAction(GameAction.human(ActionType.GUARD_PROTECT,2,2,null),events).isOk());ok(ActionType.GUARD_PASS,2,null);
  e.nextPhase(events);s.setPhase(GamePhase.CHECK_WINNER);e.nextPhase(events);ok(ActionType.GUARD_PROTECT,2,2);
 }
 @Test void witchSelfSaveOnlyFirstNightAndPotionsBothOrders(){for(boolean poisonFirst:List.of(false,true)){setup(RoleType.WEREWOLF,RoleType.WITCH,RoleType.SEER,RoleType.VILLAGER,RoleType.VILLAGER,RoleType.VILLAGER);
  ok(ActionType.WOLF_KILL,1,2);if(poisonFirst){ok(ActionType.WITCH_POISON,2,4);ok(ActionType.WITCH_HEAL,2,null);}else{ok(ActionType.WITCH_HEAL,2,null);ok(ActionType.WITCH_POISON,2,4);}e.nextPhase(events);assertTrue(s.bySeat(2).isAlive());assertFalse(s.bySeat(4).isAlive());}
  setup(RoleType.WEREWOLF,RoleType.WITCH,RoleType.SEER,RoleType.VILLAGER);s.setRound(2);ok(ActionType.WOLF_KILL,1,2);assertFalse(e.applyAction(GameAction.human(ActionType.WITCH_HEAL,2,null,null),events).isOk());assertFalse(e.availableActions(2).contains(ActionType.WITCH_HEAL));
 }
 @Test void hunterDelaysWinnerAndCanKillLastWolf(){setup(RoleType.WEREWOLF,RoleType.HUNTER,RoleType.VILLAGER,RoleType.VILLAGER);ok(ActionType.WOLF_KILL,1,2);e.nextPhase(events);
  assertEquals(GamePhase.DEATH_SKILL,s.getPhase());assertFalse(s.isFinished());assertTrue(e.availableActions(2).contains(ActionType.HUNTER_SHOOT));
  assertFalse(e.applyAction(GameAction.human(ActionType.HUNTER_SHOOT,3,1,null),events).isOk());ok(ActionType.HUNTER_SHOOT,2,1);assertEquals("VILLAGE",s.getWinner());assertEquals(GamePhase.END,s.getPhase());
 }
 @Test void poisonedHunterHasNoGunEvenWhenAlsoBitten(){setup(RoleType.WEREWOLF,RoleType.HUNTER,RoleType.WITCH,RoleType.SEER,RoleType.VILLAGER,RoleType.VILLAGER);ok(ActionType.WOLF_KILL,1,2);ok(ActionType.WITCH_POISON,3,2);e.nextPhase(events);
  assertEquals("POISON",s.bySeat(2).getDeathCause());assertNotEquals(GamePhase.DEATH_SKILL,s.getPhase());assertTrue(e.availableActions(2).isEmpty());
 }
 @Test void cupidIsPrivateAndLoverHunterCannotShoot(){setup(RoleType.WEREWOLF,RoleType.CUPID,RoleType.HUNTER,RoleType.SEER,RoleType.VILLAGER,RoleType.VILLAGER);
  ok(ActionType.CUPID_LINK,2,3);assertFalse(e.applyAction(GameAction.human(ActionType.CUPID_LINK,2,3,null),events).isOk());ok(ActionType.CUPID_LINK,2,5);assertNull(s.bySeat(2).getLoverSeatId());
  var link=events.stream().filter(x->x.getEventType()==GameEvent.EventType.CUPID_LINKED).findFirst().orElseThrow();assertTrue(link.isPrivate());assertFalse(link.visibleFor(1));
  ok(ActionType.WOLF_KILL,1,5);e.nextPhase(events);assertFalse(s.bySeat(3).isAlive());assertEquals("LOVER",s.bySeat(3).getDeathCause());assertNotEquals(GamePhase.DEATH_SKILL,s.getPhase());
 }
 @Test void crossCampLoversWinOnlyAsLastTwo(){setup(RoleType.WEREWOLF,RoleType.CUPID,RoleType.VILLAGER,RoleType.VILLAGER);s.bySeat(1).setLoverSeatId(3);s.bySeat(3).setLoverSeatId(1);s.bySeat(2).setAlive(false);
  assertNull(e.evaluateWinner());s.bySeat(4).setAlive(false);assertEquals("LOVERS",e.evaluateWinner());assertFalse(s.belongsToLovers(s.bySeat(2)));
 }
 @Test void idiotSurvivesExileThenLosesVote(){setup(RoleType.WEREWOLF,RoleType.IDIOT,RoleType.SEER,RoleType.WITCH,RoleType.VILLAGER);s.setPhase(GamePhase.VOTING);
  for(int i=1;i<=5;i++)if(i==2)ok(ActionType.ABSTAIN,i,null);else ok(ActionType.VOTE,i,2);
  assertTrue(s.bySeat(2).isAlive());assertTrue(s.bySeat(2).getIdiotRevealed());s.setPhase(GamePhase.CHECK_WINNER);e.nextPhase(events);e.nextPhase(events);s.setPhase(GamePhase.VOTING);
  assertFalse(e.availableActions(2).contains(ActionType.VOTE));assertFalse(e.applyAction(GameAction.human(ActionType.VOTE,2,1,null),events).isOk());
 }
 @Test void whiteWolfCarryOnlyOnVote(){setup(RoleType.WHITE_WOLF_KING,RoleType.WEREWOLF,RoleType.HUNTER,RoleType.SEER,RoleType.VILLAGER,RoleType.VILLAGER);s.setPhase(GamePhase.VOTING);
  for(int i=1;i<=6;i++)if(i==1)ok(ActionType.ABSTAIN,i,null);else ok(ActionType.VOTE,i,1);
  assertEquals("WHITE_WOLF_KING",s.getSkillKind());ok(ActionType.WHITE_WOLF_CARRY,1,3);assertEquals("HUNTER",s.getSkillKind());ok(ActionType.HUNTER_SHOOT,3,2);assertEquals("VILLAGE",s.getWinner());
  setup(RoleType.WHITE_WOLF_KING,RoleType.WEREWOLF,RoleType.WITCH,RoleType.SEER,RoleType.VILLAGER,RoleType.VILLAGER);ok(ActionType.WITCH_POISON,3,1);e.nextPhase(events);assertNotEquals(GamePhase.DEATH_SKILL,s.getPhase());
 }
 @Test void sheriffTieRevotesOnceThenVacant(){setup(RoleType.WEREWOLF,RoleType.SEER,RoleType.WITCH,RoleType.VILLAGER);s.setStandard13(true);e.nextPhase(events);assertEquals(GamePhase.SHERIFF_VOTING,s.getPhase());
  for(int round=1;round<=2;round++){ok(ActionType.SHERIFF_VOTE,1,1);ok(ActionType.SHERIFF_VOTE,2,1);ok(ActionType.SHERIFF_VOTE,3,2);ok(ActionType.SHERIFF_VOTE,4,2);if(round==1){assertEquals(2,s.getSheriffVoteRound());assertEquals(Set.of(1,2),s.getSheriffCandidates());}}
  assertTrue(s.getSheriffElectionDone());assertNull(s.getSheriffSeatId());assertEquals(GamePhase.DISCUSSION,s.getPhase());
 }
 @Test void sheriffWeightAndBadgeAfterDeath(){setup(RoleType.WEREWOLF,RoleType.SEER,RoleType.WITCH,RoleType.VILLAGER,RoleType.VILLAGER);s.setSheriffSeatId(2);s.setPhase(GamePhase.VOTING);
  ok(ActionType.VOTE,1,2);ok(ActionType.VOTE,2,1);ok(ActionType.VOTE,3,2);ok(ActionType.VOTE,4,1);ok(ActionType.ABSTAIN,5,null);assertFalse(s.bySeat(1).isAlive());assertTrue(s.bySeat(2).isAlive());
  setup(RoleType.WEREWOLF,RoleType.SEER,RoleType.WITCH,RoleType.VILLAGER,RoleType.VILLAGER);s.setSheriffSeatId(2);ok(ActionType.WOLF_KILL,1,2);e.nextPhase(events);assertEquals("BADGE",s.getSkillKind());ok(ActionType.BADGE_TRANSFER,2,3);assertEquals(3,s.getSheriffSeatId());
 }
 @Test void timeoutSkipsSkillsAndDestroysBadge(){setup(RoleType.WEREWOLF,RoleType.HUNTER,RoleType.WITCH,RoleType.VILLAGER,RoleType.VILLAGER);s.setSheriffSeatId(2);ok(ActionType.WOLF_KILL,1,2);e.nextPhase(events);assertEquals("HUNTER",s.getSkillKind());e.nextPhase(events);assertEquals("BADGE",s.getSkillKind());e.nextPhase(events);assertNull(s.getSheriffSeatId());assertTrue(s.bySeat(1).isAlive());
 }
 @Test void projectionHidesRolesGuardAndOtherLovers(){setup(RoleType.WEREWOLF,RoleType.GUARD,RoleType.CUPID,RoleType.SEER,RoleType.VILLAGER,RoleType.VILLAGER);ok(ActionType.CUPID_LINK,3,1);ok(ActionType.CUPID_LINK,3,5);ok(ActionType.GUARD_PROTECT,2,4);
  var good=GameViewProjector.project(s,e,"test",6,1,4);assertNull(good.getMe().getLoverSeatId());assertNull(good.getMe().getLastGuardSeatId());assertTrue(good.getPendingActorSeats().isEmpty());assertTrue(good.getPhaseDoneSeats().isEmpty());assertTrue(good.getPlayers().stream().allMatch(x->x.getRole()==null));
  var lover=GameViewProjector.project(s,e,"test",6,1,1);assertEquals(5,lover.getMe().getLoverSeatId());assertEquals("LOVERS",lover.getMe().getCamp());
 }
}
