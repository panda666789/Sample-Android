package com.tsinghua.sample.model;

public class QuickLoginRequest {
    private String phoneNumber;        // 手机号
    private String verificationCode;  // 短信验证码
    private String correctCode;       // 正确的验证码

    // Getter 和 Setter 方法
    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getVerificationCode() {
        return verificationCode;
    }

    public void setVerificationCode(String verificationCode) {
        this.verificationCode = verificationCode;
    }

    public String getCorrectCode() {
        return correctCode;
    }

    public void setCorrectCode(String correctCode) {
        this.correctCode = correctCode;
    }
}
