package com.tsinghua.sample.media;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;


import com.tsinghua.sample.MainActivity;

import java.util.Arrays;

public class CameraHelper {
    private static final String TAG = "CameraHelper";
    private CameraManager cameraManager;
    private CameraDevice cameraDeviceFront, cameraDeviceBack;
    private CameraCaptureSession captureSessionFront, captureSessionBack;
    private Context context;
    private SurfaceView surfaceViewFront, surfaceViewBack;
    private String cameraIdFront, cameraIdBack;
    private boolean isRecording = false;
    public RecorderHelper recorderHelper;
    private Handler mCalHandler;
    private Runnable mTicker;
    private boolean isFirstTime = true;
    public CameraHelper(Context context, SurfaceView surfaceViewBack) {
        this.context = context;
        this.surfaceViewBack = surfaceViewBack;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.recorderHelper = new RecorderHelper(this,context);
        this.mCalHandler = new Handler();
        this.surfaceViewBack.getHolder().addCallback(surfaceCallbackBack);
        initializeCameras();
    }

    private SurfaceHolder.Callback surfaceCallbackFront = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            holder.setFixedSize(1920, 1080);
            openCamera(cameraIdFront, true);
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {}
    };

    private SurfaceHolder.Callback surfaceCallbackBack = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            openCamera(cameraIdBack, false);
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {}
    };

    public void initializeCameras() {
        try {
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                // 获取 StreamConfigurationMap 来检索支持的分辨率
//                if (cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING)
//                        == CameraCharacteristics.LENS_FACING_FRONT) {
//                    cameraIdFront = cameraId;
//                    Log.e(TAG,"CameraId:"+cameraId);
//                }
                if (cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING)
                        == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraIdBack = cameraId;
                }
                openCamera(cameraIdBack, false);

            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void openCamera(String cameraId, boolean isFront) {
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    Log.d(TAG, (isFront ? "Front" : "Back") + " camera opened.");
                    if (isFront) {
                        cameraDeviceFront = camera;
                        startPreview(cameraDeviceFront, surfaceViewFront.getHolder().getSurface(), true);
                    } else {
                        cameraDeviceBack = camera;
                        startPreview(cameraDeviceBack, surfaceViewBack.getHolder().getSurface(), false);
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.d(TAG, (isFront ? "Front" : "Back") + " camera disconnected.");
                    camera.close();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, (isFront ? "Front" : "Back") + " camera error: " + error);
                    camera.close();
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void startPreview(CameraDevice cameraDevice, Surface surface, boolean isFront) {
        try {
            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    Log.d(TAG, (isFront ? "Front" : "Back") + " camera preview configured.");
                    if (isFront) {
                        captureSessionFront = session;
                        try {
                            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                            builder.addTarget(surface);
                            session.setRepeatingRequest(builder.build(), null, null);
                        } catch (CameraAccessException e) {
                            e.printStackTrace();
                        }
                    } else {
                        captureSessionBack = session;
                        try {
                            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                            builder.addTarget(surface);
                            if(isFirstTime) {
                                if (recorderHelper.isFlashlightOn) {
                                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
                                    isFirstTime = false;
                                }
                            }
                            session.setRepeatingRequest(builder.build(), null, null);
                        } catch (CameraAccessException e) {
                            e.printStackTrace();
                        }
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, (isFront ? "Front" : "Back") + " camera preview configuration failed.");
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public boolean isRecording() {
        return isRecording;
    }

    public void startRecording() {
        isRecording = true;
        recorderHelper.setupRecording();
    }

    public void stopRecording(
                              final IMURecorder imuRecorder,
                              final MultiMicAudioRecorderHelper multiMicAudioRecorderHelper,
                              final RecordingStopCallback callback) {
        if (!isRecording) {
            // 如果当前没有在录制，直接回调，表示录制已结束（或无录制）
            callback.onRecordingStopped();
            return;
        }

        isRecording = false;
        recorderHelper.stopRecording();
        imuRecorder.outputDirectory = recorderHelper.newOutputDirectory;
        imuRecorder.stopRecording();
        multiMicAudioRecorderHelper.outputDirectory = recorderHelper.newOutputDirectory;
        Log.e(TAG,recorderHelper.newOutputDirectory.toString());
        multiMicAudioRecorderHelper.stopRecording();

        // 当停止动作真正执行完毕后调用回调
        callback.onRecordingStopped();
    }

    public Runnable createTicker(Handler mCalHandler, TextView textViewTimer, Button btnRecord,IMURecorder imuRecorder,MultiMicAudioRecorderHelper multiMicAudioRecorderHelper) {
        this.mCalHandler = mCalHandler;
        this.mTicker = recorderHelper.createTicker(mCalHandler, textViewTimer, btnRecord,imuRecorder,multiMicAudioRecorderHelper);
        return this.mTicker;
    }


    // Getter and Setter methods
    public CameraDevice getCameraDeviceFront() {
        return cameraDeviceFront;
    }

    public CameraDevice getCameraDeviceBack() {
        return cameraDeviceBack;
    }

    public CameraCaptureSession getCaptureSessionFront() {
        return captureSessionFront;
    }

    public CameraCaptureSession getCaptureSessionBack() {
        return captureSessionBack;
    }

    public void setCaptureSessionFront(CameraCaptureSession session) {
        this.captureSessionFront = session;
    }

    public void setCaptureSessionBack(CameraCaptureSession session) {
        this.captureSessionBack = session;
    }

    public SurfaceView getSurfaceViewFront() {
        return surfaceViewFront;
    }

    public SurfaceView getSurfaceViewBack() {
        return surfaceViewBack;
    }

    public Context getContext() {
        return context;
    }

    public Handler getCalHandler() {
        return mCalHandler;
    }

    public Runnable getTicker() {
        return mTicker;
    }

    public interface RecordingStopCallback {
        void onRecordingStopped();
    }
}
