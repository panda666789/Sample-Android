package com.tsinghua.sample;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import com.tsinghua.sample.device.OximeterManager;
import com.tsinghua.sample.device.model.OximeterData;
import com.tsinghua.sample.core.Constants;
import com.tsinghua.sample.core.SessionManager;
import com.tsinghua.sample.core.TimeSync;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * 血氧仪设备ViewHolder
 * 负责显示和记录SpO2、HR、BVP数据
 *
 * 注意：Service 绑定已移至 ListActivity，这里只负责 UI 显示
 */
public class OximeterViewHolder extends RecyclerView.ViewHolder {
    private static final String TAG = "OximeterViewHolder";

    public TextView deviceName;
    public Button startBtn;
    public ImageButton settingsBtn;
    public View infoLayout;
    public TextView hrText, spo2Text;
    public TextView statusText;
    public View headerLayout;
    public View expandArrow;
    public TextView debugLogText;  // 调试日志显示
    public PlotView bvpWaveView;   // BVP波形显示

    private boolean infoVisible = false;
    private BufferedWriter logWriter;
    private boolean isRecording = false;
    private boolean isConnected = false;
    private StringBuilder debugLogs = new StringBuilder();  // 保存调试日志
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // UI更新节流：防止数据过快导致主线程阻塞
    private long lastUiUpdateTime = 0;
    private static final long UI_UPDATE_INTERVAL_MS = 100;  // 最多每100ms更新一次UI
    private int flushCounter = 0;
    private static final int FLUSH_INTERVAL = 10;  // 每10条数据flush一次

    public OximeterViewHolder(View itemView) {
        super(itemView);
        deviceName = itemView.findViewById(R.id.deviceName);
        startBtn = itemView.findViewById(R.id.startBtn);
        settingsBtn = itemView.findViewById(R.id.settingsBtn);
        infoLayout = itemView.findViewById(R.id.infoLayout);
        hrText = itemView.findViewById(R.id.hrText);
        spo2Text = itemView.findViewById(R.id.spo2Text);
        statusText = itemView.findViewById(R.id.statusText);
        headerLayout = itemView.findViewById(R.id.headerLayout);
        expandArrow = itemView.findViewById(R.id.expandArrow);
        debugLogText = itemView.findViewById(R.id.debugLogText);
        bvpWaveView = itemView.findViewById(R.id.bvpWaveView);

        // 初始化波形显示设置
        if (bvpWaveView != null) {
            bvpWaveView.setPlotColor(0xFF1E88E5);  // 蓝色波形，与血氧颜色一致
            bvpWaveView.setAxisColor(0x331E88E5);  // 淡蓝色坐标轴
        }

        // 点击头部折叠/展开
        if (headerLayout != null) {
            headerLayout.setOnClickListener(v -> toggleInfo());
        }

        // 注意：Service 已在 ListActivity 中启动和绑定，这里只设置监听器
        setupOximeterListener();
    }

    /**
     * 设置血氧仪数据监听器（不启动新的 Service 绑定）
     */
    private void setupOximeterListener() {
        // 获取已初始化的 OximeterManager 并设置 debug 监听器
        OximeterManager manager = OximeterManager.getInstance();
        if (manager != null) {
            manager.setDebugListener(msg -> {
                mainHandler.post(() -> appendDebugLog(msg));
            });
            manager.setDataListener(data -> {
                // 波形数据不受节流限制，直接更新（PlotView内部有批量更新机制）
                if (data != null && data.bvp >= 0 && bvpWaveView != null) {
                    mainHandler.post(() -> bvpWaveView.addValue(data.bvp));
                }

                // 文本UI节流更新：避免数据过快导致主线程阻塞
                long now = System.currentTimeMillis();
                if (now - lastUiUpdateTime < UI_UPDATE_INTERVAL_MS) {
                    return;  // 跳过本次文本UI更新
                }
                lastUiUpdateTime = now;

                mainHandler.post(() -> {
                    if (data != null) {
                        if (!isConnected) {
                            isConnected = true;
                            updateConnectionStatus(true);
                        }
                        bindTextData(data);
                    }
                });
            });
            appendDebugLog("监听器已设置，等待USB设备...");

            // 如果已经连接，更新状态
            if (manager.isConnected()) {
                isConnected = true;
                updateConnectionStatus(true);
            }
        }
    }

    /**
     * 追加调试日志到界面
     */
    private void appendDebugLog(String msg) {
        // 保持最近6行
        String[] lines = debugLogs.toString().split("\n");
        if (lines.length >= 6) {
            debugLogs = new StringBuilder();
            for (int i = 1; i < lines.length; i++) {
                debugLogs.append(lines[i]).append("\n");
            }
        }
        debugLogs.append(msg).append("\n");
        if (debugLogText != null) {
            debugLogText.setText(debugLogs.toString().trim());
        }
    }

    /**
     * 更新连接状态显示
     */
    public void updateConnectionStatus(boolean connected) {
        isConnected = connected;
        if (statusText != null) {
            if (connected) {
                statusText.setText("已连接");
                statusText.setTextColor(0xFF4CAF50); // 绿色
            } else {
                statusText.setText("未连接");
                statusText.setTextColor(0xFFFF9800); // 橙色
            }
        }
    }

    public void toggleInfo() {
        if (infoVisible) {
            infoLayout.animate()
                    .translationY(-100)
                    .alpha(0)
                    .setDuration(200)
                    .withEndAction(() -> infoLayout.setVisibility(View.GONE))
                    .start();
        } else {
            infoLayout.setVisibility(View.VISIBLE);
            infoLayout.setAlpha(0);
            infoLayout.setTranslationY(-100);
            infoLayout.animate()
                    .translationY(0)
                    .alpha(1)
                    .setDuration(200)
                    .start();
        }
        infoVisible = !infoVisible;
    }

    public void bindData(OximeterData data) {
        if (data.hr >= 0) hrText.setText("HR: " + data.hr + " bpm");
        if (data.spo2 >= 0) spo2Text.setText("SpO2: " + data.spo2 + " %");

        // 更新BVP波形显示
        if (bvpWaveView != null && data.bvp >= 0) {
            bvpWaveView.addValue(data.bvp);
        }

        recordData(data);
    }

    /**
     * 只更新文本数据（HR、SpO2），用于节流场景
     */
    private void bindTextData(OximeterData data) {
        if (data.hr >= 0) hrText.setText("HR: " + data.hr + " bpm");
        if (data.spo2 >= 0) spo2Text.setText("SpO2: " + data.spo2 + " %");
        recordData(data);
    }

    public void startRecord(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("AppSettings", MODE_PRIVATE);
        try {
            String experimentId = prefs.getString("experiment_id", "default");
            SessionManager sm = SessionManager.getInstance();
            sm.ensureSession(context, experimentId);
            File dir = sm.subDir(Constants.DIR_SPO2);
            if (dir != null && !dir.exists()) dir.mkdirs();

            String fileName = "spo2_" + TimeSync.nowWallMillis() + ".csv";
            File logFile = new File(dir, fileName);
            logWriter = new BufferedWriter(new FileWriter(logFile, true));
            // 使用统一的CSV表头
            logWriter.write(Constants.CSV_HEADER_SPO2 + "\n");
            isRecording = true;

            // 开始录制时清除旧波形数据
            if (bvpWaveView != null) {
                bvpWaveView.clearPlot();
            }

            // 通过OximeterManager启动录制（Service已在ListActivity管理）
            OximeterManager manager = OximeterManager.getInstance();
            if (manager != null && manager.isConnected()) {
                manager.startRecording(dir.getAbsolutePath());
            }

            Log.i(TAG, "SpO2 recording started: " + logFile.getAbsolutePath());
            startBtn.setText("停止");

        } catch (IOException e) {
            Log.e(TAG, "Failed to start SpO2 logging", e);
            Toast.makeText(context, "Failed to start logging", Toast.LENGTH_SHORT).show();
        }
    }

    public void stopRecord() {
        try {
            if (logWriter != null) {
                logWriter.close();
            }

            // 停止OximeterManager的录制
            OximeterManager manager = OximeterManager.getInstance();
            if (manager != null) {
                manager.stopRecording();
            }

            // 停止录制时强制刷新波形显示
            if (bvpWaveView != null) {
                bvpWaveView.forceRefresh();
            }

            Log.i(TAG, "SpO2 recording stopped");
            Toast.makeText(itemView.getContext(), "已停止录制", Toast.LENGTH_SHORT).show();
            startBtn.setText("开始");
        } catch (IOException e) {
            Log.e(TAG, "Error stopping SpO2 recording", e);
            Toast.makeText(itemView.getContext(), "停止录制时出错", Toast.LENGTH_SHORT).show();
        } finally {
            isRecording = false;
            logWriter = null;
        }
    }

    private void recordData(OximeterData data) {
        if (!isRecording || logWriter == null) return;
        try {
            // 使用统一时间戳格式：wall_ms,hr,spo2,bvp
            long wall = TimeSync.nowWallMillis();
            logWriter.write(wall + "," + data.hr + "," + data.spo2 + "," + data.bvp);
            logWriter.newLine();
            // 减少flush频率，每FLUSH_INTERVAL条数据flush一次
            flushCounter++;
            if (flushCounter >= FLUSH_INTERVAL) {
                logWriter.flush();
                flushCounter = 0;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error writing SpO2 data", e);
        }
    }

    public boolean isRecording() {
        return isRecording;
    }

    public boolean isConnected() {
        return isConnected;
    }

    /**
     * 释放资源
     */
    public void release() {
        // 注意：Service 绑定已移至 ListActivity，这里只清理本地资源
        if (logWriter != null) {
            try {
                logWriter.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing log writer", e);
            }
            logWriter = null;
        }
    }
}
