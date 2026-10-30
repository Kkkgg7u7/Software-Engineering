package com.werewolf.media;

import com.werewolf.common.*;
import com.werewolf.media.mapper.MediaAssetMapper;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.awt.RenderingHints;
import java.io.*;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class MediaService {
    private final MediaAssetMapper assets;
    private final ImageStorage storage;
    private final java.util.concurrent.Semaphore processing = new java.util.concurrent.Semaphore(2);
    private final org.springframework.beans.factory.ObjectProvider<com.werewolf.conversation.ChatMediaAccess> chatAccess;
    private final org.springframework.beans.factory.ObjectProvider<com.werewolf.community.WallMediaAccess> wallAccess;
    public MediaService(MediaAssetMapper assets,ImageStorage storage,org.springframework.beans.factory.ObjectProvider<com.werewolf.conversation.ChatMediaAccess> chatAccess,org.springframework.beans.factory.ObjectProvider<com.werewolf.community.WallMediaAccess> wallAccess){this.assets=assets;this.storage=storage;this.chatAccess=chatAccess;this.wallAccess=wallAccess;}
    public record ImageView(String id,String url,String purpose,int width,int height,long sizeBytes,String contentType) { }
    public ImageView upload(Long owner,MultipartFile file,String purpose) {
        if(!processing.tryAcquire())throw BizException.of(ErrorCode.RATE_LIMITED,"图片处理繁忙，请稍后重试");
        try { return process(owner,file,purpose); } finally { processing.release(); }
    }
    private ImageView process(Long owner,MultipartFile file,String purpose) {
        if(!Set.of("PRIVATE","AVATAR").contains(purpose))throw BizException.of(ErrorCode.BAD_REQUEST,"图片用途不正确");
        if(file==null||file.isEmpty()||file.getSize()>5L*1024*1024)throw BizException.of(ErrorCode.BAD_REQUEST,"图片不能为空且不能超过5MB");
        try (var stream=new MemoryCacheImageInputStream(new ByteArrayInputStream(file.getBytes()))) {
            var candidates=ImageIO.getImageReaders(stream);
            if(!candidates.hasNext())throw BizException.of(ErrorCode.BAD_REQUEST,"仅支持有效的 JPEG、PNG、WebP 图片");
            ImageReader reader=candidates.next();
            BufferedImage image;
            try {
                reader.setInput(stream,true,true);
                if(!Set.of("jpeg","jpg","png","webp").contains(reader.getFormatName().toLowerCase(Locale.ROOT)))throw BizException.of(ErrorCode.BAD_REQUEST,"不支持此图片格式");
                int w=reader.getWidth(0),h=reader.getHeight(0);
                if(w<1||h<1||w>6000||h>6000||(long)w*h>12_000_000)throw BizException.of(ErrorCode.BAD_REQUEST,"图片尺寸过大，最大1200万像素且单边不超过6000");
                image=reader.read(0);
                if(image==null)throw BizException.of(ErrorCode.BAD_REQUEST,"图片无法解码");
            } finally {reader.dispose();}
            int max="AVATAR".equals(purpose)?512:2560;
            double scale=Math.min(1d,(double)max/Math.max(image.getWidth(),image.getHeight()));
            int width=Math.max(1,(int)Math.round(image.getWidth()*scale)),height=Math.max(1,(int)Math.round(image.getHeight()*scale));
            var normalized=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
            var graphics=normalized.createGraphics();
            try {graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);graphics.drawImage(image,0,0,width,height,null);}finally{graphics.dispose();}
            var bytes=new ByteArrayOutputStream();ImageIO.write(normalized,"png",bytes);
            if(bytes.size()>10*1024*1024)throw BizException.of(ErrorCode.BAD_REQUEST,"处理后的图片过大，请压缩后重试");
            String id=UUID.randomUUID().toString().replace("-","");
            var asset=new MediaAsset();asset.setId(id);asset.setOwnerId(owner);asset.setPurpose(purpose);asset.setStorageName(id+".png");asset.setContentType("image/png");asset.setWidth(width);asset.setHeight(height);asset.setSizeBytes((long)bytes.size());asset.setCreatedAt(LocalDateTime.now());
            storage.save(asset.getStorageName(),bytes.toByteArray());
            try {assets.insert(asset);}catch(RuntimeException ex){storage.discard(asset.getStorageName());throw ex;}
            String url="AVATAR".equals(purpose)?"/api/media/avatars/"+id:"/api/media/"+id+"/content";
            return new ImageView(id,url,purpose,width,height,asset.getSizeBytes(),asset.getContentType());
        } catch(IOException ex){throw BizException.of(ErrorCode.BAD_REQUEST,"图片无法读取或保存，请检查文件后重试");}
    }
    public MediaAsset require(String id) {
        if(id==null||!id.matches("[a-f0-9]{32}"))throw BizException.of(ErrorCode.NOT_FOUND);
        var asset=assets.selectById(id);if(asset==null)throw BizException.of(ErrorCode.NOT_FOUND);return asset;
    }
    public void requireOwnedAvatar(Long owner,String url) {
        if(url!=null&&url.startsWith("/api/media/avatars/")) {
            var asset=require(url.substring("/api/media/avatars/".length()));
            if(!owner.equals(asset.getOwnerId())||!"AVATAR".equals(asset.getPurpose()))throw BizException.of(ErrorCode.FORBIDDEN,"只能设置自己上传的头像");
        }
    }
    public Resource content(String id,Long userId,boolean avatarRoute) {
        var asset=require(id);
        if(avatarRoute) {if(!"AVATAR".equals(asset.getPurpose()))throw BizException.of(ErrorCode.NOT_FOUND);}
        else if(!asset.getOwnerId().equals(userId)&&!chatAccess.getObject().allowed(userId,id)&&!wallAccess.getObject().allowed(userId,id))throw BizException.of(ErrorCode.FORBIDDEN,"无权读取此图片");
        try{return storage.load(asset.getStorageName());}catch(IOException ex){throw BizException.of(ErrorCode.NOT_FOUND,"图片文件不存在");}
    }
}
