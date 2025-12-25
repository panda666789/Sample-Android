package com.tsinghua.sample.media;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.*;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.util.Arrays;

public class CameraHelper {
    private static final String TAG = "CameraHelper";
    private CameraManager cameraManager;
    private CameraDevice cameraDeviceFront, cameraDeviceBack;
    private CameraCaptureSession captureSessionFront, captureSessionBack;
    private Context context;
    private SurfaceView surfaceViewFront, surfaceViewBack;
    private String cameraIdFront, cameraIdBack;

    private RecorderHelper recorderHelperFront;
    private RecorderHelper recorderHelperBack;

    private boolean isRecordingFront = false;
    private boolean isRecordingBack = false;
    private boolean frontPreviewReady = false;
    private boolean backPreviewReady = false;

    // 后台Handler线程，避免在主线程进行相机操作
    private HandlerThread cameraThread;
    private Handler cameraHandler;

    // 标记Surface是否已经添加了Callback
    private boolean frontCallbackAdded = false;
    private boolean backCallbackAdded = false;

    // 关闭状态标志 - 防止release后回调继续执行
    private volatile boolean isClosed = false;

    public boolean isFrontPreviewReady() {
        return frontPreviewReady;
    }

    public boolean isBackPreviewReady() {
        return backPreviewReady;
    }

    public CameraHelper(Context context, SurfaceView surfaceViewFront, SurfaceView surfaceViewBack) {
        Log.d(TAG, "CameraHelper constructor: front=" + surfaceViewFront + ", back=" + surfaceViewBack);
        this.context = context;
        this.surfaceViewFront = surfaceViewFront;
        this.surfaceViewBack = surfaceViewBack;
        this.recorderHelperFront = new RecorderHelper(this, context);
        this.recorderHelperBack = new RecorderHelper(this, context);

        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        // 创建后台线程
        cameraThread = new HandlerThread("CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        // 先初始化相机ID
        initializeCameras();
        Log.d(TAG, "Camera IDs initialized: front=" + cameraIdFront + ", back=" + cameraIdBack);

        if (surfaceViewFront != null && !frontCallbackAdded) {
            Log.d(TAG, "Adding front surface callback");
            surfaceViewFront.getHolder().addCallback(surfaceCallbackFront);
            frontCallbackAdded = true;
            // 检查Surface是否已存在（RecyclerView中SurfaceView可能已经创建）
            Surface surface = surfaceViewFront.getHolder().getSurface();
            Log.d(TAG, "Front surface check: surface=" + surface + ", isValid=" + (surface != null && surface.isValid()));
            if (surface != null && surface.isValid()) {
                Log.d(TAG, "Front Surface already exists, opening camera directly");
                openCamera(cameraIdFront, true);
            } else {
                Log.d(TAG, "Front Surface not ready, waiting for surfaceCreated callback");
            }
        }
        if (surfaceViewBack != null && !backCallbackAdded) {
            surfaceViewBack.getHolder().addCallback(surfaceCallbackBack);
            backCallbackAdded = true;
            // 检查Surface是否已存在
            Surface surface = surfaceViewBack.getHolder().getSurface();
            if (surface != null && surface.isValid()) {
                Log.d(TAG, "Back Surface already exists, opening camera directly");
                openCamera(cameraIdBack, false);
            }
        }
    }

    private void initializeCameras() {
        try {
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(cameraId);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    cameraIdFront = cameraId;
                } else if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraIdBack = cameraId;

                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // region Surface Callbacks
    private final SurfaceHolder.Callback surfaceCallbackFront = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Log.d(TAG, "Front surface created, isRecording=" + isRecordingFront + ", isClosed=" + isClosed);
            // 检查是否已关闭，防止release后回调继续执行
            if (isClosed) {
                Log.d(TAG, "CameraHelper already closed, ignoring front surfaceCreated");
                return;
            }
            if (isRecordingFront) {
                // 录制中Surface重新创建，尝试恢复预览
                // 注意：MediaRecorder已经在运行，只需要重新启动预览请求
                Log.d(TAG, "Surface recreated during recording, attempting to restore preview");
                if (cameraDeviceFront != null && holder.getSurface() != null && holder.getSurface().isValid()) {
                    // 重新配置预览会话（录制会话由RecorderHelper管理）
                    restorePreviewDuringRecording(cameraDeviceFront, holder.getSurface(), true);
                }
            } else {
                openCamera(cameraIdFront, true);
            }
        }
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.d(TAG, "Front surface changed: " + width + "x" + height);
        }
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.d(TAG, "Front surface destroyed, isRecording=" + isRecordingFront + ", isClosed=" + isClosed);
            // 如果已关闭，不做任何操作
            if (isClosed) {
                Log.d(TAG, "CameraHelper already closed, ignoring front surfaceDestroyed");
                return;
            }
            // 只有在非录制状态时才关闭相机
            // 录制时保持相机打开，避免录制中断
            if (!isRecordingFront) {
                if (cameraDeviceFront != null) {
                    cameraDeviceFront.close();
                    cameraDeviceFront = null;
                    frontPreviewReady = false;
                }
            } else {
                Log.d(TAG, "Surface destroyed during recording, keeping camera open");
            }
        }
    };

    private final SurfaceHolder.Callback surfaceCallbackBack = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Log.d(TAG, "Back surface created, isRecording=" + isRecordingBack + ", isClosed=" + isClosed);
            // 检查是否已关闭
            if (isClosed) {
                Log.d(TAG, "CameraHelper already closed, ignoring back surfaceCreated");
                return;
            }
            if (isRecordingBack) {
                Log.d(TAG, "Surface recreated during recording, attempting to restore preview");
                if (cameraDeviceBack != null && holder.getSurface() != null && holder.getSurface().isValid()) {
                    restorePreviewDuringRecording(cameraDeviceBack, holder.getSurface(), false);
                }
            } else {
                openCamera(cameraIdBack, false);
            }
        }
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.d(TAG, "Back surface changed: " + width + "x" + height);
        }
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.d(TAG, "Back surface destroyed, isRecording=" + isRecordingBack + ", isClosed=" + isClosed);
            // 如果已关闭，不做任何操作
            if (isClosed) {
                Log.d(TAG, "CameraHelper already closed, ignoring back surfaceDestroyed");
                return;
            }
            // 只有在非录制状态时才关闭相机
            if (!isRecordingBack) {
                if (cameraDeviceBack != null) {
                    cameraDeviceBack.close();
                    cameraDeviceBack = null;
                    backPreviewReady = false;
                }
            } else {
                Log.d(TAG, "Surface destroyed during recording, keeping camera open");
            }
        }
    };
    // endregion

    /**
     * 在录制过程中恢复预览显示
     * 由于MediaRecorder已经在运行，这里只创建一个简单的预览请求
     */
    private void restorePreviewDuringRecording(CameraDevice device, Surface surface, boolean isFront) {
        if (device == null || surface == null || !surface.isValid()) {
            Log.w(TAG, "Cannot restore preview: invalid device or surface");
            return;
        }
        try {
            // 获取当前录制会话并添加预览目标
            CameraCaptureSession session = isFront ? captureSessionFront : captureSessionBack;
            if (session != null) {
                CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                builder.addTarget(surface);
                // 尝试用单独的预览请求，不干扰录制
                session.capture(builder.build(), null, cameraHandler);
                Log.d(TAG, "Preview restore request sent for " + (isFront ? "front" : "back"));
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to restore preview during recording", e);
        } catch (IllegalStateException e) {
            Log.w(TAG, "Session not ready for preview restore", e);
        }
    }

    private void openCamera(String cameraId, boolean isFront) {
        Log.d(TAG, "openCamera called: cameraId=" + cameraId + ", isFront=" + isFront + ", isClosed=" + isClosed);
        // 检查是否已关闭
        if (isClosed) {
            Log.d(TAG, "CameraHelper already closed, not opening camera");
            return;
        }
        if (cameraId == null) {
            Log.e(TAG, "Camera ID is null for " + (isFront ? "front" : "back"));
            return;
        }
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Camera permission not granted!");
                return;
            }
            Log.d(TAG, "Opening camera: " + cameraId);
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    Log.d(TAG, (isFront ? "Front" : "Back") + " camera opened, isClosed=" + isClosed);
                    // 检查是否已关闭，如果已关闭则立即关闭相机
                    if (isClosed) {
                        Log.d(TAG, "CameraHelper closed during camera open, closing camera immediately");
                        camera.close();
                        return;
                    }
                    if (isFront) {
                        cameraDeviceFront = camera;
                        if (surfaceViewFront != null && surfaceViewFront.getHolder().getSurface() != null
                                && surfaceViewFront.getHolder().getSurface().isValid()) {
                            startPreview(cameraDeviceFront, surfaceViewFront.getHolder().getSurface(), true);
                        }
                    } else {
                        cameraDeviceBack = camera;
                        if (surfaceViewBack != null && surfaceViewBack.getHolder().getSurface() != null
                                && surfaceViewBack.getHolder().getSurface().isValid()) {
                            startPreview(cameraDeviceBack, surfaceViewBack.getHolder().getSurface(), false);
                        }
                    }
                }
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.w(TAG, (isFront ? "Front" : "Back") + " camera disconnected");
                    camera.close();
                    if (isFront) {
                        cameraDeviceFront = null;
                        frontPreviewReady = false;
                    } else {
                        cameraDeviceBack = null;
                        backPreviewReady = false;
                    }
                }
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "Camera error: " + error + " for " + (isFront ? "front" : "back") + " camera");
                    camera.close();
                    if (isFront) {
                        cameraDeviceFront = null;
                        frontPreviewReady = false;
                    } else {
                        cameraDeviceBack = null;
                        backPreviewReady = false;
                    }
                }
            }, cameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to open camera", e);
        }
    }

    void startPreview(CameraDevice device, Surface surface, boolean isFront) {
        if (device == null || surface == null || !surface.isValid()) {
            Log.e(TAG, "Cannot start preview: device=" + device + ", surface valid=" + (surface != null && surface.isValid()));
            return;
        }
        // 检查是否已关闭
        if (isClosed) {
            Log.d(TAG, "CameraHelper already closed, not starting preview");
            return;
        }
        try {
            device.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    // 关键检查：防止相机关闭后仍执行此回调导致崩溃
                    if (isClosed) {
                        Log.d(TAG, "CameraHelper closed during session config, closing session");
                        try {
                            session.close();
                        } catch (Exception e) {
                            Log.w(TAG, "Error closing session after CameraHelper closed", e);
                        }
                        return;
                    }
                    try {
                        // 再次检查device是否仍然有效
                        CameraDevice currentDevice = isFront ? cameraDeviceFront : cameraDeviceBack;
                        if (currentDevice == null || currentDevice != device) {
                            Log.w(TAG, "Camera device changed or closed, skipping preview setup");
                            session.close();
                            return;
                        }
                        CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        builder.addTarget(surface);
                        session.setRepeatingRequest(builder.build(), null, cameraHandler);
                        if (isFront) {
                            captureSessionFront = session;
                            frontPreviewReady = true;
                            Log.d(TAG, "Front camera preview ready");
                        } else {
                            captureSessionBack = session;
                            backPreviewReady = true;
                            Log.d(TAG, "Back camera preview ready");
                        }
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Failed to start preview request", e);
                    } catch (IllegalStateException e) {
                        // CameraDevice was already closed
                        Log.w(TAG, "Camera device closed before preview could start", e);
                        if (isFront) {
                            frontPreviewReady = false;
                        } else {
                            backPreviewReady = false;
                        }
                    }
                }

                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "Preview config failed: " + (isFront ? "Front" : "Back"));
                    if (isFront) {
                        frontPreviewReady = false;
                    } else {
                        backPreviewReady = false;
                    }
                }
            }, cameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to create capture session", e);
            if (isFront) {
                frontPreviewReady = false;
            } else {
                backPreviewReady = false;
            }
        } catch (IllegalStateException e) {
            // CameraDevice was already closed
            Log.w(TAG, "Camera device already closed when creating capture session", e);
            if (isFront) {
                frontPreviewReady = false;
            } else {
                backPreviewReady = false;
            }
        }
    }

    // region 录像控制

    public void startFrontRecording() {
        Log.d(TAG, "Attempting to start front recording...");
        if (cameraDeviceFront == null) {
            Log.e(TAG, "Front camera device is null");
            throw new IllegalStateException("Front camera device not initialized");
        }
        if (surfaceViewFront == null) {
            Log.e(TAG, "Front surface view is null");
            throw new IllegalStateException("Front surface view not initialized");
        }

        try {
            isRecordingFront = true;  // 先设置标记，防止surfaceDestroyed关闭相机
            recorderHelperFront.setupFrontRecording();
            Log.d(TAG, "Front recording started successfully");
        } catch (Exception e) {
            isRecordingFront = false;
            Log.e(TAG, "Failed to start front recording", e);
            throw e;
        }
    }

    /**
     * 设置双摄模式标志，用于控制后置摄像头闪光灯
     * @param isDualMode true表示双摄模式，后置录制时自动开启闪光灯
     */
    public void setDualCameraMode(boolean isDualMode) {
        if (recorderHelperBack != null) {
            recorderHelperBack.setDualCameraMode(isDualMode);
        }
    }

    public void startBackRecording() {
        Log.d(TAG, "Attempting to start back recording...");
        if (cameraDeviceBack == null) {
            Log.e(TAG, "Back camera device is null");
            throw new IllegalStateException("Back camera device not initialized");
        }
        if (surfaceViewBack == null) {
            Log.e(TAG, "Back surface view is null");
            throw new IllegalStateException("Back surface view not initialized");
        }

        try {
            isRecordingBack = true;  // 先设置标记
            recorderHelperBack.setupBackRecording();
            Log.d(TAG, "Back recording started successfully");
        } catch (Exception e) {
            isRecordingBack = false;
            Log.e(TAG, "Failed to start back recording", e);
            throw e;
        }
    }

    public void stopFrontRecording() {
        if (!isRecordingFront || recorderHelperFront == null) return;

        Log.d(TAG, "Stopping front recording");
        recorderHelperFront.stopFrontRecording();
        isRecordingFront = false;

        // 录制停止后，如果Surface仍然有效，重新启动预览
        if (surfaceViewFront != null && cameraDeviceFront != null) {
            Surface surface = surfaceViewFront.getHolder().getSurface();
            if (surface != null && surface.isValid()) {
                startPreview(cameraDeviceFront, surface, true);
            }
        }
        Log.d(TAG, "Front recording stopped");
    }

    public void stopBackRecording() {
        if (!isRecordingBack || recorderHelperBack == null) return;

        Log.d(TAG, "Stopping back recording");
        recorderHelperBack.stopBackRecording();
        isRecordingBack = false;

        // 录制停止后，如果Surface仍然有效，重新启动预览
        if (surfaceViewBack != null && cameraDeviceBack != null) {
            Surface surface = surfaceViewBack.getHolder().getSurface();
            if (surface != null && surface.isValid()) {
                startPreview(cameraDeviceBack, surface, false);
            }
        }
        Log.d(TAG, "Back recording stopped");
    }

    public boolean isFrontCameraReady() {
        boolean ready = frontPreviewReady && cameraDeviceFront != null && surfaceViewFront != null;
        // 每10次检查打印一次状态，避免日志过多
        if (!ready) {
            Log.d(TAG, "isFrontCameraReady: frontPreviewReady=" + frontPreviewReady
                    + ", cameraDeviceFront=" + (cameraDeviceFront != null)
                    + ", surfaceViewFront=" + (surfaceViewFront != null));
        }
        return ready;
    }

    public boolean isBackCameraReady() {
        return backPreviewReady && cameraDeviceBack != null && surfaceViewBack != null;
    }

    public boolean isFrontRecording() { return isRecordingFront; }
    public boolean isBackRecording() { return isRecordingBack; }

    public void setCaptureSessionFront(CameraCaptureSession session) {
        this.captureSessionFront = session;
    }

    public CameraCaptureSession getCaptureSessionFront() {
        return captureSessionFront;
    }

    public void setCaptureSessionBack(CameraCaptureSession session) {
        this.captureSessionBack = session;
    }

    public CameraCaptureSession getCaptureSessionBack() {
        return captureSessionBack;
    }

    // endregion

    // Getter
    public CameraDevice getCameraDeviceFront() { return cameraDeviceFront; }
    public CameraDevice getCameraDeviceBack() { return cameraDeviceBack; }
    public SurfaceView getSurfaceViewFront() { return surfaceViewFront; }
    public SurfaceView getSurfaceViewBack() { return surfaceViewBack; }

    /**
     * 获取当前前置摄像头视频路径
     */
    public String getCurrentFrontVideoPath() {
        if (recorderHelperFront != null) {
            return recorderHelperFront.getCurrentFrontVideoPath();
        }
        return null;
    }

    /**
     * 获取front目录路径
     */
    public String getFrontDir() {
        if (recorderHelperFront != null) {
            return recorderHelperFront.getFrontDir();
        }
        return null;
    }

    // 释放资源
    public void release() {
        Log.d(TAG, "Releasing camera resources, isClosed was: " + isClosed);

        // 【关键】首先设置关闭标志，阻止所有回调继续执行
        isClosed = true;

        // 移除SurfaceHolder回调，防止release后回调重新打开相机
        if (surfaceViewFront != null && frontCallbackAdded) {
            try {
                surfaceViewFront.getHolder().removeCallback(surfaceCallbackFront);
                Log.d(TAG, "Removed front surface callback");
            } catch (Exception e) {
                Log.w(TAG, "Error removing front surface callback", e);
            }
            frontCallbackAdded = false;
        }
        if (surfaceViewBack != null && backCallbackAdded) {
            try {
                surfaceViewBack.getHolder().removeCallback(surfaceCallbackBack);
                Log.d(TAG, "Removed back surface callback");
            } catch (Exception e) {
                Log.w(TAG, "Error removing back surface callback", e);
            }
            backCallbackAdded = false;
        }

        // 先停止录制
        if (isRecordingFront) {
            try {
                if (recorderHelperFront != null) {
                    recorderHelperFront.stopFrontRecording();
                }
            } catch (Exception e) {
                Log.w(TAG, "Error stopping front recording", e);
            }
            isRecordingFront = false;
        }
        if (isRecordingBack) {
            try {
                if (recorderHelperBack != null) {
                    recorderHelperBack.stopBackRecording();
                }
            } catch (Exception e) {
                Log.w(TAG, "Error stopping back recording", e);
            }
            isRecordingBack = false;
        }

        // 关闭capture sessions
        if (captureSessionFront != null) {
            try {
                captureSessionFront.close();
            } catch (Exception e) {
                Log.w(TAG, "Error closing front capture session", e);
            }
            captureSessionFront = null;
        }
        if (captureSessionBack != null) {
            try {
                captureSessionBack.close();
            } catch (Exception e) {
                Log.w(TAG, "Error closing back capture session", e);
            }
            captureSessionBack = null;
        }

        // 关闭相机
        if (cameraDeviceFront != null) {
            try {
                cameraDeviceFront.close();
            } catch (Exception e) {
                Log.w(TAG, "Error closing front camera", e);
            }
            cameraDeviceFront = null;
        }
        if (cameraDeviceBack != null) {
            try {
                cameraDeviceBack.close();
            } catch (Exception e) {
                Log.w(TAG, "Error closing back camera", e);
            }
            cameraDeviceBack = null;
        }

        // 停止后台线程
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try {
                cameraThread.join(1000);  // 最多等待1秒
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted while waiting for camera thread", e);
            }
            cameraThread = null;
            cameraHandler = null;
        }

        frontPreviewReady = false;
        backPreviewReady = false;

        Log.d(TAG, "Camera resources released");
    }
}