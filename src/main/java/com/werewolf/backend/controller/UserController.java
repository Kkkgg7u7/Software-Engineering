package com.werewolf.backend.controller;
import com.werewolf.backend.utils.JwtUtil;
import com.werewolf.backend.common.Result;
import com.werewolf.backend.entity.User;
import com.werewolf.backend.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class UserController {

    @Autowired
    private UserMapper userMapper;

    // ----- 昨天的注册逻辑 -----
    @PostMapping("/register")
    public Result<String> register(@RequestBody User user) {
        try {
            // 【新增】：把明文密码变魔术，变成 32 位 MD5 密文
            String md5Pwd = org.springframework.util.DigestUtils.md5DigestAsHex(user.getPassword().getBytes());
            user.setPassword(md5Pwd); // 把密文重新塞回给 user 对象

            userMapper.insertUser(user); // 现在存进去的就是密文了
            userMapper.insertUser(user);
            return Result.success("牛逼！真正规范的注册成功了！玩家：" + user.getUsername());
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error("注册失败，账号可能已被占用！");
        }
    }

    @PostMapping("/login")
    public Result<String> login(@RequestBody User user) {
        // 【新增】：前端传过来的还是明文，我们也把它变成 MD5 密文，再去数据库查！
        String md5Pwd = org.springframework.util.DigestUtils.md5DigestAsHex(user.getPassword().getBytes());
        user.setPassword(md5Pwd);

        // 1. 让老大爷去查（这时候是用密文去查了）
        User dbUser = userMapper.loginUser(user);

        if (dbUser != null) {
            // 查到了！密码全对！
            // 【新增逻辑】：启动印钞机，制作门禁卡
            // 我们把用户的 id 和 username 印在卡面上（不用印密码，不安全）
            java.util.Map<String, Object> claims = new java.util.HashMap<>();
            claims.put("id", dbUser.getId());
            claims.put("username", dbUser.getUsername());

            // 摇动摇把，生成一串极其牛逼的防伪字符串 (Token)
            String token = JwtUtil.genToken(claims);

            // 把这串 Token 连同成功信息一起发给前端！
            return Result.success("登录成功！赐你免死金牌（Token）", token);

        } else {
            return Result.error("登录失败：账号或密码错误！你是哪来的刁民？");
        }
    }
}