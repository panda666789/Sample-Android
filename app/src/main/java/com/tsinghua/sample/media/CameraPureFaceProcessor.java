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

public class CameraPureFaceProcessor {
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
    private static final int VIDEO_WIDTH = 640;  // 相机原生宽度（不旋转）
    private static final int VIDEO_HEIGHT = 480; // 相机原生高度（不旋转）
    private final Object encoderLock = new Object();

    // 帧计数统计（用于调试）
    private int encodedFrameCount = 0;
    private int droppedFrameCount = 0;

    // 视频编码线程池（使用有界队列，防止内存堆积）
    // 编码线程优先级在首次执行任务时设置
    private final java.util.concurrent.ArrayBlockingQueue<Runnable> encoderQueue =
            new java.util.concurrent.ArrayBlockingQueue<>(10);  // 增加到10帧缓存
    private volatile boolean encoderPrioritySet = false;  // 标记是否已设置编码线程优先级
    private final ExecutorService videoEncoderExecutor = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            encoderQueue,
            r -> {
                Thread t = new Thread(r, "VideoEncoderThread");
                t.setPriority(Thread.MAX_PRIORITY);  // Java线程优先级
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy()  // 队列满时直接丢弃
    );

    // NV12 缓冲区复用（避免频繁 GC）
    private byte[] nv12Buffer = null;

    // AI处理帧率控制（每N帧处理一次AI，减少CPU负载）
    private static final int AI_PROCESS_INTERVAL = 5;  // 每5帧处理1次AI
    private int frameIndex = 0;

    // AI处理同步控制（防止回调堆积）
    private volatile Bitmap pendingBitmap = null;  // 待处理的bitmap
    private final Object bitmapLock = new Object();
    private volatile boolean isAIProcessing = false;  // 是否正在处理AI
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

    public CameraPureFaceProcessor(Context activity, SurfaceView surfaceView, PlotView plotView)  {

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

        // 只设置一次 ResultListener（避免每帧重复设置导致回调堆积）
        faceMesh.setResultListener(result -> {
            Bitmap bitmapToProcess = null;
            synchronized (bitmapLock) {
                if (pendingBitmap != null) {
                    bitmapToProcess = pendingBitmap;
                    pendingBitmap = null;  // 取出后清空
                }
            }

            if (bitmapToProcess != null && isCameraRunning && facePreProcessor != null) {
                try {
                    facePreProcessor.addFrameResults(result, bitmapToProcess);
                    // 注意：bitmapToProcess 传给 facePreProcessor 后由其负责回收
                } catch (Exception e) {
                    Log.e(TAG, "FaceMesh result处理异常", e);
                    bitmapToProcess.recycle();  // 异常时回收
                }
            } else if (bitmapToProcess != null) {
                bitmapToProcess.recycle();  // 不需要处理时回收
            }

            isAIProcessing = false;  // 标记处理完成
        });
    }

    private void setupImageReader(int width, int height) {
        if (width > 0 && height > 0) {
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 4);
            imageReader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler);
        } else {
            Log.e(TAG, "Invalid dimensions for ImageReader: width=" + width + ", height=" + height);
            if (callback != null) {
                callback.onError("Invalid ImageReader dimensions");
            }
        }
    }

    /**
     * 初始化 MediaCodec + MediaMuxer 用于 mp4 录制
     */
    private void setupVideoEncoder() {
        try {
            // 先释放可能存在的旧编码器（防止资源泄漏）
            synchronized (encoderLock) {
                if (mediaCodec != null || mediaMuxer != null) {
                    Log.w(TAG, "setupVideoEncoder: 发现未释放的旧编码器，先释放");
                    releaseVideoEncoderInternal();
                }
                // 重置编码线程优先级标记（确保新录制时重新设置）
                encoderPrioritySet = false;
            }

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
                // 设置视频旋转角度（前置摄像头需要270度旋转）
                // 这样播放器会自动旋转视频，而不需要在编码时旋转
                mediaMuxer.setOrientationHint(270);
                videoTrackIndex = -1;
                muxerStarted = false;
                recordingStartTimeNs = System.nanoTime();  // 记录开始时间
                encodedFrameCount = 0;  // 重置帧计数
                droppedFrameCount = 0;
                isRecording = true;

                Log.d(TAG, "MediaCodec + MediaMuxer 初始化成功: " + currentVideoPath);
            }
        } catch (Exception e) {
            Log.e(TAG, "MediaCodec 初始化异常", e);
            releaseVideoEncoder();
        }
    }

    /**
     * 释放 MediaCodec + MediaMuxer 资源（异步执行，避免阻塞主线程）
     */
    private void releaseVideoEncoder() {
        Log.d(TAG, "releaseVideoEncoder called, isRecording=" + isRecording);
        Log.d(TAG, "录制统计 - 总编码帧数: " + encodedFrameCount + ", 丢帧数: " + droppedFrameCount);

        // 先标记停止录制，防止新帧进入
        synchronized (encoderLock) {
            isRecording = false;
        }

        // 在后台线程执行释放操作，避免阻塞主线程导致ANR
        new Thread(() -> {
            synchronized (encoderLock) {
                releaseVideoEncoderInternal();
            }
        }, "VideoEncoderRelease").start();
    }

    /**
     * 内部释放方法（必须在 encoderLock 同步块内调用）
     */
    private void releaseVideoEncoderInternal() {
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

        // 使用非阻塞模式（0超时），除非是结束时需要等待
        long timeoutUs = endOfStream ? 10000 : 0;
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        // 限制单次调用最多处理的输出缓冲区数量，避免阻塞太久
        int maxOutputBuffers = endOfStream ? 100 : 5;
        int processedBuffers = 0;

        while (processedBuffers < maxOutputBuffers) {
            int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, timeoutUs);
            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break;
                // endOfStream时继续等待
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
                processedBuffers++;
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

    /**
     * 直接从 YUV_420_888 Image 转换为 NV12 格式（带旋转）
     * 避免中间 ARGB 转换，性能更好
     * @param image YUV_420_888 格式的 Image
     * @param rotationDegrees 旋转角度（仅支持 0, 90, 180, 270）
     * @return NV12 格式的字节数组
     */
    private byte[] imageToNv12WithRotation(Image image, int rotationDegrees) {
        int srcWidth = image.getWidth();
        int srcHeight = image.getHeight();

        // 计算旋转后的尺寸
        int dstWidth, dstHeight;
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            dstWidth = srcHeight;
            dstHeight = srcWidth;
        } else {
            dstWidth = srcWidth;
            dstHeight = srcHeight;
        }

        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int yRowStride = planes[0].getRowStride();
        int uvRowStride = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();

        byte[] nv12 = new byte[dstWidth * dstHeight * 3 / 2];
        int yDstIndex = 0;
        int uvDstIndex = dstWidth * dstHeight;

        // 根据旋转角度计算源像素位置
        for (int dstRow = 0; dstRow < dstHeight; dstRow++) {
            for (int dstCol = 0; dstCol < dstWidth; dstCol++) {
                int srcRow, srcCol;

                switch (rotationDegrees) {
                    case 90:
                        srcRow = dstWidth - 1 - dstCol;
                        srcCol = dstRow;
                        break;
                    case 180:
                        srcRow = dstHeight - 1 - dstRow;
                        srcCol = dstWidth - 1 - dstCol;
                        break;
                    case 270:
                        srcRow = dstCol;
                        srcCol = dstHeight - 1 - dstRow;
                        break;
                    default: // 0
                        srcRow = dstRow;
                        srcCol = dstCol;
                        break;
                }

                // Y 分量
                int yIndex = srcRow * yRowStride + srcCol;
                nv12[yDstIndex++] = yBuffer.get(yIndex);

                // UV 分量（2x2 下采样）
                if (dstRow % 2 == 0 && dstCol % 2 == 0) {
                    int uvRow = srcRow / 2;
                    int uvCol = srcCol / 2;
                    int uvIndex = uvRow * uvRowStride + uvCol * uvPixelStride;

                    nv12[uvDstIndex++] = uBuffer.get(uvIndex);
                    nv12[uvDstIndex++] = vBuffer.get(uvIndex);
                }
            }
        }

        return nv12;
    }

    /**
     * 直接从 YUV 数据写入视频（不做旋转，用 MediaMuxer orientation hint）
     */
    private void writeYuvToVideo(Image image) {
        if (!isRecording || mediaCodec == null) {
            return;
        }

        // 检查队列是否满，满则直接丢弃这帧（避免无意义的内存分配）
        if (encoderQueue.remainingCapacity() == 0) {
            droppedFrameCount++;
            return;
        }

        final long captureTimeNs = System.nanoTime();

        // 复用或创建 NV12 缓冲区
        int bufferSize = VIDEO_WIDTH * VIDEO_HEIGHT * 3 / 2;
        if (nv12Buffer == null || nv12Buffer.length != bufferSize) {
            nv12Buffer = new byte[bufferSize];
        }

        // 直接复制到缓冲区
        imageToNv12FastInPlace(image, nv12Buffer);

        // 复制一份用于异步编码（因为下一帧会覆盖 nv12Buffer）
        final byte[] nv12Data = nv12Buffer.clone();

        videoEncoderExecutor.execute(() -> {
            writeNv12ToVideoInternal(nv12Data, captureTimeNs);
        });
    }

    /**
     * 快速 YUV_420_888 转 NV12（原地写入，不分配新数组）
     */
    private void imageToNv12FastInPlace(Image image, byte[] nv12) {
        int width = image.getWidth();
        int height = image.getHeight();

        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int yRowStride = planes[0].getRowStride();
        int uvRowStride = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();

        // Copy Y plane
        int yPos = 0;
        for (int row = 0; row < height; row++) {
            yBuffer.position(row * yRowStride);
            yBuffer.get(nv12, yPos, width);
            yPos += width;
        }

        // Copy UV plane (interleaved)
        int uvPos = width * height;
        for (int row = 0; row < height / 2; row++) {
            for (int col = 0; col < width / 2; col++) {
                int uvIndex = row * uvRowStride + col * uvPixelStride;
                nv12[uvPos++] = uBuffer.get(uvIndex);
                nv12[uvPos++] = vBuffer.get(uvIndex);
            }
        }

        // 重置 buffer position
        yBuffer.rewind();
        uBuffer.rewind();
        vBuffer.rewind();
    }

    /**
     * 将 NV12 数据写入编码器
     */
    private void writeNv12ToVideoInternal(byte[] nv12Data, long captureTimeNs) {
        // 首次执行时设置线程优先级（只执行一次）
        if (!encoderPrioritySet) {
            encoderPrioritySet = true;
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            Log.d(TAG, "编码线程优先级已设置为 URGENT_AUDIO");
        }

        synchronized (encoderLock) {
            if (!isRecording || mediaCodec == null) {
                if (mediaCodec == null && isRecording) {
                    Log.e(TAG, "编码器异常: isRecording=true 但 mediaCodec=null!");
                }
                return;
            }
            try {
                int inputBufferIndex = mediaCodec.dequeueInputBuffer(5000);  // 5ms 超时（进一步减少阻塞）
                if (inputBufferIndex >= 0) {
                    ByteBuffer inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex);
                    if (inputBuffer != null) {
                        inputBuffer.clear();
                        inputBuffer.put(nv12Data);
                        long presentationTimeUs = (captureTimeNs - recordingStartTimeNs) / 1000;
                        mediaCodec.queueInputBuffer(inputBufferIndex, 0, nv12Data.length, presentationTimeUs, 0);
                        encodedFrameCount++;
                        if (encodedFrameCount % 30 == 0) {  // 每30帧打印一次
                            Log.d(TAG, "视频编码帧数: " + encodedFrameCount + ", 丢帧: " + droppedFrameCount);
                        }
                    }
                } else {
                    droppedFrameCount++;
                    if (droppedFrameCount % 30 == 0) {
                        Log.w(TAG, "编码器丢帧(无可用缓冲): " + droppedFrameCount);
                    }
                }
                drainEncoder(false);
            } catch (IllegalStateException e) {
                Log.e(TAG, "编码器状态异常 - 可能已被释放", e);
            } catch (Exception e) {
                Log.e(TAG, "NV12写入失败", e);
            }
        }
    }

    private final ImageReader.OnImageAvailableListener onImageAvailableListener = reader -> {
        Image image = null;
        try {
            image = reader.acquireNextImage();
            if (image != null && isCameraRunning) {
                frameIndex++;

                // 1. 视频编码：直接从 YUV 写入，不经过 Bitmap（每帧都编码）
                // 使用 MediaMuxer orientation hint 处理旋转，不在转换时旋转
                if (isRecording) {
                    writeYuvToVideo(image);
                }

                // 2. AI 处理：只在每 N 帧执行一次（降低CPU负载）
                // 并且只有上一帧处理完成后才处理新帧（防止堆积）
                boolean shouldProcessAI = (frameIndex % AI_PROCESS_INTERVAL == 0);

                if (shouldProcessAI && isInitialized && facePreProcessor != null && !isAIProcessing) {
                    // 标记开始处理（防止并发）
                    isAIProcessing = true;

                    // 只有需要 AI 处理时才创建 Bitmap
                    Bitmap bitmap = convertYUVToBitmap(image, 270);
                    if (bitmap != null) {
                        // 回收旧的pending bitmap（如果有的话）
                        synchronized (bitmapLock) {
                            if (pendingBitmap != null) {
                                pendingBitmap.recycle();
                            }
                            pendingBitmap = bitmap;  // 设置新的待处理bitmap
                        }

                        long timestamp = System.nanoTime();
                        faceMesh.send(bitmap, timestamp);
                        // 注意：ResultListener 已经在 setupFaceMesh 中设置，这里不再重复设置
                    } else {
                        isAIProcessing = false;  // bitmap创建失败，重置标记
                    }
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

        // 重置质量评估器和帧计数
        resetQualityEvaluator();
        frameIndex = 0;

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

                    // 通知初始化完成，并启动视频编码器
                    mainHandler.post(() -> {
                        // 模型加载完成后再启动视频编码器，确保质量评估统计完整
                        resetQualityEvaluator();  // 重置质量评估器
                        setupVideoEncoder();
                        Log.d(TAG, "视频编码器已启动（模型加载后）");

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

        // 清理待处理的 Bitmap（防止内存泄漏）
        synchronized (bitmapLock) {
            if (pendingBitmap != null) {
                pendingBitmap.recycle();
                pendingBitmap = null;
            }
        }
        isAIProcessing = false;

        // 停止并释放 MediaCodec 编码器
        releaseVideoEncoder();

        // 关闭初始化线程池
        if (initExecutor != null && !initExecutor.isShutdown()) {
            initExecutor.shutdownNow();
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

        // 计算并回调视频质量评估结果
        Log.i(TAG, "stopCamera: 开始计算质量评估, facePreProcessor=" + facePreProcessor);
        if (facePreProcessor != null) {
            VideoQualityEvaluator.QualityResult result = facePreProcessor.evaluateQuality();
            Log.i(TAG, "视频质量评估完成:\n" + result.toString());

            // 将质量评估报告写入 txt 文件
            saveQualityReportToFile(result);

            Log.i(TAG, "qualityResultListener=" + qualityResultListener);
            if (qualityResultListener != null) {
                mainHandler.post(() -> qualityResultListener.onQualityResult(result));
            }
        } else {
            Log.w(TAG, "stopCamera: facePreProcessor为null，无法评估质量");
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

            // 如果模型已经预加载完成（setPreloadedEstimator），立即启动视频编码器
            // 否则等待异步加载完成后再启动
            if (isInitialized && facePreProcessor != null) {
                resetQualityEvaluator();
                setupVideoEncoder();
                Log.d(TAG, "视频编码器已启动（预加载模型）");
            }

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
     * 获取视频质量评估结果
     */
    public VideoQualityEvaluator.QualityResult getQualityResult() {
        if (facePreProcessor != null) {
            return facePreProcessor.evaluateQuality();
        }
        return null;
    }

    /**
     * 重置视频质量评估器
     */
    public void resetQualityEvaluator() {
        if (facePreProcessor != null) {
            facePreProcessor.resetQualityEvaluator();
            Log.d(TAG, "质量评估器已重置");
        }
    }

    /**
     * 将质量评估报告写入 txt 文件
     */
    private void saveQualityReportToFile(VideoQualityEvaluator.QualityResult result) {
        if (currentVideoPath == null || result == null) {
            Log.w(TAG, "无法保存质量报告：视频路径或结果为空");
            return;
        }

        try {
            // 从视频路径获取 front 目录
            File videoFile = new File(currentVideoPath);
            File frontDir = videoFile.getParentFile();

            if (frontDir == null || !frontDir.exists()) {
                Log.w(TAG, "front 目录不存在");
                return;
            }

            // 生成报告文件名（与视频文件名对应）
            String videoName = videoFile.getName().replace(".mp4", "");
            String reportFileName = videoName + "_quality_report.txt";
            File reportFile = new File(frontDir, reportFileName);

            // 生成报告内容
            StringBuilder report = new StringBuilder();
            report.append("========== 视频质量评估报告 ==========\n");
            report.append("生成时间: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date())).append("\n");
            report.append("视频文件: ").append(videoFile.getName()).append("\n");
            report.append("\n");
            report.append("---------- 基础统计 ----------\n");
            report.append("总帧数: ").append(result.totalFrames).append("\n");
            report.append("有效帧数: ").append(result.validFrames).append("\n");
            report.append("\n");
            report.append("---------- 质量指标 ----------\n");
            report.append(String.format(java.util.Locale.getDefault(), "人脸检测率: %.1f%%\n", result.faceDetectionRate * 100));
            report.append(String.format(java.util.Locale.getDefault(), "平均人脸面积: %.1f%%\n", result.avgFaceAreaRatio * 100));
            report.append(String.format(java.util.Locale.getDefault(), "位置稳定性: %.1f%%\n", result.positionStability * 100));
            report.append(String.format(java.util.Locale.getDefault(), "平均亮度: %.0f\n", result.avgBrightness));
            report.append(String.format(java.util.Locale.getDefault(), "亮度稳定性: %.1f%%\n", result.brightnessStability * 100));
            report.append("\n");
            report.append("---------- 综合评估 ----------\n");
            report.append(String.format(java.util.Locale.getDefault(), "综合评分: %.0f 分\n", result.overallScore));
            report.append("质量等级: ").append(result.qualityLevel).append("\n");
            report.append("\n");
            report.append("========================================\n");

            // 写入文件
            java.io.FileWriter writer = new java.io.FileWriter(reportFile);
            writer.write(report.toString());
            writer.close();

            Log.i(TAG, "质量评估报告已保存: " + reportFile.getAbsolutePath());

        } catch (Exception e) {
            Log.e(TAG, "保存质量评估报告失败", e);
        }
    }
}
