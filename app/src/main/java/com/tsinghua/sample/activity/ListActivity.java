package com.tsinghua.sample.activity;

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
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
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
import com.tsinghua.sample.activity.FrontCameraSettingsActivity;
import com.tsinghua.sample.media.CameraFaceProcessor;
import com.tsinghua.sample.media.CameraHelper;
import com.tsinghua.sample.media.CameraPureFaceProcessor;
import com.tsinghua.sample.model.Device;
import com.tsinghua.sample.utils.NotificationHandler;
import com.tsinghua.sample.utils.HeartRateEstimator;
import com.tsinghua.sample.utils.PlotView;
import com.tsinghua.sample.utils.VideoQualityEvaluator;
import com.tsinghua.sample.ecg.ECGMeasurementController;
import com.vivalnk.sdk.BuildConfig;
import com.vivalnk.sdk.VitalClient;
import com.vivalnk.sdk.exception.VitalCode;
import com.vivalnk.sdk.utils.ProcessUtils;

import com.tsinghua.sample.core.RecordingCoordinator;
import com.tsinghua.sample.core.SessionManager;
import com.tsinghua.sample.core.TimeSync;
import com.tsinghua.sample.media.IMURecorder;
import com.tsinghua.sample.media.MultiMicAudioRecorderHelper;
import com.tsinghua.sample.media.RecorderHelper;
import com.tsinghua.sample.media.CameraHelper;
import com.tsinghua.sample.device.OximeterManager;
import com.tsinghua.sample.utils.VideoPostProcessor;
import org.opencv.android.OpenCVLoader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ListActivity extends AppCompatActivity implements IResponseListener {

    private static final String ACTION_USB_PERMISSION = "com.tsinghua.sample.USB_PERMISSION";
    private UsbManager usbManager;
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    public RingViewHolder ringViewHolder;

    private RecyclerView recyclerView;
    private DeviceAdapter adapter;

    // 固定摄像头控制卡片（顶部）
    private com.google.android.material.card.MaterialCardView cameraControlCard;
    private android.widget.LinearLayout cameraHeaderLayout;
    private android.widget.LinearLayout cameraContentLayout;
    private android.widget.ImageView cameraExpandArrow;
    private android.widget.TextView cameraRecordingIndicator;

    // 摄像头选择按钮
    private com.google.android.material.button.MaterialButton btnFrontCamera;
    private com.google.android.material.button.MaterialButton btnBackCamera;
    private com.google.android.material.button.MaterialButton btnDualCamera;

    // 摄像头预览区域
    private androidx.constraintlayout.widget.ConstraintLayout cameraPreviewContainer;
    private SurfaceView mainCameraSurfaceView;
    private SurfaceView pipCameraSurfaceView;
    private com.google.android.material.card.MaterialCardView pipCameraContainer;
    private android.widget.LinearLayout cameraPreviewPlaceholder;
    private android.widget.TextView cameraPlaceholderText;

    // 心率显示（面部推理模式）
    private android.widget.LinearLayout heartRateLayout;
    private PlotView cameraPlotViewHR;
    private android.widget.TextView cameraHeartRateText;

    // 摄像头设置按钮
    private android.widget.ImageButton cameraSettingsBtn;

    // 底部操作栏
    private Button btnStartAll;
    private Button btnStopAll;
    private android.widget.TextView statusText; // 录制状态和剩余时间显示

    // 摄像头状态
    private boolean cameraExpanded = true;
    private int currentCameraMode = 0;  // 0=前置, 1=后置, 2=双摄

    // 摄像头处理器
    private CameraHelper frontCameraHelper;
    private CameraHelper backCameraHelper;
    private CameraFaceProcessor cameraFaceProcessor;
    private CameraPureFaceProcessor cameraPureFaceProcessor;
    private RecordingCoordinator recordingCoordinator;
    private IMURecorder sharedImuRecorder;  // 共享的IMU记录器实例

    // 摄像头状态
    private boolean frontCameraActive = false;
    private boolean backCameraActive = false;
    private boolean frontCameraRecording = false;
    private boolean backCameraRecording = false;

    // 录制时间追踪
    private long recordingStartTime = 0;

    // 预加载的HeartRateEstimator
    private HeartRateEstimator preloadedEstimator;
    private volatile boolean isModelLoaded = false;
    private final ExecutorService modelLoadExecutor = Executors.newSingleThreadExecutor();

    // 视频后处理器
    private VideoPostProcessor videoPostProcessor;
    private volatile boolean isPostProcessing = false;
    private android.app.ProgressDialog postProcessDialog;

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

        // 初始化全局时间基准（必须在所有数据采集模块之前）
        TimeSync.initializeGlobal();

        OpenCVLoader.initLocal();

        // 预加载AI模型（后台线程）
        preloadHeartRateModel();

        // 初始化摄像头相关UI组件
        initCameraViews();
        initCoordinator();

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

        // 注册USB相关广播接收器（权限、连接、断开）
        // 注意：USB广播是系统广播，必须使用RECEIVER_EXPORTED才能接收
        IntentFilter usbFilter = new IntentFilter(ACTION_USB_PERMISSION);
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, usbFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(usbReceiver, usbFilter);
        }

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

        // 增加缓存大小，防止摄像头卡片被回收导致Surface销毁
        recyclerView.setItemViewCacheSize(10);
        recyclerView.setHasFixedSize(false);

        if (ProcessUtils.isMainProcess(this)) {
            VitalClient.getInstance().init(this);
            if (BuildConfig.DEBUG) {
                VitalClient.getInstance().openLog();
                VitalClient.getInstance().allowWriteToFile(true);
            }
        }

        int resultCode = VitalClient.getInstance().checkBle();
        Log.e("ECG", String.valueOf(resultCode));
        if (resultCode != VitalCode.RESULT_OK) {
            Toast.makeText(ListActivity.this, "Vital Client runtime check failed",
                    Toast.LENGTH_LONG).show();
        }

        List<Device> devices = new ArrayList<>();
        // 摄像头已移至固定顶部区域，不再放入RecyclerView
        devices.add(new Device(Device.TYPE_MICROPHONE, "麦克风"));
        devices.add(new Device(Device.TYPE_IMU, "IMU"));
        devices.add(new Device(Device.TYPE_RING, "指环"));
        devices.add(new Device(Device.TYPE_ECG, "心电"));
        devices.add(new Device(Device.TYPE_OXIMETER, "血氧仪"));

        adapter = new DeviceAdapter(this, devices, cameraController, sharedImuRecorder);
        recyclerView.setAdapter(adapter);

        // 获取 RingViewHolder 引用（用于指环回调）
        recyclerView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                // 指环现在是 position 2（原来是3，去掉摄像头后变成2）
                ringViewHolder = (RingViewHolder) recyclerView.findViewHolderForAdapterPosition(2);
                if (ringViewHolder != null) {
                    Log.d("RingViewHolder", "RingViewHolder at position 2 is ready.");
                }
            }
        });

        Intent svcIntent = new Intent(this, OximeterService.class);
        this.startService(svcIntent);
        this.bindService(svcIntent, adapter.getOximeterConnection(), Context.BIND_AUTO_CREATE);
    }

    private void initCameraViews() {
        // 固定摄像头控制卡片
        cameraControlCard = findViewById(R.id.cameraControlCard);
        cameraHeaderLayout = findViewById(R.id.cameraHeaderLayout);
        cameraContentLayout = findViewById(R.id.cameraContentLayout);
        cameraExpandArrow = findViewById(R.id.cameraExpandArrow);
        cameraRecordingIndicator = findViewById(R.id.cameraRecordingIndicator);

        // 摄像头选择按钮
        btnFrontCamera = findViewById(R.id.btnFrontCamera);
        btnBackCamera = findViewById(R.id.btnBackCamera);
        btnDualCamera = findViewById(R.id.btnDualCamera);

        // 预览区域
        cameraPreviewContainer = findViewById(R.id.cameraPreviewContainer);
        mainCameraSurfaceView = findViewById(R.id.mainCameraSurfaceView);
        pipCameraSurfaceView = findViewById(R.id.pipCameraSurfaceView);
        pipCameraContainer = findViewById(R.id.pipCameraContainer);
        cameraPreviewPlaceholder = findViewById(R.id.cameraPreviewPlaceholder);
        cameraPlaceholderText = findViewById(R.id.cameraPlaceholderText);

        // 心率显示
        heartRateLayout = findViewById(R.id.heartRateLayout);
        cameraPlotViewHR = findViewById(R.id.cameraPlotViewHR);
        cameraHeartRateText = findViewById(R.id.cameraHeartRateText);

        // 摄像头设置按钮
        cameraSettingsBtn = findViewById(R.id.cameraSettingsBtn);

        // 底部操作按钮
        btnStartAll = findViewById(R.id.btn_start_all);
        btnStopAll = findViewById(R.id.btn_stop_all);
        statusText = findViewById(R.id.statusText);

        // 设置摄像头卡片折叠/展开
        cameraHeaderLayout.setOnClickListener(v -> toggleCameraExpand());

        // 摄像头设置按钮点击
        cameraSettingsBtn.setOnClickListener(v -> {
            Intent intent = new Intent(this, FrontCameraSettingsActivity.class);
            intent.putExtra("deviceName", "摄像头");
            startActivity(intent);
        });

        // 摄像头模式选择按钮
        btnFrontCamera.setOnClickListener(v -> updateCameraSelection(0));
        btnBackCamera.setOnClickListener(v -> updateCameraSelection(1));
        btnDualCamera.setOnClickListener(v -> updateCameraSelection(2));

        // 主录制按钮 - 切换开始/停止
        btnStartAll.setOnClickListener(v -> toggleRecording());
        btnStopAll.setOnClickListener(v -> stopAllRecording());

        // 默认选中前置
        updateCameraSelection(0);
    }

    /**
     * 切换摄像头卡片折叠/展开状态
     */
    private void toggleCameraExpand() {
        cameraExpanded = !cameraExpanded;
        cameraContentLayout.setVisibility(cameraExpanded ? View.VISIBLE : View.GONE);
        cameraExpandArrow.setRotation(cameraExpanded ? 180 : 0);
    }

    /**
     * 更新摄像头选择模式
     * @param mode 0=前置, 1=后置, 2=双摄
     */
    private void updateCameraSelection(int mode) {
        currentCameraMode = mode;

        // 更新按钮样式（使用Material Button风格）
        btnFrontCamera.setBackgroundTintList(
            getColorStateList(mode == 0 ? R.color.my_primary : android.R.color.transparent));
        btnBackCamera.setBackgroundTintList(
            getColorStateList(mode == 1 ? R.color.my_primary : android.R.color.transparent));
        btnDualCamera.setBackgroundTintList(
            getColorStateList(mode == 2 ? R.color.my_primary : android.R.color.transparent));

        // 更新文字颜色
        btnFrontCamera.setTextColor(getColor(mode == 0 ? android.R.color.white : R.color.my_primary));
        btnBackCamera.setTextColor(getColor(mode == 1 ? android.R.color.white : R.color.my_primary));
        btnDualCamera.setTextColor(getColor(mode == 2 ? android.R.color.white : R.color.my_primary));
    }

    /**
     * 显示摄像头预览（隐藏占位符）
     */
    private void showCameraPreview() {
        cameraPreviewPlaceholder.setVisibility(View.GONE);
        cameraRecordingIndicator.setVisibility(View.VISIBLE);
    }

    /**
     * 隐藏摄像头预览（显示占位符）
     */
    private void hideCameraPreview() {
        cameraPreviewPlaceholder.setVisibility(View.VISIBLE);
        cameraRecordingIndicator.setVisibility(View.GONE);
    }

    /**
     * 更新占位符文本
     */
    private void setCameraPlaceholderText(String text) {
        if (cameraPlaceholderText != null) {
            cameraPlaceholderText.setText(text);
        }
    }

    /**
     * 显示画中画容器（双摄模式）
     */
    private void showPipPreview() {
        if (pipCameraContainer != null) {
            pipCameraContainer.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 隐藏画中画容器
     */
    private void hidePipPreview() {
        if (pipCameraContainer != null) {
            pipCameraContainer.setVisibility(View.GONE);
        }
    }

    /**
     * 显示心率波形（面部推理模式）
     */
    private void showHeartRateLayout() {
        if (heartRateLayout != null) {
            heartRateLayout.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 隐藏心率波形
     */
    private void hideHeartRateLayout() {
        if (heartRateLayout != null) {
            heartRateLayout.setVisibility(View.GONE);
        }
    }

    /**
     * 更新心率显示
     */
    private void updateHeartRateDisplay(int heartRate) {
        if (cameraHeartRateText != null) {
            cameraHeartRateText.setText("心率: " + heartRate);
        }
    }

    /**
     * 显示视频质量评估结果对话框
     */
    private void showQualityResultDialog(VideoQualityEvaluator.QualityResult result) {
        showQualityResultDialog(result, null);
    }

    /**
     * 显示视频质量评估结果对话框，对话框关闭后执行回调
     */
    private void showQualityResultDialog(VideoQualityEvaluator.QualityResult result, Runnable onDismiss) {
        if (result == null) {
            if (onDismiss != null) onDismiss.run();
            return;
        }

        // 根据质量等级设置颜色
        int colorRes;
        switch (result.qualityLevel) {
            case "EXCELLENT":
                colorRes = android.R.color.holo_green_dark;
                break;
            case "GOOD":
                colorRes = android.R.color.holo_blue_dark;
                break;
            case "FAIR":
                colorRes = android.R.color.holo_orange_dark;
                break;
            default:
                colorRes = android.R.color.holo_red_dark;
                break;
        }

        String qualityLevelCN;
        switch (result.qualityLevel) {
            case "EXCELLENT": qualityLevelCN = "优秀"; break;
            case "GOOD": qualityLevelCN = "良好"; break;
            case "FAIR": qualityLevelCN = "一般"; break;
            default: qualityLevelCN = "较差"; break;
        }

        String message = String.format(
            "质量等级: %s\n综合评分: %.0f分\n\n" +
            "详细指标:\n" +
            "• 人脸检测率: %.1f%%\n" +
            "• 位置稳定性: %.1f%%\n" +
            "• 亮度稳定性: %.1f%%\n" +
            "• 平均人脸面积: %.1f%%\n\n" +
            "总帧数: %d, 有效帧: %d",
            qualityLevelCN, result.overallScore,
            result.faceDetectionRate * 100,
            result.positionStability * 100,
            result.brightnessStability * 100,
            result.avgFaceAreaRatio * 100,
            result.totalFrames, result.validFrames
        );

        new android.app.AlertDialog.Builder(this)
            .setTitle("视频质量评估")
            .setMessage(message)
            .setPositiveButton("确定", (dialog, which) -> {
                if (onDismiss != null) onDismiss.run();
            })
            .setOnCancelListener(dialog -> {
                if (onDismiss != null) onDismiss.run();
            })
            .show();
    }

    // 前置摄像头控制方法
    public void startFrontCameraRecording() {
        Log.d("Camera", "startFrontCameraRecording called, frontCameraRecording=" + frontCameraRecording);
        if (frontCameraRecording) {
            Log.d("Camera", "Front camera already recording, skipping");
            return;
        }

        SharedPreferences prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE);
        boolean enableInference = prefs.getBoolean("enable_inference", false);
        Log.d("Camera", "enable_inference=" + enableInference);

        if (enableInference) {
            // AI推理模式：使用固定的预览区域
            showCameraPreview();
            showHeartRateLayout();

            String enableRecording = prefs.getString("video_format", "none");
            if (enableRecording.equals("none")) {
                try {
                    cameraPureFaceProcessor = new CameraPureFaceProcessor(this, mainCameraSurfaceView, cameraPlotViewHR);
                    // 设置心率回调以更新UI
                    cameraPureFaceProcessor.setOnHeartRateListener(heartRate -> {
                        runOnUiThread(() -> updateHeartRateDisplay(Math.round(heartRate)));
                    });

                    // 质量评估结果在stopAllRecording中同步获取，不使用异步回调

                    // 使用预加载的模型（如果可用）
                    if (isModelLoaded && preloadedEstimator != null) {
                        cameraPureFaceProcessor.setPreloadedEstimator(preloadedEstimator, cameraPlotViewHR);
                        Toast.makeText(this, "使用预加载的AI模型", Toast.LENGTH_SHORT).show();
                    } else {
                        // 设置初始化完成回调（异步加载模式）
                        cameraPureFaceProcessor.setOnInitializedListener(new CameraPureFaceProcessor.OnInitializedListener() {
                            @Override
                            public void onInitialized() {
                                runOnUiThread(() -> {
                                    Toast.makeText(ListActivity.this, "AI模型加载完成", Toast.LENGTH_SHORT).show();
                                });
                            }

                            @Override
                            public void onInitializeFailed(String error) {
                                runOnUiThread(() -> {
                                    Toast.makeText(ListActivity.this, "AI模型加载失败: " + error, Toast.LENGTH_LONG).show();
                                });
                            }
                        });
                        setCameraPlaceholderText("AI模型加载中...");
                    }

                    cameraPureFaceProcessor.startCamera();
                    frontCameraActive = true;
                    frontCameraRecording = true;
                } catch (Exception e) {
                    Log.e("Camera", "Failed to start pure face processor", e);
                    Toast.makeText(this, "启动人脸处理失败", Toast.LENGTH_SHORT).show();
                    hideCameraPreview();
                    hideHeartRateLayout();
                    return;
                }
            } else {
                try {
                    cameraFaceProcessor = new CameraFaceProcessor(this, mainCameraSurfaceView, cameraPlotViewHR);
                    // 设置心率回调以更新UI
                    cameraFaceProcessor.setOnHeartRateListener(heartRate -> {
                        runOnUiThread(() -> updateHeartRateDisplay(Math.round(heartRate)));
                    });

                    // 质量评估结果在stopAllRecording中同步获取，不使用异步回调

                    // 使用预加载的模型（如果可用）
                    if (isModelLoaded && preloadedEstimator != null) {
                        cameraFaceProcessor.setPreloadedEstimator(preloadedEstimator, cameraPlotViewHR);
                        Toast.makeText(this, "使用预加载的AI模型", Toast.LENGTH_SHORT).show();
                    } else {
                        // 设置初始化完成回调（异步加载模式）
                        cameraFaceProcessor.setOnInitializedListener(new CameraFaceProcessor.OnInitializedListener() {
                            @Override
                            public void onInitialized() {
                                runOnUiThread(() -> {
                                    Toast.makeText(ListActivity.this, "AI模型加载完成", Toast.LENGTH_SHORT).show();
                                });
                            }

                            @Override
                            public void onInitializeFailed(String error) {
                                runOnUiThread(() -> {
                                    Toast.makeText(ListActivity.this, "AI模型加载失败: " + error, Toast.LENGTH_LONG).show();
                                });
                            }
                        });
                        setCameraPlaceholderText("AI模型加载中...");
                    }

                    cameraFaceProcessor.startCamera();
                    frontCameraActive = true;
                    frontCameraRecording = true;
                } catch (Exception e) {
                    Log.e("Camera", "Failed to start face processor", e);
                    Toast.makeText(this, "启动摄像头失败", Toast.LENGTH_SHORT).show();
                    hideCameraPreview();
                    hideHeartRateLayout();
                    return;
                }
            }
        } else {
            // 非AI模式：使用固定预览区域
            Log.d("Camera", "Non-inference mode: starting front camera recording");
            Log.d("Camera", "mainCameraSurfaceView=" + mainCameraSurfaceView);
            showCameraPreview();

            try {
                // 每次都重新创建CameraHelper以使用正确的SurfaceView
                if (frontCameraHelper != null) {
                    Log.d("Camera", "Releasing existing frontCameraHelper");
                    frontCameraHelper.release();
                    frontCameraHelper = null;
                }
                Log.d("Camera", "Creating new CameraHelper for front camera");
                frontCameraHelper = new CameraHelper(this, mainCameraSurfaceView, null);
                Log.d("Camera", "CameraHelper created, waiting for camera ready...");
                // 轮询等待摄像头就绪，最多等待5秒
                waitForCameraReady(frontCameraHelper, true, 50, () -> {
                    try {
                        Log.d("Camera", "Front camera ready, starting recording...");
                        frontCameraHelper.startFrontRecording();
                        frontCameraActive = true;
                        frontCameraRecording = true;
                        Log.d("Camera", "Front camera recording started successfully");
                    } catch (Exception e) {
                        Log.e("Camera", "Failed to start front recording: " + e.getMessage(), e);
                        Toast.makeText(this, "前置录制启动失败", Toast.LENGTH_SHORT).show();
                        hideCameraPreview();
                    }
                });

            } catch (Exception e) {
                Log.e("Camera", "Failed to initialize front camera: " + e.getMessage(), e);
                Toast.makeText(this, "前置摄像头初始化失败", Toast.LENGTH_SHORT).show();
                hideCameraPreview();
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
            frontCameraHelper.release();
            frontCameraHelper = null;
        }

        // 隐藏预览和心率显示
        hideCameraPreview();
        hideHeartRateLayout();

        frontCameraActive = false;
        frontCameraRecording = false;
    }

    private void initCoordinator() {
        // 统一时钟会在 startAllRecording 时启动
        // 创建共享的IMU记录器（用于RecordingCoordinator和DeviceAdapter）
        sharedImuRecorder = new IMURecorder(this);

        // 注意：摄像头录制由 ListActivity 直接管理（frontCameraHelper/backCameraHelper）
        // RecordingCoordinator 不再管理摄像头，传入 null 避免占用 SurfaceView
        recordingCoordinator = new RecordingCoordinator(
                this,
                null,  // 摄像头由 ListActivity 直接控制
                sharedImuRecorder,
                new MultiMicAudioRecorderHelper(this),
                OximeterManager.getInstance(this)  // 使用单例
        );
        recordingCoordinator.setStatusCallback(msg ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());

        // 设置剩余时间回调（更新UI显示）
        recordingCoordinator.setRemainingTimeCallback(remaining -> {
            runOnUiThread(() -> {
                if (statusText != null) {
                    statusText.setText(formatRemainingTime(remaining));
                }
            });
        });

        // 设置自动停止回调（录制时长到达时通知UI）
        recordingCoordinator.setAutoStopCallback(() -> {
            runOnUiThread(() -> {
                try {
                    // 先获取视频路径和目录信息（在停止相机之前）
                    String videoPath = null;
                    String frontDir = null;
                    String spo2Dir = null;

                    // 获取视频路径（支持AI推理模式和非AI模式）
                    if (cameraPureFaceProcessor != null) {
                        videoPath = cameraPureFaceProcessor.getCurrentVideoPath();
                        frontDir = cameraPureFaceProcessor.getFrontDir();
                    } else if (cameraFaceProcessor != null) {
                        videoPath = cameraFaceProcessor.getCurrentVideoPath();
                        frontDir = cameraFaceProcessor.getFrontDir();
                    } else if (frontCameraHelper != null) {
                        // 非AI推理模式：从CameraHelper获取
                        videoPath = frontCameraHelper.getCurrentFrontVideoPath();
                        frontDir = frontCameraHelper.getFrontDir();
                    }

                    java.io.File sessionDir = SessionManager.getInstance().getSessionDir();
                    if (sessionDir != null) {
                        java.io.File spo2DirFile = new java.io.File(sessionDir, "spo2");
                        if (spo2DirFile.exists()) {
                            spo2Dir = spo2DirFile.getAbsolutePath();
                        }
                    }

                    final String finalVideoPath = videoPath;
                    final String finalFrontDir = frontDir;
                    final String finalSpo2Dir = spo2Dir;

                    // 判断是否需要后处理
                    boolean needPostProcess = (currentCameraMode == 0 || currentCameraMode == 2)
                                              && finalVideoPath != null;

                    // 停止摄像头录制
                    stopFrontCameraRecording();
                    stopBackCameraRecording();

                    // 更新UI状态
                    if (btnStartAll != null) {
                        btnStartAll.setText("开始录制");
                    }
                    if (statusText != null) {
                        statusText.setText("录制完成");
                    }
                    setCameraPlaceholderText("点击开始录制显示预览");
                    Toast.makeText(this, "录制时长到达，自动停止", Toast.LENGTH_SHORT).show();

                    // 如果需要后处理，启动后处理流程
                    if (needPostProcess) {
                        startVideoPostProcessing(finalVideoPath, finalFrontDir, finalSpo2Dir);
                    } else {
                        navigateToPatientInfo();
                    }
                } catch (Exception e) {
                    Log.e("ListActivity", "Error in autoStopCallback UI update", e);
                }
            });
        });
    }

    /**
     * 切换录制状态（开始/停止）
     */
    private void toggleRecording() {
        if (recordingCoordinator != null && recordingCoordinator.isRecording()) {
            stopAllRecording();
        } else {
            startAllRecording();
        }
    }

    /**
     * 格式化剩余时间显示
     */
    private String formatRemainingTime(int seconds) {
        if (seconds <= 0) return "录制完成";
        if (seconds < 60) {
            return String.format("剩余 %d 秒", seconds);
        } else if (seconds < 3600) {
            int min = seconds / 60;
            int sec = seconds % 60;
            return String.format("剩余 %d:%02d", min, sec);
        } else {
            int hour = seconds / 3600;
            int min = (seconds % 3600) / 60;
            int sec = seconds % 60;
            return String.format("剩余 %d:%02d:%02d", hour, min, sec);
        }
    }

    private void startAllRecording() {
        Log.d("ListActivity", "startAllRecording called, currentCameraMode=" + currentCameraMode);

        // 检查是否正在进行后处理
        if (isPostProcessing) {
            Toast.makeText(this, "视频分析中，请等待完成后再开始新录制", Toast.LENGTH_SHORT).show();
            return;
        }

        SharedPreferences prefs = getSharedPreferences("AppSettings", MODE_PRIVATE);
        String experimentId = prefs.getString("experiment_id", "default");
        boolean enableInference = prefs.getBoolean("enable_inference", false);

        // 只有开启AI推理时才检查模型是否已加载完成
        if (enableInference && (!isModelLoaded || preloadedEstimator == null)) {
            Toast.makeText(this, "AI模型尚未准备完毕，请稍等几秒再试", Toast.LENGTH_SHORT).show();
            return;
        }
        Log.d("ListActivity", "experimentId=" + experimentId + ", enableInference=" + enableInference);

        SessionManager.getInstance().startSession(this, experimentId);
        TimeSync.startSessionClock();

        // 记录录制开始时间
        recordingStartTime = System.currentTimeMillis();

        // 启用全部模块
        recordingCoordinator.setModules(true, true, true, true, true, true);
        recordingCoordinator.start(experimentId);

        // 更新UI状态
        if (btnStartAll != null) {
            btnStartAll.setText("停止录制");
        }
        int duration = recordingCoordinator.getMaxRecordingDuration();
        if (statusText != null) {
            statusText.setText(formatRemainingTime(duration));
        }
        setCameraPlaceholderText("摄像头启动中...");

        // 根据当前选择的摄像头模式启动
        Log.d("ListActivity", "Starting camera based on mode: " + currentCameraMode);
        switch (currentCameraMode) {
            case 0:  // 前置录制
                Log.d("ListActivity", "Mode 0: Starting FRONT camera only");
                startFrontCameraRecording();
                break;
            case 1:  // 后置录制
                Log.d("ListActivity", "Mode 1: Starting BACK camera only");
                startBackCameraRecording();
                break;
            case 2:  // 前后同开
                Log.d("ListActivity", "Mode 2: Starting BOTH cameras");
                startFrontCameraRecording();
                startBackCameraRecording();
                break;
        }

        Toast.makeText(this, "一键录制已启动 (时长: " + duration + "秒)", Toast.LENGTH_SHORT).show();
    }

    private void stopAllRecording() {
        // 先获取视频路径和目录信息（在停止相机之前）
        String videoPath = null;
        String frontDir = null;
        String spo2Dir = null;

        // 获取视频路径（支持AI推理模式和非AI模式）
        if (cameraPureFaceProcessor != null) {
            videoPath = cameraPureFaceProcessor.getCurrentVideoPath();
            frontDir = cameraPureFaceProcessor.getFrontDir();
        } else if (cameraFaceProcessor != null) {
            videoPath = cameraFaceProcessor.getCurrentVideoPath();
            frontDir = cameraFaceProcessor.getFrontDir();
        } else if (frontCameraHelper != null) {
            // 非AI推理模式：从CameraHelper获取
            videoPath = frontCameraHelper.getCurrentFrontVideoPath();
            frontDir = frontCameraHelper.getFrontDir();
        }

        // 获取spo2目录
        java.io.File sessionDir = SessionManager.getInstance().getSessionDir();
        if (sessionDir != null) {
            java.io.File spo2DirFile = new java.io.File(sessionDir, "spo2");
            if (spo2DirFile.exists()) {
                spo2Dir = spo2DirFile.getAbsolutePath();
            }
        }

        final String finalVideoPath = videoPath;
        final String finalFrontDir = frontDir;
        final String finalSpo2Dir = spo2Dir;

        // 判断是否需要后处理（前置摄像头模式或双摄模式）
        boolean needPostProcess = (currentCameraMode == 0 || currentCameraMode == 2)
                                  && finalVideoPath != null;

        Log.d("ListActivity", "stopAllRecording: videoPath=" + finalVideoPath
              + ", needPostProcess=" + needPostProcess);

        stopFrontCameraRecording();
        stopBackCameraRecording();
        if (recordingCoordinator != null) {
            recordingCoordinator.stop();
        }

        // 更新UI状态
        if (btnStartAll != null) {
            btnStartAll.setText("开始录制");
        }
        if (statusText != null) {
            statusText.setText("准备就绪");
        }
        setCameraPlaceholderText("点击开始录制显示预览");

        Toast.makeText(this, "一键录制已停止", Toast.LENGTH_SHORT).show();

        // 如果需要后处理，启动视频后处理流程
        if (needPostProcess) {
            startVideoPostProcessing(finalVideoPath, finalFrontDir, finalSpo2Dir);
        } else {
            // 不需要后处理，直接跳转到患者信息页面
            navigateToPatientInfo();
        }
    }

    /**
     * 启动视频后处理
     */
    private void startVideoPostProcessing(String videoPath, String frontDir, String spo2Dir) {
        if (isPostProcessing) {
            Toast.makeText(this, "正在处理中，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }

        isPostProcessing = true;

        // 禁用开始录制按钮
        if (btnStartAll != null) {
            btnStartAll.setEnabled(false);
        }

        // 显示进度对话框
        showPostProcessDialog();

        // 创建后处理器
        videoPostProcessor = new VideoPostProcessor(this);

        try {
            // 加载模型资源
            java.io.InputStream modelStream = getAssets().open("model.onnx");
            java.io.InputStream stateJsonStream = getAssets().open("state.json");
            java.io.InputStream welchModelStream = getAssets().open("welch_psd.onnx");
            java.io.InputStream hrModelStream = getAssets().open("get_hr.onnx");

            // 启动后处理
            videoPostProcessor.processVideo(videoPath, frontDir, spo2Dir,
                    modelStream, stateJsonStream, welchModelStream, hrModelStream,
                    new VideoPostProcessor.OnProgressListener() {
                        @Override
                        public void onProgress(int current, int total, String message) {
                            updatePostProcessProgress(current, total, message);
                        }

                        @Override
                        public void onComplete(VideoPostProcessor.PostProcessResult result) {
                            dismissPostProcessDialog();
                            isPostProcessing = false;

                            // 恢复开始录制按钮
                            if (btnStartAll != null) {
                                btnStartAll.setEnabled(true);
                            }

                            // 显示简化版结果弹窗
                            showSimplifiedResultDialog(result);
                        }

                        @Override
                        public void onError(String error) {
                            dismissPostProcessDialog();
                            isPostProcessing = false;

                            // 恢复开始录制按钮
                            if (btnStartAll != null) {
                                btnStartAll.setEnabled(true);
                            }

                            Toast.makeText(ListActivity.this,
                                    "视频分析失败: " + error, Toast.LENGTH_LONG).show();

                            // 出错时也跳转到患者信息页面
                            navigateToPatientInfo();
                        }
                    });

        } catch (java.io.IOException e) {
            Log.e("ListActivity", "加载模型资源失败", e);
            dismissPostProcessDialog();
            isPostProcessing = false;

            if (btnStartAll != null) {
                btnStartAll.setEnabled(true);
            }

            Toast.makeText(this, "模型加载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            navigateToPatientInfo();
        }
    }

    /**
     * 显示后处理进度对话框
     */
    private void showPostProcessDialog() {
        if (postProcessDialog != null && postProcessDialog.isShowing()) {
            return;
        }

        postProcessDialog = new android.app.ProgressDialog(this);
        postProcessDialog.setTitle("视频分析中");
        postProcessDialog.setMessage("正在分析视频，请勿关闭应用...");
        postProcessDialog.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        postProcessDialog.setMax(100);
        postProcessDialog.setProgress(0);
        postProcessDialog.setCancelable(false);
        postProcessDialog.setCanceledOnTouchOutside(false);
        postProcessDialog.show();
    }

    /**
     * 更新后处理进度
     */
    private void updatePostProcessProgress(int current, int total, String message) {
        if (postProcessDialog != null && postProcessDialog.isShowing()) {
            postProcessDialog.setProgress(current);
            postProcessDialog.setMessage(message);
        }
    }

    /**
     * 关闭后处理进度对话框
     */
    private void dismissPostProcessDialog() {
        if (postProcessDialog != null && postProcessDialog.isShowing()) {
            try {
                postProcessDialog.dismiss();
            } catch (Exception e) {
                Log.w("ListActivity", "关闭进度对话框失败", e);
            }
        }
        postProcessDialog = null;
    }

    /**
     * 显示简化版结果弹窗（评分+结论）
     */
    private void showSimplifiedResultDialog(VideoPostProcessor.PostProcessResult result) {
        if (result == null) {
            navigateToPatientInfo();
            return;
        }

        // 根据评分设置颜色
        int colorRes;
        if (result.overallScore >= 85) {
            colorRes = android.R.color.holo_green_dark;
        } else if (result.overallScore >= 70) {
            colorRes = android.R.color.holo_blue_dark;
        } else if (result.overallScore >= 50) {
            colorRes = android.R.color.holo_orange_dark;
        } else {
            colorRes = android.R.color.holo_red_dark;
        }

        String message = String.format(java.util.Locale.US,
                "综合评分: %d 分\n\n%s\n\n" +
                "心率分析:\n• AI推理心率: %.1f bpm\n• 血氧仪心率: %.1f bpm\n• 差值: %.1f bpm",
                result.overallScore,
                result.conclusion,
                result.aiHeartRate,
                result.oximeterHeartRate,
                result.heartRateDiff);

        new android.app.AlertDialog.Builder(this)
            .setTitle("视频分析结果")
            .setMessage(message)
            .setPositiveButton("确定", (dialog, which) -> navigateToPatientInfo())
            .setOnCancelListener(dialog -> navigateToPatientInfo())
            .show();
    }

    /**
     * 跳转到患者信息页面
     */
    private void navigateToPatientInfo() {
        try {
            // 获取会话目录
            java.io.File sessionDir = SessionManager.getInstance().getSessionDir();
            if (sessionDir == null) {
                Log.w("ListActivity", "会话目录为空，无法跳转到患者信息页");
                return;
            }

            // 获取会话ID（目录名）
            String sessionId = sessionDir.getName();

            // 计算录制时长
            long recordingDuration = System.currentTimeMillis() - recordingStartTime;
            String recordingTime = formatRecordingDuration(recordingDuration);

            // 跳转到患者信息页面
            PatientInfoActivity.start(this, sessionDir.getAbsolutePath(), sessionId, recordingTime);
            Log.i("ListActivity", "跳转到患者信息页: sessionId=" + sessionId + ", duration=" + recordingTime);

        } catch (Exception e) {
            Log.e("ListActivity", "跳转患者信息页失败", e);
        }
    }

    /**
     * 格式化录制时长为可读字符串
     */
    private String formatRecordingDuration(long durationMs) {
        long seconds = durationMs / 1000;
        if (seconds < 60) {
            return seconds + " 秒";
        } else if (seconds < 3600) {
            long min = seconds / 60;
            long sec = seconds % 60;
            return String.format(java.util.Locale.US, "%d 分 %d 秒", min, sec);
        } else {
            long hour = seconds / 3600;
            long min = (seconds % 3600) / 60;
            long sec = seconds % 60;
            return String.format(java.util.Locale.US, "%d 时 %d 分 %d 秒", hour, min, sec);
        }
    }

    // 后置摄像头控制方法
    public void startBackCameraRecording() {
        if (backCameraRecording) return;

        // 判断是单后置还是双摄模式
        boolean isDualMode = (currentCameraMode == 2);

        SurfaceView targetSurface;
        boolean needDelay = false;  // 是否需要延迟等待前置相机释放
        if (isDualMode) {
            // 双摄模式：使用画中画SurfaceView
            targetSurface = pipCameraSurfaceView;
            showPipPreview();  // 显示画中画容器
        } else {
            // 单后置模式：使用主SurfaceView
            // 重要：先释放可能占用mainCameraSurfaceView的前置摄像头资源
            if (frontCameraHelper != null) {
                Log.d("Camera", "Releasing frontCameraHelper before single back camera mode");
                frontCameraHelper.release();
                frontCameraHelper = null;
                needDelay = true;  // 需要等待释放完成
            }
            // 同样释放AI模式下可能占用的处理器
            if (cameraFaceProcessor != null) {
                cameraFaceProcessor.stopCamera();
                cameraFaceProcessor = null;
                needDelay = true;
            }
            if (cameraPureFaceProcessor != null) {
                cameraPureFaceProcessor.stopCamera();
                cameraPureFaceProcessor = null;
                needDelay = true;
            }
            targetSurface = mainCameraSurfaceView;
            showCameraPreview();  // 隐藏占位符
        }

        // 封装实际启动后置相机的逻辑
        Runnable startBackCamera = () -> {
            try {
                // 每次都重新创建CameraHelper以使用正确的SurfaceView
                if (backCameraHelper != null) {
                    backCameraHelper.release();
                    backCameraHelper = null;
                }
                backCameraHelper = new CameraHelper(this, null, targetSurface);

                // 设置双摄模式标志（只在双摄模式下开启闪光灯）
                backCameraHelper.setDualCameraMode(isDualMode);

                // 轮询等待摄像头就绪，最多等待5秒
                final boolean finalIsDualMode = isDualMode;
                waitForCameraReady(backCameraHelper, false, 50, () -> {
                    try {
                        backCameraHelper.startBackRecording();
                        backCameraActive = true;
                        backCameraRecording = true;
                        Log.d("Camera", "Back camera recording started successfully (dual=" + finalIsDualMode + ", flashlight=" + finalIsDualMode + ")");
                    } catch (Exception e) {
                        Log.e("Camera", "Failed to start back recording: " + e.getMessage(), e);
                        Toast.makeText(this, "后置摄像头录制启动失败", Toast.LENGTH_SHORT).show();
                        if (finalIsDualMode) {
                            hidePipPreview();
                        } else {
                            hideCameraPreview();
                        }
                    }
                });

            } catch (Exception e) {
                Log.e("Camera", "Failed to initialize back camera: " + e.getMessage(), e);
                Toast.makeText(this, "后置摄像头初始化失败", Toast.LENGTH_SHORT).show();
                if (isDualMode) {
                    hidePipPreview();
                } else {
                    hideCameraPreview();
                }
            }
        };

        // 如果需要等待前置相机释放，延迟300ms后再启动后置相机
        if (needDelay) {
            Log.d("Camera", "Delaying back camera start to wait for front camera release");
            new Handler(Looper.getMainLooper()).postDelayed(startBackCamera, 300);
        } else {
            startBackCamera.run();
        }
    }

    public void stopBackCameraRecording() {
        if (!backCameraRecording) return;

        if (backCameraHelper != null) {
            backCameraHelper.stopBackRecording();
            backCameraHelper.release();
            backCameraHelper = null;
        }

        // 根据摄像头模式隐藏对应的预览
        if (currentCameraMode == 2) {
            // 双摄模式：隐藏画中画
            hidePipPreview();
        } else {
            // 单后置模式：隐藏主预览
            hideCameraPreview();
        }

        backCameraActive = false;
        backCameraRecording = false;
    }

    /**
     * 轮询等待摄像头就绪
     * @param helper CameraHelper实例
     * @param isFront true=前置摄像头, false=后置摄像头
     * @param maxAttempts 最大尝试次数（每次间隔100ms）
     * @param onReady 摄像头就绪后的回调
     */
    private void waitForCameraReady(CameraHelper helper, boolean isFront, int maxAttempts, Runnable onReady) {
        Handler handler = new Handler(Looper.getMainLooper());
        final int[] attempts = {0};

        Runnable checkReady = new Runnable() {
            @Override
            public void run() {
                attempts[0]++;
                boolean ready = isFront ? helper.isFrontCameraReady() : helper.isBackCameraReady();

                if (ready) {
                    Log.d("Camera", (isFront ? "Front" : "Back") + " camera ready after " + attempts[0] + " attempts");
                    onReady.run();
                } else if (attempts[0] < maxAttempts) {
                    handler.postDelayed(this, 100);  // 每100ms检查一次
                } else {
                    Log.e("Camera", (isFront ? "Front" : "Back") + " camera not ready after " + maxAttempts + " attempts");
                    Toast.makeText(ListActivity.this,
                            (isFront ? "前置" : "后置") + "摄像头初始化超时", Toast.LENGTH_SHORT).show();
                    hideCameraPreview();
                }
            }
        };

        handler.postDelayed(checkReady, 100);
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
                Log.d("USB", "已有权限，尝试连接");
                // 已有权限，直接尝试连接
                OximeterManager manager = OximeterManager.getInstance();
                if (manager != null && !manager.isConnected()) {
                    manager.connectAndStart();
                }
            }
        }
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d("USB", "usbReceiver收到广播: " + action);
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

            if (ACTION_USB_PERMISSION.equals(action)) {
                // 由于FLAG_IMMUTABLE，不依赖intent.getBooleanExtra(EXTRA_PERMISSION_GRANTED)
                // 而是直接检查实际权限状态
                synchronized (this) {
                    // 如果intent中的device为null（FLAG_IMMUTABLE可能导致），遍历所有设备
                    boolean permissionGranted = false;
                    if (device != null) {
                        permissionGranted = usbManager.hasPermission(device);
                        Log.d("USB", "检查设备权限: " + device.getProductName() + " = " + permissionGranted);
                    } else {
                        // device为null时，检查所有连接的USB设备
                        Log.d("USB", "Intent中device为null，遍历所有USB设备");
                        for (UsbDevice d : usbManager.getDeviceList().values()) {
                            if (usbManager.hasPermission(d)) {
                                permissionGranted = true;
                                Log.d("USB", "发现有权限的设备: " + d.getProductName());
                                break;
                            }
                        }
                    }

                    if (permissionGranted) {
                        Log.d("USB", "权限已授予，尝试连接血氧仪");
                        OximeterManager manager = OximeterManager.getInstance();
                        if (manager != null && !manager.isConnected()) {
                            manager.connectAndStart();
                            if (adapter != null) {
                                adapter.notifyOximeterConnected();
                            }
                        }
                    } else {
                        Log.d("USB", "权限被拒绝");
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                // USB设备连接
                Log.d("USB", "USB设备已连接: " + (device != null ? device.getProductName() : "unknown"));
                // 延迟一点再检查权限和连接，给系统时间完成设备初始化
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    checkOximeterUsbPermission();
                }, 500);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                // USB设备断开
                Log.d("USB", "USB设备已断开");
                OximeterManager manager = OximeterManager.getInstance();
                if (manager != null) {
                    manager.disconnect();
                }
                // 通知UI更新状态
                if (adapter != null) {
                    adapter.notifyOximeterDisconnected();
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

    /**
     * 预加载HeartRateEstimator模型（应用启动时调用）
     */
    private void preloadHeartRateModel() {
        SharedPreferences prefs = getSharedPreferences("AppSettings", MODE_PRIVATE);
        boolean enableInference = prefs.getBoolean("enable_inference", false);
        if (!enableInference) return;  // 未开启推理则不预加载

        modelLoadExecutor.execute(() -> {
            try {
                Log.d("ListActivity", "开始预加载ONNX模型...");
                long startTime = System.currentTimeMillis();

                String experimentId = prefs.getString("experiment_id", "default");
                String baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                        + "/Sample/" + experimentId + "/";

                java.io.InputStream modelStream = getAssets().open("model.onnx");
                java.io.InputStream stateJsonStream = getAssets().open("state.json");
                java.io.InputStream welchModelStream = getAssets().open("welch_psd.onnx");
                java.io.InputStream hrModelStream = getAssets().open("get_hr.onnx");

                // 预加载时使用空的PlotView，后续替换
                preloadedEstimator = new HeartRateEstimator(
                        modelStream, stateJsonStream, welchModelStream, hrModelStream,
                        null, baseDir
                );
                isModelLoaded = true;

                long loadTime = System.currentTimeMillis() - startTime;
                Log.d("ListActivity", "ONNX模型预加载完成，耗时: " + loadTime + "ms");

            } catch (Exception e) {
                Log.e("ListActivity", "模型预加载失败", e);
            }
        });
    }

    /**
     * 获取预加载的HeartRateEstimator
     */
    public HeartRateEstimator getPreloadedEstimator() {
        return preloadedEstimator;
    }

    public boolean isModelPreloaded() {
        return isModelLoaded;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        // 处理USB设备连接事件（从singleTask模式触发）
        String action = intent.getAction();
        Log.d("USB", "onNewIntent收到: " + action);

        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            Log.d("USB", "onNewIntent: USB设备已连接");
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null) {
                Log.d("USB", "设备: VID=" + device.getVendorId() + " PID=" + device.getProductId());
            }
            // 延迟检查权限和连接
            new Handler(Looper.getMainLooper()).postDelayed(this::checkOximeterUsbPermission, 500);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // 检查是否需要加载AI模型（用户可能在设置中开启了推理）
        SharedPreferences prefs = getSharedPreferences("AppSettings", MODE_PRIVATE);
        boolean enableInference = prefs.getBoolean("enable_inference", false);
        if (enableInference && !isModelLoaded) {
            Log.d("ListActivity", "onResume: 检测到推理已开启但模型未加载，开始加载模型");
            preloadHeartRateModel();
        }

        // 检查USB血氧仪状态（处理广播可能漏收的情况）
        OximeterManager oxManager = OximeterManager.getInstance();
        if (oxManager != null && !oxManager.isConnected() && usbManager != null) {
            // 检查是否有USB设备连接但未建立连接
            HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
            if (!deviceList.isEmpty()) {
                Log.d("USB", "onResume: 发现USB设备，检查权限");
                checkOximeterUsbPermission();
            }
        }
    }

    @Override
    protected void onDestroy() {
        // 停止所有摄像头
        stopFrontCameraRecording();
        stopBackCameraRecording();

        // 关闭模型加载线程池
        if (modelLoadExecutor != null && !modelLoadExecutor.isShutdown()) {
            modelLoadExecutor.shutdownNow();
        }

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

        // 释放视频后处理器资源
        if (videoPostProcessor != null) {
            videoPostProcessor.release();
            videoPostProcessor = null;
        }
        dismissPostProcessDialog();

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
        if (ringViewHolder != null) {
            ringViewHolder.recordLog(msg);
        }
    }

    @Override
    public void lmBleConnectionSucceeded(int i) {
        String msg = "蓝牙连接成功，状态码：" + i;
        if (ringViewHolder != null) {
            ringViewHolder.recordLog(msg);
        }
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
        if (ringViewHolder != null) {
            ringViewHolder.recordLog(msg);
        }
    }

    @Override
    public void VERSION(byte b, String s) {
        if (ringViewHolder != null) {
            ringViewHolder.recordLog(s);
        }
    }

    @Override
    public void syncTime(byte b, byte[] bytes) {
        if (ringViewHolder != null) {
            ringViewHolder.recordLog("时间同步完成");
        }
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
            if (ringViewHolder != null) {
                ringViewHolder.recordLog("电池电量为" + b1);
            }
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
        if (ringViewHolder != null) {
            ringViewHolder.recordLog(msg);
        }
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
