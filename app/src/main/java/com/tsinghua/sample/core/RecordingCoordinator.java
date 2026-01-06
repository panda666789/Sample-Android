package com.tsinghua.sample.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.tsinghua.sample.media.IMURecorder;
import com.tsinghua.sample.media.MultiMicAudioRecorderHelper;
import com.tsinghua.sample.media.RecorderHelper;
import com.tsinghua.sample.device.OximeterManager;
import com.tsinghua.sample.utils.NotificationHandler;
import com.tsinghua.sample.ecg.ECGMeasurementController;

import java.io.File;
import java.util.function.Consumer;

/**
 * 录制协调器（与iOS DataCollectionCoordinator对齐）
 *
 * 负责统一管理所有数据采集模块的启动/停止：
 * - Camera（前置/后置摄像头）
 * - IMU（加速度计、陀螺仪）
 * - Audio（双麦克风音频）
 * - Ring（智能戒指）
 * - ECG（心电）
 * - SpO2（血氧仪）
 *
 * 确保所有模块使用统一的时间基准（TimeSync）
 */
public class RecordingCoordinator {
    private static final String TAG = "RecordingCoordinator";

    private final Context context;
    private final RecorderHelper recorderHelper;
    private final IMURecorder imuRecorder;
    private final MultiMicAudioRecorderHelper audioRecorder;
    private final OximeterManager oximeterManager;

    private boolean enableCamera;
    private boolean enableIMU;
    private boolean enableAudio;
    private boolean enableRing;
    private boolean enableECG;
    private boolean enableSpO2;

    private boolean isRecording = false;
    private Consumer<String> statusCallback;

    // 录制时长控制（对齐iOS）
    private int maxRecordingDuration = 2400; // 默认40分钟
    private long recordingStartTime = 0;
    private Handler durationHandler;
    private Runnable durationRunnable;
    private Consumer<Integer> remainingTimeCallback; // 剩余时间回调
    private Runnable autoStopCallback; // 自动停止回调

    public RecordingCoordinator(Context context,
                                RecorderHelper recorderHelper,
                                IMURecorder imuRecorder,
                                MultiMicAudioRecorderHelper audioRecorder,
                                OximeterManager oximeterManager) {
        this.context = context;
        this.recorderHelper = recorderHelper;
        this.imuRecorder = imuRecorder;
        this.audioRecorder = audioRecorder;
        this.oximeterManager = oximeterManager;
        this.durationHandler = new Handler(Looper.getMainLooper());
    }

    public void setModules(boolean camera, boolean imu, boolean audio, boolean ring, boolean ecg, boolean spo2) {
        this.enableCamera = camera;
        this.enableIMU = imu;
        this.enableAudio = audio;
        this.enableRing = ring;
        this.enableECG = ecg;
        this.enableSpO2 = spo2;
    }

    public void setStatusCallback(Consumer<String> cb) {
        this.statusCallback = cb;
    }

    /**
     * 设置剩余时间回调（用于UI显示）
     */
    public void setRemainingTimeCallback(Consumer<Integer> cb) {
        this.remainingTimeCallback = cb;
    }

    /**
     * 设置自动停止回调（达到时长时自动调用stop后通知UI）
     */
    public void setAutoStopCallback(Runnable cb) {
        this.autoStopCallback = cb;
    }

    /**
     * 从SharedPreferences加载录制时长配置
     */
    public void loadRecordingDuration() {
        SharedPreferences prefs = context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE);
        maxRecordingDuration = prefs.getInt("recording_duration", 2400);
        Log.d(TAG, "Loaded recording duration: " + maxRecordingDuration + " seconds");
    }

    /**
     * 设置录制时长（秒）
     */
    public void setMaxRecordingDuration(int seconds) {
        this.maxRecordingDuration = Math.max(1, Math.min(7200, seconds));
    }

    /**
     * 获取当前配置的录制时长
     */
    public int getMaxRecordingDuration() {
        return maxRecordingDuration;
    }

    /**
     * 获取已录制时间（秒）
     */
    public int getElapsedSeconds() {
        if (!isRecording || recordingStartTime == 0) return 0;
        return (int) ((System.currentTimeMillis() - recordingStartTime) / 1000);
    }

    /**
     * 获取剩余时间（秒）
     */
    public int getRemainingSeconds() {
        return Math.max(0, maxRecordingDuration - getElapsedSeconds());
    }

    /**
     * 启动录制会话
     * 确保所有模块使用同一个会话目录和统一时间基准
     */
    public void start(String experimentId) {
        if (isRecording) {
            Log.w(TAG, "Recording already in progress");
            return;
        }

        // 加载录制时长配置
        loadRecordingDuration();

        // 1. 创建会话目录（如果还没有的话）
        SessionManager.getInstance().ensureSession(context, experimentId);

        // 2. 启动统一时间基准（关键：确保所有模块时间戳一致）
        TimeSync.startSessionClock();
        TimeSync.logTimestampStatus("RecordingCoordinator");
        Log.i(TAG, "Session started with unified time base: " + TimeSync.getSessionStartWallMillis());

        notifyStatus("会话启动");
        isRecording = true;
        recordingStartTime = System.currentTimeMillis();

        // 3. 按顺序启动各模块
        if (enableIMU && imuRecorder != null) {
            imuRecorder.startRecording();
            Log.d(TAG, "IMU recording started");
        }

        if (enableAudio && audioRecorder != null) {
            audioRecorder.startRecording();
            Log.d(TAG, "Audio recording started");
        }

        if (enableSpO2 && oximeterManager != null) {
            if (oximeterManager.isConnected()) {
                oximeterManager.startRecording("unused");
                Log.d(TAG, "SpO2 recording started");
            } else {
                Log.w(TAG, "SpO2 device not connected, skipping");
                notifyStatus("血氧仪未连接");
            }
        }

        // Ring同步测量 - 纳入一键录制（对齐iOS beginSynchronizedMeasurement）
        if (enableRing) {
            startRingSynchronizedMeasurement();
        }

        // ECG同步测量 - 纳入一键录制
        if (enableECG) {
            startEcgSynchronizedMeasurement();
        }

        // 4. 启动录制时长定时器
        startDurationTimer();
    }

    /**
     * 启动指环同步测量（对齐iOS ringController.beginSynchronizedMeasurement）
     */
    private void startRingSynchronizedMeasurement() {
        // 开始新一轮录制前先清空波形图，避免与上一轮波形混叠
        NotificationHandler.clearRingPlots();

        // 设置测量时长
        NotificationHandler.setMeasurementTime(maxRecordingDuration);

        // 获取当前会话目录用于数据落盘
        File sessionDir = SessionManager.getInstance().getSessionDir();
        if (sessionDir != null) {
            try {
                // 创建DataLogger用于指环数据记录
                File ringFile = new File(SessionManager.getInstance().subDir("ring"), "ring_data.csv");
                String header = "wall_ms,frame_ts,green,red,ir,accX,accY,accZ,gyroX,gyroY,gyroZ,temp0,temp1,temp2";
                DataLogger ringLogger = new DataLogger(ringFile, header);
                NotificationHandler.setDataLogger(ringLogger);
                Log.d(TAG, "Ring DataLogger set to: " + ringFile.getAbsolutePath());
            } catch (Exception e) {
                Log.e(TAG, "Failed to create ring DataLogger", e);
            }
        }

        // 启动主动测量（内部会检查连接状态）
        boolean started = NotificationHandler.startActiveMeasurement();
        if (started) {
            Log.d(TAG, "Ring synchronized measurement started, duration: " + maxRecordingDuration + "s");
            notifyStatus("指环测量启动");
        } else {
            Log.w(TAG, "Failed to start ring measurement (not connected or already measuring)");
            notifyStatus("指环测量启动失败");
        }
    }

    /**
     * 启动ECG同步测量（对齐iOS ecgController.beginSynchronizedMeasurement）
     */
    private void startEcgSynchronizedMeasurement() {
        ECGMeasurementController ecgController = ECGMeasurementController.getInstance();
        ecgController.init(context);

        if (ecgController.isConnected()) {
            File sessionDir = SessionManager.getInstance().getSessionDir();
            ecgController.beginSynchronizedMeasurement(maxRecordingDuration, sessionDir);
            Log.d(TAG, "ECG synchronized measurement started");
            notifyStatus("ECG测量启动");
        } else {
            Log.w(TAG, "ECG device not connected, skipping");
            notifyStatus("ECG未连接");
        }
    }

    /**
     * 启动录制时长定时器（对齐iOS startDurationTimer）
     * 每100ms检查一次，达到配置时长自动停止
     */
    private void startDurationTimer() {
        stopDurationTimer(); // 先清理之前的定时器

        durationRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRecording) return;

                try {
                    int elapsed = getElapsedSeconds();
                    int remaining = getRemainingSeconds();

                    // 通知剩余时间
                    if (remainingTimeCallback != null) {
                        remainingTimeCallback.accept(remaining);
                    }

                    // 检查是否达到配置时长
                    if (elapsed >= maxRecordingDuration) {
                        Log.i(TAG, "Recording duration reached, auto-stopping...");
                        notifyStatus("录制时长到达，自动停止");
                        stop();

                        // 通知UI自动停止（在新的 try-catch 中，防止 UI 回调出错影响其他逻辑）
                        if (autoStopCallback != null) {
                            try {
                                autoStopCallback.run();
                            } catch (Exception e) {
                                Log.e(TAG, "Error in autoStopCallback", e);
                            }
                        }
                        return;
                    }

                    // 继续定时
                    durationHandler.postDelayed(this, 100);
                } catch (Exception e) {
                    Log.e(TAG, "Error in duration timer", e);
                    // 出错时仍然继续定时，避免定时器停止
                    durationHandler.postDelayed(this, 100);
                }
            }
        };

        durationHandler.post(durationRunnable);
        Log.d(TAG, "Duration timer started, max duration: " + maxRecordingDuration + "s");
    }

    /**
     * 停止录制时长定时器
     */
    private void stopDurationTimer() {
        if (durationRunnable != null) {
            durationHandler.removeCallbacks(durationRunnable);
            durationRunnable = null;
        }
    }

    /**
     * 停止录制会话
     */
    public void stop() {
        if (!isRecording) {
            Log.w(TAG, "No recording in progress");
            return;
        }

        Log.i(TAG, "Stopping recording session");

        // 停止定时器
        stopDurationTimer();

        // 使用 try-catch 包裹每个停止操作，防止一个模块出错导致其他模块无法停止
        if (enableCamera && recorderHelper != null) {
            try {
                recorderHelper.stopFrontRecording();
                recorderHelper.stopBackRecording();
                Log.d(TAG, "Camera recording stopped");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping camera", e);
            }
        }

        if (enableIMU && imuRecorder != null) {
            try {
                imuRecorder.stopRecording();
                Log.d(TAG, "IMU recording stopped");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping IMU", e);
            }
        }

        if (enableAudio && audioRecorder != null) {
            try {
                audioRecorder.stopRecording();
                Log.d(TAG, "Audio recording stopped");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping audio", e);
            }
        }

        if (enableSpO2 && oximeterManager != null) {
            try {
                oximeterManager.stopRecording();
                Log.d(TAG, "SpO2 recording stopped");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping SpO2", e);
            }
        }

        // 停止指环测量
        if (enableRing) {
            try {
                NotificationHandler.stopMeasurement();
                NotificationHandler.setDataLogger(null);
                Log.d(TAG, "Ring measurement stopped");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping ring", e);
            }
        }

        // 停止ECG测量
        if (enableECG) {
            try {
                ECGMeasurementController ecgController = ECGMeasurementController.getInstance();
                if (ecgController.isConnected()) {
                    ecgController.stopSynchronizedMeasurement();
                    Log.d(TAG, "ECG sampling stopped");
                } else {
                    Log.d(TAG, "ECG device not connected, skip stop");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error stopping ECG", e);
            }
        }

        isRecording = false;
        recordingStartTime = 0;
        notifyStatus("会话停止");
    }

    public boolean isRecording() {
        return isRecording;
    }

    private void notifyStatus(String msg) {
        Log.i(TAG, msg);
        if (statusCallback != null) statusCallback.accept(msg);
    }
}
