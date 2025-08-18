package com.tsinghua.sample.media;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.*;
import android.os.Handler;
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

    public boolean isFrontPreviewReady() {
        return frontPreviewReady;
    }

    public boolean isBackPreviewReady() {
        return backPreviewReady;
    }

    public CameraHelper(Context context, SurfaceView surfaceViewFront, SurfaceView surfaceViewBack) {
        this.context = context;
        this.surfaceViewFront = surfaceViewFront;
        this.surfaceViewBack = surfaceViewBack;
        this.recorderHelperFront = new RecorderHelper(this, context);
        this.recorderHelperBack = new RecorderHelper(this, context);

        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        if (surfaceViewFront != null) {
            surfaceViewFront.getHolder().addCallback(surfaceCallbackFront);
        }
        if (surfaceViewBack != null) {
            surfaceViewBack.getHolder().addCallback(surfaceCallbackBack);
        }
        initializeCameras();

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
            openCamera(cameraIdFront, true);

        }
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
        public void surfaceDestroyed(SurfaceHolder holder) {
            if (cameraDeviceFront != null) {
                cameraDeviceFront.close();
                cameraDeviceFront = null;
                frontPreviewReady = false;
            }
        }
    };

    private final SurfaceHolder.Callback surfaceCallbackBack = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            openCamera(cameraIdBack, false);

        }
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
        public void surfaceDestroyed(SurfaceHolder holder) {
            if (cameraDeviceBack != null) {
                cameraDeviceBack.close();
                cameraDeviceBack = null;
                backPreviewReady = false;
            }
        }
    };
    // endregion

    private void openCamera(String cameraId, boolean isFront) {
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    if (isFront) {
                        cameraDeviceFront = camera;
                        startPreview(cameraDeviceFront, surfaceViewFront.getHolder().getSurface(), true);
                    } else {
                        cameraDeviceBack = camera;
                        startPreview(cameraDeviceBack, surfaceViewBack.getHolder().getSurface(), false);
                    }
                }
                public void onDisconnected(@NonNull CameraDevice camera) {
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
                    camera.close();
                    if (isFront) {
                        cameraDeviceFront = null;
                        frontPreviewReady = false;
                    } else {
                        cameraDeviceBack = null;
                        backPreviewReady = false;
                    }
                    Log.e(TAG, "Camera error: " + error + " for " + (isFront ? "front" : "back") + " camera");
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    void startPreview(CameraDevice device, Surface surface, boolean isFront) {
        try {
            device.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    try {
                        CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        builder.addTarget(surface);
                        session.setRepeatingRequest(builder.build(), null, null);
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
                        e.printStackTrace();
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
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
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
            recorderHelperFront.setupFrontRecording();
            isRecordingFront = true;
            Log.d(TAG, "Front recording started successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start front recording", e);
            throw e;
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
            recorderHelperBack.setupBackRecording();
            isRecordingBack = true;
            Log.d(TAG, "Back recording started successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start back recording", e);
            throw e;
        }
    }

    public void stopFrontRecording() {
        if (!isRecordingFront || recorderHelperFront == null) return;
        recorderHelperFront.stopFrontRecording();
        if (surfaceViewFront != null && cameraDeviceFront != null) {
            startPreview(cameraDeviceFront, surfaceViewFront.getHolder().getSurface(), true);
        }
        isRecordingFront = false;
        Log.d(TAG, "Front recording stopped");
    }

    public void stopBackRecording() {
        if (!isRecordingBack || recorderHelperBack == null) return;
        recorderHelperBack.stopBackRecording();
        if (surfaceViewBack != null && cameraDeviceBack != null) {
            startPreview(cameraDeviceBack, surfaceViewBack.getHolder().getSurface(), false);
        }
        isRecordingBack = false;
        Log.d(TAG, "Back recording stopped");
    }

    public boolean isFrontCameraReady() {
        return frontPreviewReady && cameraDeviceFront != null && surfaceViewFront != null;
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

    // 释放资源
    public void release() {
        if (cameraDeviceFront != null) {
            cameraDeviceFront.close();
            cameraDeviceFront = null;
        }
        if (cameraDeviceBack != null) {
            cameraDeviceBack.close();
            cameraDeviceBack = null;
        }
        frontPreviewReady = false;
        backPreviewReady = false;
        isRecordingFront = false;
        isRecordingBack = false;
    }
}