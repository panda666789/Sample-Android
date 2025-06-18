package com.tsinghua.sample.utils;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import com.google.mediapipe.formats.proto.LandmarkProto;
import com.google.mediapipe.solutions.facemesh.FaceMeshResult;
import com.tsinghua.sample.PlotView;


import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;


public class FacePreprocessor {
    private static final String TAG = "FacePreprocessor";

    private final ConcurrentHashMap<Integer, FrameResult> resultMap = new ConcurrentHashMap<>();
    private Context context;
    private int invalidFrameCount = 0;
    private final int MAX_INVALID_FRAMES = 60;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private Retrofit retrofit;


    private final HeartRateEstimator heartRateEstimator;
    private final HeartRateViewModel heartRateViewModel;
    public interface OnDetectionFailListener {
        void onTooManyInvalidFrames();  // 连续无效帧触发回调
    }
    private OnDetectionFailListener detectionFailListener;

    public void setOnDetectionFailListener(OnDetectionFailListener listener) {
        this.detectionFailListener = listener;
    }

    public FacePreprocessor(Context context, HeartRateEstimator estimator) {

        this.context = context;

        this.heartRateEstimator = estimator;


        this.heartRateViewModel = new HeartRateViewModel();
    }


    /**
     * 内部类，用于存储帧结果。
     */
    private static class FrameResult {
        Bitmap rawBitmap;
        FaceMeshResult result;

        FrameResult(FaceMeshResult result, Bitmap bitmap) {
            this.result = result;
            this.rawBitmap = bitmap;
        }
    }

    /**
     * 添加帧结果到结果映射表。
     *
     * @param result     面部网格结果
     * @param bitmap     原始位图
     */
    public void addFrameResults(FaceMeshResult result, Bitmap bitmap) {
        worker.execute(() -> processLandmarksInOrder(result,bitmap));
    }

    /**
     * 按顺序处理面部关键点。
     *
     */
    private void processLandmarksInOrder(FaceMeshResult result,Bitmap bitmap) {
         final long nowMs = System.currentTimeMillis();
            float[][][][] afterPreprocess = new float[1][36][36][3];
            RectF box = new RectF(-100, -100, -100, -100);
            Rect box_ = new Rect(-100, -100, -100, -100);
            try {

                if (result != null) {

                    if (result != null && !result.multiFaceLandmarks().isEmpty()) {
                        List<LandmarkProto.NormalizedLandmark> landmarks = result.multiFaceLandmarks().get(0).getLandmarkList();
                        RectF bounds = calculateBoundingBox(landmarks);

                        if (box.left < -1) {
                            box = bounds;
                        } else {
                            float aveW = calculateWeight(bounds, box);
                            box = interpolateBounds(bounds, box, aveW);
                        }

                        Rect tmpBox = processBox(box, bitmap.getWidth(), bitmap.getHeight());
                        if (box_.left < -1) {
                            box_ = tmpBox;
                        } else {
                            box_ = updateBox(tmpBox, box_, bitmap.getWidth(), bitmap.getHeight());
                        }
                    }

                    if (box_.left >= 0) {
                        org.opencv.core.Rect boundsBox = convertToOpenCVRect(box_);
                        Mat mat = new Mat();
                        Utils.bitmapToMat(bitmap, mat);
                        bitmap.recycle();

                        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2RGB);
                        Core.rotate(mat, mat, Core.ROTATE_90_COUNTERCLOCKWISE);

                        Mat croppedMat = processFrame(mat, boundsBox, new Size(36, 36));

                        croppedMat.convertTo(croppedMat, CvType.CV_32F, 1.0 / 255.0);

                        synchronized (afterPreprocess) {
                            for (int row = 0; row < 36; row++) {
                                for (int col = 0; col < 36; col++) {
                                    float[] pixel = new float[3];
                                    croppedMat.get(row, col, pixel);
                                    afterPreprocess[0][row][col][0] = pixel[0];
                                    afterPreprocess[0][row][col][1] = pixel[1];
                                    afterPreprocess[0][row][col][2] = pixel[2];
                                }
                            }
                        }
                        mat.release();
                        croppedMat.release();
                    }

                }
                float[][][] currentFrame = afterPreprocess[0];
                if (isAllZero(currentFrame)) {
                    invalidFrameCount++;
                    Log.w(TAG, "第 " + invalidFrameCount + " 帧无效（无人脸）");
                    if (invalidFrameCount >= MAX_INVALID_FRAMES) {
                        invalidFrameCount = 0;  // 重置防止重复触发
                        new Handler(context.getMainLooper()).post(() -> {
                            if (detectionFailListener != null) {
                                detectionFailListener.onTooManyInvalidFrames();
                            }
                        });
                    }
                    return;
                } else {
                    // ✅ 检测到了人脸，重置计数器
                    invalidFrameCount = 0;
                }
                heartRateEstimator.estimateFromFrame(currentFrame,nowMs);

            } catch (Exception e) {
                e.printStackTrace();
            } finally {

            }

    }

    private Mat processFrame(Mat frame, org.opencv.core.Rect box_, Size resolution) {
        int width = frame.cols();
        int height = frame.rows();

        int correctedWidth = Math.min(box_.width, width - box_.x);
        int correctedHeight = Math.min(box_.height, height - box_.y);
        if (correctedWidth <= 0 || correctedHeight <= 0) {
            Log.w(TAG, "processFrame: ROI empty, skip this frame");
            return null;
        }
        org.opencv.core.Rect correctedBox = new org.opencv.core.Rect(box_.x, box_.y, correctedWidth, correctedHeight);

        Mat cropped = new Mat(frame, correctedBox);

        Mat resized = new Mat();
        Imgproc.resize(cropped, resized, resolution, 0, 0, Imgproc.INTER_AREA);

        cropped.release();
        return resized;
    }
    private boolean isAllZero(float[][][] frame) {
        for (int i = 0; i < frame.length; i++) {
            for (int j = 0; j < frame[0].length; j++) {
                for (int k = 0; k < frame[0][0].length; k++) {
                    if (frame[i][j][k] != 0.0f) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private org.opencv.core.Rect convertToOpenCVRect(Rect graphicsRect) {
        int left = graphicsRect.left;
        int top = graphicsRect.top;
        int width = graphicsRect.width();
        int height = graphicsRect.height();

        return new org.opencv.core.Rect(left, top, width, height);
    }

    private Rect updateBox(Rect box, Rect box_, int frameWidth, int frameHeight) {
        int minX = box.left;
        int minY = box.top;
        int maxX = box.right;
        int maxY = box.bottom;

        int boxMinX = box_.left;
        int boxMinY = box_.top;
        int boxMaxX = box_.right;
        int boxMaxY = box_.bottom;

        double distance = Math.sqrt(Math.pow(minX - boxMinX, 2) + Math.pow(minY - boxMinY, 2)
                + Math.pow(maxX - boxMaxX, 2) + Math.pow(maxY - boxMaxY, 2));

        double threshold = (frameWidth * frameHeight) / Math.pow(10, 5);
        if (distance > threshold) {
            box_ = box;
        }

        return box_;
    }

    private RectF interpolateBounds(RectF shapeBounds, RectF box, float w) {
        float minX = shapeBounds.left;
        float minY = shapeBounds.top;
        float maxX = shapeBounds.right;
        float maxY = shapeBounds.bottom;

        float interpolatedMinX = minX * w + box.left * (1 - w);
        float interpolatedMinY = minY * w + box.top * (1 - w);
        float interpolatedMaxX = maxX * w + box.right * (1 - w);
        float interpolatedMaxY = maxY * w + box.bottom * (1 - w);

        return new RectF(interpolatedMinX, interpolatedMinY, interpolatedMaxX, interpolatedMaxY);
    }

    private Rect processBox(RectF box, int frameWidth, int frameHeight) {
        int x = Math.round(box.left * frameWidth);
        int y = Math.round(box.top * frameHeight);
        int w = Math.round((box.right - box.left) * frameWidth);
        int h = Math.round((box.bottom - box.top) * frameHeight);

        x = Math.max(0, Math.min(x, frameWidth - 1));
        y = Math.max(0, Math.min(y, frameHeight - 1));
        w = Math.min(w, frameWidth - x);
        h = Math.min(h, frameHeight - y);

        return new Rect(x, y, x + w, y + h);
    }



    private float calculateWeight(RectF shapeBounds, RectF box) {
        float[] shapeArray = {shapeBounds.left, shapeBounds.top, shapeBounds.right, shapeBounds.bottom};
        float[] boxArray = {box.left, box.top, box.right, box.bottom};

        float dx = (shapeArray[0] + shapeArray[2]) / 2 - (boxArray[0] + boxArray[2]) / 2;
        float dy = (shapeArray[1] + shapeArray[3]) / 2 - (boxArray[1] + boxArray[3]) / 2;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);

        float boxWidth = Math.abs(boxArray[2] - boxArray[0]);
        float boxHeight = Math.abs(boxArray[3] - boxArray[1]);

        float normFactor = boxWidth * boxHeight;
        float w = 1 / (1 + (float) Math.exp(-20 * distance / normFactor)) * 2 - 1;

        return w;
    }

    private RectF calculateBoundingBox(List<LandmarkProto.NormalizedLandmark> landmarks) {
        LandmarkProto.NormalizedLandmark leftEye = landmarks.get(33);
        LandmarkProto.NormalizedLandmark rightEye = landmarks.get(263);
        LandmarkProto.NormalizedLandmark chin = landmarks.get(152);
        LandmarkProto.NormalizedLandmark noseTip = landmarks.get(1);

        float eyeCenterX = (leftEye.getX() + rightEye.getX()) / 2f;
        float eyeCenterY = (leftEye.getY() + rightEye.getY()) / 2f;

        float faceHeight = (chin.getY() - eyeCenterY) * 2.0f;

        float centerX = (eyeCenterX + noseTip.getX()) / 2f;
        float centerY = eyeCenterY + faceHeight * 0.4f; // ✅ 更靠下，更贴近整脸

        float boxSize = faceHeight * 1.5f; // ✅ 更大框，捕获整个脸+边缘

        float halfBox = boxSize / 2f;

        float minX = Math.max(0f, centerX - halfBox);
        float minY = Math.max(0f, centerY - halfBox);
        float maxX = Math.min(1f, centerX + halfBox);
        float maxY = Math.min(1f, centerY + halfBox);

        return new RectF(minX, minY, maxX, maxY);
    }






}
