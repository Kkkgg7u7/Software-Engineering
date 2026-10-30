package com.werewolf.community;
import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
@TableName("post_images")
public class PostImage {
 @TableId(type=IdType.AUTO)
 private Long id;
 public Long getId(){return id;}
 public void setId(Long v){id=v;}
 private Long postId;
 public Long getPostId(){return postId;}
 public void setPostId(Long v){postId=v;}
 private String mediaId;
 public String getMediaId(){return mediaId;}
 public void setMediaId(String v){mediaId=v;}
 private Integer sortOrder;
 public Integer getSortOrder(){return sortOrder;}
 public void setSortOrder(Integer v){sortOrder=v;}
}
