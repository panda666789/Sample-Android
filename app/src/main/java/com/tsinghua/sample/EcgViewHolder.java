package com.tsinghua.sample;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.tsinghua.sample.core.SessionManager;
import com.tsinghua.sample.core.TimeSync;
import com.tsinghua.sample.ecg.ECGMeasurementController;
import com.vivalnk.sdk.ble.BluetoothConnectListener;
import com.vivalnk.sdk.ble.BluetoothScanListener;
import com.vivalnk.sdk.model.Device;
import com.vivalnk.sdk.model.Motion;

import java.util.Locale;

/**
 * 心电设备卡片 ViewHolder（完全重写版本）
 * - 卡片内完成扫描、连接、实时数据显示
 * - 支持手动开始/结束录制（与一键录制复用同一控制器）
 */
public class EcgViewHolder extends RecyclerView.ViewHolder {

    final TextView deviceName;
    private final TextView statusText;
    private final ImageView expandArrow;
    private final LinearLayout deviceContainer;
    private final MaterialButton scanBtn;
    private final MaterialButton disconnectBtn;
    private final LinearLayout scannedDeviceContainer;
    private final LinearLayout scannedDeviceList;
    private final LinearLayout deviceListContainer;

    // 连接后子视图
    private TextView tvMac;
    private TextView tvBattery;
    private View leadIndicator;
    private TextView tvLeadStatus;
    private TextView tvHeartRate;
    private TextView tvRespRate;
    private TextView tvMeasurementStatus;
    private MaterialButton toggleSamplingBtn;
    private PlotView ecgPlot;
    private TextView tvLog;

    private boolean expanded = false;
    private boolean initialized = false;
    private boolean manualRecording = false;
    private Device currentDevice;

    private ECGMeasurementController controller;
    private Context ctx;

    public EcgViewHolder(@NonNull View itemView) {
        super(itemView);
        deviceName = itemView.findViewById(R.id.deviceName);
        statusText = itemView.findViewById(R.id.statusText);
        expandArrow = itemView.findViewById(R.id.expandArrow);
        deviceContainer = itemView.findViewById(R.id.deviceContainer);
        scanBtn = itemView.findViewById(R.id.scanBtn);
        disconnectBtn = itemView.findViewById(R.id.disconnectBtn);
        scannedDeviceContainer = itemView.findViewById(R.id.scannedDeviceContainer);
        scannedDeviceList = itemView.findViewById(R.id.scannedDeviceList);
        deviceListContainer = itemView.findViewById(R.id.deviceListContainer);
    }

    public void init(Context context) {
        if (initialized) return;
        initialized = true;
        ctx = context;
        controller = ECGMeasurementController.getInstance();
        controller.init(context);
        controller.addListener(listener);

        // 初始状态为折叠
        deviceContainer.setVisibility(View.GONE);
        expandArrow.setRotation(0);

        expandArrow.setOnClickListener(v -> toggleExpand());
        itemView.findViewById(R.id.headerLayout).setOnClickListener(v -> toggleExpand());

        scanBtn.setOnClickListener(v -> {
            if (scanBtn.getText().toString().contains("停止")) {
                stopScan();
            } else {
                startScan();
            }
        });

        disconnectBtn.setOnClickListener(v -> {
            controller.disconnect();
            statusText.setText("未连接");
            Toast.makeText(ctx, "已断开心电设备", Toast.LENGTH_SHORT).show();
            deviceListContainer.removeAllViews();
            currentDevice = null;
            manualRecording = false;
        });
    }

    private void toggleExpand() {
        expanded = !expanded;
        deviceContainer.setVisibility(expanded ? View.VISIBLE : View.GONE);
        expandArrow.setRotation(expanded ? 180 : 0);
    }

    // region 扫描 & 连接
    private void startScan() {
        scannedDeviceContainer.removeAllViews();
        scannedDeviceList.setVisibility(View.VISIBLE);
        scanBtn.setText("停止扫描");
        controller.startScan(new BluetoothScanListener() {
            @Override
            public void onDeviceFound(Device device) {
                LayoutInflater inflater = LayoutInflater.from(ctx);
                View row = inflater.inflate(R.layout.item_scanned_ecg_device, scannedDeviceContainer, false);
                ((TextView) row.findViewById(R.id.deviceName)).setText(device.getName());
                ((TextView) row.findViewById(R.id.deviceMac)).setText(device.getId());
                row.findViewById(R.id.connectBtn).setOnClickListener(v -> {
                    stopScan();
                    connectDevice(device);
                });
                scannedDeviceContainer.addView(row);
            }

            @Override
            public void onStop() {
                scanBtn.setText("扫描心电设备");
            }
        });
    }

    private void stopScan() {
        controller.stopScan();
        scanBtn.setText("扫描心电设备");
    }

    private void connectDevice(Device device) {
        statusText.setText("连接中...");
        controller.connect(device, new BluetoothConnectListener() {
            @Override
            public void onConnected(Device d) {
                currentDevice = d;
            }

            @Override
            public void onDeviceReady(Device d) {
                statusText.setText("已连接");
                // 记录最近一次成功连接的心电设备MAC（用于“一键连接”）
                ctx.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
                        .edit()
                        .putString("last_connected_ecg_mac", d.getId())
                        .apply();
            }
        });
    }
    // endregion

    // region 渲染连接后的视图
    private void renderConnectedDevice(Device device) {
        deviceListContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(ctx);
        View card = inflater.inflate(R.layout.item_sub_ecg_device, deviceListContainer, false);
        deviceListContainer.addView(card);

        tvMac = card.findViewById(R.id.macAddress);
        tvBattery = card.findViewById(R.id.batteryText);
        leadIndicator = card.findViewById(R.id.leadStatusIndicator);
        tvLeadStatus = card.findViewById(R.id.leadStatusText);
        tvHeartRate = card.findViewById(R.id.heartRateText);
        tvRespRate = card.findViewById(R.id.respiratoryRateText);
        tvMeasurementStatus = card.findViewById(R.id.measurementStatusText);
        toggleSamplingBtn = card.findViewById(R.id.toggleSamplingBtn);
        ecgPlot = card.findViewById(R.id.plotView);
        tvLog = card.findViewById(R.id.tvLog);

        tvMac.setText(device.getId());
        tvBattery.setText("--%");
        tvLeadStatus.setText("导联未知");
        tvHeartRate.setText("-- bpm");
        tvRespRate.setText("-- rpm");
        tvMeasurementStatus.setText("等待连接...");
        toggleSamplingBtn.setText(controller.isMeasuring() ? "停止录制" : "开始录制");
        manualRecording = controller.isMeasuring();

        // 手动录制按钮
        toggleSamplingBtn.setOnClickListener(v -> {
            if (manualRecording) {
                controller.stopSynchronizedMeasurement();
                manualRecording = false;
                toggleSamplingBtn.setText("开始录制");
                tvMeasurementStatus.setText("已停止");
            } else {
                startManualMeasurement();
            }
        });

        // 连接按钮改为断开
        MaterialButton connectBtn = card.findViewById(R.id.connectBtn);
        connectBtn.setText("断开");
        connectBtn.setOnClickListener(v -> {
            controller.disconnect();
            deviceListContainer.removeAllViews();
            statusText.setText("未连接");
            currentDevice = null;
            manualRecording = false;
        });
    }

    private void startManualMeasurement() {
        SharedPreferences prefs = ctx.getSharedPreferences("AppSettings", Context.MODE_PRIVATE);
        String experiment = prefs.getString("experiment_id", "default");
        int duration = prefs.getInt("recording_duration", 2400);

        SessionManager.getInstance().startSession(ctx, experiment);
        TimeSync.startSessionClock();
        controller.beginSynchronizedMeasurement(duration, SessionManager.getInstance().getSessionDir());

        manualRecording = true;
        toggleSamplingBtn.setText("停止录制");
        tvMeasurementStatus.setText(String.format(Locale.CHINA, "录制中（%d秒）", duration));
        Toast.makeText(ctx, "心电录制开始", Toast.LENGTH_SHORT).show();
    }
    // endregion

    /**
     * 刷新连接状态UI（用于“一键连接”/自动断开等非卡片内按钮触发的场景）
     * 在UI线程调用。
     */
    public void refreshConnectionUi() {
        if (!initialized || controller == null) return;

        Device d = controller.getConnectedDevice();
        if (d != null && controller.isConnected()) {
            statusText.setText("已连接");
            currentDevice = d;
            renderConnectedDevice(d);
            return;
        }

        statusText.setText("未连接");
        manualRecording = false;
        currentDevice = null;
        if (toggleSamplingBtn != null) toggleSamplingBtn.setText("开始录制");
        if (deviceListContainer != null) deviceListContainer.removeAllViews();
    }

    // region 监听器
    private final ECGMeasurementController.Listener listener = new ECGMeasurementController.Listener() {
        @Override
        public void onConnectionStateChanged(ECGMeasurementController.ConnectionState state, Device device) {
            switch (state) {
                case CONNECTING:
                    statusText.setText("连接中...");
                    break;
                case CONNECTED:
                case READY:
                    statusText.setText("已连接");
                    // 兼容“一键连接”：当连接不是由本ViewHolder的 connectDevice() 触发时，
                    // 也需要渲染已连接设备的详情卡片
                    if (device != null && (currentDevice == null
                            || deviceListContainer.getChildCount() == 0
                            || (currentDevice.getId() != null && !currentDevice.getId().equals(device.getId())))) {
                        currentDevice = device;
                        renderConnectedDevice(device);
                    }
                    break;
                case DISCONNECTED:
                    statusText.setText("未连接");
                    manualRecording = false;
                    if (toggleSamplingBtn != null) toggleSamplingBtn.setText("开始录制");
                    if (deviceListContainer != null) deviceListContainer.removeAllViews();
                    if (scannedDeviceContainer != null) scannedDeviceContainer.removeAllViews();
                    currentDevice = null;
                    break;
            }
        }

        @Override
        public void onRealtimeData(ECGMeasurementController.ECGRealtimeData data) {
            if (data == null) return;
            manualRecording = controller.isMeasuring();
            if (toggleSamplingBtn != null) {
                toggleSamplingBtn.setText(manualRecording ? "停止录制" : "开始录制");
            }
            if (tvMeasurementStatus != null) {
                tvMeasurementStatus.setText(manualRecording ? "录制中" : "待机");
            }
            if (tvHeartRate != null && data.hr != null && data.hr > 0) {
                tvHeartRate.setText(data.hr + " bpm");
            }
            if (tvRespRate != null && data.rr != null && data.rr > 0) {
                tvRespRate.setText(data.rr + " rpm");
            }
            if (tvBattery != null && data.batteryPercent != null && data.batteryPercent >= 0) {
                tvBattery.setText(data.batteryPercent + "%");
            }
            if (tvLeadStatus != null) {
                tvLeadStatus.setText(data.leadOn ? "导联良好" : "导联脱落");
                tintLeadIndicator(data.leadOn);
            }
            if (ecgPlot != null && data.ecgMv != null) {
                for (float v : data.ecgMv) {
                    ecgPlot.addValue((int) (v * 1000)); // 转为近似微伏整数用于绘图
                }
            }
        }

        @Override
        public void onLog(String msg) {
            if (tvLog != null) tvLog.setText(msg);
        }
    };
    // endregion

    private void tintLeadIndicator(boolean leadOn) {
        if (leadIndicator == null) return;
        Drawable bg = leadIndicator.getBackground();
        int color = leadOn ? 0xFF4CAF50 : 0xFFF44336;
        if (bg != null) {
            bg.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        } else {
            leadIndicator.setBackgroundColor(color);
        }
    }
}
