package com.werewolf.conversation;
import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
@TableName("conversations")
public class Conversation {
 @TableId(type=IdType.AUTO)
 private Long id;
 public Long getId(){return id;}
 public void setId(Long v){id=v;}
 private String type;
 public String getType(){return type;}
 public void setType(String v){type=v;}
 private String directKey;
 public String getDirectKey(){return directKey;}
 public void setDirectKey(String v){directKey=v;}
 private String code;
 public String getCode(){return code;}
 public void setCode(String v){code=v;}
 private String title;
 public String getTitle(){return title;}
 public void setTitle(String v){title=v;}
 private Long createdBy;
 public Long getCreatedBy(){return createdBy;}
 public void setCreatedBy(Long v){createdBy=v;}
 private Boolean closed;
 public Boolean getClosed(){return closed;}
 public void setClosed(Boolean v){closed=v;}
 private LocalDateTime createdAt;
 public LocalDateTime getCreatedAt(){return createdAt;}
 public void setCreatedAt(LocalDateTime v){createdAt=v;}
 private LocalDateTime updatedAt;
 public LocalDateTime getUpdatedAt(){return updatedAt;}
 public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
