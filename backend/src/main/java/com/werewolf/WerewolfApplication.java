package com.werewolf;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 基于 AI 增强的多人在线狼人杀系统 —— 后端入口。
 *
 * <p>架构约定（见 docs/ARCHITECTURE.md）：
 * <ul>
 *   <li>单体应用，同一进程内提供 REST 与 WebSocket(STOMP)；</li>
 *   <li>服务器权威：所有游戏状态变更只发生在 {@code com.werewolf.game.engine.WerewolfGameEngine}；</li>
 *   <li>AI 只产生“建议行动 / 发言”，必须经过 GameEngine 校验后才能生效。</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.werewolf.**.mapper")
@EnableScheduling
public class WerewolfApplication {

    public static void main(String[] args) {
        SpringApplication.run(WerewolfApplication.class, args);
    }
}
