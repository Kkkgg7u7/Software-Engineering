package com.werewolf.community;
import com.werewolf.community.mapper.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
@Component
public class WallMediaAccess {
 private final PostMapper posts;private final PostImageMapper images;
 public WallMediaAccess(PostMapper p,PostImageMapper i){posts=p;images=i;}
 public boolean allowed(Long uid,String mediaId){if(uid==null)return false;var ids=images.selectList(Wrappers.<PostImage>lambdaQuery().eq(PostImage::getMediaId,mediaId)).stream().map(PostImage::getPostId).distinct().toList();return !ids.isEmpty()&&posts.selectCount(Wrappers.<Post>lambdaQuery().in(Post::getId,ids).eq(Post::getDeleted,false))>0;}
}
