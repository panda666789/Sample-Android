package com.tsinghua.sample.media;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;


import com.google.mediapipe.solutions.facemesh.FaceMesh;
import com.google.mediapipe.solutions.facemesh.FaceMeshOptions;
import com.tsinghua.sample.core.SessionManager;
import com.tsinghua.sample.utils.FacePreprocessor;
import com.tsinghua.sample.utils.HeartRateEstimator;
import com.tsinghua.sample.utils.PlotView;
import com.tsinghua.sample.utils.VideoQualityEvaluator;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraFaceProcessor {
    private static final String TAG = "CameraFaceProcessor";
    private static final boolean RUN_ON_GPU = true;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 200;
    private HeartRateEstimator heartRateEstimator;

    private FacePreprocessor facePreProcessor;

    // 心率更新回调
    private HeartRateEstimator.OnHeartRateListener heartRateListener;

    // 异步初始化相关
    private volatile boolean isInitialized = false;
    private OnInitializedListener initListener;
    private final ExecutorService initExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(android.os.Looper.getMainLooper());


    private CameraDevice cameraDevice;
    private CaptureRequest.Builder captureRequestBuilder;
    private CameraCaptureSession cameraCaptureSession;
    private ImageReader imageReader;
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;

    private Handler backgroundHandler;
    private HandlerThread backgroundThread;


    private FaceMesh faceMesh;

    // Context and lifecycle
    private Context activity;
    static public String outDir;
    private boolean isCameraRunning = false;

    // Callbacks
    private CameraFaceProcessorCallback callback;
    private String currentVideoPath;
    private volatile boolean isRecording = false;
    private File tempDir;         // 用来存每帧的 PNG
    private int frameCount = 0;   // 帧计数

    // 使用MediaCodec+MediaMuxer录制（避免MediaRecorder需要额外Surface，且比OpenCV VideoWriter更稳定）
    private MediaCodec mediaCodec;
    private MediaMuxer mediaMuxer;
    private int videoTrackIndex = -1;
    private boolean muxerStarted = false;
    private long recordingStartTimeNs = 0;  // 录制开始时间（纳秒）
    private static final int FRAME_RATE = 30;
    private static final int I_FRAME_INTERVAL = 1;
    private static final int VIDEO_WIDTH = 480;  // 旋转后的宽度
    private static final int VIDEO_HEIGHT = 640; // 旋转后的高度
    private final Object encoderLock = new Object();

    // 帧计数统计（用于调试）
    private int encodedFrameCount = 0;
    private int droppedFrameCount = 0;

    // 异步文件写入线程池
    private final ExecutorService fileWriteExecutor = Executors.newSingleThreadExecutor();
    // 视频编码线程池（独立于文件写入）
    private final ExecutorService videoEncoderExecutor = Executors.newSingleThreadExecutor();
    private com.tsinghua.sample.utils.PlotView plotView;
    public interface CameraFaceProcessorCallback {
        void onCameraStarted();
        void onCameraStopped();
        void onError(String error);
        void onFaceDetected(Bitmap faceBitmap);
        void onFaceProcessingResult(Object result); // Replace Object with your specific result type
    }

    /**
     * 视频质量评估回调
     */
    public interface OnQualityResultListener {
        void onQualityResult(VideoQualityEvaluator.QualityResult result);
    }
    private OnQualityResultListener qualityResultListener;

    /**
     * 初始化完成回调
     */
    public interface OnInitializedListener {
        void onInitialized();
        void onInitializeFailed(String error);
    }

    public void setOnInitializedListener(OnInitializedListener listener) {
        this.initListener = listener;
    }

    public boolean isInitialized() {
        return isInitialized;
    }

    public CameraFaceProcessor(Context activity, SurfaceView surfaceView, PlotView plotView)  {

        this.activity = activity;
        this.surfaceView = surfaceView;
        this.callback = callback;
        this.plotView = plotView;
        setupSurfaceView();
        setupFaceMesh();
        startBackgroundThread();
    }

    private void setupSurfaceView() {
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                int width = surfaceView.getWidth();
                int height = surfaceView.getHeight();
                if (width > 0 && height > 0) {
                    setupImageReader(width, height);
//                    startCamera();
                } else {
                    Log.e(TAG, "SurfaceView dimensions are invalid");
                    if (callback != null) {
                        callback.onError("Invalid surface dimensions");
                    }
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                // Handle surface changes if needed
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                stopCamera();
            }
        });
    }

    private void setupFaceMesh() {
        faceMesh = new FaceMesh(
                activity,
                FaceMeshOptions.builder()
                        .setStaticImageMode(false)
                        .setRefineLandmarks(false)
                        .setRunOnGpu(RUN_ON_GPU)
                        .setMaxNumFaces(1)
                        .build());

        faceMesh.setErrorListener((message, e) -> {
            Log.e(TAG, "MediaPipe Face Mesh error: " + message);
            if (callback != null) {
                callback.onError("Face mesh error: " + message);
            }
        });
    }

    private void setupImageReader(int width, int height) {
        if (width > 0 && height > 0) {
            // 增加缓冲区数量从2到4，减少帧丢失
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 4);
            imageReader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler);
        } else {
            Log.e(TAG, "Invalid dimensions for ImageReader: width=" + width + ", height=" + height);
            if (callback != null) {
                callback.onError("Invalid ImageReader dimensions");
            }
        }
    }

    /**
     * 初始化 MediaCodec + MediaMuxer 用于视频录制
     */
    private void setupVideoEncoder() {
        try {
            SharedPreferences prefs = activity.getSharedPreferences("AppSettings", Context.MODE_PRIVATE);
            String experimentId = prefs.getString("experiment_id", "default");
            SessionManager sm = SessionManager.getInstance();
            File sessionDir = sm.ensureSession(activity, experimentId);

            if (sessionDir == null) {
                Log.e(TAG, "无法创建 session 目录");
                return;
            }

            File frontDir = new File(sessionDir, "front");
            if (!frontDir.exists()) frontDir.mkdirs();

            String timestamp = String.valueOf(System.currentTimeMillis());
            currentVideoPath = new File(frontDir, "front_camera_" + timestamp + ".mp4").getAbsolutePath();

            synchronized (encoderLock) {
                // 配置 MediaFormat
                MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
                format.setInteger(MediaFormat.KEY_BIT_RATE, 8000000); // 8 Mbps 高画质
                format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
                // 使用 CBR 模式确保比特率
                format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);

                // 创建编码器
                mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                mediaCodec.start();

                // 创建 Muxer
                mediaMuxer = new MediaMuxer(currentVideoPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                videoTrackIndex = -1;
                muxerStarted = false;
                recordingStartTimeNs = System.nanoTime();  // 记录开始时间
                encodedFrameCount = 0;  // 重置帧计数
                droppedFrameCount = 0;
                isRecording = true;

                // 重置质量评估器
                resetQualityEvaluator();

                Log.d(TAG, "MediaCodec + MediaMuxer 初始化成功: " + currentVideoPath);
            }
        } catch (Exception e) {
            Log.e(TAG, "MediaCodec 初始化异常", e);
            releaseVideoEncoder();
        }
    }

    /**
     * 释放 MediaCodec + MediaMuxer 资源
     */
    private void releaseVideoEncoder() {
        Log.d(TAG, "releaseVideoEncoder called, isRecording=" + isRecording);
        Log.d(TAG, "录制统计 - 总编码帧数: " + encodedFrameCount + ", 丢帧数: " + droppedFrameCount);
        synchronized (encoderLock) {
            isRecording = false;

            if (mediaCodec != null) {
                try {
                    // 发送 EOS 并等待处理完成
                    drainEncoder(true);
                    mediaCodec.stop();
                    mediaCodec.release();
                    Log.d(TAG, "MediaCodec released");
                } catch (Exception e) {
                    Log.e(TAG, "释放 MediaCodec 失败", e);
                }
                mediaCodec = null;
            }

            if (mediaMuxer != null) {
                try {
                    if (muxerStarted) {
                        mediaMuxer.stop();
                    }
                    mediaMuxer.release();
                    Log.d(TAG, "MediaMuxer released, file: " + currentVideoPath);
                } catch (Exception e) {
                    Log.e(TAG, "释放 MediaMuxer 失败", e);
                }
                mediaMuxer = null;
            }

            videoTrackIndex = -1;
            muxerStarted = false;
        }

        // 计算并回调视频质量评估结果
        Log.i(TAG, "releaseVideoEncoder: 开始计算质量评估, facePreProcessor=" + facePreProcessor);
        if (facePreProcessor != null) {
            VideoQualityEvaluator.QualityResult result = facePreProcessor.evaluateQuality();
            Log.i(TAG, "视频质量评估完成:\n" + result.toString());
            Log.i(TAG, "qualityResultListener=" + qualityResultListener);
            if (qualityResultListener != null) {
                mainHandler.post(() -> qualityResultListener.onQualityResult(result));
            }
        } else {
            Log.w(TAG, "releaseVideoEncoder: facePreProcessor为null，无法评估质量");
        }
    }

    /**
     * 将帧写入编码器（异步执行，避免阻塞相机回调）
     */
    private void writeFrameToVideo(Bitmap bitmap) {
        if (!isRecording || mediaCodec == null) {
            return;
        }

        // 复制bitmap以便在后台线程使用
        final Bitmap bitmapCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        final long captureTimeNs = System.nanoTime();

        videoEncoderExecutor.execute(() -> {
            writeFrameToVideoInternal(bitmapCopy, captureTimeNs);
            bitmapCopy.recycle();
        });
    }

    /**
     * 实际的帧写入操作（在后台线程执行）
     */
    private void writeFrameToVideoInternal(Bitmap bitmap, long captureTimeNs) {
        synchronized (encoderLock) {
            if (!isRecording || mediaCodec == null) {
                return;
            }
            try {
                // 获取输入缓冲区（增加超时到100ms以减少丢帧）
                int inputBufferIndex = mediaCodec.dequeueInputBuffer(100000);
                if (inputBufferIndex >= 0) {
                    ByteBuffer inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex);
                    if (inputBuffer != null) {
                        inputBuffer.clear();
                        // 将 Bitmap 转换为 NV12 格式
                        byte[] yuv = bitmapToNv12(bitmap);
                        inputBuffer.put(yuv);
                        // 使用捕获时间戳（纳秒转微秒）
                        long presentationTimeUs = (captureTimeNs - recordingStartTimeNs) / 1000;
                        mediaCodec.queueInputBuffer(inputBufferIndex, 0, yuv.length, presentationTimeUs, 0);
                        encodedFrameCount++;
                        if (encodedFrameCount % 30 == 0) {
                            Log.d(TAG, "已编码帧数: " + encodedFrameCount + ", 丢帧: " + droppedFrameCount);
                        }
                    }
                } else {
                    droppedFrameCount++;
                    if (droppedFrameCount % 10 == 0) {
                        Log.w(TAG, "丢帧（无可用缓冲区）, 已丢弃: " + droppedFrameCount);
                    }
                }
                // 处理输出
                drainEncoder(false);
            } catch (Exception e) {
                Log.e(TAG, "写入帧失败", e);
            }
        }
    }

    /**
     * 从编码器读取输出数据并写入 Muxer
     * 注意：使用 ByteBuffer 模式时，不能调用 signalEndOfInputStream()
     * 需要通过发送带 BUFFER_FLAG_END_OF_STREAM 标志的空缓冲区来表示结束
     */
    private void drainEncoder(boolean endOfStream) {
        if (endOfStream && mediaCodec != null) {
            // ByteBuffer 模式：发送带 EOS 标志的空缓冲区
            try {
                int inputBufferIndex = mediaCodec.dequeueInputBuffer(10000);
                if (inputBufferIndex >= 0) {
                    long presentationTimeUs = (System.nanoTime() - recordingStartTimeNs) / 1000;
                    mediaCodec.queueInputBuffer(inputBufferIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
            } catch (Exception e) {
                Log.e(TAG, "发送 EOS 失败", e);
            }
        }

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (true) {
            int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000);
            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break;
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    Log.w(TAG, "Format changed after muxer started");
                } else {
                    MediaFormat newFormat = mediaCodec.getOutputFormat();
                    videoTrackIndex = mediaMuxer.addTrack(newFormat);
                    mediaMuxer.start();
                    muxerStarted = true;
                    Log.d(TAG, "Muxer started with format: " + newFormat);
                }
            } else if (outputBufferIndex >= 0) {
                ByteBuffer outputBuffer = mediaCodec.getOutputBuffer(outputBufferIndex);
                if (outputBuffer != null && muxerStarted && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    outputBuffer.position(bufferInfo.offset);
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                    mediaMuxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo);
                }
                mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }
    }

    /**
     * 将 Bitmap 转换为 NV12 (YUV420SemiPlanar) 格式
     * NV12: Y plane followed by interleaved UV plane
     */
    private byte[] bitmapToNv12(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] argb = new int[width * height];
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);

        byte[] yuv = new byte[width * height * 3 / 2];
        int yIndex = 0;
        int uvIndex = width * height;

        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int argbValue = argb[j * width + i];
                int r = (argbValue >> 16) & 0xFF;
                int g = (argbValue >> 8) & 0xFF;
                int b = argbValue & 0xFF;

                // RGB to YUV
                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;

                yuv[yIndex++] = (byte) Math.max(0, Math.min(255, y));

                // NV12: UV interleaved, subsampled 2x2
                if (j % 2 == 0 && i % 2 == 0) {
                    yuv[uvIndex++] = (byte) Math.max(0, Math.min(255, u));
                    yuv[uvIndex++] = (byte) Math.max(0, Math.min(255, v));
                }
            }
        }
        return yuv;
    }

    private final ImageReader.OnImageAvailableListener onImageAvailableListener = reader -> {
        Image image = null;
        try {
            image = reader.acquireNextImage();
            if (image != null && isCameraRunning) {
                // 获取 JPEG 数据
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);

                // 异步保存 JPEG 帧（不阻塞相机回调）
                final int currentFrameCount = frameCount++;
                final long ts = System.currentTimeMillis();
                fileWriteExecutor.execute(() -> {
                    try {
                        String filename = String.format("%d_frame_%06d.jpg", ts, currentFrameCount);
                        File f = new File(tempDir, filename);
                        try (FileOutputStream out = new FileOutputStream(f)) {
                            out.write(bytes);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "异步保存帧失败", e);
                    }
                });

                // 解码并旋转图像
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap != null) {
                    bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
                    Bitmap rotated = rotateBitmap(bitmap, 270);

                    // 写入视频帧（即使模型未加载也录制）
                    if (rotated != null && isRecording) {
                        writeFrameToVideo(rotated);
                    }

                    // 模型未加载完成时跳过AI处理
                    if (!isInitialized || facePreProcessor == null) {
                        return;
                    }

                    long timestamp = System.nanoTime();
                    faceMesh.send(rotated, timestamp);
                    final Bitmap finalRotated = rotated;
                    faceMesh.setResultListener(result -> {
                        if (isCameraRunning) {
                            facePreProcessor.addFrameResults(result, finalRotated.copy(Bitmap.Config.ARGB_8888, true));
                        }
                    });
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing image", e);
            if (callback != null) {
                callback.onError("Image processing error: " + e.getMessage());
            }
        } finally {
            if (image != null) {
                image.close();
            }
        }
    };


    public void startCamera() {
        if (!checkCameraPermission()) {
            return;
        }
        SharedPreferences prefs = activity.getSharedPreferences("AppSettings", Context.MODE_PRIVATE);
        String experimentId = prefs.getString("experiment_id", "default");
        final String baseDir = Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                + "/Sample/" + experimentId + "/"+"Inference_"+System.currentTimeMillis()+"/";
        tempDir = new File(baseDir + "frames_" + System.currentTimeMillis() + "/");
        if (!tempDir.exists()) tempDir.mkdirs();
        frameCount = 0;

        // 先启动摄像头预览（快速操作）
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = getFrontFacingCameraId(manager);
            if (cameraId == null) {
                Log.e(TAG, "No front-facing camera found.");
                if (callback != null) {
                    callback.onError("No front-facing camera found");
                }
                return;
            }

            manager.openCamera(cameraId, stateCallback, backgroundHandler);

            // 异步加载模型（避免ANR）
            initExecutor.execute(() -> {
                try {
                    Log.d(TAG, "开始异步加载ONNX模型...");
                    long startTime = System.currentTimeMillis();

                    AssetManager assetManager = activity.getAssets();
                    InputStream modelStream = assetManager.open("model.onnx");
                    InputStream stateJsonStream = assetManager.open("state.json");
                    InputStream welchModelStream = assetManager.open("welch_psd.onnx");
                    InputStream hrModelStream = assetManager.open("get_hr.onnx");

                    heartRateEstimator = new HeartRateEstimator(
                            modelStream,
                            stateJsonStream,
                            welchModelStream,
                            hrModelStream,
                            plotView,
                            baseDir
                    );

                    // 设置心率回调监听器
                    if (heartRateListener != null) {
                        heartRateEstimator.setOnHeartRateListener(heartRateListener);
                    }

                    facePreProcessor = new FacePreprocessor(activity, heartRateEstimator);
                    isInitialized = true;

                    long loadTime = System.currentTimeMillis() - startTime;
                    Log.d(TAG, "ONNX模型加载完成，耗时: " + loadTime + "ms");

                    // 通知初始化完成
                    mainHandler.post(() -> {
                        if (initListener != null) {
                            initListener.onInitialized();
                        }
                    });

                } catch (Exception e) {
                    Log.e(TAG, "模型加载失败", e);
                    mainHandler.post(() -> {
                        if (initListener != null) {
                            initListener.onInitializeFailed(e.getMessage());
                        }
                    });
                }
            });

        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access exception", e);
            if (callback != null) {
                callback.onError("Camera access error: " + e.getMessage());
            }
        }
    }

    public void stopCamera() {
        isCameraRunning = false;
        isInitialized = false;

        // 停止并释放 MediaCodec 编码器
        releaseVideoEncoder();

        // 关闭初始化线程池
        if (initExecutor != null && !initExecutor.isShutdown()) {
            initExecutor.shutdownNow();
        }

        // 关闭文件写入线程池
        if (fileWriteExecutor != null && !fileWriteExecutor.isShutdown()) {
            fileWriteExecutor.shutdown();
        }

        // 关闭视频编码线程池
        if (videoEncoderExecutor != null && !videoEncoderExecutor.isShutdown()) {
            videoEncoderExecutor.shutdown();
            try {
                // 等待编码任务完成
                videoEncoderExecutor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Log.w(TAG, "Video encoder shutdown interrupted");
            }
        }

        if (cameraCaptureSession != null) {
            cameraCaptureSession.close();
            cameraCaptureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        if (callback != null) {
            callback.onCameraStopped();
        }
    }
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            if (surfaceView.getWidth() > 0 && surfaceView.getHeight() > 0) {
                setupImageReader(surfaceView.getWidth(), surfaceView.getHeight());
                createCameraPreview();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            stopCamera();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            stopCamera();
            if (callback != null) {
                callback.onError("Camera device error: " + error);
            }
        }
    };

    private void createCameraPreview() {
        try {
            if (surfaceHolder == null || !surfaceHolder.getSurface().isValid()) {
                Log.e(TAG, "SurfaceHolder is not valid.");
                if (callback != null) {
                    callback.onError("Surface holder is not valid");
                }
                return;
            }

            // 初始化 MediaCodec 编码器（在 ImageReader 回调中写入帧，不需要额外 Surface）
            setupVideoEncoder();

            // 使用 TEMPLATE_PREVIEW（只需要2个Surface：预览 + ImageReader）
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(imageReader.getSurface());
            captureRequestBuilder.addTarget(surfaceHolder.getSurface());

            // 构建输出 Surface 列表（只有2个）
            java.util.List<Surface> outputSurfaces = new java.util.ArrayList<>();
            outputSurfaces.add(surfaceHolder.getSurface());
            outputSurfaces.add(imageReader.getSurface());

            cameraDevice.createCaptureSession(
                    outputSurfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            cameraCaptureSession = session;
                            updatePreview();
                            isCameraRunning = true;
                            Log.d(TAG, "Camera preview started, VideoWriter isRecording=" + isRecording);

                            if (callback != null) {
                                callback.onCameraStarted();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Camera configuration failed.");
                            if (callback != null) {
                                callback.onError("Camera configuration failed");
                            }
                        }
                    }, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access exception in createCameraPreview", e);
            if (callback != null) {
                callback.onError("Camera preview error: " + e.getMessage());
            }
        }
    }

    private void updatePreview() {
        if (cameraDevice == null) return;

        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(30, 30));

        try {
            cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access exception in updatePreview", e);
            if (callback != null) {
                callback.onError("Preview update error: " + e.getMessage());
            }
        }
    }

    private String getFrontFacingCameraId(CameraManager cameraManager) {
        try {
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    return cameraId;
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error getting front camera ID", e);
        }
        return null;
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping background thread", e);
            }
        }
    }

    private boolean checkCameraPermission() {
        return ActivityCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    // Public methods for external control
    public boolean isCameraRunning() {
        return isCameraRunning;
    }

    /**
     * 设置心率更新监听器
     */
    public void setOnHeartRateListener(HeartRateEstimator.OnHeartRateListener listener) {
        this.heartRateListener = listener;
        // 如果 HeartRateEstimator 已创建，直接设置监听器
        if (heartRateEstimator != null) {
            heartRateEstimator.setOnHeartRateListener(listener);
        }
    }

    /**
     * 设置预加载的HeartRateEstimator（跳过模型加载）
     */
    public void setPreloadedEstimator(HeartRateEstimator estimator, PlotView plotView) {
        this.heartRateEstimator = estimator;
        if (estimator != null) {
            estimator.setPlotView(plotView);
            if (heartRateListener != null) {
                estimator.setOnHeartRateListener(heartRateListener);
            }
            facePreProcessor = new FacePreprocessor(activity, heartRateEstimator);
            isInitialized = true;
        }
    }

    public  Bitmap convertYUVToBitmap(Image image, int rotationDegrees) {
        if (image == null || image.getFormat() != ImageFormat.YUV_420_888) {
            Log.e(TAG, "Invalid image format or null image.");
            return null;
        }

        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();

        ByteBuffer yBuffer = planes[0].getBuffer(); // Y
        ByteBuffer uBuffer = planes[1].getBuffer(); // U
        ByteBuffer vBuffer = planes[2].getBuffer(); // V

        int yRowStride = planes[0].getRowStride();
        int uvRowStride = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();

        int[] argb = new int[width * height];

        byte[] yBytes = new byte[yBuffer.remaining()];
        yBuffer.get(yBytes);

        byte[] uBytes = new byte[uBuffer.remaining()];
        uBuffer.get(uBytes);

        byte[] vBytes = new byte[vBuffer.remaining()];
        vBuffer.get(vBytes);

        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int yIndex = row * yRowStride + col;
                int uvRow = row / 2;
                int uvCol = col / 2;
                int uvIndex = uvRow * uvRowStride + uvCol * uvPixelStride;

                int y = yBytes[yIndex] & 0xFF;
                int u = uBytes[uvIndex] & 0xFF;
                int v = vBytes[uvIndex] & 0xFF;

                // Convert YUV to RGB
                int r = (int)(y + 1.370705f * (v - 128));
                int g = (int)(y - 0.337633f * (u - 128) - 0.698001f * (v - 128));
                int b = (int)(y + 1.732446f * (u - 128));

                r = Math.min(255, Math.max(0, r));
                g = Math.min(255, Math.max(0, g));
                b = Math.min(255, Math.max(0, b));

                argb[row * width + col] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(argb, 0, width, 0, 0, width, height);
        return rotateBitmap(bitmap, rotationDegrees);
    }
    private Bitmap rotateBitmap(Bitmap bitmap, int rotationDegrees) {
        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    public static Bitmap convertJPEGToBitmap(Image image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    /**
     * 设置视频质量评估结果监听器
     */
    public void setOnQualityResultListener(OnQualityResultListener listener) {
        this.qualityResultListener = listener;
    }

    /**
     * 获取视频质量评估结果（录制结束时调用）
     */
    public VideoQualityEvaluator.QualityResult getQualityResult() {
        if (facePreProcessor != null) {
            return facePreProcessor.evaluateQuality();
        }
        return null;
    }

    /**
     * 重置视频质量评估器（录制开始时自动调用）
     */
    private void resetQualityEvaluator() {
        if (facePreProcessor != null) {
            facePreProcessor.resetQualityEvaluator();
            Log.d(TAG, "质量评估器已重置");
        }
    }
}
