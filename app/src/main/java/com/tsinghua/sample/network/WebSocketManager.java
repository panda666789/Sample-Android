package com.tsinghua.sample.network;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.tsinghua.sample.BuildConfig;

import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import okhttp3.*;
import ua.naiksoftware.stomp.Stomp;
import ua.naiksoftware.stomp.StompClient;
import ua.naiksoftware.stomp.dto.StompCommand;
import ua.naiksoftware.stomp.dto.StompHeader;
import ua.naiksoftware.stomp.dto.StompMessage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class WebSocketManager {

    private static final String TAG = "WebSocketManager";
    private static final String SERVER_URL = BuildConfig.API_BASE_URL.replace("http", "ws") + "/ws";
    private static WebSocketManager instance;
    private WebSocket webSocket;
    private OkHttpClient client;
    private WebSocketListener listener;
    private String jwtToken;
    private StompClient stompClient;
    private Disposable topicSubscription;
    private Context context;
    private WebSocketManager(Context context) {
        this.context = context;
        client = new OkHttpClient();
    }

    public static WebSocketManager getInstance(Context context) {
        if (instance == null) {
            instance = new WebSocketManager(context);
        }
        return instance;
    }

    private static final int MAX_RECONNECT_ATTEMPTS = 1000; // 最大重试次数
    private int reconnectAttempts = 0; // 当前重试次数
    private Handler reconnectHandler = new Handler(Looper.getMainLooper());

    public void connectWebSocket(String token) {
        this.jwtToken = token;  // 保存 JWT 令牌
        attemptWebSocketConnection();
    }

    private void attemptWebSocketConnection() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "达到最大重连次数，停止重连");
            showToast("WebSocket 连接失败，请检查网络");
            return;
        }

        stompClient = Stomp.over(Stomp.ConnectionProvider.OKHTTP, SERVER_URL + "?token=" + jwtToken);
        List<StompHeader> headers = new ArrayList<>();
        headers.add(new StompHeader("Authorization", "Bearer " + jwtToken));

        stompClient.lifecycle().subscribe(event -> {
            switch (event.getType()) {
                case OPENED:
                    Log.d(TAG, "STOMP 连接成功");
                    showToast("WebSocket 连接成功");
                    subscribeToMessages();
                    reconnectAttempts = 0; // 连接成功，重置重连计数
                    break;
                case ERROR:
                    Log.e(TAG, "STOMP 连接错误", event.getException());
                    showToast("WebSocket 连接失败，正在重试...");
                    scheduleReconnect();
                    break;
                case CLOSED:
                    Log.d(TAG, "STOMP 连接关闭");
                    showToast("WebSocket 连接断开，正在重连...");
                    scheduleReconnect();
                    break;
            }
        });

        stompClient.connect(headers);
    }

    // 调度延迟重连
    private void scheduleReconnect() {
        reconnectAttempts++;
        reconnectHandler.postDelayed(this::attemptWebSocketConnection, 3000); // 3秒后重连
    }

    // 发送 Toast 提示
    private void showToast(String message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        );
    }
    private void subscribeToMessages() {
        if (topicSubscription != null && !topicSubscription.isDisposed()) {
            topicSubscription.dispose();
        }

        List<StompHeader> headers = new ArrayList<>();
        headers.add(new StompHeader("Authorization", "Bearer " + jwtToken));

        topicSubscription = stompClient.topic("/user/queue/messages",headers)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.newThread())
                .subscribe(topicMessage -> {
                    Log.d(TAG, "收到私聊消息: " + topicMessage.getPayload());
                }, throwable -> {
                    Log.e(TAG, "订阅失败", throwable);
                });
    }

    /**
     * 发送私聊消息
     * @param content 消息内容
     */
    public void sendMessage(String recipientId, String content) {
        // 构造要发送的消息内容
        String jsonMessage = "{\"recipientId\":\"" + recipientId + "\", \"content\":\"" + content + "\"}";

        // 创建 Authorization 头部
        StompHeader authHeader = new StompHeader("Authorization", "Bearer " + jwtToken);

        // 创建消息头部列表，添加 Authorization 头部和 destination 头部
        List<StompHeader> headers = new ArrayList<>();
        headers.add(authHeader);  // 添加 Authorization 头部
        headers.add(new StompHeader(StompHeader.DESTINATION, "/app/private-message"));  // 发送到 /app/private-message 路径

        // 构建 StompMessage
        StompMessage stompMessage = new StompMessage(
                StompCommand.SEND,
                headers,
                jsonMessage);

        // 发送消息
        stompClient.send(stompMessage)
                .subscribe(() -> Log.d(TAG, "发送成功: " + jsonMessage),
                        throwable -> Log.e(TAG, "发送失败", throwable));
    }


    /**
     * 关闭 WebSocket 连接
     */
    public void closeWebSocket() {
        if (webSocket != null) {
            webSocket.close(1000, "用户主动关闭连接");
            webSocket = null;
        }
    }
}
