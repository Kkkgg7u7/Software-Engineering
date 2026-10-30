package com.werewolf.community;
import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
@TableName("post_comments")
public class PostComment {
 @TableId(type=IdType.AUTO)
 private Long id;
 public Long getId(){return id;}
 public void setId(Long v){id=v;}
 private Long postId;
 public Long getPostId(){return postId;}
 public void setPostId(Long v){postId=v;}
 private Long authorId;
 public Long getAuthorId(){return authorId;}
 public void setAuthorId(Long v){authorId=v;}
 private String clientRequestId;
 public String getClientRequestId(){return clientRequestId;}
 public void setClientRequestId(String v){clientRequestId=v;}
 private String content;
 public String getContent(){return content;}
 public void setContent(String v){content=v;}
 private Boolean deleted;
 public Boolean getDeleted(){return deleted;}
 public void setDeleted(Boolean v){deleted=v;}
 private LocalDateTime createdAt;
 public LocalDateTime getCreatedAt(){return createdAt;}
 public void setCreatedAt(LocalDateTime v){createdAt=v;}
}
