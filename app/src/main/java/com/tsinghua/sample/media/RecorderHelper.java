package com.tsinghua.sample.media;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.Image;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.tsinghua.sample.core.FrameMetadataRecorder;
import com.tsinghua.sample.core.SessionManager;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RecorderHelper {
    private static final String TAG = "RecorderHelper";
    private final CameraHelper cameraHelper;
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MediaRecorder mediaRecorderFront, mediaRecorderBack;
    public String startTimestamp;
    public String stopTimestamp;
    public File outputDirectory;

    private List<String> frameDataFront = new ArrayList<>();
    private List<String> frameDataBack = new ArrayList<>();
    private FrameMetadataRecorder frontMetaRecorder;
    private FrameMetadataRecorder backMetaRecorder;

    // 存储当前视频路径
    private String currentFrontVideoPath;
    private String currentBackVideoPath;

    // 双摄模式标志，用于控制闪光灯
    private boolean isDualCameraMode = false;

    public RecorderHelper(CameraHelper cameraHelper, Context context) {
        this.cameraHelper = cameraHelper;
        this.context = context;
    }

    /**
     * 设置双摄模式标志
     * @param isDualMode true表示双摄模式，后置摄像头录制时会自动开启闪光灯
     */
    public void setDualCameraMode(boolean isDualMode) {
        this.isDualCameraMode = isDualMode;
        Log.d(TAG, "Dual camera mode set to: " + isDualMode);
    }

    private String generateTimestamp() {
        return String.valueOf(System.currentTimeMillis());
    }

    private void prepareDirectories() {
        SharedPreferences prefs = context.getSharedPreferences("AppSettings", MODE_PRIVATE);
        String experimentId = prefs.getString("experiment_id", "default");
        SessionManager sm = SessionManager.getInstance();
        outputDirectory = sm.ensureSession(context, experimentId);
    }

    public void setupFrontRecording() {
        startTimestamp = null;
        outputDirectory = null;
        frameDataFront.clear();
        currentFrontVideoPath = null;  // 重置视频路径
        startTimestamp = generateTimestamp();
        prepareDirectories();

        File frontDir = new File(outputDirectory, "front");
        if (!frontDir.exists()) frontDir.mkdirs();
        File frontOutputFile = new File(frontDir, "front_camera_" + startTimestamp + ".mp4");
        currentFrontVideoPath = frontOutputFile.getAbsolutePath();  // 保存视频路径
        File metaFile = new File(frontDir, "frame_metadata_front_" + startTimestamp + ".csv");

        Log.d(TAG, "Setting up front recording, output: " + currentFrontVideoPath);

        try {
            frontMetaRecorder = new FrameMetadataRecorder(metaFile);
        } catch (Exception e) {
            Log.e(TAG, "init front metadata recorder failed", e);
        }

        setupMediaRecorder(currentFrontVideoPath, true, 270);

        if (cameraHelper.getSurfaceViewFront() == null) {
            Log.e(TAG, "Front SurfaceView is null");
            throw new IllegalStateException("Front SurfaceView is null");
        }

        Surface frontSurface = cameraHelper.getSurfaceViewFront().getHolder().getSurface();
        if (frontSurface == null || !frontSurface.isValid()) {
            Log.e(TAG, "Front Surface is invalid");
            throw new IllegalStateException("Front Surface is invalid");
        }

        if (cameraHelper.getCameraDeviceFront() == null) {
            Log.e(TAG, "Front CameraDevice is null");
            throw new IllegalStateException("Front CameraDevice is null");
        }

        if (mediaRecorderFront == null) {
            Log.e(TAG, "Front MediaRecorder is null");
            throw new IllegalStateException("Front MediaRecorder is null");
        }

        try {
            cameraHelper.getCameraDeviceFront().createCaptureSession(
                    Arrays.asList(frontSurface, mediaRecorderFront.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            Log.d(TAG, "Front recording session configured successfully");
                            cameraHelper.setCaptureSessionFront(session);
                            try {
                                CaptureRequest.Builder builder = cameraHelper.getCameraDeviceFront()
                                        .createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                builder.addTarget(frontSurface);
                                builder.addTarget(mediaRecorderFront.getSurface());

                                session.setRepeatingRequest(builder.build(), new CameraCaptureSession.CaptureCallback() {
                                    @Override
                                    public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                                   @NonNull CaptureRequest request,
                                                                   @NonNull TotalCaptureResult result) {
                                        Long sensorTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                                        long frameNumber = result.getFrameNumber();
                                        if (frontMetaRecorder != null && sensorTimestamp != null) {
                                            frontMetaRecorder.record(sensorTimestamp, frameNumber);
                                        }
                                    }
                                }, mainHandler);

                                mediaRecorderFront.start();
                                Log.i(TAG, "Front MediaRecorder started successfully, output: " + frontOutputFile.getAbsolutePath());
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Front recording CameraAccessException", e);
                                mainHandler.post(() ->
                                        Toast.makeText(context, "前摄录制失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
                            } catch (IllegalStateException e) {
                                Log.e(TAG, "Front MediaRecorder start failed - IllegalStateException", e);
                                mainHandler.post(() ->
                                        Toast.makeText(context, "前摄MediaRecorder启动失败", Toast.LENGTH_LONG).show());
                            } catch (Exception e) {
                                Log.e(TAG, "Front recording unexpected error", e);
                                mainHandler.post(() ->
                                        Toast.makeText(context, "前摄录制出错: " + e.getMessage(), Toast.LENGTH_LONG).show());
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Front camera session config failed - this usually means unsupported resolution or surface configuration");
                            // 释放MediaRecorder资源
                            if (mediaRecorderFront != null) {
                                try {
                                    mediaRecorderFront.release();
                                } catch (Exception ignored) {}
                                mediaRecorderFront = null;
                            }
                            mainHandler.post(() ->
                                    Toast.makeText(context, "前摄配置失败，请检查分辨率设置", Toast.LENGTH_LONG).show());
                        }
                    }, mainHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Front camera session error", e);
            throw new RuntimeException("Failed to create front camera session", e);
        }
    }

    public void setupBackRecording() {
        // 只在双摄模式下自动开启闪光灯
        Log.d(TAG, "Back recording starting, dual camera mode: " + isDualCameraMode);

        startTimestamp = null;
        outputDirectory = null;
        frameDataBack.clear();
        startTimestamp = generateTimestamp();
        prepareDirectories();

        File backDir = new File(outputDirectory, "back");
        if (!backDir.exists()) backDir.mkdirs();
        File backOutputFile = new File(backDir, "back_camera_" + startTimestamp + ".mp4");
        File metaFile = new File(backDir, "frame_metadata_back_" + startTimestamp + ".csv");

        Log.d(TAG, "Setting up back recording, output: " + backOutputFile.getAbsolutePath());

        try {
            backMetaRecorder = new FrameMetadataRecorder(metaFile);
        } catch (Exception e) {
            Log.e(TAG, "init back metadata recorder failed", e);
        }

        setupMediaRecorder(backOutputFile.getAbsolutePath(), false, 90);

        if (cameraHelper.getSurfaceViewBack() == null) {
            Log.e(TAG, "Back SurfaceView is null");
            throw new IllegalStateException("Back SurfaceView is null");
        }

        Surface backSurface = cameraHelper.getSurfaceViewBack().getHolder().getSurface();
        if (backSurface == null || !backSurface.isValid()) {
            Log.e(TAG, "Back Surface is invalid");
            throw new IllegalStateException("Back Surface is invalid");
        }

        if (cameraHelper.getCameraDeviceBack() == null) {
            Log.e(TAG, "Back CameraDevice is null");
            throw new IllegalStateException("Back CameraDevice is null");
        }

        if (mediaRecorderBack == null) {
            Log.e(TAG, "Back MediaRecorder is null");
            throw new IllegalStateException("Back MediaRecorder is null");
        }

        try {
            cameraHelper.getCameraDeviceBack().createCaptureSession(
                    Arrays.asList(backSurface, mediaRecorderBack.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            Log.d(TAG, "Back recording session configured");
                            cameraHelper.setCaptureSessionBack(session);
                            try {
                                CaptureRequest.Builder builder = cameraHelper.getCameraDeviceBack()
                                        .createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                builder.addTarget(backSurface);
                                builder.addTarget(mediaRecorderBack.getSurface());
                                // 只在双摄模式下自动开启闪光灯（TORCH模式）
                                builder.set(CaptureRequest.FLASH_MODE, isDualCameraMode ?
                                        CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);

                                session.setRepeatingRequest(builder.build(), new CameraCaptureSession.CaptureCallback() {
                                    @Override
                                    public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                                   @NonNull CaptureRequest request,
                                                                   @NonNull TotalCaptureResult result) {
                                        Long sensorTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                                        long frameNumber = result.getFrameNumber();
                                        if (backMetaRecorder != null && sensorTimestamp != null) {
                                            backMetaRecorder.record(sensorTimestamp, frameNumber);
                                        }
                                    }
                                }, mainHandler);

                                mediaRecorderBack.start();
                                Log.i(TAG, "Back MediaRecorder started successfully, output: " + backOutputFile.getAbsolutePath());
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Back recording CameraAccessException", e);
                                mainHandler.post(() ->
                                        Toast.makeText(context, "后摄录制失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
                            } catch (IllegalStateException e) {
                                Log.e(TAG, "Back MediaRecorder start failed - IllegalStateException", e);
                                mainHandler.post(() ->
                                        Toast.makeText(context, "后摄MediaRecorder启动失败", Toast.LENGTH_LONG).show());
                            } catch (Exception e) {
                                Log.e(TAG, "Back recording unexpected error", e);
                                mainHandler.post(() ->
                                        Toast.makeText(context, "后摄录制出错: " + e.getMessage(), Toast.LENGTH_LONG).show());
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Back camera session config failed - this usually means unsupported resolution or surface configuration");
                            // 释放MediaRecorder资源
                            if (mediaRecorderBack != null) {
                                try {
                                    mediaRecorderBack.release();
                                } catch (Exception ignored) {}
                                mediaRecorderBack = null;
                            }
                            mainHandler.post(() ->
                                    Toast.makeText(context, "后摄配置失败，请检查分辨率设置", Toast.LENGTH_LONG).show());
                        }
                    }, mainHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Back camera session error", e);
            throw new RuntimeException("Failed to create back camera session", e);
        }
    }

    /**
     * 获取摄像头支持的最佳视频录制分辨率
     * 优先选择1920x1080，如果不支持则选择最接近的较低分辨率
     */
    private Size getBestVideoSize(boolean isFront) {
        Size defaultSize = new Size(1920, 1080);  // 默认1080p
        Size fallbackSize = new Size(1280, 720);  // 回退到720p

        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String[] cameraIds = manager.getCameraIdList();

            for (String cameraId : cameraIds) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);

                boolean isTargetCamera = (isFront && facing == CameraCharacteristics.LENS_FACING_FRONT)
                        || (!isFront && facing == CameraCharacteristics.LENS_FACING_BACK);

                if (isTargetCamera) {
                    StreamConfigurationMap map = characteristics.get(
                            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map == null) continue;

                    Size[] sizes = map.getOutputSizes(MediaRecorder.class);
                    if (sizes == null || sizes.length == 0) {
                        Log.w(TAG, "No output sizes for MediaRecorder on " + (isFront ? "front" : "back") + " camera");
                        continue;
                    }

                    Log.d(TAG, (isFront ? "Front" : "Back") + " camera supported video sizes:");
                    Size bestSize = null;
                    int bestArea = 0;
                    int targetArea = 1920 * 1080;

                    for (Size size : sizes) {
                        Log.d(TAG, "  " + size.getWidth() + "x" + size.getHeight());
                        int area = size.getWidth() * size.getHeight();

                        // 优先选择1920x1080
                        if (size.getWidth() == 1920 && size.getHeight() == 1080) {
                            Log.d(TAG, "Found exact 1920x1080 support");
                            return size;
                        }

                        // 否则选择不超过1080p的最大分辨率
                        if (area <= targetArea && area > bestArea) {
                            bestSize = size;
                            bestArea = area;
                        }
                    }

                    if (bestSize != null) {
                        Log.d(TAG, "Selected video size: " + bestSize.getWidth() + "x" + bestSize.getHeight());
                        return bestSize;
                    }
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to query camera sizes", e);
        }

        // 如果查询失败，前置摄像头使用720p，后置使用1080p
        Size result = isFront ? fallbackSize : defaultSize;
        Log.w(TAG, "Using fallback size: " + result.getWidth() + "x" + result.getHeight());
        return result;
    }

    private void setupMediaRecorder(String outputPath, boolean isFront, int rotate) {
        MediaRecorder recorder = new MediaRecorder();
        try {
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
            for (AudioDeviceInfo d : devices) {
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        recorder.setPreferredDevice(d);
                    }
                    break;
                }
            }
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setOutputFile(outputPath);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);

            // 动态选择摄像头支持的分辨率
            Size videoSize = getBestVideoSize(isFront);
            recorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
            Log.d(TAG, "MediaRecorder using size: " + videoSize.getWidth() + "x" + videoSize.getHeight() + " for " + (isFront ? "front" : "back"));

            recorder.setVideoFrameRate(30);

            // 根据分辨率调整码率
            int bitRate = (videoSize.getWidth() * videoSize.getHeight() >= 1920 * 1080) ? 10000000 : 6000000;
            recorder.setVideoEncodingBitRate(bitRate);
            recorder.setOrientationHint(rotate);

            recorder.prepare();
            Log.d(TAG, "MediaRecorder prepared for " + (isFront ? "front" : "back") + " camera");

            if (isFront) mediaRecorderFront = recorder;
            else mediaRecorderBack = recorder;
        } catch (IOException e) {
            Log.e(TAG, "MediaRecorder prepare failed", e);
            recorder.release();
            throw new RuntimeException("MediaRecorder prepare failed", e);
        }
    }

    public void stopFrontRecording() {
        Log.d(TAG, "Stopping front recording");
        try {
            if (mediaRecorderFront != null) {
                CameraCaptureSession session = cameraHelper.getCaptureSessionFront();
                if (session != null) {
                    try {
                        session.stopRepeating();
                    } catch (Exception e) {
                        Log.w(TAG, "Error stopping repeating request", e);
                    }
                }
                try {
                    mediaRecorderFront.stop();
                } catch (Exception e) {
                    Log.w(TAG, "Error stopping MediaRecorder", e);
                }
                mediaRecorderFront.release();
                mediaRecorderFront = null;
                Log.d(TAG, "Front MediaRecorder released");
            }
        } catch (Exception e) {
            Log.e(TAG, "停止前摄录像失败", e);
        }

        stopTimestamp = generateTimestamp();
        if (frontMetaRecorder != null) {
            frontMetaRecorder.close();
            frontMetaRecorder = null;
        }
    }

    public void stopBackRecording() {
        Log.d(TAG, "Stopping back recording");
        try {
            if (mediaRecorderBack != null) {
                CameraCaptureSession session = cameraHelper.getCaptureSessionBack();
                if (session != null) {
                    try {
                        session.stopRepeating();
                    } catch (Exception e) {
                        Log.w(TAG, "Error stopping repeating request", e);
                    }
                }
                try {
                    mediaRecorderBack.stop();
                } catch (Exception e) {
                    Log.w(TAG, "Error stopping MediaRecorder", e);
                }
                mediaRecorderBack.release();
                mediaRecorderBack = null;
                Log.d(TAG, "Back MediaRecorder released");
            }
        } catch (Exception e) {
            Log.e(TAG, "停止后摄录像失败", e);
        }

        stopTimestamp = generateTimestamp();
        if (backMetaRecorder != null) {
            backMetaRecorder.close();
            backMetaRecorder = null;
        }
    }

    public static Bitmap convertJPEGToBitmap(Image image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    private Bitmap rotateBitmap(Bitmap bitmap, int rotationDegrees) {
        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    /**
     * 获取当前前置摄像头视频路径
     */
    public String getCurrentFrontVideoPath() {
        return currentFrontVideoPath;
    }

    /**
     * 获取当前后置摄像头视频路径
     */
    public String getCurrentBackVideoPath() {
        return currentBackVideoPath;
    }

    /**
     * 获取front目录路径
     */
    public String getFrontDir() {
        if (currentFrontVideoPath != null) {
            File videoFile = new File(currentFrontVideoPath);
            File frontDir = videoFile.getParentFile();
            if (frontDir != null) {
                return frontDir.getAbsolutePath();
            }
        }
        return null;
    }
}
