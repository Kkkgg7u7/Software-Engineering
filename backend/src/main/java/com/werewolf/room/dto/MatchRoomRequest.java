package com.werewolf.room.dto;
public record MatchRoomRequest(String gameType,Integer maxPlayers,Integer werewolfCount,Boolean privateChatEnabled) {
 public String type(){return gameType==null?"WEREWOLF":gameType;}
 public int capacity(){return maxPlayers==null?4:maxPlayers;}
 public int wolves(){return werewolfCount==null?1:werewolfCount;}
 public boolean privateChat(){return privateChatEnabled==null||privateChatEnabled;}
}
