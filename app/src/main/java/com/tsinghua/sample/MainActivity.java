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
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
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

import com.lm.sdk.inter.IResponseListener;
import com.tsinghua.sample.service.FloatingWindowService;
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

public class MainActivity extends AppCompatActivity implements IResponseListener {
    private static final int REQUEST_CODE_OVERLAY_PERMISSION = 100;
    public static boolean isRecordingECG = false;
    public static boolean isRecordingRing = false;
    private static final int REQUEST_CAMERA_PERMISSION = 200;

    public static boolean isRecordingVideo = false;
    private TextView tvEcgData;
    private Button btnStartEcg, btnStartRing, btnRecordVideo;
    private static final int PERMISSION_REQUEST_CODE = 100;  // Permission request code
    private TextView tvLMAPILog;
    private BufferedWriter logWriter = null;
    public static BufferedWriter logWriterECG = null;

    static String mac = "EA:36:19:43:DA:8A";
    private BluetoothAdapter.LeScanCallback leScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] bytes) {
            if (device == null || TextUtils.isEmpty(device.getName())) {
                return;
            }
            //是否符合条件，符合条件，会返回戒指设备信息
            BleDeviceInfo bleDeviceInfo = LogicalApi.getBleDeviceInfoWhenBleScan(device, rssi, bytes);
            Log.e("Vivalnk",bleDeviceInfo.toString());
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
                tvLMAPILog.append(logMessage + "\n");
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
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        Button btnSettings = findViewById(R.id.btn_settings);

        btnSettings.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
        });
        Button btnTimeStamp = findViewById(R.id.btn_Timestamp);

        btnTimeStamp.setOnClickListener(v -> {
            TimestampFragment timestampFragment = new TimestampFragment();
            timestampFragment.show(getSupportFragmentManager(), "TimestampFragment");
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
        tvLMAPILog = findViewById(R.id.tv_ring_data);

        LmAPI.init(this.getApplication());
        LmAPI.setDebug(true);
        LmAPI.addWLSCmdListener(this, this);
//// 监视蓝牙设备与APP连接的状态
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
        Log.e("ConnectDevice","mac :"+mac);
        BluetoothDevice remote = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(mac);
        if(remote != null){
            BLEUtils.connectLockByBLE(this,remote);
            Log.e("ConnectDevice","蓝牙已连接");
        }
        startScan(this);

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

        if (!isRecordingECG) {
            isRecordingECG = true;
            btnStartEcg.setText("停止心电");
            tvEcgData.setText("心电数据: 正在记录...");

            VivaLink.startSampling();
            try {
                // 建议保存到应用私有存储中，避免权限问题
                String fileName = "VivaLink_log_" + System.currentTimeMillis() + ".txt";
                File logFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)+"/Sample/VivaLinkLog/", fileName);
                logWriterECG = new BufferedWriter(new FileWriter(logFile, true));
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
        if (!isRecordingRing) {
            isRecordingRing = true;
            btnStartRing.setText("停止指环");
            tvLMAPILog.setText("");
            startSendingCommands();
            try {
                // 建议保存到应用私有存储中，避免权限问题
                String fileName = "LMAPI_log_" + System.currentTimeMillis() + ".txt";
                File logFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)+"/Sample/RingLog/", fileName);
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
            isRecordingRing = false;
            btnStartRing.setText("指环开始");
            tvLMAPILog.setText("");
            stopSendingCommands();
            try {
                if (logWriter != null) {
                    logWriter.close();
                    logWriter = null;
                }
                Toast.makeText(MainActivity.this, "日志记录结束", Toast.LENGTH_SHORT).show();
                recordLog("【日志记录结束】");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    // 模拟录制视频
    private void startVideoRecording() {
        isRecordingVideo = true;
        if (Settings.canDrawOverlays(this)) {
            // 已授权，启动服务
            startService(new Intent(this, FloatingWindowService.class));
            btnRecordVideo.setText("点击悬浮窗开始录制");
        } else {
            // 请求悬浮窗权限
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            startActivityForResult(intent, REQUEST_CODE_OVERLAY_PERMISSION);
        }
    }
    private void stopVideoRecording() {
        isRecordingVideo = false;
        Intent intent = new Intent(this, FloatingWindowService.class);
        stopService(intent);
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
            String hexData = "00003C00"+ Integer.toHexString(savedTime)+ "001010100100";
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
                    // 这里写要延迟执行的代码
                    LmAPI.SYNC_TIME();
                }
            }, 1000); // 延迟 2 秒（2000 毫秒）

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
    public void sendNotification(Context context) {
        String channelId = "my_channel_id"; // 必须与创建的通知渠道 ID 匹配

        // 点击通知时打开 MainActivity
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 创建通知
        Notification notification = new NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_launcher_background) // 替换为你的图标
                .setContentTitle("通知")
                .setContentText("指环数据已收集完毕,请手动停止")
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build();

        // 发送通知
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.notify(1, notification); // 1 是通知 ID，可用于更新或取消通知
        }
    }
}