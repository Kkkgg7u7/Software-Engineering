package com.werewolf;

import com.fasterxml.jackson.databind.*;
import com.werewolf.friendship.FriendService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="jiujiu.media.directory=target/test-media")
@AutoConfigureMockMvc @ActiveProfiles("h2")
class FriendMediaIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired FriendService friends;
    JsonNode call(String method,String path,String token,Object body)throws Exception {
        var request=MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method),path);
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(request).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    JsonNode user()throws Exception {String suffix=Long.toUnsignedString(System.nanoTime(),36);var r=call("POST","/api/auth/register",null,Map.of("username","fm"+suffix,"nickname","F"+suffix,"password","demo1234"));assertEquals(0,r.path("code").asInt());return r.path("data");}
    JsonNode upload(String token,byte[] bytes,String filename,String mime,String purpose)throws Exception {
        var request=multipart("/api/media/images").file(new MockMultipartFile("file",filename,mime,bytes)).param("purpose",purpose);
        if(token!=null)request.header("Authorization","Bearer "+token);
        return json.readTree(mvc.perform(request).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    byte[] png()throws Exception {var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(4,3,BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray();}

    @Test void friendshipPermissionsIdempotencyAndSymmetricRemoval()throws Exception {
        var a=user();var b=user();var c=user();String at=a.path("token").asText(),bt=b.path("token").asText(),ct=c.path("token").asText();
        long ai=a.path("user").path("id").asLong(),bi=b.path("user").path("id").asLong();
        assertEquals(1000,call("POST","/api/friends/requests",at,Map.of("targetUserId",ai)).path("code").asInt());
        assertEquals(1001,call("GET","/api/friends",null,null).path("code").asInt());
        var sent=call("POST","/api/friends/requests",at,Map.of("targetUserId",bi));assertEquals(0,sent.path("code").asInt());long requestId=sent.path("data").path("id").asLong();
        assertEquals(requestId,call("POST","/api/friends/requests",at,Map.of("targetUserId",bi)).path("data").path("id").asLong());
        assertEquals(1000,call("POST","/api/friends/requests",bt,Map.of("targetUserId",ai)).path("code").asInt());
        assertEquals(1002,call("POST","/api/friends/requests/"+requestId+"/accept",ct,null).path("code").asInt());
        assertEquals(1002,call("POST","/api/friends/requests/"+requestId+"/accept",at,null).path("code").asInt());
        assertEquals(0,call("POST","/api/friends/requests/"+requestId+"/reject",bt,null).path("code").asInt());
        assertEquals(0,call("POST","/api/friends/requests",at,Map.of("targetUserId",bi)).path("code").asInt());
        assertEquals(0,call("POST","/api/friends/requests/"+requestId+"/accept",bt,null).path("code").asInt());
        assertEquals(0,call("POST","/api/friends/requests/"+requestId+"/accept",bt,null).path("code").asInt());
        assertTrue(friends.areFriends(ai,bi));assertTrue(friends.areFriends(bi,ai));
        assertEquals(bi,call("GET","/api/friends",at,null).path("data").path("items").get(0).path("id").asLong());
        assertEquals(0,call("DELETE","/api/friends/"+bi,at,null).path("code").asInt());
        assertFalse(friends.areFriends(ai,bi));assertEquals(0,call("GET","/api/friends",bt,null).path("data").path("total").asInt());
    }
    @Test void duplicateConcurrentRequestsCreateOneRelation()throws Exception {
        var a=user();var b=user();long ai=a.path("user").path("id").asLong(),bi=b.path("user").path("id").asLong();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try{var first=pool.submit(()->{start.await();return friends.request(ai,bi);});var second=pool.submit(()->{start.await();return friends.request(ai,bi);});start.countDown();assertEquals(first.get(10,java.util.concurrent.TimeUnit.SECONDS).id(),second.get(10,java.util.concurrent.TimeUnit.SECONDS).id());}finally{pool.shutdownNow();}
    }
    @Test void searchOnlyReturnsPublicFields()throws Exception {
        var a=user();String token=a.path("token").asText();String name=a.path("user").path("username").asText();
        var found=call("GET","/api/users/search?q="+name,token,null);assertEquals(0,found.path("code").asInt());
        var publicUser=found.path("data").path("items").get(0);assertEquals(a.path("user").path("id"),publicUser.path("id"));
        assertFalse(publicUser.has("password"));assertFalse(publicUser.has("username"));assertFalse(publicUser.has("authVersion"));
        assertEquals(1000,call("GET","/api/users/search?q=x&size=100",token,null).path("code").asInt());
    }
    @Test void imagesAreDecodedNormalizedAndProtectedByPurposeAndOwnership()throws Exception {
        var a=user();var b=user();String at=a.path("token").asText(),bt=b.path("token").asText();
        var privateImage=upload(at,png(),"../../fake.jpg","image/jpeg","PRIVATE");assertEquals(0,privateImage.path("code").asInt());
        String id=privateImage.path("data").path("id").asText();
        mvc.perform(get("/api/media/"+id+"/content").header("Authorization","Bearer "+at)).andExpect(content().contentType(MediaType.IMAGE_PNG));
        assertEquals(1002,call("GET","/api/media/"+id+"/content",bt,null).path("code").asInt());
        assertEquals(1001,call("GET","/api/media/"+id+"/content",null,null).path("code").asInt());
        assertEquals(1003,call("GET","/api/media/avatars/"+id,null,null).path("code").asInt());
        var avatar=upload(at,png(),"avatar.png","image/png","AVATAR");assertEquals(0,avatar.path("code").asInt());String url=avatar.path("data").path("url").asText();
        mvc.perform(get(url)).andExpect(content().contentType(MediaType.IMAGE_PNG));
        assertEquals(0,call("PUT","/api/users/me",at,Map.of("avatar",url)).path("code").asInt());
        assertEquals(1002,call("PUT","/api/users/me",bt,Map.of("avatar",url)).path("code").asInt());
    }
    @Test void malformedOversizedAndUnsupportedImagesAreRejected()throws Exception {
        String token=user().path("token").asText();
        assertEquals(1000,upload(token,"<svg onload='alert(1)'/>".getBytes(StandardCharsets.UTF_8),"fake.png","image/png","PRIVATE").path("code").asInt());
        assertEquals(1000,upload(token,new byte[5*1024*1024+1],"large.png","image/png","PRIVATE").path("code").asInt());
        assertEquals(1000,upload(token,png(),"image.png","image/png","PUBLIC").path("code").asInt());
        assertEquals(1001,upload(null,png(),"image.png","image/png","PRIVATE").path("code").asInt());
    }
    @Test void webpIsDecodedAndReencodedAsPng()throws Exception {
        String token=user().path("token").asText();
        byte[] fixture=new org.springframework.core.io.ClassPathResource("fixtures/tiny.webp").getContentAsByteArray();
        var image=upload(token,fixture,"tiny.webp","image/webp","PRIVATE");
        assertEquals(0,image.path("code").asInt(),image.toString());
        assertEquals("image/png",image.path("data").path("contentType").asText());
        assertEquals(3,image.path("data").path("width").asInt());
        assertEquals(2,image.path("data").path("height").asInt());
    }
}
