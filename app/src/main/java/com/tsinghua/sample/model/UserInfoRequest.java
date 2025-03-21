package com.tsinghua.sample.model;

public class UserInfoRequest {
    private String usernameOrPhone;  // 用户名或手机号
    public String getUsernameOrPhone() {
        return usernameOrPhone;
    }

    public void setUsernameOrPhone(String usernameOrPhone) {
        this.usernameOrPhone = usernameOrPhone;
    }

}
