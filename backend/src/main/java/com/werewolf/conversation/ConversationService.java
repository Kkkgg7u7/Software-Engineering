package com.werewolf.conversation;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.werewolf.common.*;
import com.werewolf.conversation.mapper.*;
import com.werewolf.friendship.FriendService;
import com.werewolf.media.MediaService;
import com.werewolf.user.UserDirectoryService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class ConversationService {
 private final ConversationMapper conversations;
 private final ConversationMemberMapper members;
 private final ConversationMessageMapper messages;
 private final FriendService friends;
 private final UserDirectoryService users;
 private final MediaService media;
 private final TransactionTemplate tx;
 private final ApplicationEventPublisher events;
 public ConversationService(ConversationMapper c,ConversationMemberMapper m,ConversationMessageMapper msg,FriendService f,UserDirectoryService u,MediaService media,PlatformTransactionManager tm,ApplicationEventPublisher e){conversations=c;members=m;messages=msg;friends=f;users=u;this.media=media;tx=new TransactionTemplate(tm);events=e;}
 public record MessageView(Long id,Long conversationId,UserDirectoryService.PublicUser sender,String clientMessageId,String contentType,String content,String mediaId,String relatedCode,LocalDateTime createdAt){}
 public record View(Long id,String type,String title,String code,Long createdBy,int memberCount,UserDirectoryService.PublicUser peer,MessageView lastMessage,long unreadCount,LocalDateTime updatedAt){}
 public record History(List<MessageView> items,Long nextBeforeId,boolean hasMore){}
 public record Changed(List<Long> users,Long conversationId,MessageView message){}
 private Conversation require(Long id){var c=conversations.selectById(id);if(c==null)throw BizException.of(ErrorCode.NOT_FOUND,"会话不存在");return c;}
 private ConversationMember member(Long cid,Long uid){return members.selectOne(Wrappers.<ConversationMember>lambdaQuery().eq(ConversationMember::getConversationId,cid).eq(ConversationMember::getUserId,uid));}
 public ConversationMember requireMember(Long cid,Long uid){var m=member(cid,uid);if(m==null||!Boolean.TRUE.equals(m.getActive()))throw BizException.of(ErrorCode.FORBIDDEN,"你不是该会话的成员");return m;}
 private List<ConversationMember> active(Long id){return members.selectList(Wrappers.<ConversationMember>lambdaQuery().eq(ConversationMember::getConversationId,id).eq(ConversationMember::getActive,true).orderByAsc(ConversationMember::getId));}
 private void addMember(Long cid,Long uid){var m=member(cid,uid);if(m!=null){m.setActive(true);members.updateById(m);return;}m=new ConversationMember();m.setConversationId(cid);m.setUserId(uid);m.setActive(true);m.setReadMessageId(0L);m.setJoinedAt(LocalDateTime.now());members.insert(m);}
 private MessageView messageView(ConversationMessage m){return m==null?null:new MessageView(m.getId(),m.getConversationId(),users.profile(m.getSenderId()),m.getClientMessageId(),m.getContentType(),m.getContent(),m.getMediaId(),m.getRelatedCode(),m.getCreatedAt());}
 private ConversationMessage latest(Long cid){return messages.selectOne(Wrappers.<ConversationMessage>lambdaQuery().eq(ConversationMessage::getConversationId,cid).orderByDesc(ConversationMessage::getId).last("LIMIT 1"));}
 private View view(Conversation c,Long uid){var own=requireMember(c.getId(),uid);var all=active(c.getId());var peer="DIRECT".equals(c.getType())?all.stream().filter(m->!uid.equals(m.getUserId())).findFirst().map(m->users.profile(m.getUserId())).orElse(null):null;
  long unread=messages.selectCount(Wrappers.<ConversationMessage>lambdaQuery().eq(ConversationMessage::getConversationId,c.getId()).gt(ConversationMessage::getId,own.getReadMessageId()).ne(ConversationMessage::getSenderId,uid));
  return new View(c.getId(),c.getType(),peer==null?c.getTitle():peer.nickname(),c.getCode(),c.getCreatedBy(),all.size(),peer,messageView(latest(c.getId())),unread,c.getUpdatedAt());}
 private void changed(Long cid,MessageView msg){events.publishEvent(new Changed(active(cid).stream().map(ConversationMember::getUserId).toList(),cid,msg));}
 private String text(String s,int max){if(s==null||s.isBlank()||s.codePointCount(0,s.length())>max)throw BizException.of(ErrorCode.BAD_REQUEST,"内容不能为空或超过"+max+"字");return s.strip();}
 private void clientId(String id){if(id==null||!id.matches("[A-Za-z0-9_-]{8,64}"))throw BizException.of(ErrorCode.BAD_REQUEST,"消息请求标识不合法");}
 private Conversation directInternal(Long uid,Long target){friends.requireFriends(uid,target);String key=Math.min(uid,target)+":"+Math.max(uid,target);var c=conversations.selectOne(Wrappers.<Conversation>lambdaQuery().eq(Conversation::getDirectKey,key));if(c!=null)return c;c=new Conversation();c.setType("DIRECT");c.setDirectKey(key);c.setCreatedBy(uid);c.setClosed(false);c.setCreatedAt(LocalDateTime.now());c.setUpdatedAt(c.getCreatedAt());conversations.insert(c);addMember(c.getId(),uid);addMember(c.getId(),target);changed(c.getId(),null);return c;}
 public synchronized View direct(Long uid,Long target){if(target==null||uid.equals(target))throw BizException.of(ErrorCode.BAD_REQUEST);return tx.execute(s->view(directInternal(uid,target),uid));}
 public PageResult<View> list(Long uid,int page,int size){if(page<1||page>10000||size<1||size>50)throw BizException.of(ErrorCode.BAD_REQUEST);var ids=activeFor(uid);if(ids.isEmpty())return new PageResult<>(List.of(),0,page,size);var q=Wrappers.<Conversation>lambdaQuery().in(Conversation::getId,ids).eq(Conversation::getClosed,false);long count=conversations.selectCount(q);q.orderByDesc(Conversation::getUpdatedAt).orderByDesc(Conversation::getId).last("LIMIT "+size+" OFFSET "+((page-1)*size));return new PageResult<>(conversations.selectList(q).stream().map(c->view(c,uid)).toList(),count,page,size);}
 private List<Long> activeFor(Long uid){return members.selectList(Wrappers.<ConversationMember>lambdaQuery().eq(ConversationMember::getUserId,uid).eq(ConversationMember::getActive,true)).stream().map(ConversationMember::getConversationId).toList();}
 public View get(Long uid,Long cid){requireMember(cid,uid);return view(require(cid),uid);}
 public History history(Long uid,Long cid,Long before,int limit){requireMember(cid,uid);if(limit<1||limit>50||before!=null&&before<=0)throw BizException.of(ErrorCode.BAD_REQUEST);var q=Wrappers.<ConversationMessage>lambdaQuery().eq(ConversationMessage::getConversationId,cid);if(before!=null)q.lt(ConversationMessage::getId,before);var rows=messages.selectList(q.orderByDesc(ConversationMessage::getId).last("LIMIT "+(limit+1)));boolean more=rows.size()>limit;if(more)rows=new ArrayList<>(rows.subList(0,limit));Collections.reverse(rows);return new History(rows.stream().map(this::messageView).toList(),rows.isEmpty()?null:rows.get(0).getId(),more);}
 private ConversationMessage duplicate(Long uid,String key){return messages.selectOne(Wrappers.<ConversationMessage>lambdaQuery().eq(ConversationMessage::getSenderId,uid).eq(ConversationMessage::getClientMessageId,key));}
 private MessageView append(Long uid,Conversation c,String key,String type,String content,String mediaId,String code){clientId(key);var old=duplicate(uid,key);if(old!=null){if(!c.getId().equals(old.getConversationId())||!Objects.equals(type,old.getContentType())||!Objects.equals(content,old.getContent())||!Objects.equals(mediaId,old.getMediaId())||!Objects.equals(code,old.getRelatedCode()))throw BizException.of(ErrorCode.BAD_REQUEST,"消息标识已用于其他内容");return messageView(old);}
  var m=new ConversationMessage();m.setConversationId(c.getId());m.setSenderId(uid);m.setClientMessageId(key);m.setContentType(type);m.setContent(content);m.setMediaId(mediaId);m.setRelatedCode(code);m.setCreatedAt(LocalDateTime.now());messages.insert(m);c.setUpdatedAt(m.getCreatedAt());conversations.updateById(c);var v=messageView(m);changed(c.getId(),v);return v;}
 public synchronized MessageView send(Long uid,Long cid,String key,String type,String content,String mediaId){return tx.execute(s->{requireMember(cid,uid);var c=require(cid);if(Boolean.TRUE.equals(c.getClosed()))throw BizException.of(ErrorCode.BAD_REQUEST,"会话已关闭");if("DIRECT".equals(c.getType())){Long peer=active(cid).stream().map(ConversationMember::getUserId).filter(id->!uid.equals(id)).findFirst().orElseThrow();friends.requireFriends(uid,peer);}if("TEXT".equals(type)){contentCheck(content);return append(uid,c,key,type,content.strip(),null,null);}if("IMAGE".equals(type)){var asset=media.require(mediaId);if(!uid.equals(asset.getOwnerId())||!"PRIVATE".equals(asset.getPurpose()))throw BizException.of(ErrorCode.FORBIDDEN,"只能发送自己上传的聊天图片");return append(uid,c,key,type,null,mediaId,null);}throw BizException.of(ErrorCode.BAD_REQUEST,"不支持的消息类型");});}
 private void contentCheck(String content){text(content,2000);}
 public synchronized void read(Long uid,Long cid,Long id){tx.executeWithoutResult(s->{var m=requireMember(cid,uid);var target=id==null?latest(cid):messages.selectById(id);if(target==null){if(id!=null)throw BizException.of(ErrorCode.BAD_REQUEST);return;}if(!cid.equals(target.getConversationId()))throw BizException.of(ErrorCode.BAD_REQUEST,"消息不属于此会话");if(target.getId()>m.getReadMessageId()){m.setReadMessageId(target.getId());members.updateById(m);events.publishEvent(new Changed(List.of(uid),cid,null));}});}
 public synchronized View createGroup(Long uid,String title){String name=text(title,40);return tx.execute(s->{var c=new Conversation();c.setType("GROUP");c.setTitle(name);c.setCode(UUID.randomUUID().toString().replace("-","").substring(0,8).toUpperCase(Locale.ROOT));c.setCreatedBy(uid);c.setClosed(false);c.setCreatedAt(LocalDateTime.now());c.setUpdatedAt(c.getCreatedAt());conversations.insert(c);addMember(c.getId(),uid);changed(c.getId(),null);return view(c,uid);});}
 public synchronized View join(Long uid,String code){if(code==null||!code.matches("[A-Za-z0-9]{8}"))throw BizException.of(ErrorCode.BAD_REQUEST,"群码为8位字母数字");return tx.execute(s->{var c=conversations.selectOne(Wrappers.<Conversation>lambdaQuery().eq(Conversation::getCode,code.toUpperCase(Locale.ROOT)).eq(Conversation::getClosed,false));if(c==null)throw BizException.of(ErrorCode.NOT_FOUND,"群不存在或已解散");var m=member(c.getId(),uid);if(m==null||!Boolean.TRUE.equals(m.getActive())){if(active(c.getId()).size()>=50)throw BizException.of(ErrorCode.BAD_REQUEST,"群人数已满");addMember(c.getId(),uid);changed(c.getId(),null);}return view(c,uid);});}
 private Conversation group(Long uid,Long cid){requireMember(cid,uid);var c=require(cid);if(!"GROUP".equals(c.getType()))throw BizException.of(ErrorCode.BAD_REQUEST,"这不是群聊");return c;}
 public List<UserDirectoryService.PublicUser> groupMembers(Long uid,Long cid){group(uid,cid);return active(cid).stream().map(m->users.profile(m.getUserId())).toList();}
 public synchronized View rename(Long uid,Long cid,String title){String name=text(title,40);return tx.execute(s->{var c=group(uid,cid);if(!uid.equals(c.getCreatedBy()))throw BizException.of(ErrorCode.FORBIDDEN,"仅群主可以修改群名");c.setTitle(name);c.setUpdatedAt(LocalDateTime.now());conversations.updateById(c);changed(cid,null);return view(c,uid);});}
 public synchronized void leave(Long uid,Long cid){tx.executeWithoutResult(s->{var c=group(uid,cid);var old=active(cid).stream().map(ConversationMember::getUserId).toList();var m=requireMember(cid,uid);m.setActive(false);members.updateById(m);var remaining=active(cid);if(remaining.isEmpty())c.setClosed(true);else if(uid.equals(c.getCreatedBy()))c.setCreatedBy(remaining.get(0).getUserId());c.setUpdatedAt(LocalDateTime.now());conversations.updateById(c);events.publishEvent(new Changed(old,cid,null));});}
 public synchronized MessageView invite(Long uid,Long cid,Long target,String key){return tx.execute(s->{var c=group(uid,cid);var direct=directInternal(uid,target);return append(uid,direct,key,"GROUP_INVITE",c.getTitle(),null,c.getCode());});}
 public synchronized MessageView gameInvite(Long uid,Long cid,Long target,String key,String roomCode,String roomName){return tx.execute(s->{Conversation c;if(target!=null){if(cid!=null)throw BizException.of(ErrorCode.BAD_REQUEST,"邀请目标不能重复指定");c=directInternal(uid,target);}else{if(cid==null)throw BizException.of(ErrorCode.BAD_REQUEST,"请选择会话");requireMember(cid,uid);c=require(cid);if(Boolean.TRUE.equals(c.getClosed()))throw BizException.of(ErrorCode.BAD_REQUEST,"会话已关闭");if("DIRECT".equals(c.getType()))friends.requireFriends(uid,active(cid).stream().map(ConversationMember::getUserId).filter(id->!uid.equals(id)).findFirst().orElseThrow());}return append(uid,c,key,"GAME_INVITE",roomName,null,roomCode);});}

}
