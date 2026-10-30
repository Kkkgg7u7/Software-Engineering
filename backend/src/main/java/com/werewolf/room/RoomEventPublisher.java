package com.werewolf.room;

import com.werewolf.room.dto.RoomView;
import com.werewolf.room.runtime.RoomRuntime;
import com.werewolf.websocket.GameEventMessenger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 房间状态广播器 —— 房间成员发生变化时通知所有相关方。
 *
 * <p><b>为什么需要它（修 F-12 引出的第二个缺陷）</b>：
 * 早期实现里，只有"准备"和"踢人"会触发 WebSocket 广播，
 * <b>加入房间与退出房间完全没有广播</b>。后果是：
 * <ul>
 *   <li>房间里的玩家看不到新进来的人（座位一直是空的，必须刷新页面）；</li>
 *   <li>更糟的是，前端为弥补这一点会在别处推断自己的座位，容易串到别人身上。</li>
 * </ul>
 *
 * <p><b>广播的两条通道必须分开</b>：
 * <ul>
 *   <li>公共主题 {@code /topic/room/{code}}：只发不含个人视角的公开快照
 *       （{@code mySeatId=null}），供房间内所有人 + 观战者使用；</li>
 *   <li>私有队列 {@code /user/queue/room}：每个玩家自己的视图
 *       （含 {@code mySeatId} / {@code iAmReady}），<b>绝不能进公共主题</b>，
 *       否则最后一条会覆盖前面的，房主会看到「我」标记落在别人座位上。</li>
 * </ul>
 */
@Component
public class RoomEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RoomEventPublisher.class);

    private final GameEventMessenger messenger;
    private final org.springframework.beans.factory.ObjectProvider<com.werewolf.voice.VoiceService> voice;

    public RoomEventPublisher(GameEventMessenger messenger, org.springframework.beans.factory.ObjectProvider<com.werewolf.voice.VoiceService> voice) {
        this.voice=voice;
        this.messenger = messenger;
    }

    /**
     * 广播房间状态：一条公共快照 + 每人一条私有视图。
     *
     * <p>成员发生变化（加入 / 退出 / 踢人 / 准备 / 改配置）后都应调用。
     */
    public void broadcast(RoomRuntime runtime) {
        if (runtime == null) {
            return;
        }
        try {
            var service=voice.getIfAvailable();if(service!=null)service.sweep();
            // 1) 公共快照：不含任何人的私有字段
            messenger.roomUpdate(runtime.getRoom(), runtime.roomView(null));

            // 2) 每个玩家自己的视图，只投递到其私有队列
            Map<Integer, Long> seatMap = runtime.seatUserMap();
            int sent = 0;
            for (Map.Entry<Integer, Long> entry : seatMap.entrySet()) {
                Long userId = entry.getValue();
                if (userId == null || userId <= 0) {
                    continue;
                }
                messenger.roomUpdatePrivate(runtime.getRoom(), userId, runtime.roomView(userId));
                sent++;
            }
            log.debug("房间 {} 状态已广播：公共快照 + {} 条私有视图",
                    runtime.getRoom().getRoomCode(), sent);
        } catch (Exception ex) {
            // 广播失败不能影响业务事务：房间状态已经改好了，前端下次同步会补上
            log.warn("房间 {} 状态广播失败: {}", runtime.getRoom().getRoomCode(), ex.getMessage());
        }
    }

    public void reconcileVoice(){var service=voice.getIfAvailable();if(service!=null)service.sweep();}

    /** 全员系统提示（走公共主题）。 */
    public void notice(RoomRuntime runtime, String message) {
        if (runtime == null || message == null) {
            return;
        }
        try {
            runtime.broadcastNotice(message);
        } catch (Exception ex) {
            log.warn("房间提示广播失败: {}", ex.getMessage());
        }
    }
}
