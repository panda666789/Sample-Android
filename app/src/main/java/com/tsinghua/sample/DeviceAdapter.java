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
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import com.tsinghua.sample.activity.BackCameraSettingsActivity;
import com.tsinghua.sample.activity.EcgSettingsActivity;
import com.tsinghua.sample.activity.FrontCameraSettingsActivity;
import com.tsinghua.sample.activity.ImuSettingsActivity;
import com.tsinghua.sample.activity.MicrophoneSettingsActivity;
import com.tsinghua.sample.activity.OximeterSettingsActivity;
import com.tsinghua.sample.activity.RingSettingsActivity;
import com.tsinghua.sample.device.OximeterService;
import com.tsinghua.sample.media.CameraFaceProcessor;
import com.tsinghua.sample.media.CameraHelper;
import com.tsinghua.sample.media.CameraPureFaceProcessor;
import com.tsinghua.sample.media.IMURecorder;
import com.tsinghua.sample.media.MultiMicAudioRecorderHelper;
import com.tsinghua.sample.media.RecorderHelper;
import com.tsinghua.sample.model.Device;


import org.opencv.android.OpenCVLoader;

import java.util.List;

public class DeviceAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private Context context;
    private List<Device> devices;
    private OximeterService oxService;
    private boolean serviceBound = false;
    private CameraHelper frontCameraHelper;
    private CameraHelper backCameraHelper;
    private IMURecorder imuRecorder;
    private MultiMicAudioRecorderHelper multiMicAudioRecorderHelper;
    public static EcgViewHolder currentEcgViewHolder;


    private OximeterViewHolder currentOximeterViewHolder;

    public DeviceAdapter(Context context, List<Device> devices) {
        this.context = context;
        this.devices = devices;
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
            SharedPreferences prefs = context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE);
            boolean enableInference = prefs.getBoolean("enable_inference", false);
            if(enableInference){
                String enableRecording = prefs.getString("video_format", "none");
                if(enableRecording.equals("none")){
                    h.itemView.setOnClickListener(v -> {
                        h.toggleInfo();
                    });

                    CameraPureFaceProcessor cameraPureFaceProcessor = null;
                    try {
                        cameraPureFaceProcessor = new CameraPureFaceProcessor(context, h.surfaceView, h.plotView);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }

                    CameraPureFaceProcessor finalCameraPureFaceProcessor = cameraPureFaceProcessor;
                    h.startBtn.setOnClickListener(v -> {
                        if (device.isRunning()) {
                            finalCameraPureFaceProcessor.stopCamera();
                            device.setRunning(false);
                            h.startBtn.setText("开始");
                        } else {
                            finalCameraPureFaceProcessor.startCamera();
                            device.setRunning(true);
                            h.startBtn.setText("结束");
                        }
                    });
                }
                else {
                    h.itemView.setOnClickListener(v -> {
                        h.toggleInfo();
                    });

                    CameraFaceProcessor cameraFaceProcessor = null;
                    try {
                        cameraFaceProcessor = new CameraFaceProcessor(context, h.surfaceView, h.plotView);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }

                    CameraFaceProcessor finalCameraFaceProcessor = cameraFaceProcessor;
                    h.startBtn.setOnClickListener(v -> {
                        if (device.isRunning()) {
                            finalCameraFaceProcessor.stopCamera();
                            device.setRunning(false);
                            h.startBtn.setText("开始");
                        } else {
                            finalCameraFaceProcessor.startCamera();
                            device.setRunning(true);
                            h.startBtn.setText("结束");
                        }
                    });
                }

            }
            else{
                h.itemView.setOnClickListener(v -> {
                    h.toggleInfo();
                if (frontCameraHelper == null) {
                    frontCameraHelper = new CameraHelper(context, h.surfaceView, null);
                }
                });
                h.startBtn.setOnClickListener(v -> {
                    if (device.isRunning()) {
                        frontCameraHelper.stopFrontRecording();
                        device.setRunning(false);
                        h.startBtn.setText("开始");
                    } else {
                        frontCameraHelper.startFrontRecording();
                        device.setRunning(true);
                        h.startBtn.setText("结束");
                    }
                });
            }
            h.settingsBtn.setOnClickListener(v -> {
                Intent intent = new Intent(context, FrontCameraSettingsActivity.class);
                intent.putExtra("deviceName", device.getName());
                context.startActivity(intent);
            });
        } else if (holder instanceof BackCameraViewHolder) {
            BackCameraViewHolder h = (BackCameraViewHolder) holder;

            h.deviceName.setText(device.getName());

            h.itemView.setOnClickListener(v -> {
                h.toggleInfo();
                if (backCameraHelper == null) {
                    backCameraHelper = new CameraHelper(context, null, h.surfaceView);
                }
            });


            h.startBtn.setOnClickListener(v -> {
                if (device.isRunning()) {
                    backCameraHelper.stopBackRecording();
                    device.setRunning(false);
                    h.startBtn.setText("开始");
                } else {
                    backCameraHelper.startBackRecording();
                    device.setRunning(true);
                    h.startBtn.setText("结束");
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
                } else {
                    multiMicAudioRecorderHelper.stopRecording();
                    h.startBtn.setText("开始");
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

            // 展开IMU数据的预览
            h.itemView.setOnClickListener(v -> {
                h.toggleInfo();  // 点击展开或收起 IMU 数据的预览
            });

            h.startBtn.setOnClickListener(v -> {
                if (device.isRunning()) {
                    // 停止 IMU 录制
                    imuRecorder.stopRecording();
                    device.setRunning(false);
                    h.startBtn.setText("开始");
                } else {
                    // 启动 IMU 录制
                    imuRecorder.startRecording();
                    device.setRunning(true);
                    h.startBtn.setText("结束");
                }
            });

            h.settingsBtn.setOnClickListener(v -> {
                Intent intent = new Intent(context, ImuSettingsActivity.class);
                intent.putExtra("deviceName", device.getName());
                context.startActivity(intent);
            });

            // 获取 IMU 数据并实时更新
            imuRecorder.setOnDataUpdateListener((accelData, gyroData) -> {
                h.updateIMUData(accelData, gyroData);
            });
        } else if (holder instanceof RingViewHolder) {
            RingViewHolder h = (RingViewHolder) holder;

            h.deviceName.setText(device.getName());
            h.itemView.setOnClickListener(v -> {
                h.toggleInfo();  // 点击展开或收起 IMU 数据的预览
            });
            h.connectBtn.setOnClickListener(v -> h.connectToDevice(context));

            h.startBtn.setOnClickListener(v -> {
                if (device.isRunning()) {
                    h.stopRingRecording();
                    device.setRunning(false);
                    h.startBtn.setText("开始");
                } else {
                    h.startRingRecording(context);
                    device.setRunning(true);
                    h.startBtn.setText("结束");
                }
            });

            h.settingsBtn.setOnClickListener(v -> {
                Intent intent = new Intent(context, RingSettingsActivity.class);
                intent.putExtra("deviceName", device.getName());
                context.startActivity(intent);
            });
        } else if (holder instanceof EcgViewHolder) {
            EcgViewHolder h = (EcgViewHolder) holder;
            DeviceAdapter.currentEcgViewHolder = (EcgViewHolder) holder;

            h.deviceName.setText(device.getName());

            h.itemView.setOnClickListener(v -> h.toggleInfo());

            // 从当前 device 对象里拿子设备列表
            List<com.vivalnk.sdk.model.Device> ecgSubDevices = device.getEcgSubDevices();

            h.bindData(context, ecgSubDevices);

            h.settingsBtn.setOnClickListener(v -> {
                Intent intent = new Intent(context, EcgSettingsActivity.class);
                intent.putExtra("deviceName", device.getName());
                intent.putExtra("device_object", device); // 把Device对象传过去

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
                } else {
                    h.stopRecord();
                    device.setRunning(false);

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
