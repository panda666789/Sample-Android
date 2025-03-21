package com.tsinghua.sample.model;

public class LoginRequest {
    private String usernameOrPhone;  // 用户名或手机号
    private String password;         // 用户密码

    // Getter 和 Setter 方法
    public String getUsernameOrPhone() {
        return usernameOrPhone;
    }

    public void setUsernameOrPhone(String usernameOrPhone) {
        this.usernameOrPhone = usernameOrPhone;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
