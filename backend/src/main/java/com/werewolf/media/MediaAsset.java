package com.werewolf.media;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

@TableName("media_assets")
public class MediaAsset {
    @TableId(type=IdType.INPUT)
    private String id;
    public String getId() { return id; }
    public void setId(String value) { id = value; }
    private Long ownerId;
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long value) { ownerId = value; }
    private String storageName;
    public String getStorageName() { return storageName; }
    public void setStorageName(String value) { storageName = value; }
    private String purpose;
    public String getPurpose() { return purpose; }
    public void setPurpose(String value) { purpose = value; }
    private String contentType;
    public String getContentType() { return contentType; }
    public void setContentType(String value) { contentType = value; }
    private Long sizeBytes;
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long value) { sizeBytes = value; }
    private Integer width;
    public Integer getWidth() { return width; }
    public void setWidth(Integer value) { width = value; }
    private Integer height;
    public Integer getHeight() { return height; }
    public void setHeight(Integer value) { height = value; }
    private LocalDateTime createdAt;
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
}
