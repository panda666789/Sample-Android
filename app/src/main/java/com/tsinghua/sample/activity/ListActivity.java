package com.tsinghua.sample.activity;

import static com.tsinghua.sample.utils.VivaLink.startScan;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.mediapipe.solutions.facemesh.FaceMesh;
import com.lm.sdk.LmAPI;
import com.lm.sdk.inter.IHistoryListener;
import com.lm.sdk.inter.IResponseListener;
import com.lm.sdk.mode.HistoryDataBean;
import com.lm.sdk.mode.SystemControlBean;
import com.lm.sdk.utils.BLEUtils;
import com.tsinghua.sample.DeviceAdapter;
import com.tsinghua.sample.R;
import com.tsinghua.sample.RingViewHolder;
import com.tsinghua.sample.SettingsActivity;
import com.tsinghua.sample.TimestampFragment;
import com.tsinghua.sample.device.OximeterService;
import com.tsinghua.sample.media.CameraFaceProcessor;
import com.tsinghua.sample.media.CameraHelper;
import com.tsinghua.sample.media.CameraPureFaceProcessor;
import com.tsinghua.sample.model.Device;
import com.tsinghua.sample.utils.NotificationHandler;
import com.tsinghua.sample.utils.PlotView;
import com.tsinghua.sample.utils.VivaLink;
import com.vivalnk.sdk.BuildConfig;
import com.vivalnk.sdk.VitalClient;
import com.vivalnk.sdk.exception.VitalCode;
import com.vivalnk.sdk.utils.ProcessUtils;

import org.opencv.android.OpenCVLoader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class ListActivity extends AppCompatActivity implements IResponseListener {

    private static final String ACTION_USB_PERMISSION = "com.tsinghua.sample.USB_PERMISSION";
    private UsbManager usbManager;
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    public RingViewHolder ringViewHolder;

    private RecyclerView recyclerView;
    private DeviceAdapter adapter;

    // 独立的摄像头相关组件
    private CardView frontCameraCard;
    private CardView backCameraCard;
    private CardView frontCameraPlotCard;
    private SurfaceView frontCameraSurfaceView;
    private SurfaceView backCameraSurfaceView;
    private PlotView frontCameraPlotView;
    private ImageButton frontCameraCloseBtn;
    private ImageButton backCameraCloseBtn;
    private ImageButton frontCameraPlotCloseBtn;

    // 摄像头处理器
    private CameraHelper frontCameraHelper;
    private CameraHelper backCameraHelper;
    private CameraFaceProcessor cameraFaceProcessor;
    private CameraPureFaceProcessor cameraPureFaceProcessor;

    // 摄像头状态
    private boolean frontCameraActive = false;
    private boolean backCameraActive = false;
    private boolean frontCameraRecording = false;
    private boolean backCameraRecording = false;

    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_OFF) {
                    Toast.makeText(context, "Bluetooth is OFF", Toast.LENGTH_SHORT).show();
                } else if (state == BluetoothAdapter.STATE_ON) {
                    Toast.makeText(context, "Bluetooth is ON", Toast.LENGTH_SHORT).show();
                }
            }
        }
    };

    // 摄像头控制接口，供DeviceAdapter调用
    public interface CameraController {
        void startFrontCamera();
        void stopFrontCamera();
        void startBackCamera();
        void stopBackCamera();
        boolean isFrontCameraRecording();
        boolean isBackCameraRecording();
    }

    private CameraController cameraController = new CameraController() {
        @Override
        public void startFrontCamera() {
            startFrontCameraRecording();
        }

        @Override
        public void stopFrontCamera() {
            stopFrontCameraRecording();
        }

        @Override
        public void startBackCamera() {
            startBackCameraRecording();
        }

        @Override
        public void stopBackCamera() {
            stopBackCameraRecording();
        }

        @Override
        public boolean isFrontCameraRecording() {
            return frontCameraRecording;
        }

        @Override
        public boolean isBackCameraRecording() {
            return backCameraRecording;
        }
    };

    @SuppressLint("NewApi")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        OpenCVLoader.initLocal();

        // 初始化摄像头相关UI组件
        initCameraViews();

        LmAPI.init(getApplication());
        LmAPI.setDebug(true);
        LmAPI.addWLSCmdListener(this, this);
        LmAPI.READ_HISTORY_AUTO(new IHistoryListener() {
            @Override
            public void error(int code) {

            }

            @Override
            public void success() {

            }

            @Override
            public void progress(double value, HistoryDataBean bean) {
                Log.d("HistoryListener", "progress: " + value);
            }

            @Override
            public void noNewDataAvailable() {

            }
        });

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        registerReceiver(broadcastReceiver, intentFilter);

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

        checkPermissions();
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        registerReceiver(usbReceiver, new IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED);

        checkOximeterUsbPermission();
        EdgeToEdge.enable(this);

        Button btnSettings = findViewById(R.id.btn_settings);
        btnSettings.setOnClickListener(v -> {
            startActivity(new Intent(ListActivity.this, SettingsActivity.class));
        });

        Button btnTimeStamp = findViewById(R.id.btn_Timestamp);
        btnTimeStamp.setOnClickListener(v -> {
            FragmentManager fm = getSupportFragmentManager();
            TimestampFragment fragment = (TimestampFragment) fm.findFragmentByTag("TimestampFragment");

            if (fragment == null) {
                fragment = new TimestampFragment();
            }

            fragment.show(fm, "TimestampFragment");
        });

        recyclerView = findViewById(R.id.deviceRecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        if (ProcessUtils.isMainProcess(this)) {
            VitalClient.getInstance().init(this);
            if (BuildConfig.DEBUG) {
                VitalClient.getInstance().openLog();
                VitalClient.getInstance().allowWriteToFile(true);
            }
        }

        int resultCode = VitalClient.getInstance().checkBle();
        Log.e("VivaLink", String.valueOf(resultCode));
        if (resultCode != VitalCode.RESULT_OK) {
            Toast.makeText(ListActivity.this, "Vital Client runtime check failed",
                    Toast.LENGTH_LONG).show();
        }

        List<Device> devices = new ArrayList<>();
        devices.add(new Device(Device.TYPE_FRONT_CAMERA, "前置录制"));
        devices.add(new Device(Device.TYPE_BACK_CAMERA, "后置录制"));
        devices.add(new Device(Device.TYPE_MICROPHONE, "麦克风"));
        devices.add(new Device(Device.TYPE_IMU, "IMU"));
        devices.add(new Device(Device.TYPE_RING, "指环"));
        devices.add(new Device(Device.TYPE_ECG, "心电"));
        devices.add(new Device(Device.TYPE_OXIMETER, "血氧仪"));

        adapter = new DeviceAdapter(this, devices, cameraController);
        recyclerView.setAdapter(adapter);

        recyclerView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                ringViewHolder = (RingViewHolder) recyclerView.findViewHolderForAdapterPosition(4);
                if (ringViewHolder != null) {
                    Log.d("RingViewHolder", "RingViewHolder at position 4 is ready.");
                } else {
                    Log.d("RingViewHolder", "RingViewHolder at position 4 is null.");
                }
            }
        });

        Intent svcIntent = new Intent(this, OximeterService.class);
        this.startService(svcIntent);
        this.bindService(svcIntent, adapter.getOximeterConnection(), Context.BIND_AUTO_CREATE);
    }

    private void initCameraViews() {
        frontCameraCard = findViewById(R.id.frontCameraCard);
        backCameraCard = findViewById(R.id.backCameraCard);
        frontCameraPlotCard = findViewById(R.id.frontCameraPlotCard);
        frontCameraSurfaceView = findViewById(R.id.frontCameraSurfaceView);
        backCameraSurfaceView = findViewById(R.id.backCameraSurfaceView);
        frontCameraPlotView = findViewById(R.id.frontCameraPlotView);
        frontCameraCloseBtn = findViewById(R.id.frontCameraCloseBtn);
        backCameraCloseBtn = findViewById(R.id.backCameraCloseBtn);
        frontCameraPlotCloseBtn = findViewById(R.id.frontCameraPlotCloseBtn);

        // 设置关闭按钮事件
        frontCameraCloseBtn.setOnClickListener(v -> hideFrontCameraPreview());
        backCameraCloseBtn.setOnClickListener(v -> hideBackCameraPreview());
        frontCameraPlotCloseBtn.setOnClickListener(v -> hideFrontCameraPlot());
    }

    // 前置摄像头控制方法
    public void startFrontCameraRecording() {
        if (frontCameraRecording) return;

        SharedPreferences prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE);
        boolean enableInference = prefs.getBoolean("enable_inference", false);

        showFrontCameraPreview();

        if (enableInference) {
            String enableRecording = prefs.getString("video_format", "none");
            if (enableRecording.equals("none")) {
                try {
                    cameraPureFaceProcessor = new CameraPureFaceProcessor(this, frontCameraSurfaceView, frontCameraPlotView);
                    cameraPureFaceProcessor.startCamera();
                    showFrontCameraPlot();
                    frontCameraActive = true;
                    frontCameraRecording = true;
                } catch (Exception e) {
                    Log.e("Camera", "Failed to start pure face processor", e);
                    Toast.makeText(this, "启动人脸处理失败", Toast.LENGTH_SHORT).show();
                    hideFrontCameraPreview();
                    return;
                }
            } else {
                try {
                    cameraFaceProcessor = new CameraFaceProcessor(this, frontCameraSurfaceView, frontCameraPlotView);
                    cameraFaceProcessor.startCamera();
                    showFrontCameraPlot();
                    frontCameraActive = true;
                    frontCameraRecording = true;
                } catch (Exception e) {
                    Log.e("Camera", "Failed to start face processor", e);
                    Toast.makeText(this, "启动摄像头失败", Toast.LENGTH_SHORT).show();
                    hideFrontCameraPreview();
                    return;
                }
            }
        } else {
            // 简化的摄像头初始化 - 延迟初始化，等SurfaceView准备好
                try {
                    if (frontCameraHelper == null) {
                        frontCameraHelper = new CameraHelper(this, frontCameraSurfaceView, null);
                    }
                    // 给摄像头一些时间来初始化，然后开始录制
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            frontCameraHelper.startFrontRecording();
                            frontCameraActive = true;
                            frontCameraRecording = true;
                            Log.d("Camera", "Front camera recording started successfully");
                        } catch (Exception e) {
                            Log.e("Camera", "Failed to start front recording: " + e.getMessage(), e);
                            Toast.makeText(this, "前置摄像头录制启动失败", Toast.LENGTH_SHORT).show();
                            hideFrontCameraPreview();
                        }
                    }, 1000); // 给摄像头1秒时间初始化

                } catch (Exception e) {
                    Log.e("Camera", "Failed to initialize front camera: " + e.getMessage(), e);
                    Toast.makeText(this, "前置摄像头初始化失败", Toast.LENGTH_SHORT).show();
                    hideFrontCameraPreview();
                }
        }
    }

    public void stopFrontCameraRecording() {
        if (!frontCameraRecording) return;

        if (cameraPureFaceProcessor != null) {
            cameraPureFaceProcessor.stopCamera();
            cameraPureFaceProcessor = null;
        }

        if (cameraFaceProcessor != null) {
            cameraFaceProcessor.stopCamera();
            cameraFaceProcessor = null;
        }

        if (frontCameraHelper != null) {
            frontCameraHelper.stopFrontRecording();
        }

        hideFrontCameraPreview();
        hideFrontCameraPlot();

        frontCameraActive = false;
        frontCameraRecording = false;
    }

    // 后置摄像头控制方法
    public void startBackCameraRecording() {
        if (backCameraRecording) return;

        showBackCameraPreview();

        // 简化的摄像头初始化 - 延迟初始化，等SurfaceView准备好
            try {
                if (backCameraHelper == null) {
                    backCameraHelper = new CameraHelper(this, null, backCameraSurfaceView);
                }

                // 给摄像头一些时间来初始化，然后开始录制
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        backCameraHelper.startBackRecording();
                        backCameraActive = true;
                        backCameraRecording = true;
                        Log.d("Camera", "Back camera recording started successfully");
                    } catch (Exception e) {
                        Log.e("Camera", "Failed to start back recording: " + e.getMessage(), e);
                        Toast.makeText(this, "后置摄像头录制启动失败", Toast.LENGTH_SHORT).show();
                        hideBackCameraPreview();
                    }
                }, 1000); // 给摄像头1秒时间初始化

            } catch (Exception e) {
                Log.e("Camera", "Failed to initialize back camera: " + e.getMessage(), e);
                Toast.makeText(this, "后置摄像头初始化失败", Toast.LENGTH_SHORT).show();
                hideBackCameraPreview();
            }
    }

    public void stopBackCameraRecording() {
        if (!backCameraRecording) return;

        if (backCameraHelper != null) {
            backCameraHelper.stopBackRecording();
        }

        hideBackCameraPreview();

        backCameraActive = false;
        backCameraRecording = false;
    }

    private void showFrontCameraPreview() {
        frontCameraCard.setVisibility(View.VISIBLE);
        frontCameraCard.animate()
                .alpha(1f)
                .setDuration(300)
                .start();
    }

    private void hideFrontCameraPreview() {
        frontCameraCard.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction(() -> frontCameraCard.setVisibility(View.GONE))
                .start();
    }

    private void showBackCameraPreview() {
        backCameraCard.setVisibility(View.VISIBLE);
        backCameraCard.animate()
                .alpha(1f)
                .setDuration(300)
                .start();
    }

    private void hideBackCameraPreview() {
        backCameraCard.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction(() -> backCameraCard.setVisibility(View.GONE))
                .start();
    }

    private void showFrontCameraPlot() {
        frontCameraPlotCard.setVisibility(View.VISIBLE);
        frontCameraPlotCard.animate()
                .alpha(1f)
                .setDuration(300)
                .start();
    }

    private void hideFrontCameraPlot() {
        frontCameraPlotCard.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction(() -> frontCameraPlotCard.setVisibility(View.GONE))
                .start();
    }

    // 原有的其他方法保持不变...
    private void checkOximeterUsbPermission() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        for (UsbDevice device : deviceList.values()) {
            Log.d("USB", "Detected device: VID=" + device.getVendorId() + " PID=" + device.getProductId());
            if (!usbManager.hasPermission(device)) {
                PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0,
                        new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
                usbManager.requestPermission(device, permissionIntent);
            } else {
                Log.d("USB", "已有权限，无需请求");
            }
        }
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            Log.d("USB", "权限已授予");
                        }
                    } else {
                        Log.w("USB", "权限被拒绝");
                    }
                }
            }
        }
    };

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_CAMERA_PERMISSION);
        }
    }

    @Override
    protected void onDestroy() {
        // 停止所有摄像头
        stopFrontCameraRecording();
        stopBackCameraRecording();

        // 释放摄像头资源
        if (frontCameraHelper != null) {
            frontCameraHelper.release();
            frontCameraHelper = null;
        }
        if (backCameraHelper != null) {
            backCameraHelper.release();
            backCameraHelper = null;
        }

        // 释放AI处理器资源
        if (cameraFaceProcessor != null) {
            cameraFaceProcessor.stopCamera();
            cameraFaceProcessor = null;
        }
        if (cameraPureFaceProcessor != null) {
            cameraPureFaceProcessor.stopCamera();
            cameraPureFaceProcessor = null;
        }

        unregisterReceiver(usbReceiver);
        super.onDestroy();
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

    private void requestPermission(String[] permissions, int requestCode) {
        ActivityCompat.requestPermissions(this, permissions, requestCode);
    }

    private boolean checkPermissions(String[] permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    // IResponseListener 接口实现保持不变...
    @Override
    public void lmBleConnecting(int i) {
        Log.e("ConnectDevice", " 蓝牙连接中");
        String msg = "蓝牙连接中，状态码：" + i;
        ringViewHolder.recordLog(msg);
    }

    @Override
    public void lmBleConnectionSucceeded(int i) {
        String msg = "蓝牙连接成功，状态码：" + i;
        ringViewHolder.recordLog(msg);
        if (i == 7) {
            BLEUtils.setGetToken(true);
            Log.e("TAG", "\n连接成功");
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                }
            }, 500);
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    LmAPI.GET_BATTERY((byte) 0x00);
                }
            }, 1000);
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    LmAPI.GET_VERSION((byte) 0x00);
                }
            }, 1500);
        }
    }

    @Override
    public void lmBleConnectionFailed(int i) {
        String msg = "蓝牙连接失败，状态码：" + i;
        Log.e("RingLog", msg);
        ringViewHolder.recordLog(msg);
    }

    @Override
    public void VERSION(byte b, String s) {
        ringViewHolder.recordLog(s);
    }

    @Override
    public void syncTime(byte b, byte[] bytes) {
        ringViewHolder.recordLog("时间同步完成");
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
            ringViewHolder.recordLog("电池电量为" + b1);
        }
    }

    @Override
    public void battery_push(byte b, byte datum) {
    }

    @Override
    public void timeOut() {
    }

    @Override
    public void saveData(String s) {
        String msg = NotificationHandler.handleNotification(hexStringToByteArray(s));
        ringViewHolder.recordLog(msg);
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
    public void TOUCH_AUDIO_FINISH_XUN_FEI() {
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
    public void setUserInfo(byte result) {
    }

    @Override
    public void getUserInfo(int sex, int height, int weight, int age) {
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

    public static byte[] hexStringToByteArray(String hexString) {
        int len = hexString.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hexString.charAt(i), 16) << 4)
                    + Character.digit(hexString.charAt(i + 1), 16));
        }
        return data;
    }
}