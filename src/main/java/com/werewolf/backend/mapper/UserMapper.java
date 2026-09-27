package com.werewolf.backend.mapper;

import com.werewolf.backend.entity.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {
    //注册（存进文件柜）
    @Insert("INSERT INTO users(username, password, nickname) VALUES(#{username}, #{password}, '狼村萌新')")
    void insertUser(User user);

    // 今天加的：登录（去文件柜里按账号密码找人）
    // 只要账号和密码都对上了，就把这个人完整的数据捞出来
    @Select("SELECT * FROM users WHERE username = #{username} AND password = #{password}")
    User loginUser(User user);
}