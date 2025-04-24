package com.tsinghua.sample;

import static android.content.Context.MODE_PRIVATE;

import static com.tsinghua.sample.MainActivity.hexStringToByteArray;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import com.github.AAChartModel.AAChartCore.AAChartCreator.AAChartModel;
import com.github.AAChartModel.AAChartCore.AAChartCreator.AAChartView;
import com.github.AAChartModel.AAChartCore.AAChartCreator.AASeriesElement;
import com.github.AAChartModel.AAChartCore.AAChartEnum.AAChartAnimationType;
import com.github.AAChartModel.AAChartCore.AAChartEnum.AAChartType;
import com.github.AAChartModel.AAChartCore.AAOptionsModel.AAScrollablePlotArea;
import com.lm.sdk.LmAPI;
import com.lm.sdk.LogicalApi;
import com.lm.sdk.utils.BLEUtils;
import com.tsinghua.sample.utils.NotificationHandler;
import com.tsinghua.sample.utils.VivaLink;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class RingViewHolder extends RecyclerView.ViewHolder {
    TextView deviceName;
    Button startBtn;
    ImageButton settingsBtn;
    private LinearLayout infoLayout;     // 用于显示设备信息区域
    private boolean infoVisible = false;
    private TextView tvLog;     // 用于显示日志
    Button connectBtn;  // 用于连接蓝牙按钮
    private BufferedWriter logWriter; // 日志写入器
    private boolean isRecordingRing = false;
    private AAChartView chartViewGreen, chartViewIr, chartViewRed, chartViewIMU;  // 为每个数据系列分别设置图表
    private PlotView plotViewG,plotViewI;
    private PlotView plotViewR,plotViewX;
    private PlotView plotViewY,plotViewZ;

    public RingViewHolder(View itemView) {
        super(itemView);

        deviceName = itemView.findViewById(R.id.deviceName);
        startBtn = itemView.findViewById(R.id.startBtn);
        settingsBtn = itemView.findViewById(R.id.settingsBtn);
        infoLayout = itemView.findViewById(R.id.infoLayout);
        tvLog = itemView.findViewById(R.id.tvLog); // 显示日志
        connectBtn = itemView.findViewById(R.id.connectBtn); // 蓝牙连接按钮
        connectBtn.setOnClickListener(v -> connectToDevice(itemView.getContext()));
        plotViewG = itemView.findViewById(R.id.plotViewG);
        plotViewI = itemView.findViewById(R.id.plotViewI);
        plotViewR = itemView.findViewById(R.id.plotViewR);
        plotViewX = itemView.findViewById(R.id.plotViewX);
        plotViewY = itemView.findViewById(R.id.plotViewY);
        plotViewZ = itemView.findViewById(R.id.plotViewZ);
        plotViewG.setPlotColor(Color.parseColor("#00FF00")); // 设置 green 颜色
        plotViewI.setPlotColor(Color.parseColor("#0000FF")); // 设置 ir 颜色
        plotViewR.setPlotColor(Color.parseColor("#FF0000")); // 设置 red 颜色
        plotViewX.setPlotColor(Color.parseColor("#FFFF00")); // 设置 x 轴的颜色
        plotViewY.setPlotColor(Color.parseColor("#FF00FF")); // 设置 y 轴的颜色
        plotViewZ.setPlotColor(Color.parseColor("#00FFFF")); // 设置 z 轴的颜色
        NotificationHandler.setPlotViewG(plotViewG);
        NotificationHandler.setPlotViewI(plotViewI);
        NotificationHandler.setPlotViewR(plotViewR);
        NotificationHandler.setPlotViewX(plotViewX);
        NotificationHandler.setPlotViewY(plotViewY);
        NotificationHandler.setPlotViewZ(plotViewZ);




    }

    // 切换展开与收起的设备信息显示
    public void toggleInfo() {
        if (infoVisible) {
            infoLayout.animate()
                    .translationY(100)
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction(() -> infoLayout.setVisibility(View.GONE))
                    .start();
        } else {
            infoLayout.setAlpha(0f);
            infoLayout.setTranslationY(100);
            infoLayout.setVisibility(View.VISIBLE);
            infoLayout.animate()
                    .translationY(0)
                    .alpha(1f)
                    .setDuration(200)
                    .start();
        }
        infoVisible = !infoVisible;
    }


    // 连接蓝牙设备
    public void connectToDevice(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("AppSettings", MODE_PRIVATE);
        String macAddress = prefs.getString("mac_address", "");

        if (macAddress.isEmpty()) {
            Toast.makeText(context, "No device selected", Toast.LENGTH_SHORT).show();
            return;
        }

        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(macAddress);
        if (device != null) {
            BLEUtils.connectLockByBLE(context, device);
            recordLog("Connecting to device: " + macAddress);
        } else {
            Toast.makeText(context, "Invalid MAC address", Toast.LENGTH_SHORT).show();
        }
    }

    // 记录日志
    public void recordLog(String logMessage) {
        // 显示到UI
        tvLog.setText(logMessage);

        // 写入文件
        if (isRecordingRing && logWriter != null) {
            try {
                logWriter.write(logMessage + "\n");
                logWriter.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // 启动指环数据录制
    public void startRingRecording(Context context) {
        if (!isRecordingRing) {
            isRecordingRing = true;
            startBtn.setText("停止指环");
            SharedPreferences prefs = context.getSharedPreferences("AppSettings", MODE_PRIVATE);
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
            // 创建日志文件夹
            try {
                String experimentId = prefs.getString("experiment_id", "");
                String directoryPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES) + "/Sample/" + experimentId + "/RingLog/";
                File directory = new File(directoryPath);
                if (!directory.exists()) {
                    if (directory.mkdirs()) {
                        Log.d("FileSave", "Directory created successfully: " + directoryPath);
                    } else {
                        Log.e("FileSave", "Failed to create directory: " + directoryPath);
                        return;
                    }
                }
                String fileName = "RingLog_" + System.currentTimeMillis() + ".txt";
                File logFile = new File(directory, fileName);
                logWriter = new BufferedWriter(new FileWriter(logFile, true));

                recordLog("【Ring Recording Started】");

            } catch (IOException e) {
                e.printStackTrace();
                Toast.makeText(context, "Failed to start logging", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // 停止指环数据录制
    public void stopRingRecording() {
        if (isRecordingRing) {
            isRecordingRing = false;
            startBtn.setText("开始指环");
            recordLog("【Ring Recording Stopped】");
            byte[] data = hexStringToByteArray("00003C04");

            LmAPI.SEND_CMD(data);
            new android.os.Handler().postDelayed(() -> {
                try {
                    isRecordingRing = false;
                    if (logWriter != null) {
                        logWriter.close();
                        logWriter = null;
                    }
                    recordLog("【日志记录结束】");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }, 1000); // 延迟 1 秒
        }
    }
}
