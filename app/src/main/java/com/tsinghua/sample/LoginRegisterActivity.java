package com.tsinghua.sample;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;


import com.tsinghua.sample.model.ApiResponse;
import com.tsinghua.sample.model.LoginRequest;
import com.tsinghua.sample.model.RegisterRequest;
import com.tsinghua.sample.network.ApiService;
import com.tsinghua.sample.network.AuthInterceptor;

import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class LoginRegisterActivity extends AppCompatActivity {

    private EditText usernameEditText;
    private EditText passwordEditText;
    private Button loginRegisterButton;

    private Retrofit retrofit;
    private ApiService apiService;

    private Context context = this;
    String BASE_URL = BuildConfig.API_BASE_URL;
    String API_URL = BASE_URL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login_register);

        // 检查 SharedPreferences 是否已有用户名
        SharedPreferences preferences = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        String storedUsername = preferences.getString("username", null);
        if (storedUsername != null) {
            // 如果用户名已存储，直接跳到 MainActivity
            Intent intent = new Intent(LoginRegisterActivity.this, MainActivity.class);
            startActivity(intent);
            finish();
            return;
        }

        // 获取 UI 组件
        usernameEditText = findViewById(R.id.usernameEditText);
        passwordEditText = findViewById(R.id.passwordEditText);
        loginRegisterButton = findViewById(R.id.loginRegisterButton);

        // 初始化 Retrofit
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new AuthInterceptor(this))
                .build();

        // 初始化 Retrofit
        retrofit = new Retrofit.Builder()
                .baseUrl(API_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .client(client) // 这里添加 OkHttp 客户端
                .build();

        apiService = retrofit.create(ApiService.class);

        // 设置按钮点击事件
        loginRegisterButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String usernameOrPhone = usernameEditText.getText().toString();
                String password = passwordEditText.getText().toString();

                if (usernameOrPhone.isEmpty() || password.isEmpty()) {
                    Toast.makeText(LoginRegisterActivity.this, "请输入用户名和密码", Toast.LENGTH_SHORT).show();
                } else {
                    login(usernameOrPhone, password);
                }
            }
        });
    }

    // 登录逻辑
    private void login(final String usernameOrPhone, String password) {
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsernameOrPhone(usernameOrPhone);
        loginRequest.setPassword(password);

        Call<ApiResponse> loginCall = apiService.login(loginRequest.getUsernameOrPhone(), loginRequest.getPassword());

        loginCall.enqueue(new Callback<ApiResponse>() {
            @Override
            public void onResponse(Call<ApiResponse> call, Response<ApiResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    ApiResponse apiResponse = response.body();
                    if (apiResponse.getStatusCode() == 200) {
                        // 获取 Token 并存储
                        String token = apiResponse.getData().toString();  // 确保后端返回 JSON 中有 "token" 字段
                        SharedPreferences preferences = getSharedPreferences("UserPrefs", MODE_PRIVATE);
                        SharedPreferences.Editor editor = preferences.edit();
                        editor.putString("username", usernameOrPhone);
                        editor.putString("jwt_token", token); // 存储 JWT
                        editor.apply();

                        Intent intent = new Intent(context, MainActivity.class);
                        startActivity(intent);
                        finish();
                        Toast.makeText(LoginRegisterActivity.this, "登录成功", Toast.LENGTH_SHORT).show();
                    } else {
                        register(usernameOrPhone, password);
                    }
                } else {
                    Log.e("NetWork", response.toString());
                    Toast.makeText(LoginRegisterActivity.this, "登录失败", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<ApiResponse> call, Throwable t) {
                Log.e("NetWork", t.toString());
                Toast.makeText(LoginRegisterActivity.this, "网络错误", Toast.LENGTH_SHORT).show();
            }
        });
    }


    // 注册逻辑
    private void register(String usernameOrPhone, String password) {
        RegisterRequest registerRequest = new RegisterRequest();
        registerRequest.setUsername(usernameOrPhone);
        registerRequest.setPassword(password);
        registerRequest.setPhoneNumber(usernameOrPhone);

        Call<ApiResponse> registerCall = apiService.register(registerRequest);
        registerCall.enqueue(new Callback<ApiResponse>() {
            @Override
            public void onResponse(Call<ApiResponse> call, Response<ApiResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    ApiResponse apiResponse = response.body();
                    if (apiResponse.getStatusCode() == 200) {
                        login(usernameOrPhone,password);
                        Toast.makeText(LoginRegisterActivity.this, "注册成功，已登录", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(LoginRegisterActivity.this, "注册失败: " + apiResponse.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(LoginRegisterActivity.this, "注册失败", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<ApiResponse> call, Throwable t) {
                Toast.makeText(LoginRegisterActivity.this, "网络错误", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
