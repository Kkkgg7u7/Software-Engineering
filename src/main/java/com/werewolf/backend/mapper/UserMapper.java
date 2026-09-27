package com.werewolf.backend.mapper;

import com.werewolf.backend.entity.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper {

    // 这行 SQL 语句的意思是：往 users 表里插入账号和密码
    @Insert("INSERT INTO users(username, password, nickname) VALUES(#{username}, #{password}, '狼村萌新')")
    void insertUser(User user);

}