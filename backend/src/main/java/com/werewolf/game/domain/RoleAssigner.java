package com.werewolf.game.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 角色分配器。第一版规则（方案第五节）：
 *
 * <ul>
 *   <li>狼人：1 ~ ⌊(人数-1)/2⌋，必须少于总人数的一半；</li>
 *   <li>预言家：固定 1 名；</li>
 *   <li>女巫：固定 1 名（与预言家同为 P0 必选角色，4 人局也保留）；</li>
 *   <li>剩余座位全部为平民。</li>
 * </ul>
 *
 * <p>因此房间人数必须满足 {@code 人数 >= 狼人数 + 2}（狼人 + 预言家 + 女巫），
 * 否则会出现"神职放不下"的配置，由 {@link #buildDeck} 与
 * {@code RoomService.validateConfig} 双重拦截。
 *
 * <p>角色顺序打乱后按座位依次发放，避免"座位号 = 身份"的规律被利用。
 */
public final class RoleAssigner {

    private RoleAssigner() {
    }

    /** 除狼人外必须保留的神职数量（预言家 + 女巫）。 */
    public static final int REQUIRED_SPECIAL_ROLES = 2;

    /** 生成一副牌（未打乱）。 */
    public static List<RoleType> buildDeck(int totalPlayers, int werewolfCount) {
        if (totalPlayers < 4) {
            throw new IllegalArgumentException(
                    "至少需要 4 名玩家（含 AI）才能开局，当前只有 " + totalPlayers + " 个座位");
        }
        if (werewolfCount + REQUIRED_SPECIAL_ROLES > totalPlayers) {
            throw new IllegalArgumentException(
                    "狼人太多：需要为预言家和女巫各保留 1 个座位，当前 " + totalPlayers + " 人最多 "
                            + (totalPlayers - REQUIRED_SPECIAL_ROLES) + " 只狼");
        }
        if (totalPlayers == 13) {
            if (werewolfCount != 4) throw new IllegalArgumentException("13 人标准局必须为 4 狼（含白狼王）");
            return new ArrayList<>(List.of(RoleType.WHITE_WOLF_KING,RoleType.WEREWOLF,RoleType.WEREWOLF,RoleType.WEREWOLF,
                RoleType.SEER,RoleType.WITCH,RoleType.HUNTER,RoleType.GUARD,RoleType.IDIOT,RoleType.CUPID,
                RoleType.VILLAGER,RoleType.VILLAGER,RoleType.VILLAGER));
        }
        List<RoleType> deck = new ArrayList<>();
        for (int i = 0; i < werewolfCount; i++) {
            deck.add(RoleType.WEREWOLF);
        }
        deck.add(RoleType.SEER);
        deck.add(RoleType.WITCH);
        while (deck.size() < totalPlayers) {
            deck.add(RoleType.VILLAGER);
        }
        if (deck.size() > totalPlayers) {
            throw new IllegalArgumentException(
                    "角色数量(" + deck.size() + ")超过玩家人数(" + totalPlayers + ")");
        }
        return deck;
    }

    /**
     * 打乱并分配角色。
     *
     * @param players        玩家列表（座位号已确定）
     * @param werewolfCount  狼人数量
     * @param random         随机源（测试可注入固定种子）
     */
    public static void assign(List<PlayerState> players, int werewolfCount, Random random) {
        assignDeck(players, buildDeck(players.size(), werewolfCount), random);
    }

    public static void assign(List<PlayerState> players, java.util.Map<String,Integer> counts, Random random) {
        assignDeck(players, RoleConfiguration.buildDeck(players.size(), counts), random);
    }

    private static void assignDeck(List<PlayerState> players, List<RoleType> deck, Random random) {
        int total = players.size();
        if (total < 4) {
            throw new IllegalArgumentException("至少需要 4 名玩家才能开始游戏");
        }
        Collections.shuffle(deck, random);
        for (int i = 0; i < total; i++) {
            PlayerState p = players.get(i);
            p.setRole(deck.get(i));
            p.setAlive(true);
            p.setLoverSeatId(null);p.setIdiotRevealed(false);p.setLastGuardSeatId(null);
            p.setDeathCause(null);p.setDeathSkillUsed(false);
            // 女巫药水重置
            p.setHealPotionAvailable(true);
            p.setPoisonPotionAvailable(true);
            // 身份确认状态重置：新的一局必须重新确认
            p.setRoleConfirmed(false);
            p.getCheckRecords().clear();
        }
    }

    /** 校验房间角色配置是否合法（房间创建/开始前调用）。 */
    public static void validateConfig(int totalPlayers, int werewolfCount) {
        if (totalPlayers < 4 || totalPlayers > 13) {
            throw new IllegalArgumentException("房间人数必须在 4 ~ 13 之间");
        }
        if (totalPlayers == 13 && werewolfCount != 4) throw new IllegalArgumentException("13 人标准局必须为 4 狼");
        int maxWolves = maxWerewolfCount(totalPlayers);
        if (werewolfCount < 1 || werewolfCount > maxWolves) {
            throw new IllegalArgumentException(
                    "狼人数量必须在 1 ~ " + maxWolves + " 之间（需为预言家和女巫留出座位）");
        }
    }

    /** 给定人数的狼人数量上限：同时满足"少于一半"和"放得下两个神职"。 */
    public static int maxWerewolfCount(int totalPlayers) {
        if(totalPlayers==13)return 4;
        return Math.max(0, Math.min((totalPlayers - 1) / 2, totalPlayers - REQUIRED_SPECIAL_ROLES));
    }
}
