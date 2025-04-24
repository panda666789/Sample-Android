package com.tsinghua.sample;


import static com.tsinghua.sample.utils.VivaLink.startScan;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.github.AAChartModel.AAChartCore.AAChartCreator.AAChartModel;
import com.github.AAChartModel.AAChartCore.AAChartCreator.AAChartView;
import com.github.AAChartModel.AAChartCore.AAChartCreator.AASeriesElement;
import com.github.AAChartModel.AAChartCore.AAChartEnum.AAChartAnimationType;
import com.github.AAChartModel.AAChartCore.AAChartEnum.AAChartType;
import com.github.AAChartModel.AAChartCore.AAOptionsModel.AAScrollablePlotArea;
import com.google.gson.Gson;
import com.google.gson.internal.LinkedTreeMap;
import com.lm.sdk.inter.IResponseListener;
import com.petterp.floatingx.FloatingX;
import com.tsinghua.sample.media.CameraHelper;
import com.tsinghua.sample.media.IMURecorder;
import com.tsinghua.sample.media.MultiMicAudioRecorderHelper;
import com.tsinghua.sample.model.ApiResponse;
import com.tsinghua.sample.model.User;
import com.tsinghua.sample.model.UserInfoRequest;
import com.tsinghua.sample.network.ApiService;
import com.tsinghua.sample.network.AuthInterceptor;
import com.tsinghua.sample.network.WebSocketManager;
import com.tsinghua.sample.utils.NotificationHandler;
import com.tsinghua.sample.utils.VivaLink;
import com.vivalnk.sdk.BuildConfig;
import com.vivalnk.sdk.CommandRequest;
import com.vivalnk.sdk.VitalClient;
import com.vivalnk.sdk.command.base.CommandType;
import com.vivalnk.sdk.exception.VitalCode;
import com.vivalnk.sdk.utils.ProcessUtils;
import com.lm.sdk.BLEService;
import com.lm.sdk.LmAPI;
import com.lm.sdk.LogicalApi;
import com.lm.sdk.inter.IHeartListener;
import com.lm.sdk.inter.IResponseListener;
import com.lm.sdk.mode.BleDeviceInfo;
import com.lm.sdk.mode.SystemControlBean;
import com.lm.sdk.utils.BLEUtils;
import com.lm.sdk.utils.ConvertUtils;
import com.lm.sdk.utils.StringUtils;
import com.lm.sdk.utils.UtilSharedPreference;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;


public class MainActivity extends AppCompatActivity implements IResponseListener {
    private static final int REQUEST_CODE_OVERLAY_PERMISSION = 100;
    public static boolean isRecordingECG = false;
    public static boolean isRecordingRing = false;
    private static final int REQUEST_CAMERA_PERMISSION = 200;

    public static boolean isRecordingVideo = false;
    private TextView tvEcgData;
    private Button btnStartEcg, btnStartRing, btnRecordVideo,btnScanECG,btnScanRing;
    private static final int PERMISSION_REQUEST_CODE = 100;  // Permission request code
    private TextView tvLMAPILog;
    private BufferedWriter logWriter = null;
    public static BufferedWriter logWriterECG = null;
    private AAChartView chartViewACC,chartViewECG,chartViewGIR,chartViewIMU;

    private WebSocketManager webSocketManager;
    private String jwtToken; // 这里替换为后端返回的真实 JWT
    public static User user;
    private ApiService apiService;
    private Retrofit retrofit;
    private Context context = this;
    String BASE_URL = com.tsinghua.sample.BuildConfig.API_BASE_URL;
    String API_URL = BASE_URL;
    private BroadcastReceiver updateTextViewReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
                btnRecordVideo.setText("结束录制"); // 确保 btnRecordVideo 已初始化
        }
    };
    private BluetoothAdapter.LeScanCallback leScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] bytes) {
            if (device == null || TextUtils.isEmpty(device.getName())) {
                return;
            }
            BleDeviceInfo bleDeviceInfo = LogicalApi.getBleDeviceInfoWhenBleScan(device, rssi, bytes);
            Log.e("RingLog",bleDeviceInfo.toString());
        }
    };
    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_OFF) {
                    // Bluetooth is off
                    Toast.makeText(context, "Bluetooth is OFF", Toast.LENGTH_SHORT).show();
                } else if (state == BluetoothAdapter.STATE_ON) {
                    // Bluetooth is on
                    Toast.makeText(context, "Bluetooth is ON", Toast.LENGTH_SHORT).show();
                }
            }


        }
    };

    private void recordLog(String logMessage) {
        // 将日志显示到TextView中（确保在UI线程执行）
        runOnUiThread(() -> {
            if(tvLMAPILog != null){
                tvLMAPILog.setText(logMessage);
            }
        });
        // 如果正在记录，则写入文件
        if (isRecordingRing && logWriter != null) {
            try {
                logWriter.write(logMessage + "\n");
                logWriter.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences preferences = this.getSharedPreferences("UserPrefs", Context.MODE_PRIVATE);
        user = new User();
        jwtToken = preferences.getString("jwt_token", null);
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
        webSocketManager = WebSocketManager.getInstance(context);

        user.setUsername(preferences.getString("username",null));
        UserInfo(user.getUsername());

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        Button btnSettings = findViewById(R.id.btn_settings);
        btnScanECG = findViewById(R.id.btn_scanECG);
        btnScanRing = findViewById(R.id.btn_scanRing);
        btnSettings.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
        });
        Button btnTimeStamp = findViewById(R.id.btn_Timestamp);

        btnTimeStamp.setOnClickListener(v -> {
            FragmentManager fm = getSupportFragmentManager();
            TimestampFragment fragment = (TimestampFragment) fm.findFragmentByTag("TimestampFragment");

            if (fragment == null) {
                fragment = new TimestampFragment(); // 仅在 Fragment 为空时创建新实例
            }

            fragment.show(fm, "TimestampFragment");
        });
        btnScanECG.setOnClickListener(v -> {
            startScan(this);
        });
        btnScanRing.setOnClickListener(v->{
            SharedPreferences prefs = getSharedPreferences("AppSettings", MODE_PRIVATE);
            String mac = prefs.getString("mac_address", "");
            BluetoothDevice remote = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(mac);
            if(remote != null){
                BLEUtils.connectLockByBLE(this,remote);
            }
        });

        if (ProcessUtils.isMainProcess(this)) {
            VitalClient.getInstance().init(this);
            if (BuildConfig.DEBUG) {
                VitalClient.getInstance().openLog();
                VitalClient.getInstance().allowWriteToFile(true);
            }
        }
        VivaLink.checkAndRequestPermissions(this);
        int resultCode = VitalClient.getInstance().checkBle();
        Log.e("VivaLink",String.valueOf(resultCode));
        if (resultCode != VitalCode.RESULT_OK) {
            Toast.makeText(MainActivity.this, "Vital Client runtime check failed",
                    Toast.LENGTH_LONG).show();
        }
        checkPermissions();
        LmAPI.init(this.getApplication());
        LmAPI.setDebug(true);
        LmAPI.addWLSCmdListener(this, this);
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        registerReceiver(broadcastReceiver,intentFilter);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!checkPermissions(new String[]{
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
            })) {
                showPermissionDialog();
                return;
            }
        } else {
            if (!checkPermissions(new String[]{
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
            })) {
                showPermissionDialog();
                return;
            }
        }
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(updateTextViewReceiver, new IntentFilter("com.tsinghua.UPDATE_TEXT_VIEW"));
        btnRecordVideo = findViewById(R.id.btn_record_video);
        btnStartEcg = findViewById(R.id.btn_start_ecg);
        btnStartRing = findViewById(R.id.btn_start_ring);
        tvEcgData = findViewById(R.id.tv_ecg_data);
        btnRecordVideo.setOnClickListener(view -> {
            if (!isRecordingVideo) {
                startVideoRecording();
            } else {
                stopVideoRecording();
            }
        });
        btnStartEcg.setOnClickListener(view -> {
            if (!isRecordingECG) {
                startECGRecording();
            } else {
                stopECGRecording();
            }
        });

        // 指环按钮逻辑
        btnStartRing.setOnClickListener(view -> {
            if (!isRecordingRing) {
                startRingRecording();
            } else {
                stopRingRecording();
            }
        });
        setUpChart();
    }
    private void setUpChart(){
        tvLMAPILog = findViewById(R.id.tv_ring_data);
        chartViewACC = findViewById(R.id.chartViewForACC);
        chartViewECG = findViewById(R.id.chartViewForECG);
        chartViewGIR = findViewById(R.id.chartViewForGIR);
        chartViewIMU = findViewById(R.id.chartViewForIMU);
        AASeriesElement ECGSeries = new AASeriesElement()
                .name("ECG")
                .data(new Object[]{0});
        AASeriesElement greenSeries = new AASeriesElement()
                .name("green")
                .color("#00FF00")
                .data(new Object[]{});

        AASeriesElement irSeries = new AASeriesElement()
                .name("ir")
                .color("#0000FF")
                .data(new Object[]{});

        AASeriesElement redSeries = new AASeriesElement()
                .name("red")
                .color("#FF0000")
                .data(new Object[]{});
        AASeriesElement xSeries = new AASeriesElement()
                .name("x")
                .data(new Object[]{0});

        AASeriesElement ySeries = new AASeriesElement()
                .name("y")
                .data(new Object[]{0});

        AASeriesElement zSeries = new AASeriesElement()
                .name("z")
                .data(new Object[]{0});
        AAChartModel aaChartModelECG = new AAChartModel()
                .chartType(AAChartType.Spline) // 选择折线图
                .markerRadius(0)
                .animationType(AAChartAnimationType.EaseTo)
                .xAxisLabelsEnabled(false)
                .scrollablePlotArea(new AAScrollablePlotArea()
                        .minWidth(800)
                        .minHeight(400)
                        .opacity(0.8f)
                        .scrollPositionX(1)
                        .scrollPositionY(1))
                .series(new AASeriesElement[]{ECGSeries})
                .dataLabelsEnabled(false);
        chartViewECG.aa_drawChartWithChartModel(aaChartModelECG);
        AAChartModel aaChartModelACC = new AAChartModel()
                .chartType(AAChartType.Spline) // 选择折线图
                .markerRadius(0)
                .animationType(AAChartAnimationType.EaseTo)
                .xAxisLabelsEnabled(false)
                .scrollablePlotArea(new AAScrollablePlotArea()
                        .minWidth(800)
                        .minHeight(400)
                        .opacity(0.8f)
                        .scrollPositionX(1)
                        .scrollPositionY(1))
                .series(new AASeriesElement[]{xSeries,ySeries,zSeries})
                .dataLabelsEnabled(false);
        chartViewACC.aa_drawChartWithChartModel(aaChartModelACC);
        VivaLink.setAAChartViewAcc(chartViewACC);
        VivaLink.setAAChartViewECG(chartViewECG);
        SharedPreferences prefs = getSharedPreferences("AppSettings", MODE_PRIVATE);
        int savedYAxisMin = prefs.getInt("y_axis_min", 0);

        AAChartModel aaChartModelGIR = new AAChartModel()
                .chartType(AAChartType.Spline) // 选择折线图
                .markerRadius(0)
                .yAxisMin(savedYAxisMin)
                .animationType(AAChartAnimationType.EaseTo)
                .xAxisLabelsEnabled(false)
                .scrollablePlotArea(new AAScrollablePlotArea()
                        .minWidth(800)
                        .minHeight(400)
                        .opacity(1f)
                        .scrollPositionX(1)
                        .scrollPositionY(1))
                .series(new AASeriesElement[]{greenSeries, irSeries, redSeries})
                .dataLabelsEnabled(false);
        chartViewGIR.aa_drawChartWithChartModel(aaChartModelGIR);
        AAChartModel aaChartModelIMU = new AAChartModel()
                .chartType(AAChartType.Spline) // 选择折线图
                .markerRadius(0)
                .xAxisLabelsEnabled(false)
                .scrollablePlotArea(new AAScrollablePlotArea()
                        .minWidth(800)
                        .minHeight(400)
                        .opacity(1f)
                        .scrollPositionX(1)
                        .scrollPositionY(1))
                .series(new AASeriesElement[]{xSeries, ySeries, zSeries})
                .dataLabelsEnabled(false);
        chartViewIMU.aa_drawChartWithChartModel(aaChartModelIMU);
    }
    private void UserInfo(final String usernameOrPhone) {
        UserInfoRequest userInfoRequest = new UserInfoRequest();
        userInfoRequest.setUsernameOrPhone(usernameOrPhone);

        Call<ApiResponse> userInfoCall = apiService.getUserInfo(userInfoRequest.getUsernameOrPhone());

        userInfoCall.enqueue(new Callback<ApiResponse>() {
            @Override
            public void onResponse(Call<ApiResponse> call, Response<ApiResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    ApiResponse apiResponse = response.body();
                    if (apiResponse.getStatusCode() == 200) {
                        User tempUser = null;
                        Object data = apiResponse.getData();
                        if (data instanceof LinkedTreeMap) {
                            Gson gson = new Gson();
                            String json = gson.toJson(data); // 先转成 JSON 字符串
                            tempUser = gson.fromJson(json, User.class); // 再解析成 User 对象
                        }
                        user.setId(tempUser.getId());
                        webSocketManager.connectWebSocket(jwtToken);

                        Toast.makeText(context, "获取用户信息成功", Toast.LENGTH_SHORT).show();
                    } else {
                        SharedPreferences preferences = getSharedPreferences("UserPrefs", MODE_PRIVATE);
                        SharedPreferences.Editor editor = preferences.edit();
                        editor.clear();
                        editor.apply();

                        Intent intent = new Intent(context, LoginRegisterActivity.class);
                        startActivity(intent);
                        finish();
                        Toast.makeText(context, "获取用户信息失败", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    SharedPreferences preferences = getSharedPreferences("UserPrefs", MODE_PRIVATE);
                    SharedPreferences.Editor editor = preferences.edit();
                    editor.clear();
                    editor.apply();

                    Intent intent = new Intent(context, LoginRegisterActivity.class);
                    startActivity(intent);
                    finish();
                    Toast.makeText(context, "获取用户信息失败", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<ApiResponse> call, Throwable t) {
                SharedPreferences preferences = getSharedPreferences("UserPrefs", MODE_PRIVATE);
                SharedPreferences.Editor editor = preferences.edit();
                editor.clear();
                editor.apply();

                Intent intent = new Intent(context, LoginRegisterActivity.class);
                startActivity(intent);
                finish();
                Toast.makeText(context, "网络错误", Toast.LENGTH_SHORT).show();
            }
        });
    }
    private void showPermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle("权限请求")
                .setMessage("该应用需要访问位置信息和蓝牙权限，请授予权限以继续使用蓝牙功能。")
                .setPositiveButton("确认", (dialog, which) -> {
                    requestPermission(new String[]{
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_ADVERTISE
                    }, PERMISSION_REQUEST_CODE);
                })
                .setNegativeButton("取消", null)
                .show();

    }
    private void startECGRecording() {
        VivaLink.clearAllData();
        if (!isRecordingECG) {

            try {
                VivaLink.startSampling();
                // 建议保存到应用私有存储中，避免权限问题
                SharedPreferences prefs;
                prefs = this.getSharedPreferences("AppSettings", MODE_PRIVATE);
                String savedExperimentId = prefs.getString("experiment_id", "");
                String directoryPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES) + "/Sample/"+savedExperimentId+"/VivaLinkLog/";
                File directory = new File(directoryPath);
                if (!directory.exists()) {
                    if (directory.mkdirs()) {
                        Log.d("FileSave", "目录创建成功：" + directoryPath);
                    } else {
                        Log.e("FileSave", "目录创建失败：" + directoryPath);
                        Toast.makeText(this, "无法创建目录，保存失败", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
                String fileName = "VivaLink_log_" + System.currentTimeMillis() + ".txt";
                File logFile = new File(directory, fileName);
                logWriterECG = new BufferedWriter(new FileWriter(logFile, true));
                isRecordingECG = true;
                btnStartEcg.setText("停止心电");
                tvEcgData.setText("心电数据: 正在记录...");
                Toast.makeText(MainActivity.this, "日志记录开始", Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                e.printStackTrace();
                Toast.makeText(MainActivity.this, "日志记录启动失败", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // 模拟停止录制心电数据
    private void stopECGRecording() {
        if (isRecordingECG) {
            isRecordingECG = false;
            btnStartEcg.setText("心电开始");
            tvEcgData.setText("心电数据: 记录停止");
            VivaLink.stopSampling();
            try {
                if (logWriter != null) {
                    logWriter.close();
                    logWriter = null;
                }
                Toast.makeText(MainActivity.this, "日志记录结束", Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // 模拟开始录制指环数据
    private void startRingRecording() {
        NotificationHandler.clearAllData();
        if (!isRecordingRing) {
            isRecordingRing = true;
            btnStartRing.setText("停止指环");
            tvLMAPILog.setText("");
            startSendingCommands();
            try {
                SharedPreferences prefs;
                prefs = this.getSharedPreferences("AppSettings", MODE_PRIVATE);
                String savedExperimentId = prefs.getString("experiment_id", "");
                String directoryPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES) + "/Sample/"+savedExperimentId+"/RingLog/";
                File directory = new File(directoryPath);
                if (!directory.exists()) {
                    if (directory.mkdirs()) {
                        Log.d("FileSave", "目录创建成功：" + directoryPath);
                    } else {
                        Log.e("FileSave", "目录创建失败：" + directoryPath);
                        Toast.makeText(this, "无法创建目录，保存失败", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
                String fileName = "LMAPI_log_" + System.currentTimeMillis() + ".txt";
                File logFile = new File(directory, fileName);
                logWriter = new BufferedWriter(new FileWriter(logFile, true));
                Toast.makeText(MainActivity.this, "日志记录开始", Toast.LENGTH_SHORT).show();
                recordLog("【日志记录开始】");
            } catch (IOException e) {
                e.printStackTrace();
                Toast.makeText(MainActivity.this, "日志记录启动失败", Toast.LENGTH_SHORT).show();
            }
        }

    }

    // 模拟停止录制指环数据
    private void stopRingRecording() {
        if (isRecordingRing) {
            btnStartRing.setText("指环开始");
            tvLMAPILog.setText("");
            byte[] data = hexStringToByteArray("00003C04");

            LmAPI.SEND_CMD(data);
            new android.os.Handler().postDelayed(() -> {
                try {
                    isRecordingRing = false;
                    if (logWriter != null) {
                        logWriter.close();
                        logWriter = null;
                    }
                    Toast.makeText(MainActivity.this, "日志记录结束", Toast.LENGTH_SHORT).show();
                    recordLog("【日志记录结束】");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }, 1000); // 延迟 1 秒
            }

        }

    private void startVideoRecording() {
        isRecordingVideo = true;
        if (Settings.canDrawOverlays(this)) {
            // 已授权，启动服务
            //startService(new Intent(this, FloatingWindowService.class));
            btnRecordVideo.setText("点击悬浮窗开始录制");
        } else {
            // 请求悬浮窗权限
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            startActivityForResult(intent, REQUEST_CODE_OVERLAY_PERMISSION);
        }
    }
    private void stopVideoRecording() {
        isRecordingVideo = false;
        //Intent intent = new Intent(this, FloatingWindowService.class);
        //stopService(intent);
        btnRecordVideo.setText("录制视频");
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
    private void checkPermissions(){

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
            }
        }

        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    android.Manifest.permission.CAMERA,
                    android.Manifest.permission.RECORD_AUDIO,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_CAMERA_PERMISSION);
        }
    }
    private boolean checkPermissions(String[] permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
    private void requestPermission(String[] permissions, int requestCode) {
        ActivityCompat.requestPermissions(this, permissions, requestCode);
    }
    private Handler commandHandler = new Handler(Looper.getMainLooper());
    private Runnable commandRunnable = new Runnable() {
        @Override
        public void run() {
            SharedPreferences prefs = getSharedPreferences("AppSettings", MODE_PRIVATE);
            int savedTime = prefs.getInt("time_parameter", 0);
            String hexData;
            if(savedTime == 0){
                hexData = "00003C00"+ "00"+ "001010100101";
            }
            else {
                hexData = "00003C00" + Integer.toHexString(savedTime) + "001010100101";
            }
            byte[] data = hexStringToByteArray(hexData);
            LmAPI.SEND_CMD(data);
        }
    };
    public static byte[] hexStringToByteArray(String hexString) {
        int len = hexString.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hexString.charAt(i), 16) << 4)
                    + Character.digit(hexString.charAt(i + 1), 16));
        }
        return data;
    }
    private void startSendingCommands() {
        commandHandler.post(commandRunnable);
    }

    // 当数据记录结束时，停止发送命令
    private void stopSendingCommands() {
        commandHandler.removeCallbacks(commandRunnable);
    }
    @Override
    public void lmBleConnecting(int i) {
        Log.e("ConnectDevice"," 蓝牙连接中");
        String msg = "蓝牙连接中，状态码：" + i;
        recordLog(msg);

    }

    @Override
    public void lmBleConnectionSucceeded(int i) {
        String msg = "蓝牙连接成功，状态码：" + i;
        recordLog(msg);
        if(i==7){
            BLEUtils.setGetToken(true);
            Log.e("TAG","\n连接成功");
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    LmAPI.SYNC_TIME();
                }
            }, 500); // 延迟 2 秒（2000 毫秒）
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {

                    LmAPI.GET_BATTERY((byte) 0x00);
                }
            }, 1000); // 延迟 2 秒（2000 毫秒）
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {

                    LmAPI.GET_VERSION((byte) 0x00);
                }
            }, 1500); // 延迟 2 秒（2000 毫秒）

        }
    }

    @Override
    public void lmBleConnectionFailed(int i) {
        String msg = "蓝牙连接失败，状态码：" + i;
        Log.e("RingLog", msg);
        recordLog(msg);
    }

    @Override
    public void VERSION(byte b, String s) {
        recordLog(s);
    }

    @Override
    public void syncTime(byte b, byte[] bytes) {
        recordLog("时间同步完成");
    }

    @Override
    public void stepCount(byte[] bytes) {

    }

    @Override
    public void clearStepCount(byte b) {

    }

    @Override
    public void battery(byte b, byte b1) {
        if (b == 0) {
            recordLog("电池电量为" + b1);
        }
    }

    @Override
    public void timeOut() {

    }

    @Override
    public void saveData(String s) {
        String msg = NotificationHandler.handleNotification(hexStringToByteArray(s));
        recordLog(msg);
    }

    @Override
    public void reset(byte[] bytes) {

    }

    @Override
    public void setCollection(byte b) {

    }

    @Override
    public void getCollection(byte[] bytes) {

    }

    @Override
    public void getSerialNum(byte[] bytes) {

    }

    @Override
    public void setSerialNum(byte b) {

    }

    @Override
    public void cleanHistory(byte b) {

    }

    @Override
    public void setBlueToolName(byte b) {

    }

    @Override
    public void readBlueToolName(byte b, String s) {

    }

    @Override
    public void stopRealTimeBP(byte b) {

    }

    @Override
    public void BPwaveformData(byte b, byte b1, String s) {

    }

    @Override
    public void onSport(int i, byte[] bytes) {

    }

    @Override
    public void breathLight(byte b) {

    }

    @Override
    public void SET_HID(byte b) {

    }

    @Override
    public void GET_HID(byte b, byte b1, byte b2) {

    }

    @Override
    public void GET_HID_CODE(byte[] bytes) {

    }

    @Override
    public void GET_CONTROL_AUDIO_ADPCM(byte b) {

    }

    @Override
    public void SET_AUDIO_ADPCM_AUDIO(byte b) {

    }

    @Override
    public void setAudio(short i, int i1, byte[] bytes) {

    }

    @Override
    public void stopHeart(byte b) {

    }

    @Override
    public void stopQ2(byte b) {

    }

    @Override
    public void GET_ECG(byte[] bytes) {

    }

    @Override
    public void SystemControl(SystemControlBean systemControlBean) {

    }

    @Override
    public void CONTROL_AUDIO(byte[] bytes) {

    }

    @Override
    public void motionCalibration(byte b) {

    }

    @Override
    public void stopBloodPressure(byte b) {

    }

    @Override
    public void appBind(SystemControlBean systemControlBean) {

    }

    @Override
    public void appConnect(SystemControlBean systemControlBean) {

    }

    @Override
    public void appRefresh(SystemControlBean systemControlBean) {

    }
}