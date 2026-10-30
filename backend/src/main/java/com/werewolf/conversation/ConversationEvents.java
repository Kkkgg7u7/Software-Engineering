package com.werewolf.conversation;
import com.werewolf.websocket.GameEventMessenger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import java.util.Map;
@Component
public class ConversationEvents {
 private final GameEventMessenger messenger;
 public ConversationEvents(GameEventMessenger m){messenger=m;}
 @TransactionalEventListener public void changed(ConversationService.Changed e){for(Long uid:e.users())messenger.sendToUser(uid,"/queue/social",e.message()==null?"CONVERSATION_UPDATE":"CHAT_MESSAGE",e.message()==null?Map.of("conversationId",e.conversationId()):e.message());}
}
