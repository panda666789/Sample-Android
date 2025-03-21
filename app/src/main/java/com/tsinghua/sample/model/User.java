package com.tsinghua.sample.model;

public class User {
    private Long id;
    private String username;
    private String password;  // 可以是加密后的密码
    private String role;
    private String phoneNumber;  // 用户手机号
    private boolean isPhoneNumberVerified;  // 手机号是否验证过

    private Integer version;
    // Getter 和 Setter 方法
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public boolean isPhoneNumberVerified() {
        return isPhoneNumberVerified;
    }

    public void setPhoneNumberVerified(boolean phoneNumberVerified) {
        isPhoneNumberVerified = phoneNumberVerified;
    }
}
