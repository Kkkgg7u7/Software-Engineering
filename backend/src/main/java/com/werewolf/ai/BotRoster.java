package com.werewolf.ai;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI NPC 花名册：昵称 + 本地头像素材（frontend/public/assets/avatars/bot-XX.svg）。
 *
 * <p>头像已提前生成并随前端一起部署，运行时不产生任何生成成本（方案第九节）。
 */
@Component
public class BotRoster {

    private static final List<String> NAMES = List.of(
            "AI·月影", "AI·银月", "AI·猎风", "AI·灰羽", "AI·夜莺", "AI·长夜",
            "AI·冷杉", "AI·烬火", "AI·霜刃", "AI·微光", "AI·沉钟", "AI·流沙");

    private final AtomicInteger cursor = new AtomicInteger();

    public String nextName() {
        return NAMES.get(Math.floorMod(cursor.getAndIncrement(), NAMES.size()));
    }

    /** 头像与昵称槽位对应，避免所有 AI 长一样。 */
    public String nextAvatar() {
        int index = Math.floorMod(cursor.get() - 1, 12) + 1;
        return "/assets/avatars/bot-%02d.svg".formatted(index);
    }

    public int size() {
        return NAMES.size();
    }
}
