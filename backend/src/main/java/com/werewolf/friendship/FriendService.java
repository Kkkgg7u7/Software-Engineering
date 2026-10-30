package com.werewolf.friendship;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.werewolf.common.*;
import com.werewolf.friendship.mapper.FriendRelationMapper;
import com.werewolf.user.UserDirectoryService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;

@Service
public class FriendService {
    private final FriendRelationMapper relations;
    private final UserDirectoryService users;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    public FriendService(FriendRelationMapper relations,UserDirectoryService users,PlatformTransactionManager manager,ApplicationEventPublisher events) {
        this.relations=relations;this.users=users;this.tx=new TransactionTemplate(manager);this.events=events;
    }
    public record RequestView(Long id,UserDirectoryService.PublicUser requester,UserDirectoryService.PublicUser recipient,String status,LocalDateTime createdAt) { }
    public record Changed(Long first,Long second) { }
    private FriendRelation pair(Long a,Long b) {
        return relations.selectOne(Wrappers.<FriendRelation>lambdaQuery().eq(FriendRelation::getUserLow,Math.min(a,b)).eq(FriendRelation::getUserHigh,Math.max(a,b)));
    }
    public boolean areFriends(Long a,Long b) {if(a==null||b==null||a.equals(b))return false;var row=pair(a,b);return row!=null&&"ACCEPTED".equals(row.getStatus());}
    public void requireFriends(Long a,Long b) {if(!areFriends(a,b))throw BizException.of(ErrorCode.FORBIDDEN,"仅好友之间可以执行此操作");}
    private RequestView view(FriendRelation row) {
        Long recipient=row.getRequesterId().equals(row.getUserLow())?row.getUserHigh():row.getUserLow();
        return new RequestView(row.getId(),users.profile(row.getRequesterId()),users.profile(recipient),row.getStatus(),row.getCreatedAt());
    }
    // The monitor spans TransactionTemplate.commit, so retries cannot observe an uncommitted pair.
    public synchronized RequestView request(Long from,Long to) {
        if(to==null||to<=0||from.equals(to))throw BizException.of(ErrorCode.BAD_REQUEST,"不能添加自己或无效用户");
        users.profile(to);
        return tx.execute(status->{
            FriendRelation row=pair(from,to);
            if(row!=null&&"ACCEPTED".equals(row.getStatus()))throw BizException.of(ErrorCode.BAD_REQUEST,"你们已经是好友");
            if(row!=null&&"PENDING".equals(row.getStatus())) {
                if(!from.equals(row.getRequesterId()))throw BizException.of(ErrorCode.BAD_REQUEST,"对方已发来申请，请先处理");
                return view(row);
            }
            boolean fresh=row==null;
            if(fresh){row=new FriendRelation();row.setUserLow(Math.min(from,to));row.setUserHigh(Math.max(from,to));}
            row.setRequesterId(from);row.setStatus("PENDING");row.setCreatedAt(LocalDateTime.now());row.setUpdatedAt(row.getCreatedAt());
            if(fresh)relations.insert(row);else relations.updateById(row);
            events.publishEvent(new Changed(from,to));
            return view(row);
        });
    }
    public synchronized RequestView respond(Long userId,Long id,boolean accept) {
        return tx.execute(status->{
            var row=relations.selectById(id);
            if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);
            Long recipient=row.getRequesterId().equals(row.getUserLow())?row.getUserHigh():row.getUserLow();
            if(!userId.equals(recipient))throw BizException.of(ErrorCode.FORBIDDEN);
            String desired=accept?"ACCEPTED":"REJECTED";
            if(desired.equals(row.getStatus()))return view(row);
            if(!"PENDING".equals(row.getStatus()))throw BizException.of(ErrorCode.BAD_REQUEST,"该申请已处理");
            row.setStatus(desired);row.setUpdatedAt(LocalDateTime.now());relations.updateById(row);
            events.publishEvent(new Changed(row.getUserLow(),row.getUserHigh()));return view(row);
        });
    }
    public synchronized void remove(Long userId,Long other) {
        tx.executeWithoutResult(status->{
            var row=pair(userId,other);
            if(row==null||!"ACCEPTED".equals(row.getStatus()))throw BizException.of(ErrorCode.NOT_FOUND,"好友关系不存在");
            row.setStatus("DELETED");row.setUpdatedAt(LocalDateTime.now());relations.updateById(row);
            events.publishEvent(new Changed(userId,other));
        });
    }
    public PageResult<UserDirectoryService.PublicUser> friends(Long id,int page,int size) {
        validatePage(page,size);
        var query=Wrappers.<FriendRelation>lambdaQuery().eq(FriendRelation::getStatus,"ACCEPTED").and(w->w.eq(FriendRelation::getUserLow,id).or().eq(FriendRelation::getUserHigh,id));
        long total=relations.selectCount(query);
        query.orderByDesc(FriendRelation::getUpdatedAt).last("LIMIT "+size+" OFFSET "+((page-1)*size));
        var items=relations.selectList(query).stream().map(r->users.profile(r.getUserLow().equals(id)?r.getUserHigh():r.getUserLow())).toList();
        return new PageResult<>(items,total,page,size);
    }
    public PageResult<RequestView> requests(Long id,String direction,int page,int size) {
        validatePage(page,size);
        if(!"incoming".equals(direction)&&!"outgoing".equals(direction))throw BizException.of(ErrorCode.BAD_REQUEST,"申请方向不正确");
        var query=Wrappers.<FriendRelation>lambdaQuery().and(w->w.eq(FriendRelation::getUserLow,id).or().eq(FriendRelation::getUserHigh,id));
        if("incoming".equals(direction))query.ne(FriendRelation::getRequesterId,id).eq(FriendRelation::getStatus,"PENDING");
        else query.eq(FriendRelation::getRequesterId,id);
        long total=relations.selectCount(query);
        query.orderByDesc(FriendRelation::getUpdatedAt).last("LIMIT "+size+" OFFSET "+((page-1)*size));
        return new PageResult<>(relations.selectList(query).stream().map(this::view).toList(),total,page,size);
    }
    private void validatePage(int page,int size){if(page<1||page>10000||size<1||size>50)throw BizException.of(ErrorCode.BAD_REQUEST,"分页参数不合法");}
}
