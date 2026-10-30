package com.werewolf.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.werewolf.user.entity.User;
import com.werewolf.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 演示账号初始化（仅 h2 profile：本地零依赖演示 / 集成测试）。
 *
 * <p>为什么用代码而不是 data.sql：BCrypt 哈希必须由 {@link PasswordEncoder} 现场生成，
 * 写死在 SQL 里既不可读也容易与编码器版本不一致。
 *
 * <p>演示账号：demo / player1~player4，密码统一 demo1234。
 *
 * <p>开关方式：本类通过 {@code @Profile("h2")} 生效 —— 只在本地零依赖演示 profile 下创建，
 * 使用 mysql profile 的生产环境不会写入任何演示数据。
 */
@Component
@Profile("h2")
public class DemoDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    /** 演示账号清单：用户名 / 密码 / 昵称。 */
    private static final List<String[]> DEMO_USERS = List.of(
            new String[]{"demo", "demo1234", "演示玩家"},
            new String[]{"player1", "demo1234", "1号玩家"},
            new String[]{"player2", "demo1234", "2号玩家"},
            new String[]{"player3", "demo1234", "3号玩家"},
            new String[]{"player4", "demo1234", "4号玩家"});

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public DemoDataInitializer(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        int created = 0;
        int index = 0;
        for (String[] row : DEMO_USERS) {
            index++;
            String username = row[0];
            Long exists = userMapper.selectCount(Wrappers.<User>lambdaQuery()
                    .eq(User::getUsername, username));
            if (exists != null && exists > 0) {
                continue;
            }
            User user = new User();
            user.setUsername(username);
            user.setPassword(passwordEncoder.encode(row[1]));
            user.setNickname(row[2]);
            user.setAvatar("/assets/avatars/bot-%02d.svg".formatted(index));
            user.setTotalGames(0);
            user.setWinGames(0);
            user.setAuthVersion(0);
            user.setCreatedAt(LocalDateTime.now());
            userMapper.insert(user);
            created++;
        }
        if (created > 0) {
            log.info("已创建 {} 个演示账号（密码统一为 demo1234），可直接用 demo 登录", created);
        } else {
            log.info("演示账号已存在，跳过初始化");
        }
    }
}
