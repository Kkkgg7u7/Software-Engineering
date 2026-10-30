package com.werewolf.conversation;
import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
@TableName("conversation_members")
public class ConversationMember {
 @TableId(type=IdType.AUTO)
 private Long id;
 public Long getId(){return id;}
 public void setId(Long v){id=v;}
 private Long conversationId;
 public Long getConversationId(){return conversationId;}
 public void setConversationId(Long v){conversationId=v;}
 private Long userId;
 public Long getUserId(){return userId;}
 public void setUserId(Long v){userId=v;}
 private Boolean active;
 public Boolean getActive(){return active;}
 public void setActive(Boolean v){active=v;}
 private Long readMessageId;
 public Long getReadMessageId(){return readMessageId;}
 public void setReadMessageId(Long v){readMessageId=v;}
 private LocalDateTime joinedAt;
 public LocalDateTime getJoinedAt(){return joinedAt;}
 public void setJoinedAt(LocalDateTime v){joinedAt=v;}
}
