package com.werewolf.backend.controller;

import com.werewolf.backend.entity.User;
import com.werewolf.backend.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api") // 这家餐厅的门牌号
public class UserController {

    @Autowired
    private UserMapper userMapper; // 把刚才的仓库管理员叫过来

    // 这是一个测试注册的接口
    @GetMapping("/register")
    public String register(String username, String password) {
        // 1. 捏一个新泥人
        User newUser = new User();
        newUser.setUsername(username);
        newUser.setPassword(password);

        // 2. 让管理员把泥人塞进 MySQL 数据库
        userMapper.insertUser(newUser);

        // 3. 告诉浏览器结果
        return "牛逼！注册成功！欢迎玩家：" + username;
    }
}