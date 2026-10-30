package com.werewolf.community;
import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
@TableName("post_likes")
public class PostLike {
 @TableId(type=IdType.AUTO)
 private Long id;
 public Long getId(){return id;}
 public void setId(Long v){id=v;}
 private Long postId;
 public Long getPostId(){return postId;}
 public void setPostId(Long v){postId=v;}
 private Long userId;
 public Long getUserId(){return userId;}
 public void setUserId(Long v){userId=v;}
 private LocalDateTime createdAt;
 public LocalDateTime getCreatedAt(){return createdAt;}
 public void setCreatedAt(LocalDateTime v){createdAt=v;}
}
