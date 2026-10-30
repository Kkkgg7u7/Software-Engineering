package com.werewolf.conversation;
import com.werewolf.conversation.mapper.ConversationMessageMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
@Component
public class ChatMediaAccess {
 private final ConversationMessageMapper messages;
 private final com.werewolf.conversation.mapper.ConversationMemberMapper members;
 public ChatMediaAccess(ConversationMessageMapper msg,com.werewolf.conversation.mapper.ConversationMemberMapper m){messages=msg;members=m;}
 public boolean allowed(Long uid,String media){if(uid==null)return false;var ids=messages.selectList(Wrappers.<ConversationMessage>lambdaQuery().eq(ConversationMessage::getMediaId,media)).stream().map(ConversationMessage::getConversationId).distinct().toList();return !ids.isEmpty()&&members.selectCount(Wrappers.<ConversationMember>lambdaQuery().in(ConversationMember::getConversationId,ids).eq(ConversationMember::getUserId,uid).eq(ConversationMember::getActive,true))>0;}
}
