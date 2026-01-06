package com.tsinghua.sample.utils;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.github.AAChartModel.AAChartCore.AAChartCreator.AAChartView;
import com.tsinghua.sample.core.SessionManager;
import com.tsinghua.sample.ecg.ECGMeasurementController;
import com.vivalnk.sdk.VitalClient;
import com.vivalnk.sdk.common.ble.scan.ScanOptions;
import com.vivalnk.sdk.ble.BluetoothScanListener;
import com.vivalnk.sdk.model.Device;

/**
 * 轻量封装，提供 MainActivity 期望的静态接口。
 * 仅做最小实现以满足编译和基础运行，核心 ECG 逻辑由 ECGMeasurementController 负责。
 */
public class VivaLink {

    private static final int REQ_CODE = 0xEE10;
    private static AAChartView chartAcc;
    private static AAChartView chartEcg;
    private static Context appCtx;

    public static void setAAChartViewAcc(AAChartView v) { chartAcc = v; }
    public static void setAAChartViewECG(AAChartView v) { chartEcg = v; }

    public static void checkAndRequestPermissions(Activity activity) {
        String[] perms = new String[]{
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        };
        appCtx = activity.getApplicationContext();
        boolean need = false;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(activity, p) != PackageManager.PERMISSION_GRANTED) {
                need = true;
                break;
            }
        }
        if (need) {
            ActivityCompat.requestPermissions(activity, perms, REQ_CODE);
        }
    }

    /**
     * 简单扫描封装，找到设备后立即停止扫描。
     */
    public static void startScan(Activity activity) {
        VitalClient.getInstance().startScan(new ScanOptions.Builder().build(), new BluetoothScanListener() {
            @Override
            public void onDeviceFound(Device device) {
                // 自动停止，交由上层处理
                VitalClient.getInstance().stopScan(this);
            }

            @Override
            public void onStop() { }
        });
    }

    public static void clearAllData() {
        // AAChartView doesn't have aa_onlyRefreshTheChartDataWithChartOptions
        // Charts will be cleared when new data is drawn
        chartAcc = null;
        chartEcg = null;
    }

    public static void startSampling() {
        if (appCtx == null) return;
        SharedPreferences prefs = appCtx.getSharedPreferences("AppSettings", Context.MODE_PRIVATE);
        String experiment = prefs.getString("experiment_id", "default");
        int duration = prefs.getInt("recording_duration", 2400);

        SessionManager sm = SessionManager.getInstance();
        sm.ensureSession(appCtx, experiment);

        ECGMeasurementController ctrl = ECGMeasurementController.getInstance();
        ctrl.init(appCtx);
        ctrl.beginSynchronizedMeasurement(duration, sm.getSessionDir());
    }

    public static void stopSampling() {
        ECGMeasurementController.getInstance().stopSynchronizedMeasurement();
    }
}
