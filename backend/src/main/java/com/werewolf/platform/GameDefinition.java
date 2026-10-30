package com.werewolf.platform;
import java.util.List;
import java.util.Map;
/** Catalogue and configuration boundary. Game-specific action/state code stays in its module. */
public interface GameDefinition {
 record Metadata(String type,String name,String description,String category,String status,String poster,List<Integer> recommendedPlayers,int minPlayers,int maxPlayers,List<String> rules,String lobbyPath){}
 Metadata metadata();
 void validateConfiguration(Map<String,Object> configuration);
 com.werewolf.room.runtime.RoomRuntime createRuntime(com.werewolf.room.entity.Room room);
}
