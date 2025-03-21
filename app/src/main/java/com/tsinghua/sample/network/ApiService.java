package com.tsinghua.sample.network;




import com.tsinghua.sample.model.ApiResponse;
import com.tsinghua.sample.model.QuickLoginRequest;
import com.tsinghua.sample.model.RegisterRequest;

import java.io.File;
import java.util.List;

import okhttp3.MultipartBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface ApiService {

    @POST("/api/user/login")
    Call<ApiResponse> login(@Query("usernameOrPhone") String usernameOrPhone, @Query("password") String password);

    @POST("/api/user/register")
    Call<ApiResponse> register(@Body RegisterRequest registerRequest);

    @POST("/api/user/user-info")
    Call<ApiResponse>getUserInfo(@Query("usernameOrPhone") String usernameOrPhone);
    @POST("/api/user/quick-login")
    Call<ApiResponse> quickLogin(@Body QuickLoginRequest quickLoginRequest);
    // 上传文件
    @Multipart
    @POST("/files/upload")
    Call<ApiResponse> uploadFile(@Part MultipartBody.Part file,@Header("Authorization") String token
    );

    // 获取用户文件列表
    @GET("/api/files/user/{userId}")
    Call<List<File>> getUserFiles(@Path("userId") Long userId);

    // 获取单个文件详情
    @GET("/api/files/{fileId}")
    Call<File> getFileDetails(@Path("fileId") Long fileId);

    // 下载文件
    @GET("/api/files/download/{fileId}")
    Call<ResponseBody> downloadFile(@Path("fileId") Long fileId);

    // 删除文件
    @DELETE("/api/files/{fileId}")
    Call<ApiResponse> deleteFile(@Path("fileId") Long fileId, @Query("userId") Long userId);
}
