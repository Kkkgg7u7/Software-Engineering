package com.werewolf.friendship;
import com.werewolf.websocket.GameEventMessenger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import java.util.Map;

@Component
public class FriendEvents {
    private final GameEventMessenger messenger;
    public FriendEvents(GameEventMessenger messenger){this.messenger=messenger;}
    @TransactionalEventListener
    public void changed(FriendService.Changed event){
        messenger.sendToUser(event.first(),"/queue/social","FRIEND_UPDATE",Map.of("refresh",true));
        messenger.sendToUser(event.second(),"/queue/social","FRIEND_UPDATE",Map.of("refresh",true));
    }
}
