package com.werewolf.user;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.werewolf.common.*;
import com.werewolf.user.entity.User;
import com.werewolf.user.mapper.UserMapper;
import com.werewolf.websocket.WsSessionRegistry;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class UserDirectoryService {
    private final UserMapper users;
    private final WsSessionRegistry sessions;
    public UserDirectoryService(UserMapper users, WsSessionRegistry sessions) { this.users=users; this.sessions=sessions; }
    public record PublicUser(Long id, String nickname, String avatar, String signature, boolean online, int totalGames, int winGames) { }
    public PublicUser profile(Long id) {
        User user=users.selectById(id);
        if(user==null) throw BizException.of(ErrorCode.USER_NOT_FOUND);
        return view(user);
    }
    private PublicUser view(User user) {
        return new PublicUser(user.getId(),user.getNickname(),user.getAvatar(),user.getSignature(),
                sessions.snapshot().containsKey(user.getId()),user.getTotalGames()==null?0:user.getTotalGames(),user.getWinGames()==null?0:user.getWinGames());
    }
    public PageResult<PublicUser> search(String raw, int page, int size) {
        String q=raw==null?"":raw.trim();
        if(q.isEmpty() || q.length()>50 || page<1 || page>10000 || size<1 || size>50) throw BizException.of(ErrorCode.BAD_REQUEST,"请输入1—50位搜索词并使用有效分页");
        var query=Wrappers.<User>lambdaQuery().and(w->{
            w.eq(User::getUsername,q).or().like(User::getNickname,q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_"));
            if(q.matches("[0-9]{1,18}")) w.or().eq(User::getId,Long.parseLong(q));
        });
        long count=users.selectCount(query);
        query.orderByAsc(User::getId).last("LIMIT "+size+" OFFSET "+((page-1)*size));
        return new PageResult<>(users.selectList(query).stream().map(this::view).toList(),count,page,size);
    }
}
