package com.werewolf.friendship;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

@TableName("friendships")
public class FriendRelation {
    @TableId(type=IdType.AUTO)
    private Long id;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    private Long userLow;
    public Long getUserLow() { return userLow; }
    public void setUserLow(Long value) { userLow = value; }
    private Long userHigh;
    public Long getUserHigh() { return userHigh; }
    public void setUserHigh(Long value) { userHigh = value; }
    private Long requesterId;
    public Long getRequesterId() { return requesterId; }
    public void setRequesterId(Long value) { requesterId = value; }
    private String status;
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    private LocalDateTime createdAt;
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    private LocalDateTime updatedAt;
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
