package com.werewolf.game.engine;

import com.werewolf.game.domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CustomRoleConfigurationTest {
    private GameState state(int n) {
        var state=new GameState(44L,"CUSTOM");
        for(int i=1;i<=n;i++)state.getPlayers().add(new PlayerState(i,(long)i,"p"+i,null,false));
        return state;
    }
    private Map<String,Integer> counts(RoleType... deck) {
        var result=new LinkedHashMap<String,Integer>();
        for(var r:deck)result.merge(r.name(),1,Integer::sum);
        return result;
    }
    @Test void shuffledDeckMatchesEveryConfiguredCountAndResetsEveryPlayerAcrossSeeds() {
        var configured=RoleConfiguration.validate(13,Map.of("WEREWOLF",2,"WHITE_WOLF_KING",2,"HUNTER",2,"IDIOT",2,"SEER",1,"WITCH",1,"GUARD",1,"CUPID",1,"VILLAGER",1));
        var arrangements=new HashSet<List<RoleType>>();
        for(int seed=0;seed<100;seed++){
            var s=state(13);
            for(var p:s.getPlayers()){p.setAlive(false);p.setLoverSeatId(2);p.setLastGuardSeatId(3);p.setIdiotRevealed(true);p.setDeathSkillUsed(true);p.setDeathCause("POISON");p.setHealPotionAvailable(false);p.setPoisonPotionAvailable(false);p.setRoleConfirmed(true);p.getCheckRecords().add(new PlayerState.CheckRecord(1,2,"p2",true));}
            RoleAssigner.assign(s.getPlayers(),configured,new Random(seed));
            var actual=new LinkedHashMap<String,Integer>();for(var r:RoleType.values())actual.put(r.name(),0);
            for(var p:s.getPlayers()){
                actual.merge(p.getRole().name(),1,Integer::sum);assertTrue(p.isAlive());assertNull(p.getLoverSeatId());assertNull(p.getLastGuardSeatId());assertFalse(p.getIdiotRevealed());assertFalse(p.getDeathSkillUsed());assertNull(p.getDeathCause());assertTrue(p.isHealPotionAvailable());assertTrue(p.isPoisonPotionAvailable());assertFalse(p.isRoleConfirmed());assertTrue(p.getCheckRecords().isEmpty());
            }
            assertEquals(configured,actual);arrangements.add(s.getPlayers().stream().map(PlayerState::getRole).toList());
        }
        assertTrue(arrangements.size()>90,"different random seeds must actually change seat assignments");
    }
    @Test void configurationRejectsWrongTotalsNoCampsUnknownNegativeAndSharedSkillDuplicates() {
        for(var c:List.of(Map.of("WEREWOLF",1,"VILLAGER",2),Map.of("WEREWOLF",1,"VILLAGER",4),Map.of("VILLAGER",4),Map.of("WEREWOLF",4),Map.of("WEREWOLF",2,"VILLAGER",2),Map.of("WEREWOLF",1,"ALIEN",3),Map.of("WEREWOLF",1,"VILLAGER",4,"HUNTER",-1)))
            assertThrows(IllegalArgumentException.class,()->RoleConfiguration.validate(4,c));
        for(String role:List.of("SEER","WITCH","GUARD","CUPID"))assertThrows(IllegalArgumentException.class,()->RoleConfiguration.validate(6,Map.of("WEREWOLF",1,role,2,"VILLAGER",3)));
        assertEquals(0,RoleConfiguration.validate(4,Map.of("WHITE_WOLF_KING",1,"VILLAGER",3)).get("WEREWOLF"));
    }
    @Test void absentSpecialRolesProduceNoInvalidNightActionsAndFullGameEnds() {
        var s=state(6);var e=new WerewolfGameEngine(s,new Random(3));var events=new ArrayList<GameEvent>();
        assertTrue(e.startGame(2,Map.of("WEREWOLF",2,"VILLAGER",4),events).isOk());
        e.nextPhase(events);e.nextPhase(events);assertEquals(GamePhase.NIGHT,s.getPhase());
        assertEquals(2,e.phaseActorList().size());assertTrue(e.phaseActorList().stream().allMatch(PlayerState::isWolf));
        for(var p:s.aliveVillagers())assertTrue(e.availableActions(p.getSeatId()).isEmpty());
        for(int i=0;i<100 && s.getPhase()!=GamePhase.END;i++){e.autoCompleteCurrentPhase(events);if(s.getPhase()!=GamePhase.END)e.nextPhase(events);}
        assertEquals(GamePhase.END,s.getPhase());assertNotNull(s.getWinner());
    }
    @Test void multipleHuntersQueueIndependentShotsBeforeWinnerAndWhiteKingsCarryIndependently() {
        var s=state(8);var e=new WerewolfGameEngine(s,new Random(2));var events=new ArrayList<GameEvent>();
        assertTrue(e.startGame(2,Map.of("WHITE_WOLF_KING",2,"HUNTER",2,"VILLAGER",4),events).isOk());
        // Deterministic seats isolate death-skill behavior after exercising custom assignment.
        for(int i=1;i<=8;i++)s.bySeat(i).setRole(i<=2?RoleType.WHITE_WOLF_KING:i<=4?RoleType.HUNTER:RoleType.VILLAGER);
        s.setPhase(GamePhase.NIGHT);s.setRound(1);s.setWolfTargetSeatId(3);e.nextPhase(events);
        assertEquals(3,s.getSkillActorSeatId());assertFalse(s.isFinished());
        assertTrue(e.applyAction(GameAction.human(ActionType.HUNTER_SHOOT,3,4,null),events).isOk());
        assertEquals(4,s.getSkillActorSeatId());assertTrue(e.applyAction(GameAction.human(ActionType.HUNTER_SHOOT,4,1,null),events).isOk());
        assertTrue(s.bySeat(3).getDeathSkillUsed());assertTrue(s.bySeat(4).getDeathSkillUsed());assertTrue(s.bySeat(2).isAlive());
        s.setPhase(GamePhase.VOTING);s.getVotes().clear();s.getVotes().put(5,2);e.nextPhase(events);
        assertEquals(2,s.getSkillActorSeatId());assertFalse(s.isFinished());
        assertTrue(e.applyAction(GameAction.human(ActionType.WHITE_WOLF_CARRY,2,5,null),events).isOk());
        assertFalse(s.bySeat(5).isAlive());assertEquals(GamePhase.END,s.getPhase());assertEquals("VILLAGE",s.getWinner());
    }
    @Test void multipleIdiotsRevealAndLoseVotesIndividually() {
        var s=state(6);var e=new WerewolfGameEngine(s,new Random(5));var events=new ArrayList<GameEvent>();
        assertTrue(e.startGame(1,Map.of("WEREWOLF",1,"IDIOT",2,"VILLAGER",3),events).isOk());
        for(int i=1;i<=6;i++)s.bySeat(i).setRole(i==1?RoleType.WEREWOLF:i<=3?RoleType.IDIOT:RoleType.VILLAGER);
        for(int target:List.of(2,3)){
            s.setPhase(GamePhase.VOTING);s.getVotes().clear();s.getVotes().put(4,target);e.nextPhase(events);assertTrue(s.bySeat(target).getIdiotRevealed());assertTrue(s.bySeat(target).isAlive());
        }
        s.setPhase(GamePhase.VOTING);assertFalse(e.availableActions(2).contains(ActionType.VOTE));assertFalse(e.availableActions(3).contains(ActionType.VOTE));assertTrue(e.availableActions(4).contains(ActionType.VOTE));
    }

    @Test void eachWhiteWolfKingCanCarryWhenVotedOut() {
        var s=state(8);var e=new WerewolfGameEngine(s,new Random(3));var events=new ArrayList<GameEvent>();
        assertTrue(e.startGame(2,Map.of("WHITE_WOLF_KING",2,"VILLAGER",6),events).isOk());
        for(int seat=1;seat<=8;seat++)s.bySeat(seat).setRole(seat<=2?RoleType.WHITE_WOLF_KING:RoleType.VILLAGER);
        for(int king:List.of(1,2)){
            s.setPhase(GamePhase.VOTING);s.getVotes().clear();s.getVotes().put(7,king);e.nextPhase(events);
            assertEquals(GamePhase.DEATH_SKILL,s.getPhase());assertEquals(king,s.getSkillActorSeatId());assertEquals("WHITE_WOLF_KING",s.getSkillKind());
            assertTrue(e.applyAction(GameAction.human(ActionType.WHITE_WOLF_CARRY,king,king+2,null),events).isOk());assertFalse(s.bySeat(king+2).isAlive());assertTrue(s.bySeat(king).getDeathSkillUsed());
        }
        assertEquals(GamePhase.END,s.getPhase());assertEquals("VILLAGE",s.getWinner());
    }
}
