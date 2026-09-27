package com.werewolf.backend.entity;

public class User {
    //1.账号的编号
    private Integer id;
    // 2. 账号名称
    private String username;

    // 3. 密码
    private String password;

    // 下面这些叫做 Getter 和 Setter，是为了让别人能读取和修改这个泥人的数据
    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
