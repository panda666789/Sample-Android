package com.tsinghua.sample.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.mediapipe.formats.proto.LandmarkProto;
import com.google.mediapipe.solutions.facemesh.FaceMesh;
import com.google.mediapipe.solutions.facemesh.FaceMeshOptions;
import com.google.mediapipe.solutions.facemesh.FaceMeshResult;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 视频后处理器：在录制结束后对视频进行离线分析
 * - 解码视频并均匀采样帧
 * - 使用FaceMesh检测人脸
 * - 通过HeartRateEstimator进行心率推理
 * - 与血氧仪数据对比并生成报告
 */
public class VideoPostProcessor {
    private static final String TAG = "VideoPostProcessor";

    // 目标采样帧率（模拟30fps输入）
    private static final float TARGET_FPS = 30f;
    private static final long FRAME_INTERVAL_US = (long) (1_000_000 / TARGET_FPS);  // 微秒

    // 不再限制帧数，改用流式处理避免OOM

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private FaceMesh faceMesh;
    private HeartRateEstimator heartRateEstimator;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private volatile boolean isCancelled = false;

    // 用于人脸检测的同步机制（避免每帧重复设置 resultListener）
    private volatile CountDownLatch faceLatch;
    private final AtomicReference<FaceDetectionResult> faceResultRef = new AtomicReference<>();

    /**
     * 处理结果
     */
    public static class PostProcessResult {
        public int totalFrames;           // 总帧数
        public int validFrames;           // 有效帧数（检测到人脸）
        public float faceDetectionRate;   // 人脸检测率
        public float avgFaceAreaRatio;    // 平均人脸面积比例
        public float positionStability;   // 位置稳定性
        public float aiHeartRate;         // AI推理心率
        public float oximeterHeartRate;   // 血氧仪心率
        public float heartRateDiff;       // 心率差值
        public int overallScore;          // 综合评分
        public String qualityLevel;       // 质量等级
        public String conclusion;         // 结论
        public boolean isValid;           // 结果是否有效
        public String errorMessage;       // 错误信息
        public boolean hasOximeterData;   // 是否有血氧仪数据

        public PostProcessResult() {
            isValid = false;
            hasOximeterData = false;
        }
    }

    /**
     * 进度回调
     */
    public interface OnProgressListener {
        void onProgress(int current, int total, String message);
        void onComplete(PostProcessResult result);
        void onError(String error);
    }

    public VideoPostProcessor(Context context) {
        this.context = context;
    }

    /**
     * 开始处理视频
     * @param videoPath 视频文件路径
     * @param frontDir front目录路径（用于保存报告和读取血氧仪数据）
     * @param spo2Dir spo2目录路径（用于读取血氧仪数据）
     * @param listener 进度监听器
     */
    public void processVideo(String videoPath, String frontDir, String spo2Dir,
                            InputStream modelStream, InputStream stateJsonStream,
                            InputStream welchModelStream, InputStream hrModelStream,
                            OnProgressListener listener) {
        if (isProcessing.get()) {
            listener.onError("已有处理任务在进行中");
            return;
        }

        isProcessing.set(true);
        isCancelled = false;

        executor.execute(() -> {
            PostProcessResult result = new PostProcessResult();
            try {
                processVideoInternal(videoPath, frontDir, spo2Dir,
                                   modelStream, stateJsonStream, welchModelStream, hrModelStream,
                                   result, listener);
            } catch (Exception e) {
                Log.e(TAG, "视频处理失败", e);
                result.errorMessage = e.getMessage();
                notifyError(listener, "处理失败: " + e.getMessage());
            } finally {
                cleanup();
                isProcessing.set(false);
            }
        });
    }

    /**
     * 取消处理
     */
    public void cancel() {
        isCancelled = true;
    }

    /**
     * 是否正在处理
     */
    public boolean isProcessing() {
        return isProcessing.get();
    }

    private void processVideoInternal(String videoPath, String frontDir, String spo2Dir,
                                      InputStream modelStream, InputStream stateJsonStream,
                                      InputStream welchModelStream, InputStream hrModelStream,
                                      PostProcessResult result, OnProgressListener listener) throws Exception {
        // 1. 初始化FaceMesh（静态模式）
        notifyProgress(listener, 0, 100, "初始化人脸检测...");
        initFaceMesh();

        // 2. 初始化HeartRateEstimator
        notifyProgress(listener, 5, 100, "初始化心率模型...");
        heartRateEstimator = new HeartRateEstimator(
                modelStream, stateJsonStream, welchModelStream, hrModelStream,
                null,  // 不需要PlotView
                frontDir  // 日志输出到front目录
        );

        // 3. 流式处理：边解码边处理，避免存储所有帧导致OOM
        notifyProgress(listener, 10, 100, "开始处理视频...");

        List<RectF> facePositions = new ArrayList<>();
        List<Float> faceAreas = new ArrayList<>();
        List<Float> heartRateValues = new ArrayList<>();
        int[] frameCounters = new int[2];  // [0]=totalFrames, [1]=validFrames

        // 流式处理回调
        FrameProcessor frameProcessor = (bitmap, timestampMs, frameIndex, totalFrames) -> {
            if (isCancelled) return;

            int progress = 10 + (int) ((frameIndex + 1) * 80.0 / totalFrames);
            notifyProgress(listener, progress, 100,
                    String.format(Locale.US, "处理帧 %d/%d", frameIndex + 1, totalFrames));

            frameCounters[0] = totalFrames;

            try {
                // 人脸检测
                FaceDetectionResult faceResult = detectFace(bitmap);

                if (faceResult != null && faceResult.bounds != null) {
                    frameCounters[1]++;
                    facePositions.add(faceResult.bounds);
                    faceAreas.add(calculateFaceArea(faceResult.bounds));

                    // 预处理人脸区域
                    float[][][] faceFrame = preprocessFace(bitmap, faceResult.bounds);

                    if (faceFrame != null) {
                        // AI推理
                        Float hr = heartRateEstimator.estimateFromFrame(faceFrame, timestampMs);
                        if (hr != null) {
                            heartRateValues.add(hr);
                            Log.d(TAG, "帧 " + frameIndex + " 心率: " + hr);
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "帧处理失败: " + frameIndex, e);
            } finally {
                // 立即回收bitmap
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
            }
        };

        // 执行流式解码和处理
        boolean success = decodeAndProcessVideo(videoPath, listener, frameProcessor);

        if (isCancelled) {
            result.errorMessage = "用户取消";
            return;
        }

        if (!success || frameCounters[0] == 0) {
            result.errorMessage = "无法解码视频帧";
            notifyError(listener, "无法解码视频帧");
            return;
        }

        result.totalFrames = frameCounters[0];
        int validFrameCount = frameCounters[1];

        // 5. 计算质量指标
        result.validFrames = validFrameCount;
        result.faceDetectionRate = result.totalFrames > 0 ?
                (float) validFrameCount / result.totalFrames * 100f : 0f;

        // 计算平均人脸面积
        if (!faceAreas.isEmpty()) {
            float sum = 0f;
            for (Float area : faceAreas) {
                sum += area;
            }
            result.avgFaceAreaRatio = sum / faceAreas.size() * 100f;
        }

        // 计算位置稳定性
        result.positionStability = calculatePositionStability(facePositions);

        // AI心率 - 使用所有计算结果的平均值
        if (!heartRateValues.isEmpty()) {
            float sum = 0f;
            for (Float hr : heartRateValues) {
                sum += hr;
            }
            result.aiHeartRate = sum / heartRateValues.size();
            Log.d(TAG, "心率计算次数: " + heartRateValues.size() + ", 平均心率: " + result.aiHeartRate);
        } else {
            result.aiHeartRate = 0f;
        }

        // 6. 读取血氧仪心率数据
        notifyProgress(listener, 92, 100, "读取血氧仪数据...");
        result.oximeterHeartRate = readOximeterHeartRate(spo2Dir);
        result.hasOximeterData = result.oximeterHeartRate > 0;

        // 7. 计算心率差值（仅在有血氧仪数据时）
        if (result.aiHeartRate > 0 && result.hasOximeterData) {
            result.heartRateDiff = Math.abs(result.aiHeartRate - result.oximeterHeartRate);
        }

        // 8. 计算综合评分
        result.overallScore = calculateOverallScore(result);
        result.qualityLevel = getQualityLevel(result.overallScore);
        result.conclusion = generateConclusion(result);
        result.isValid = true;

        // 9. 保存详细报告
        notifyProgress(listener, 95, 100, "保存报告...");
        saveDetailedReport(frontDir, videoPath, result);

        // 10. 完成
        notifyProgress(listener, 100, 100, "处理完成");
        notifyComplete(listener, result);
    }

    /**
     * 初始化FaceMesh（静态图像模式）
     */
    private void initFaceMesh() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Exception> error = new AtomicReference<>();

        mainHandler.post(() -> {
            try {
                faceMesh = new FaceMesh(
                        context,
                        FaceMeshOptions.builder()
                                .setStaticImageMode(true)  // 静态图像模式
                                .setRefineLandmarks(false)
                                .setRunOnGpu(true)  // 使用GPU，与实时处理保持一致
                                .setMaxNumFaces(1)
                                .build());

                faceMesh.setErrorListener((message, e) -> {
                    Log.e(TAG, "FaceMesh error: " + message, e);
                });

                // 只设置一次 resultListener（避免每帧重复设置导致回调竞态）
                faceMesh.setResultListener(result -> {
                    try {
                        if (result != null && !result.multiFaceLandmarks().isEmpty()) {
                            List<LandmarkProto.NormalizedLandmark> landmarks =
                                    result.multiFaceLandmarks().get(0).getLandmarkList();
                            RectF bounds = calculateBoundingBox(landmarks);
                            faceResultRef.set(new FaceDetectionResult(bounds, landmarks));
                        } else {
                            faceResultRef.set(null);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "处理人脸检测结果失败", e);
                        faceResultRef.set(null);
                    } finally {
                        if (faceLatch != null) {
                            faceLatch.countDown();
                        }
                    }
                });
            } catch (Exception e) {
                error.set(e);
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new Exception("FaceMesh初始化超时");
        }

        if (error.get() != null) {
            throw error.get();
        }
    }

    /**
     * 帧数据类
     */
    private static class FrameData {
        Bitmap bitmap;
        long timestampMs;

        FrameData(Bitmap bitmap, long timestampMs) {
            this.bitmap = bitmap;
            this.timestampMs = timestampMs;
        }
    }

    /**
     * 人脸检测结果
     */
    private static class FaceDetectionResult {
        RectF bounds;
        List<LandmarkProto.NormalizedLandmark> landmarks;

        FaceDetectionResult(RectF bounds, List<LandmarkProto.NormalizedLandmark> landmarks) {
            this.bounds = bounds;
            this.landmarks = landmarks;
        }
    }

    /**
     * 帧处理回调接口
     */
    private interface FrameProcessor {
        void process(Bitmap bitmap, long timestampMs, int frameIndex, int totalFrames);
    }

    /**
     * 流式解码并处理视频（边解码边处理，避免OOM）
     */
    private boolean decodeAndProcessVideo(String videoPath, OnProgressListener listener, FrameProcessor processor) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;

        try {
            extractor.setDataSource(videoPath);

            // 找到视频轨道
            int videoTrackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat trackFormat = extractor.getTrackFormat(i);
                String mime = trackFormat.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrackIndex = i;
                    format = trackFormat;
                    break;
                }
            }

            if (videoTrackIndex < 0 || format == null) {
                Log.e(TAG, "找不到视频轨道");
                return false;
            }

            extractor.selectTrack(videoTrackIndex);

            // 获取视频信息
            String mime = format.getString(MediaFormat.KEY_MIME);
            int width = format.getInteger(MediaFormat.KEY_WIDTH);
            int height = format.getInteger(MediaFormat.KEY_HEIGHT);
            long durationUs = format.getLong(MediaFormat.KEY_DURATION);

            // 获取视频旋转角度
            int rotation = 0;
            if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                rotation = format.getInteger(MediaFormat.KEY_ROTATION);
            }

            Log.d(TAG, String.format("视频信息: %dx%d, 时长: %.2f秒, 旋转: %d度",
                    width, height, durationUs / 1_000_000.0, rotation));

            // 创建解码器
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, null, null, 0);
            decoder.start();

            // 计算总帧数（不再限制）
            int totalFrameCount = (int) (durationUs / FRAME_INTERVAL_US);
            long sampleIntervalUs = durationUs / totalFrameCount;
            final int videoRotation = rotation;

            Log.d(TAG, String.format("预计处理帧数: %d", totalFrameCount));

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long nextSampleTimeUs = 0;
            int processedFrames = 0;

            while (!outputDone && !isCancelled) {
                // 输入数据
                if (!inputDone) {
                    int inputBufferIndex = decoder.dequeueInputBuffer(10000);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferIndex);
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);

                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long presentationTimeUs = extractor.getSampleTime();
                            decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize,
                                    presentationTimeUs, 0);
                            extractor.advance();
                        }
                    }
                }

                // 输出数据
                int outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000);
                if (outputBufferIndex >= 0) {
                    long currentTimeUs = bufferInfo.presentationTimeUs;

                    if (currentTimeUs >= nextSampleTimeUs) {
                        // 获取帧数据并转换为Bitmap
                        Image image = decoder.getOutputImage(outputBufferIndex);
                        if (image != null) {
                            Bitmap bitmap = imageToBitmap(image, width, height, videoRotation);
                            if (bitmap != null) {
                                // 立即处理此帧
                                processor.process(bitmap, currentTimeUs / 1000, processedFrames, totalFrameCount);
                                processedFrames++;
                            }
                            image.close();
                        }
                        nextSampleTimeUs += sampleIntervalUs;
                    }

                    decoder.releaseOutputBuffer(outputBufferIndex, false);

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "输出格式变化");
                }
            }

            Log.d(TAG, String.format("处理完成，共 %d 帧", processedFrames));
            return processedFrames > 0;

        } catch (Exception e) {
            Log.e(TAG, "视频解码失败", e);
            return false;
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                    decoder.release();
                } catch (Exception e) {
                    Log.w(TAG, "释放解码器失败", e);
                }
            }
            extractor.release();
        }
    }

    /**
     * 均匀解码视频帧（保留旧方法以备用）
     */
    @Deprecated
    private List<FrameData> decodeVideoUniformly(String videoPath, OnProgressListener listener) {
        List<FrameData> frames = new ArrayList<>();
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;

        try {
            extractor.setDataSource(videoPath);

            // 找到视频轨道
            int videoTrackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat trackFormat = extractor.getTrackFormat(i);
                String mime = trackFormat.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrackIndex = i;
                    format = trackFormat;
                    break;
                }
            }

            if (videoTrackIndex < 0 || format == null) {
                Log.e(TAG, "找不到视频轨道");
                return frames;
            }

            extractor.selectTrack(videoTrackIndex);

            // 获取视频信息
            String mime = format.getString(MediaFormat.KEY_MIME);
            int width = format.getInteger(MediaFormat.KEY_WIDTH);
            int height = format.getInteger(MediaFormat.KEY_HEIGHT);
            long durationUs = format.getLong(MediaFormat.KEY_DURATION);

            // 获取视频旋转角度
            int rotation = 0;
            if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                rotation = format.getInteger(MediaFormat.KEY_ROTATION);
            }

            Log.d(TAG, String.format("视频信息: %dx%d, 时长: %.2f秒, 旋转: %d度",
                    width, height, durationUs / 1_000_000.0, rotation));

            // 创建解码器
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, null, null, 0);
            decoder.start();

            // 均匀采样
            int targetFrameCount = (int) (durationUs / FRAME_INTERVAL_US);
            long sampleIntervalUs = durationUs / targetFrameCount;
            final int videoRotation = rotation;  // 用于lambda表达式

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long nextSampleTimeUs = 0;
            int extractedFrames = 0;

            while (!outputDone && !isCancelled) {
                // 输入数据
                if (!inputDone) {
                    int inputBufferIndex = decoder.dequeueInputBuffer(10000);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferIndex);
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);

                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long presentationTimeUs = extractor.getSampleTime();
                            decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize,
                                    presentationTimeUs, 0);
                            extractor.advance();
                        }
                    }
                }

                // 输出数据
                int outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000);
                if (outputBufferIndex >= 0) {
                    // 检查是否需要采样此帧
                    long currentTimeUs = bufferInfo.presentationTimeUs;

                    if (currentTimeUs >= nextSampleTimeUs) {
                        // 获取帧数据并转换为Bitmap
                        Image image = decoder.getOutputImage(outputBufferIndex);
                        if (image != null) {
                            Bitmap bitmap = imageToBitmap(image, width, height, videoRotation);
                            if (bitmap != null) {
                                frames.add(new FrameData(bitmap, currentTimeUs / 1000));
                                extractedFrames++;

                                // 更新进度
                                int progress = 10 + (int) (extractedFrames * 20.0 / targetFrameCount);
                                progress = Math.min(progress, 30);
                                notifyProgress(listener, progress, 100,
                                        String.format(Locale.US, "解码帧 %d/%d", extractedFrames, targetFrameCount));
                            }
                            image.close();
                        }
                        nextSampleTimeUs += sampleIntervalUs;
                    }

                    decoder.releaseOutputBuffer(outputBufferIndex, false);

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "输出格式变化");
                }
            }

            Log.d(TAG, String.format("解码完成，共 %d 帧", frames.size()));

        } catch (Exception e) {
            Log.e(TAG, "视频解码失败", e);
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                    decoder.release();
                } catch (Exception e) {
                    Log.w(TAG, "释放解码器失败", e);
                }
            }
            extractor.release();
        }

        return frames;
    }

    /**
     * Image转Bitmap（包含旋转处理，与实时处理对齐）
     * @param image 视频帧Image
     * @param width 视频宽度
     * @param height 视频高度
     * @param rotation 视频旋转角度（0, 90, 180, 270）
     */
    private Bitmap imageToBitmap(Image image, int width, int height, int rotation) {
        try {
            if (image.getFormat() != ImageFormat.YUV_420_888) {
                Log.w(TAG, "不支持的图像格式: " + image.getFormat());
                return null;
            }

            Image.Plane[] planes = image.getPlanes();
            ByteBuffer yBuffer = planes[0].getBuffer();
            ByteBuffer uBuffer = planes[1].getBuffer();
            ByteBuffer vBuffer = planes[2].getBuffer();

            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();

            byte[] nv21 = new byte[ySize + uSize + vSize];
            yBuffer.get(nv21, 0, ySize);
            vBuffer.get(nv21, ySize, vSize);
            uBuffer.get(nv21, ySize + vSize, uSize);

            // 使用OpenCV转换
            Mat yuvMat = new Mat(height + height / 2, width, CvType.CV_8UC1);
            yuvMat.put(0, 0, nv21);

            Mat rgbMat = new Mat();
            Imgproc.cvtColor(yuvMat, rgbMat, Imgproc.COLOR_YUV2RGB_NV21);

            // 根据视频旋转角度旋转图像
            // 实时处理使用 rotateBitmap(bitmap, 270)，即旋转270度
            // 270度 = 逆时针90度 = ROTATE_90_COUNTERCLOCKWISE
            if (rotation == 90) {
                // 旋转90度 = 顺时针90度
                Core.rotate(rgbMat, rgbMat, Core.ROTATE_90_CLOCKWISE);
            } else if (rotation == 180) {
                Core.rotate(rgbMat, rgbMat, Core.ROTATE_180);
            } else if (rotation == 270) {
                // 旋转270度 = 逆时针90度（与实时处理对齐）
                Core.rotate(rgbMat, rgbMat, Core.ROTATE_90_COUNTERCLOCKWISE);
            }
            // rotation == 0 时不旋转

            // 旋转后的尺寸可能变化
            int finalWidth = rgbMat.cols();
            int finalHeight = rgbMat.rows();

            Bitmap bitmap = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(rgbMat, bitmap);

            yuvMat.release();
            rgbMat.release();

            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Image转Bitmap失败", e);
            return null;
        }
    }

    /**
     * 检测人脸
     */
    private FaceDetectionResult detectFace(Bitmap bitmap) {
        if (faceMesh == null || bitmap == null) {
            return null;
        }

        // 重置状态
        faceResultRef.set(null);
        faceLatch = new CountDownLatch(1);

        // 在主线程发送图像（FaceMesh 要求在主线程操作）
        mainHandler.post(() -> {
            try {
                faceMesh.send(bitmap);
            } catch (Exception e) {
                Log.e(TAG, "发送图像到FaceMesh失败", e);
                faceLatch.countDown();
            }
        });

        // 等待结果
        try {
            if (!faceLatch.await(2, TimeUnit.SECONDS)) {
                Log.w(TAG, "人脸检测超时");
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        return faceResultRef.get();
    }

    /**
     * 计算人脸边界框（只包含脸部，不包含脖子和肩膀）
     * 使用 FaceMesh 的脸部轮廓关键点
     */
    private RectF calculateBoundingBox(List<LandmarkProto.NormalizedLandmark> landmarks) {
        // FaceMesh 关键点索引：
        // 10: 额头顶部中心
        // 152: 下巴底部
        // 234: 左脸颊边缘
        // 454: 右脸颊边缘
        // 33: 左眼外角
        // 263: 右眼外角

        LandmarkProto.NormalizedLandmark forehead = landmarks.get(10);   // 额头
        LandmarkProto.NormalizedLandmark chin = landmarks.get(152);      // 下巴
        LandmarkProto.NormalizedLandmark leftCheek = landmarks.get(234); // 左脸颊
        LandmarkProto.NormalizedLandmark rightCheek = landmarks.get(454);// 右脸颊

        // 计算脸部边界（添加少量边距）
        float padding = 0.02f;  // 2% 边距

        float minX = Math.min(leftCheek.getX(), rightCheek.getX()) - padding;
        float maxX = Math.max(leftCheek.getX(), rightCheek.getX()) + padding;
        float minY = forehead.getY() - padding;
        float maxY = chin.getY() + padding * 0.5f;  // 下巴边距小一点，避免包含脖子

        // 边界检查
        minX = Math.max(0f, minX);
        minY = Math.max(0f, minY);
        maxX = Math.min(1f, maxX);
        maxY = Math.min(1f, maxY);

        return new RectF(minX, minY, maxX, maxY);
    }

    /**
     * 预处理人脸区域
     * 注意：bitmap 已经在 imageToBitmap 中旋转过了，FaceMesh 坐标是基于旋转后的图像
     * 所以这里直接用坐标裁剪，不需要额外旋转
     */
    private float[][][] preprocessFace(Bitmap bitmap, RectF bounds) {
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();

            // 计算裁剪区域
            int x = Math.round(bounds.left * width);
            int y = Math.round(bounds.top * height);
            int w = Math.round((bounds.right - bounds.left) * width);
            int h = Math.round((bounds.bottom - bounds.top) * height);

            // 边界检查
            x = Math.max(0, Math.min(x, width - 1));
            y = Math.max(0, Math.min(y, height - 1));
            w = Math.min(w, width - x);
            h = Math.min(h, height - y);

            if (w <= 0 || h <= 0) {
                return null;
            }

            // 转换为OpenCV Mat
            Mat mat = new Mat();
            Utils.bitmapToMat(bitmap, mat);
            Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2RGB);

            // 裁剪
            org.opencv.core.Rect roi = new org.opencv.core.Rect(x, y, w, h);
            Mat cropped = new Mat(mat, roi);

            // 缩放到36x36
            Mat resized = new Mat();
            Imgproc.resize(cropped, resized, new Size(36, 36), 0, 0, Imgproc.INTER_AREA);

            // 归一化
            resized.convertTo(resized, CvType.CV_32F, 1.0 / 255.0);

            // 转换为float数组
            float[][][] result = new float[36][36][3];
            for (int row = 0; row < 36; row++) {
                for (int col = 0; col < 36; col++) {
                    float[] pixel = new float[3];
                    resized.get(row, col, pixel);
                    result[row][col][0] = pixel[0];
                    result[row][col][1] = pixel[1];
                    result[row][col][2] = pixel[2];
                }
            }

            mat.release();
            cropped.release();
            resized.release();

            return result;
        } catch (Exception e) {
            Log.e(TAG, "预处理人脸失败", e);
            return null;
        }
    }

    /**
     * 计算人脸面积比例
     */
    private float calculateFaceArea(RectF bounds) {
        return (bounds.right - bounds.left) * (bounds.bottom - bounds.top);
    }

    /**
     * 计算位置稳定性
     */
    private float calculatePositionStability(List<RectF> positions) {
        if (positions.size() < 2) {
            return 100f;
        }

        float totalMovement = 0f;
        for (int i = 1; i < positions.size(); i++) {
            RectF prev = positions.get(i - 1);
            RectF curr = positions.get(i);

            float centerXPrev = (prev.left + prev.right) / 2f;
            float centerYPrev = (prev.top + prev.bottom) / 2f;
            float centerXCurr = (curr.left + curr.right) / 2f;
            float centerYCurr = (curr.top + curr.bottom) / 2f;

            float movement = (float) Math.sqrt(
                    Math.pow(centerXCurr - centerXPrev, 2) +
                    Math.pow(centerYCurr - centerYPrev, 2));
            totalMovement += movement;
        }

        float avgMovement = totalMovement / (positions.size() - 1);
        // 转换为稳定性分数（移动越少分数越高）
        float stability = Math.max(0f, 100f - avgMovement * 500f);
        return Math.min(100f, stability);
    }

    /**
     * 读取血氧仪心率数据
     */
    private float readOximeterHeartRate(String spo2Dir) {
        if (spo2Dir == null) {
            return 0f;
        }

        File dir = new File(spo2Dir);
        if (!dir.exists() || !dir.isDirectory()) {
            Log.w(TAG, "spo2目录不存在: " + spo2Dir);
            return 0f;
        }

        // 找到最新的spo2文件
        File[] files = dir.listFiles((d, name) -> name.startsWith("spo2_") && name.endsWith(".csv"));
        if (files == null || files.length == 0) {
            Log.w(TAG, "没有找到spo2文件");
            return 0f;
        }

        // 按修改时间排序，取最新的
        File latestFile = files[0];
        for (File f : files) {
            if (f.lastModified() > latestFile.lastModified()) {
                latestFile = f;
            }
        }

        // 读取心率数据并计算平均值
        List<Float> heartRates = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(latestFile))) {
            String line;
            boolean isHeader = true;
            while ((line = reader.readLine()) != null) {
                if (isHeader) {
                    isHeader = false;
                    continue;
                }

                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    try {
                        float hr = Float.parseFloat(parts[1].trim());
                        // 只排除0值，保留所有非0心率数据
                        if (hr > 0) {
                            heartRates.add(hr);
                        }
                    } catch (NumberFormatException e) {
                        // 忽略解析错误
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "读取spo2文件失败", e);
            return 0f;
        }

        if (heartRates.isEmpty()) {
            return 0f;
        }

        // 计算平均值
        float sum = 0f;
        for (Float hr : heartRates) {
            sum += hr;
        }
        return sum / heartRates.size();
    }

    /**
     * 计算综合评分
     * 有血氧仪数据时：人脸检测率40% + 人脸面积20% + 位置稳定性20% + 心率准确度20%
     * 无血氧仪数据时：人脸检测率50% + 人脸面积25% + 位置稳定性25%
     */
    private int calculateOverallScore(PostProcessResult result) {
        int score = 0;

        if (result.hasOximeterData) {
            // 有血氧仪数据：原有评分方式
            // 人脸检测率（40分）
            score += Math.round(result.faceDetectionRate * 0.4f);

            // 人脸面积（20分）
            float areaScore = Math.min(result.avgFaceAreaRatio / 50f, 1f) * 20f;
            score += Math.round(areaScore);

            // 位置稳定性（20分）
            score += Math.round(result.positionStability * 0.2f);

            // 心率准确度（20分）
            if (result.aiHeartRate > 0) {
                float accuracy = Math.max(0f, 20f - result.heartRateDiff);
                score += Math.round(accuracy);
            }
        } else {
            // 无血氧仪数据：调整权重，只评估视频质量
            // 人脸检测率（50分）
            score += Math.round(result.faceDetectionRate * 0.5f);

            // 人脸面积（25分）
            float areaScore = Math.min(result.avgFaceAreaRatio / 50f, 1f) * 25f;
            score += Math.round(areaScore);

            // 位置稳定性（25分）
            score += Math.round(result.positionStability * 0.25f);
        }

        return Math.min(100, score);
    }

    /**
     * 获取质量等级
     */
    private String getQualityLevel(int score) {
        if (score >= 85) return "EXCELLENT";
        if (score >= 70) return "GOOD";
        if (score >= 50) return "FAIR";
        return "POOR";
    }

    /**
     * 生成结论
     */
    private String generateConclusion(PostProcessResult result) {
        String baseConclusion;
        if (result.overallScore >= 85) {
            baseConclusion = "视频质量优秀，可用于模型训练";
        } else if (result.overallScore >= 70) {
            baseConclusion = "视频质量良好，基本可用";
        } else if (result.overallScore >= 50) {
            baseConclusion = "视频质量一般，建议重新录制";
        } else {
            baseConclusion = "视频质量不足，请重新录制";
        }

        // 无血氧仪数据时添加说明
        if (!result.hasOximeterData) {
            baseConclusion += "（未连接血氧仪，仅评估视频质量）";
        }

        return baseConclusion;
    }

    /**
     * 保存详细报告
     */
    private void saveDetailedReport(String frontDir, String videoPath, PostProcessResult result) {
        if (frontDir == null) {
            return;
        }

        File dir = new File(frontDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // 生成报告文件名
        String videoName = new File(videoPath).getName();
        String baseName = videoName.replace(".mp4", "");
        String reportName = baseName + "_post_analysis.txt";
        File reportFile = new File(dir, reportName);

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        String timestamp = sdf.format(new Date());

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(reportFile))) {
            writer.write("========== 视频后处理分析报告 ==========\n");
            writer.write("生成时间: " + timestamp + "\n");
            writer.write("视频文件: " + videoName + "\n");
            writer.write("\n");

            writer.write("---------- 基础统计 ----------\n");
            writer.write("总帧数: " + result.totalFrames + "\n");
            writer.write("有效帧数: " + result.validFrames + "\n");
            writer.write("\n");

            writer.write("---------- 质量指标 ----------\n");
            writer.write(String.format(Locale.US, "人脸检测率: %.1f%%\n", result.faceDetectionRate));
            writer.write(String.format(Locale.US, "平均人脸面积: %.1f%%\n", result.avgFaceAreaRatio));
            writer.write(String.format(Locale.US, "位置稳定性: %.1f%%\n", result.positionStability));
            writer.write("\n");

            writer.write("---------- 心率分析 ----------\n");
            if (result.aiHeartRate > 0) {
                writer.write(String.format(Locale.US, "AI推理心率: %.1f bpm\n", result.aiHeartRate));
            } else {
                writer.write("AI推理心率: 无法计算\n");
            }
            if (result.hasOximeterData) {
                writer.write(String.format(Locale.US, "血氧仪心率: %.1f bpm\n", result.oximeterHeartRate));
                writer.write(String.format(Locale.US, "心率差值: %.1f bpm\n", result.heartRateDiff));
            } else {
                writer.write("血氧仪心率: 未连接血氧仪\n");
                writer.write("心率差值: N/A\n");
            }
            writer.write("\n");

            writer.write("---------- 综合评估 ----------\n");
            writer.write("综合评分: " + result.overallScore + " 分\n");
            writer.write("质量等级: " + result.qualityLevel + "\n");
            writer.write("结论: " + result.conclusion + "\n");
            writer.write("\n");

            writer.write("========================================\n");

            Log.d(TAG, "报告已保存: " + reportFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "保存报告失败", e);
        }
    }

    /**
     * 清理资源
     */
    private void cleanup() {
        mainHandler.post(() -> {
            if (faceMesh != null) {
                try {
                    faceMesh.close();
                } catch (Exception e) {
                    Log.w(TAG, "关闭FaceMesh失败", e);
                }
                faceMesh = null;
            }
        });

        heartRateEstimator = null;
    }

    /**
     * 通知进度
     */
    private void notifyProgress(OnProgressListener listener, int current, int total, String message) {
        if (listener != null) {
            mainHandler.post(() -> listener.onProgress(current, total, message));
        }
    }

    /**
     * 通知完成
     */
    private void notifyComplete(OnProgressListener listener, PostProcessResult result) {
        if (listener != null) {
            mainHandler.post(() -> listener.onComplete(result));
        }
    }

    /**
     * 通知错误
     */
    private void notifyError(OnProgressListener listener, String error) {
        if (listener != null) {
            mainHandler.post(() -> listener.onError(error));
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        isCancelled = true;
        executor.shutdown();
        cleanup();
    }
}
