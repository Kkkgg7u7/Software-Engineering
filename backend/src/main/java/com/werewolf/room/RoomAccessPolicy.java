package com.werewolf.room;

import com.werewolf.common.BizException;
import com.werewolf.common.ErrorCode;
import com.werewolf.room.runtime.RoomManager;
import com.werewolf.room.runtime.RoomRuntime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class RoomAccessPolicy {
    private final ObjectProvider<RoomManager> rooms;
    public RoomAccessPolicy(ObjectProvider<RoomManager> rooms) { this.rooms = rooms; }

    public boolean isMember(String code, Long userId) {
        RoomRuntime room = rooms.getObject().byCode(code);
        return room != null && userId != null && room.getState().byUser(userId) != null;
    }

    public void requireDetailsAccess(RoomRuntime room, Long userId) {
        if (!Boolean.TRUE.equals(room.getRoom().getPublicRoom()) && !isMember(room.getRoom().getRoomCode(), userId)) {
            throw BizException.of(ErrorCode.FORBIDDEN, "只有房间成员可以查看私人房间");
        }
    }
}
