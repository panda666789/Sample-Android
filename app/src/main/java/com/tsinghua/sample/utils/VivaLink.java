package com.tsinghua.sample.utils;

import static com.tsinghua.sample.MainActivity.isRecordingECG;
import static com.tsinghua.sample.MainActivity.logWriterECG;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.github.AAChartModel.AAChartCore.AAChartCreator.AAChartView;
import com.github.AAChartModel.AAChartCore.AAChartCreator.AASeriesElement;
import com.tsinghua.sample.MainActivity;
import com.tsinghua.sample.R;
import com.tsinghua.sample.network.WebSocketManager;
import com.vivalnk.sdk.Callback;
import com.vivalnk.sdk.CommandRequest;
import com.vivalnk.sdk.DataReceiveListener;
import com.vivalnk.sdk.SampleDataReceiveListener;
import com.vivalnk.sdk.VitalClient;
import com.vivalnk.sdk.ble.BluetoothConnectListener;
import com.vivalnk.sdk.ble.BluetoothScanListener;
import com.vivalnk.sdk.command.base.CommandType;
import com.vivalnk.sdk.common.ble.connect.BleConnectOptions;
import com.vivalnk.sdk.common.ble.scan.ScanOptions;
import com.vivalnk.sdk.device.vv330.VV330Manager;
import com.vivalnk.sdk.model.Device;
import com.vivalnk.sdk.model.Motion;
import com.vivalnk.sdk.model.SampleData;
import com.vivalnk.sdk.utils.GSON;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

public class VivaLink {

    private static final String TAG = "VivaLink";

    private static Device MainDevice;
    // 权限列表
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
    };

    // 权限请求码
    private static final int PERMISSION_REQUEST_CODE = 100;

    private static AAChartView aaChartViewECG;    // ECG 图表
    private static AAChartView aaChartViewAcc;    // Acc 图表

    // **全局数据存储**
    private static final Deque<Integer> ecgData = new ArrayDeque<>();
    private static final Deque<Integer> accXData = new ArrayDeque<>();
    private static final Deque<Integer> accYData = new ArrayDeque<>();
    private static final Deque<Integer> accZData = new ArrayDeque<>();
    private static int HR=0;
    private static int RR=0;
    private static final int MAX_DATA_POINTS = 2000;
    public static void setAAChartViewECG(AAChartView chartView) {
        aaChartViewECG = chartView;
    }

    public static void setAAChartViewAcc(AAChartView chartView) {
        aaChartViewAcc = chartView;
    }
    public static void clearAllData() {
        ecgData.clear();
        accXData.clear();
        accYData.clear();
        accZData.clear();
        refreshCharts();
    }

    // 检查并请求权限
    public static void checkAndRequestPermissions(Activity activity) {
        List<String> permissionsNeeded = new ArrayList<>();

        // 检查权限
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(permission);
            }
        }

        // 如果有权限没有被授予，请求权限
        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(activity,
                    permissionsNeeded.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE);
        } else {
            // 权限已经被授予，开始扫描
            startScan(activity);
        }
    }

    // 权限回调
    public static void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults, Activity activity) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allPermissionsGranted = true;

            // 检查权限请求结果
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false;
                    break;
                }
            }

            if (allPermissionsGranted) {
                startScan(activity);
            } else {
                Toast.makeText(activity, "权限被拒绝，无法继续", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // 执行设备扫描
    public static void startScan(final Activity activity) {
        List<Device> deviceList = new ArrayList<>();
        Log.e(TAG,"开始扫描");
        VitalClient.getInstance().startScan(new ScanOptions.Builder().build(), new BluetoothScanListener() {
            @Override
            public void onDeviceFound(Device device) {
                deviceList.add(device);
                // 在扫描过程中记录扫描到的设备
                Log.e(TAG,"找到设备: " + GSON.toJson(device));

                connectToDevice(activity, device);

            }

            @Override
            public void onStop() {
                Log.e(TAG,"扫描停止，找到 " + deviceList.size() + " 个设备");

                Device device = findNearestEcgDevice(deviceList);
                if (device != null) {
                    connectToDevice(activity, device);
                } else {
                    Toast.makeText(activity, "未找到符合条件的心电设备", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    // 查找信号最强的 ECG 设备
    private static Device findNearestEcgDevice(List<Device> deviceList) {
        Device ret = null;
        for (Device device : deviceList) {
            if (!device.getName().startsWith("ECGRec_")) {
                continue;
            }
            if (ret == null || device.getRssi() > ret.getRssi()) {
                ret = device;
            }
        }
        return ret;
    }

    // 连接到设备
    private static void connectToDevice(final Activity activity, Device device) {
        BleConnectOptions options = new BleConnectOptions.Builder().setAutoConnect(false).build();
        VitalClient.getInstance().connect(device, options, new BluetoothConnectListener() {
            @Override
            public void onConnected(Device device) {
                String logStr = "设备连接成功: " + GSON.toJson(device);
                MainDevice = device;
                Log.e(TAG, logStr);
                updateTextView(activity, logStr);
                VitalClient.getInstance().registerSampleDataReceiver(device, new SampleDataReceiveListener() {
                    @Override
                    public void onReceiveSampleData(Device device, boolean flash, SampleData data) {
                        SampleDataReceiveListener.super.onReceiveSampleData(device, flash, data);
                        HR = (Integer) data.extras.get("hr");
                        RR = (Integer) data.extras.get("rr");
                        int[] ecg = (int[]) data.extras.get("ecg");
                        Motion[] acc = (Motion[]) data.extras.get("acc");
                        List<Integer> newECGData = new ArrayList<>();
                        List<Integer> newAccXData = new ArrayList<>();
                        List<Integer> newAccYData = new ArrayList<>();
                        List<Integer> newAccZData = new ArrayList<>();
                        if (ecg != null) {
                            for (int value : ecg) {
                                newECGData.add(value);
                            }
                        }
                        if (acc != null) {
                            for (Motion motion : acc) {
                                newAccXData.add(motion.getX());
                                newAccYData.add(motion.getY());
                                newAccZData.add(motion.getZ());
                            }
                        }

                        updateECGChartData(newECGData);
                        updateAccChartData(newAccXData, newAccYData, newAccZData);
                        String logStr = "接收到数据: " + GSON.toJson(data);
                        Log.e(TAG, logStr);
                        updateTextView(activity, logStr);
                    }
                });
                VitalClient.getInstance().registerDataReceiver(device, new DataReceiveListener() {
                    @Override
                    public void onReceiveData(Device device, Map<String, Object> data) {
                        String logStr = "接收到数据: " + GSON.toJson(data);
                        Log.e(TAG, logStr);
                        updateTextView(activity, logStr);

                    }

                    @Override
                    public void onBatteryChange(Device device, Map<String, Object> data) {
                        String logStr = "电池变化: " + GSON.toJson(data);
                        Log.e(TAG, logStr);
                        //updateTextView(activity, logStr);
                    }

                    @Override
                    public void onDeviceInfoUpdate(Device device, Map<String, Object> data) {
                        String logStr = "设备信息更新: " + GSON.toJson(data);
                        Log.e(TAG, logStr);
                        updateTextView(activity, logStr);
                    }

                    @Override
                    public void onLeadStatusChange(Device device, boolean isLeadOn) {
                        String logStr = "导联状态变化: " + isLeadOn;
                        Log.e(TAG, logStr);
                        updateTextView(activity, logStr);
                    }

                    @Override
                    public void onFlashStatusChange(Device device, int remainderFlashBlock) {
                        String logStr = "闪光灯状态变化: 剩余块数 = " + remainderFlashBlock;
                        Log.e(TAG, logStr);
                        updateTextView(activity, logStr);
                    }

                    @Override
                    public void onFlashUploadFinish(Device device) {
                        String logStr = "上传完成: " + GSON.toJson(device);
                        Log.e(TAG, logStr);
                        updateTextView(activity, logStr);
                    }
                });
            }

            @Override
            public void onDeviceReady(Device device) {
                String logStr = "设备已准备好: " + GSON.toJson(device);
                Log.e(TAG, logStr);
                updateTextView(activity, logStr);
            }

            @Override
            public void onDisconnected(Device device, boolean isForce) {
                String logStr = "设备断开连接: " + GSON.toJson(device);
                Log.e(TAG, logStr);
                updateTextView(activity, logStr);
            }

            @Override
            public void onError(Device device, int code, String msg) {
                String logStr = "设备连接出错: " + code + ", 错误信息: " + msg;
                Log.e(TAG, logStr);
                updateTextView(activity, logStr);
            }
        });
    }
    private static void updateTextView(final Activity activity, final String newText) {
        activity.runOnUiThread(new Runnable() {
            @SuppressLint("SetTextI18n")
            @Override
            public void run() {
                TextView tv = activity.findViewById(R.id.tv_ecg_data);
                if (tv != null) {
                    if(isRecordingECG){
                        WebSocketManager.getInstance(activity).sendMessage(String.valueOf(MainActivity.user.getId()),"HR:"+HR + " RR:"+RR);
                        tv.setText("HR:" + HR + " RR:" +RR);
                    }
                    else {
                        tv.setText(newText);
                    }
                }
            }

        });
        if (isRecordingECG && logWriterECG != null) {
            try {
                logWriterECG.write(newText+ "\n");
                logWriterECG.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    public static void startSampling() {
        VV330Manager vv330Manager = new VV330Manager(MainDevice);
        vv330Manager.switchToRTSMode(MainDevice, new Callback() {
            @Override
            public void onStart() {
                Callback.super.onStart();
            }
        });

        CommandRequest request = new CommandRequest.Builder()
                .setType(CommandType.startSampling)
                .setTimeout(3000)  // 超时时间 3 秒
                .build();

        VitalClient.getInstance().execute(MainDevice, request, new Callback() {
            @Override
            public void onComplete(Map<String, Object> data) {
                // 开始采样命令一般返回 null，如果有返回数据也可打印出来
                Log.d("ECG", "startSampling onComplete: " + data);
            }

            @Override
            public void onError(int code, String msg) {
                Log.e("ECG", "startSampling onError: code=" + code + ", msg=" + msg);
            }
        });
    }

    public static void updateECGChartData(List<Integer> newECGData) {
        appendData(ecgData, newECGData);
        refreshCharts();
    }

    public static void updateAccChartData(List<Integer> newXData, List<Integer> newYData, List<Integer> newZData) {
        appendData(accXData, newXData);
        appendData(accYData, newYData);
        appendData(accZData, newZData);
        refreshCharts();
    }

    private static void appendData(Deque<Integer> oldData, List<Integer> newData) {
        if (oldData.size() + newData.size() > MAX_DATA_POINTS) {
            oldData.clear(); // 先清空
        }
        oldData.addAll(newData); // 再添加新数据
    }

    private static void refreshCharts() {
        new Handler(Looper.getMainLooper()).post(() -> {

                    if (aaChartViewECG != null) {
            aaChartViewECG.aa_onlyRefreshTheChartDataWithChartOptionsSeriesArray(
                    new AASeriesElement[]{
                            new AASeriesElement().name("ECG").data(ecgData.toArray(new Object[ecgData.size()]))
                    });
        }

        if (aaChartViewAcc != null) {
            aaChartViewAcc.aa_onlyRefreshTheChartDataWithChartOptionsSeriesArray(
                    new AASeriesElement[]{
                            new AASeriesElement().name("AccX").data(accXData.toArray(new Object[accXData.size()])),
                            new AASeriesElement().name("AccY").data(accYData.toArray(new Object[accYData.size()])),
                            new AASeriesElement().name("AccZ").data(accZData.toArray(new Object[accZData.size()]))
                    });
        }
        });
    }
    // 结束采样方法
    public static void stopSampling() {
        CommandRequest request = new CommandRequest.Builder()
                .setType(CommandType.stopSampling)
                .setTimeout(3000)  // 超时时间 3 秒
                .build();

        VitalClient.getInstance().execute(MainDevice, request, new Callback() {
            @Override
            public void onComplete(Map<String, Object> data) {
                Log.d("ECG", "stopSampling onComplete: " + data);
            }

            @Override
            public void onError(int code, String msg) {
                Log.e("ECG", "stopSampling onError: code=" + code + ", msg=" + msg);
            }
        });
    }
}
