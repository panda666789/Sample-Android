package com.tsinghua.sample.model;

public class ApiResponse {
    private int statusCode;  // HTTP 状态码
    private String message;  // 错误信息或成功消息
    private Object data;      // 返回的用户数据（可以为空）

    // 构造函数
    public ApiResponse(int statusCode, String message) {
        this.statusCode = statusCode;
        this.message = message;
    }

    public ApiResponse(int statusCode, String message, User data) {
        this.statusCode = statusCode;
        this.message = message;
        this.data = data;
    }

    // Getter 和 Setter 方法
    public int getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Object getData() {
        return data;
    }

    public void setData(User data) {
        this.data = data;
    }
}
