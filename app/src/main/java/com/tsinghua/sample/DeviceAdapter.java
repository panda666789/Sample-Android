package com.tsinghua.sample;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import com.tsinghua.sample.activity.BackCameraSettingsActivity;
import com.tsinghua.sample.activity.EcgSettingsActivity;
import com.tsinghua.sample.activity.FrontCameraSettingsActivity;
import com.tsinghua.sample.activity.ImuSettingsActivity;
import com.tsinghua.sample.activity.ListActivity;
import com.tsinghua.sample.activity.MicrophoneSettingsActivity;
import com.tsinghua.sample.activity.OximeterSettingsActivity;
import com.tsinghua.sample.activity.RingSettingsActivity;
import com.tsinghua.sample.device.OximeterService;
import com.tsinghua.sample.media.IMURecorder;
import com.tsinghua.sample.media.MultiMicAudioRecorderHelper;
import com.tsinghua.sample.model.Device;

import java.util.List;

public class DeviceAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private Context context;
    private List<Device> devices;
    private OximeterService oxService;
    private boolean serviceBound = false;
    private IMURecorder imuRecorder;
    private MultiMicAudioRecorderHelper multiMicAudioRecorderHelper;
    public static EcgViewHolder currentEcgViewHolder;
    private OximeterViewHolder currentOximeterViewHolder;

    // 摄像头控制器接口
    private ListActivity.CameraController cameraController;

    public DeviceAdapter(Context context, List<Device> devices, ListActivity.CameraController cameraController) {
        this.context = context;
        this.devices = devices;
        this.cameraController = cameraController;
        this.imuRecorder = new IMURecorder(context);
    }

    private final ServiceConnection oximeterConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            oxService = ((OximeterService.LocalBinder) service).getService();
            oxService.setListener(data -> {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (currentOximeterViewHolder != null) {
                        currentOximeterViewHolder.bindData(data);
                    }
                });
            });
            serviceBound = true;
            oxService.startRecording(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) + "/oximeter");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    @Override
    public int getItemViewType(int position) {
        return devices.get(position).getType();
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(context);
        switch (viewType) {
            case Device.TYPE_FRONT_CAMERA:
                return new FrontCameraViewHolder(inflater.inflate(R.layout.item_front_camera, parent, false));
            case Device.TYPE_BACK_CAMERA:
                return new BackCameraViewHolder(inflater.inflate(R.layout.item_back_camera, parent, false));
            case Device.TYPE_MICROPHONE:
                return new MicrophoneViewHolder(inflater.inflate(R.layout.item_microphone, parent, false));
            case Device.TYPE_IMU:
                return new ImuViewHolder(inflater.inflate(R.layout.item_imu, parent, false));
            case Device.TYPE_RING:
                return new RingViewHolder(inflater.inflate(R.layout.item_ring, parent, false));
            case Device.TYPE_ECG:
                return new EcgViewHolder(inflater.inflate(R.layout.item_ecg, parent, false));
            case Device.TYPE_OXIMETER:
                return new OximeterViewHolder(inflater.inflate(R.layout.item_oximeter, parent, false));
            default:
                throw new IllegalArgumentException("Invalid device type");
        }
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, @SuppressLint("RecyclerView") int position) {
        Device device = devices.get(position);

        if (holder instanceof FrontCameraViewHolder) {
            FrontCameraViewHolder h = (FrontCameraViewHolder) holder;
            h.deviceName.setText(device.getName());

            // 更新按钮状态
            boolean isRecording = cameraController.isFrontCameraRecording();
            h.startBtn.setText(isRecording ? "结束" : "开始");
            device.setRunning(isRecording);

            h.startBtn.setOnClickListener(v -> {
                if (cameraController.isFrontCameraRecording()) {
                    cameraController.stopFrontCamera();
                    device.setRunning(false);
                    h.startBtn.setText("开始");
                    Toast.makeText(context, "前置摄像头录制已停止", Toast.LENGTH_SHORT).show();
                } else {
                    try {
                        cameraController.startFrontCamera();
                        device.setRunning(true);
                        h.startBtn.setText("结束");
                        Toast.makeText(context, "前置摄像头录制正在启动...", Toast.LENGTH_SHORT).show();

                        // 1.5秒后检查录制状态
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (cameraController.isFrontCameraRecording()) {
                                Toast.makeText(context, "前置摄像头录制已开始", Toast.LENGTH_SHORT).show();
                            } else {
                                device.setRunning(false);
                                h.startBtn.setText("开始");
                                Toast.makeText(context, "前置摄像头录制启动失败", Toast.LENGTH_SHORT).show();
                            }
                        }, 1500);

                    } catch (Exception e) {
                        Log.e("DeviceAdapter", "Failed to start front camera", e);
                        device.setRunning(false);
                        h.startBtn.setText("开始");
                        Toast.makeText(context, "前置摄像头启动失败", Toast.LENGTH_SHORT).show();
                    }
                }
            });

            h.settingsBtn.setOnClickListener(v -> {
                Intent intent = new Intent(context, FrontCameraSettingsActivity.class);
                intent.putExtra("deviceName", device.getName());
                context.startActivity(intent);
            });

        } else if (holder instanceof BackCameraViewHolder) {
            BackCameraViewHolder h = (BackCameraViewHolder) holder;
            h.deviceName.setText(device.getName());

            // 更新按钮状态
            boolean isRecording = cameraController.isBackCameraRecording();
            h.startBtn.setText(isRecording ? "结束" : "开始");
            device.setRunning(isRecording);

            h.startBtn.setOnClickListener(v -> {
                if (cameraController.isBackCameraRecording()) {
                    cameraController.stopBackCamera();
                    device.setRunning(false);
                    h.startBtn.setText("开始");
                    Toast.makeText(context, "后置摄像头录制已停止", Toast.LENGTH_SHORT).show();
                } else {
                    try {
                        cameraController.startBackCamera();
                        device.setRunning(true);
                        h.startBtn.setText("结束");
                        Toast.makeText(context, "后置摄像头录制正在启动...", Toast.LENGTH_SHORT).show();

                        // 1.5秒后检查录制状态
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (cameraController.isBackCameraRecording()) {
                                Toast.makeText(context, "后置摄像头录制已开始", Toast.LENGTH_SHORT).show();
                            } else {
                                device.setRunning(false);
                                h.startBtn.setText("开始");
                                Toast.makeText(context, "后置摄像头录制启动失败", Toast.LENGTH_SHORT).show();
                            }
                        }, 1500);

                    } catch (Exception e) {
                        Log.e("DeviceAdapter", "Failed to start back camera", e);
                        device.setRunning(false);
                        h.startBtn.setText("开始");
                        Toast.makeText(context, "后置摄像头启动失败", Toast.LENGTH_SHORT).show();
                    }
                }
            });

            h.settingsBtn.setOnClickListener(v -> {
                Intent intent = new Intent(context, BackCameraSettingsActivity.class);
                intent.putExtra("deviceName", device.getName());
                context.startActivity(intent);
            });

        } else if (holder instanceof MicrophoneViewHolder) {
            MicrophoneViewHolder h = (MicrophoneViewHolder) holder;
            multiMicAudioRecorderHelper = new MultiMicAudioRecorderHelper(context);
            h.deviceName.setText(device.getName());
            h.startBtn.setText(device.isRunning() ? "结束" : "开始");

            h.startBtn.setOnClickListener(v -> {
                device.setRunning(!device.isRunning());
                if (device.isRunning()) {
                    multiMicAudioRecorderHelper.startRecording();
                    h.startBtn.setText("结束");
                    Toast.makeText(context, "麦克风录制已开始", Toast.LENGTH_SHORT).show();
                } else {
                    multiMicAudioRecorderHelper.stopRecording();
                    h.startBtn.setText("开始");
                    Toast.makeText(context, "麦克风录制已停止", Toast.LENGTH_SHORT).show();
                }
                notifyItemChanged(position);
            });

            h.settingsBtn.setOnClickListener(v -> {
                Intent intent = new Intent(context, MicrophoneSettingsActivity.class);
                intent.putExtra("deviceName", device.getName());
                context.startActivity(intent);
            });

        } else if (holder instanceof ImuViewHolder) {
            ImuViewHolder h = (ImuViewHolder) holder;
            h.deviceName.setText(device.getName());

            h.itemView.setOnClickListener(v -> {
                h.toggleInfo();
            });

            h.startBtn.setOnClickListener(v -> {
                if (device.isRunning()) {
                    imuRecorder.stopRecording();
                    device.setRunning(false);
                    h.startBtn.setText("开始");
                    Toast.makeText(context, "IMU录制已停止", Toast.LENGTH_SHORT).show();
                } else {
                    imuRecorder.startRecording();
                    device.setRunning(true);
                    h.startBtn.setText("结束");
                    Toast.makeText(context, "IMU录制已开始", Toast.LENGTH_SHORT).show();
                }
            });

            h.settingsBtn.setOnClickListener(v -> {
                Intent intent = new Intent(context, ImuSettingsActivity.class);
                intent.putExtra("deviceName", device.getName());
                context.startActivity(intent);
            });

            imuRecorder.setOnDataUpdateListener((accelData, gyroData) -> {
                h.updateIMUData(accelData, gyroData);
            });

        } else if (holder instanceof RingViewHolder) {
            RingViewHolder h = (RingViewHolder) holder;

            h.deviceName.setText(device.getName());

            h.itemView.setOnClickListener(v -> {
                h.toggleInfo();
            });

            h.connectBtn.setOnClickListener(v -> {
                h.recordLog("【尝试连接蓝牙】设备: " + device.getName());
                h.connectToDevice(context);
            });

            h.startBtn.setOnClickListener(v -> {
                if (h.isRecording()) {
                    h.stopRingRecording();
                    device.setRunning(false);
                    h.startBtn.setText("开始录制");
                    h.recordLog("=== 用户停止录制会话 ===");
                } else {
                    h.startRingRecording(context);
                    device.setRunning(true);
                    h.startBtn.setText("停止录制");
                    h.recordLog("=== 用户开始录制会话 ===");
                    h.recordLog("设备: " + device.getName());
                    h.recordLog("录制会话已启动，所有后续操作将被记录到日志文件");
                }
            });

            h.settingsBtn.setOnClickListener(v -> {
                h.recordLog("【进入设备设置】设备: " + device.getName());
                Intent intent = new Intent(context, RingSettingsActivity.class);
                intent.putExtra("deviceName", device.getName());
                context.startActivity(intent);
            });

            // 文件操作按钮日志记录
            h.requestFileListBtn.setOnClickListener(v -> {
                h.recordLog("【用户操作】请求获取文件列表");
                h.requestFileList(context);
            });

            h.downloadSelectedBtn.setOnClickListener(v -> {
                h.recordLog("【用户操作】开始下载选中文件");
                h.startDownloadSelectedFiles(context);
            });

            // 时间操作按钮日志记录
            h.timeUpdateBtn.setOnClickListener(v -> {
                h.recordLog("【用户操作】开始更新戒指时间");
                h.updateRingTime(context);
            });

            h.timeSyncBtn.setOnClickListener(v -> {
                h.recordLog("【用户操作】开始时间校准");
                h.performTimeSync(context);
            });

            // 测量和运动控制按钮日志记录
            h.startMeasurementBtn.setOnClickListener(v -> {
                String timeStr = h.measurementTimeInput.getText().toString();
                h.recordLog("【用户操作】开始主动测量，时长: " + timeStr + "秒");
                h.startActiveMeasurement(context);
            });

            h.startExerciseBtn.setOnClickListener(v -> {
                String duration = h.exerciseDurationInput.getText().toString();
                String segment = h.segmentDurationInput.getText().toString();
                h.recordLog("【用户操作】开始运动控制，总时长: " + duration + "秒，片段: " + segment + "秒");
                h.startExercise(context);
            });

            h.stopExerciseBtn.setOnClickListener(v -> {
                h.recordLog("【用户操作】手动停止运动");
                h.stopExercise(context);
            });

            if (device.isRunning() || h.isRecording()) {
                h.startBtn.setText("停止录制");
            } else {
                h.startBtn.setText("开始录制");
            }

            h.recordLog("设备初始化完成: " + device.getName());

        } else if (holder instanceof EcgViewHolder) {
            EcgViewHolder h = (EcgViewHolder) holder;
            DeviceAdapter.currentEcgViewHolder = (EcgViewHolder) holder;

            h.deviceName.setText(device.getName());

            h.itemView.setOnClickListener(v -> h.toggleInfo());

            List<com.vivalnk.sdk.model.Device> ecgSubDevices = device.getEcgSubDevices();
            h.bindData(context, ecgSubDevices);

            h.settingsBtn.setOnClickListener(v -> {
                Intent intent = new Intent(context, EcgSettingsActivity.class);
                intent.putExtra("deviceName", device.getName());
                intent.putExtra("device_object", device);
                context.startActivity(intent);
            });

        } else if (holder instanceof OximeterViewHolder) {
            OximeterViewHolder h = (OximeterViewHolder) holder;
            this.currentOximeterViewHolder = h;
            h.deviceName.setText(device.getName());

            h.startBtn.setOnClickListener(v -> {
                if (!device.isRunning()) {
                    h.startRecord(context);
                    device.setRunning(true);
                    Toast.makeText(context, "血氧仪录制已开始", Toast.LENGTH_SHORT).show();
                } else {
                    h.stopRecord();
                    device.setRunning(false);
                    Toast.makeText(context, "血氧仪录制已停止", Toast.LENGTH_SHORT).show();
                }
                h.startBtn.setText(device.isRunning() ? "停止" : "开始");
            });

            h.settingsBtn.setOnClickListener(v -> {
                Intent intent = new Intent(context, OximeterSettingsActivity.class);
                intent.putExtra("deviceName", device.getName());
                context.startActivity(intent);
            });

            h.itemView.setOnClickListener(v -> h.toggleInfo());
        }
    }

    public ServiceConnection getOximeterConnection() {
        return oximeterConnection;
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }
}