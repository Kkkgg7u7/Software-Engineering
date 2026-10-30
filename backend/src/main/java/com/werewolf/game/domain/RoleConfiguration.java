package com.werewolf.game.domain;

import java.util.*;

/** 房间牌组的唯一配置来源；不改变角色技能或胜负规则。 */
public final class RoleConfiguration {
    private RoleConfiguration() {}

    public static int maxCount(RoleType role, int players) {
        return switch (role) {
            // These skills store per-player state / queued death tasks.
            case WEREWOLF, VILLAGER, HUNTER, IDIOT, WHITE_WOLF_KING -> players;
            // These skills currently share one reveal / potion / guard / pair slot.
            case SEER, WITCH, GUARD, CUPID -> 1;
        };
    }

    public static Map<String,Integer> defaults(int players, int wolves) {
        Map<String,Integer> counts = empty();
        for (RoleType role : RoleAssigner.buildDeck(players, wolves))
            counts.merge(role.name(), 1, Integer::sum);
        return counts;
    }

    private static Map<String,Integer> empty() {
        Map<String,Integer> result = new LinkedHashMap<>();
        for (RoleType role : RoleType.values()) result.put(role.name(), 0);
        return result;
    }

    /** Missing known roles mean zero; unknown keys and invalid counts are rejected. */
    public static Map<String,Integer> validate(int players, Map<String,Integer> input) {
        if (players < 4 || players > 13) throw new IllegalArgumentException("房间人数必须在 4～13 之间");
        if (input == null) throw new IllegalArgumentException("请提供身份配置");
        Map<String,Integer> result = empty();
        for (var entry : input.entrySet()) {
            RoleType role;
            try { role = RoleType.valueOf(entry.getKey()); }
            catch (Exception ex) { throw new IllegalArgumentException("未知身份：" + entry.getKey()); }
            Integer count = entry.getValue();
            if (count == null || count < 0 || count > maxCount(role, players))
                throw new IllegalArgumentException(role.getLabel() + "人数必须在 0～" + maxCount(role, players) + " 之间"
                        + (maxCount(role, players)==1 ? "（当前技能仅支持单实例）" : ""));
            result.put(role.name(), count);
        }
        int total = result.values().stream().mapToInt(Integer::intValue).sum();
        if (total != players) throw new IllegalArgumentException("身份总人数 " + total + " 必须等于本局人数 " + players);
        int wolves = wolves(result);
        if (wolves == 0) throw new IllegalArgumentException("至少需要 1 名狼人阵营玩家（狼人或白狼王）");
        if (wolves == total) throw new IllegalArgumentException("至少需要 1 名好人阵营玩家");
        if (wolves >= total-wolves) throw new IllegalArgumentException("狼人阵营人数必须少于好人，否则按现有胜负规则开局即获胜");
        return result;
    }

    public static int wolves(Map<String,Integer> counts) {
        return counts.getOrDefault("WEREWOLF",0)+counts.getOrDefault("WHITE_WOLF_KING",0);
    }

    public static List<RoleType> buildDeck(int players, Map<String,Integer> counts) {
        List<RoleType> deck = new ArrayList<>();
        validate(players, counts).forEach((name, count) -> {
            for (int i=0; i<count; i++) deck.add(RoleType.valueOf(name));
        });
        return deck;
    }
}
