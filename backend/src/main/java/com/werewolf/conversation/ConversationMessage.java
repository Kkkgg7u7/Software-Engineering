package com.werewolf.conversation;
import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
@TableName("conversation_messages")
public class ConversationMessage {
 @TableId(type=IdType.AUTO)
 private Long id;
 public Long getId(){return id;}
 public void setId(Long v){id=v;}
 private Long conversationId;
 public Long getConversationId(){return conversationId;}
 public void setConversationId(Long v){conversationId=v;}
 private Long senderId;
 public Long getSenderId(){return senderId;}
 public void setSenderId(Long v){senderId=v;}
 private String clientMessageId;
 public String getClientMessageId(){return clientMessageId;}
 public void setClientMessageId(String v){clientMessageId=v;}
 private String contentType;
 public String getContentType(){return contentType;}
 public void setContentType(String v){contentType=v;}
 private String content;
 public String getContent(){return content;}
 public void setContent(String v){content=v;}
 private String mediaId;
 public String getMediaId(){return mediaId;}
 public void setMediaId(String v){mediaId=v;}
 private String relatedCode;
 public String getRelatedCode(){return relatedCode;}
 public void setRelatedCode(String v){relatedCode=v;}
 private LocalDateTime createdAt;
 public LocalDateTime getCreatedAt(){return createdAt;}
 public void setCreatedAt(LocalDateTime v){createdAt=v;}
}
