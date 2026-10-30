package com.werewolf.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * JWT 与 CORS 配置（werewolf.jwt / werewolf.cors）。
 */
@ConfigurationProperties(prefix = "werewolf")
public class AppProperties {

    private final Jwt jwt = new Jwt();
    private final Cors cors = new Cors();
    private final Room room = new Room();
    private final Phase phase = new Phase();

    public Jwt getJwt() {
        return jwt;
    }

    public Cors getCors() {
        return cors;
    }

    public Room getRoom() {
        return room;
    }

    public Phase getPhase() {
        return phase;
    }

    public static class Jwt {
        private String secret = "werewolf-dev-secret-key-please-change-in-production-2024";
        private long expireMinutes = 1440;
        private String issuer = "werewolf-game";

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public long getExpireMinutes() {
            return expireMinutes;
        }

        public void setExpireMinutes(long expireMinutes) {
            this.expireMinutes = expireMinutes;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }
    }

    /**
     * CORS / WebSocket 允许的来源。
     *
     * <p>这里配的是 <b>origin 模式</b>（不是精确字符串），因此支持通配。
     * 默认值覆盖了三种"别人从自己电脑连进来"的场景：
     * <ul>
     *   <li>{@code localhost / 127.0.0.1}：本机开发与演示；</li>
     *   <li>{@code http://192.168.*.*:*} 等私网网段：同一局域网内其他电脑访问你的机器；</li>
     *   <li>{@code https://*.trycloudflare.com}、{@code https://*.ngrok-free.app}：内网穿透临时域名。</li>
     * </ul>
     *
     * <p>生产部署时建议用环境变量 {@code WEREWOLF_CORS_ORIGINS} 收窄成真实域名，
     * 例如 {@code https://werewolf.example.com}。
     */
    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>(List.of(
                "http://localhost:*", "http://127.0.0.1:*",
                "http://192.168.*.*:*", "http://10.*.*.*:*",
                "http://172.16.*.*:*", "http://172.17.*.*:*", "http://172.18.*.*:*",
                "http://172.19.*.*:*", "http://172.2?.*.*:*", "http://172.30.*.*:*", "http://172.31.*.*:*",
                "https://*.trycloudflare.com", "https://*.ngrok-free.app", "https://*.ngrok.io"));

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }

    public static class Room {
        private int idleTimeoutMinutes = 120;

        public int getIdleTimeoutMinutes() {
            return idleTimeoutMinutes;
        }

        public void setIdleTimeoutMinutes(int idleTimeoutMinutes) {
            this.idleTimeoutMinutes = idleTimeoutMinutes;
        }
    }

    /** 各阶段时长（秒），可在 application.yml 里按演示需要调节。 */
    public static class Phase {
        private int startingSeconds = 6;
        /** 身份确认阶段：给足时间让所有人看清自己的身份。 */
        private int confirmSeconds = 60;
        /** 夜晚行动：狼人/预言家/女巫合计 3 分钟。 */
        private int nightSeconds = 180;
        private int daySeconds = 20;
        /** 讨论发言：<b>每人</b> 5 分钟（不是整轮总时长）。 */
        private int discussionSeconds = 300;
        /** 投票：3 分钟。 */
        private int votingSeconds = 180;
        private int checkWinnerSeconds = 4;
        /** 预言家查验后的翻牌确认窗口（秒）。 */
        private int seerConfirmSeconds = 30;

        public int getStartingSeconds() {
            return startingSeconds;
        }

        public void setStartingSeconds(int startingSeconds) {
            this.startingSeconds = startingSeconds;
        }

        public int getConfirmSeconds() {
            return confirmSeconds;
        }

        public void setConfirmSeconds(int confirmSeconds) {
            this.confirmSeconds = confirmSeconds;
        }

        public int getNightSeconds() {
            return nightSeconds;
        }

        public void setNightSeconds(int nightSeconds) {
            this.nightSeconds = nightSeconds;
        }

        public int getDaySeconds() {
            return daySeconds;
        }

        public void setDaySeconds(int daySeconds) {
            this.daySeconds = daySeconds;
        }

        public int getDiscussionSeconds() {
            return discussionSeconds;
        }

        public void setDiscussionSeconds(int discussionSeconds) {
            this.discussionSeconds = discussionSeconds;
        }

        public int getVotingSeconds() {
            return votingSeconds;
        }

        public void setVotingSeconds(int votingSeconds) {
            this.votingSeconds = votingSeconds;
        }

        public int getCheckWinnerSeconds() {
            return checkWinnerSeconds;
        }

        public void setCheckWinnerSeconds(int checkWinnerSeconds) {
            this.checkWinnerSeconds = checkWinnerSeconds;
        }

        public int getSeerConfirmSeconds() {
            return seerConfirmSeconds;
        }

        public void setSeerConfirmSeconds(int seerConfirmSeconds) {
            this.seerConfirmSeconds = seerConfirmSeconds;
        }

        /** 把配置的秒数映射到阶段枚举，保证引擎与配置一致。 */
        public int secondsOf(com.werewolf.game.domain.GamePhase phase) {
            return switch (phase) {
                case STARTING -> startingSeconds;
                case CONFIRM -> confirmSeconds;
                case NIGHT -> nightSeconds;
                case DAY -> daySeconds;
                case DISCUSSION -> discussionSeconds;
                case VOTING -> votingSeconds;
                case CHECK_WINNER -> checkWinnerSeconds;
                default -> phase.getDefaultSeconds();
            };
        }
    }
}
