package com.tsinghua.sample.activity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tsinghua.sample.DeviceAdapter;
import com.tsinghua.sample.R;
import com.tsinghua.sample.model.Device;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class ListActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "com.tsinghua.sample.USB_PERMISSION";
    private UsbManager usbManager;
    private static final int PERMISSION_REQUEST_CODE = 100;  // Permission request code
    private static final int REQUEST_CAMERA_PERMISSION = 200;

    private RecyclerView recyclerView;
    private DeviceAdapter adapter;

    @SuppressLint("NewApi")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        checkPermissions();

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        registerReceiver(usbReceiver, new IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED);

        checkOximeterUsbPermission();

        recyclerView = findViewById(R.id.deviceRecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        List<Device> devices = new ArrayList<>();
        devices.add(new Device(Device.TYPE_FRONT_CAMERA, "前置录制"));
        devices.add(new Device(Device.TYPE_BACK_CAMERA, "后置录制"));
        devices.add(new Device(Device.TYPE_MICROPHONE, "麦克风"));
        devices.add(new Device(Device.TYPE_IMU, "IMU"));
        devices.add(new Device(Device.TYPE_RING, "指环"));
        devices.add(new Device(Device.TYPE_ECG, "心电"));
        devices.add(new Device(Device.TYPE_OXIMETER, "血氧仪"));

        adapter = new DeviceAdapter(this, devices);
        recyclerView.setAdapter(adapter);
    }

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
                        Log.w("USB", "权限被拒绝" );
                    }
                }
            }
        }
    };
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
    @Override
    protected void onDestroy() {
        unregisterReceiver(usbReceiver);
        super.onDestroy();
    }

}
