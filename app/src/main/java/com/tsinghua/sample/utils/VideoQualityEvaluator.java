package com.tsinghua.sample.utils;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

/**
 * 视频质量评估器
 * 用于评估rPPG视频是否适合用于模型训练
 *
 * 评估指标：
 * 1. 人脸检测率 - 有效帧占比
 * 2. 人脸面积占比 - 人脸区域占画面比例
 * 3. 位置稳定性 - 人脸位置帧间变化程度
 * 4. 亮度均值 - ROI区域平均亮度
 * 5. 亮度稳定性 - 帧间亮度变化
 */
public class VideoQualityEvaluator {
    private static final String TAG = "VideoQualityEvaluator";

    // 统计变量
    private int totalFrames = 0;
    private int validFrames = 0;  // 检测到人脸的帧数

    // 人脸面积统计
    private double faceAreaSum = 0;
    private int faceAreaCount = 0;

    // 位置稳定性统计
    private float lastCenterX = -1;
    private float lastCenterY = -1;
    private double positionDeltaSum = 0;
    private int positionDeltaCount = 0;

    // 亮度统计
    private double brightnessSum = 0;
    private double brightnessSqSum = 0;  // 用于计算方差
    private int brightnessCount = 0;

    // 帧间亮度变化统计
    private double lastBrightness = -1;
    private double brightnessDeltaSum = 0;
    private int brightnessDeltaCount = 0;

    // 评估结果
    public static class QualityResult {
        public int totalFrames;
        public int validFrames;
        public float faceDetectionRate;      // 人脸检测率 (0-1)
        public float avgFaceAreaRatio;       // 平均人脸面积占比 (0-1)
        public float positionStability;      // 位置稳定性 (0-1, 1为最稳定)
        public float avgBrightness;          // 平均亮度 (0-255)
        public float brightnessStability;    // 亮度稳定性 (0-1, 1为最稳定)
        public float overallScore;           // 综合评分 (0-100)
        public String qualityLevel;          // 质量等级: EXCELLENT/GOOD/FAIR/POOR

        @Override
        public String toString() {
            return String.format(
                "质量评估结果:\n" +
                "- 总帧数: %d, 有效帧: %d\n" +
                "- 人脸检测率: %.1f%%\n" +
                "- 平均人脸面积: %.1f%%\n" +
                "- 位置稳定性: %.1f%%\n" +
                "- 平均亮度: %.0f\n" +
                "- 亮度稳定性: %.1f%%\n" +
                "- 综合评分: %.0f分\n" +
                "- 质量等级: %s",
                totalFrames, validFrames,
                faceDetectionRate * 100,
                avgFaceAreaRatio * 100,
                positionStability * 100,
                avgBrightness,
                brightnessStability * 100,
                overallScore,
                qualityLevel
            );
        }
    }

    /**
     * 重置所有统计数据（开始新的录制时调用）
     */
    public void reset() {
        totalFrames = 0;
        validFrames = 0;
        faceAreaSum = 0;
        faceAreaCount = 0;
        lastCenterX = -1;
        lastCenterY = -1;
        positionDeltaSum = 0;
        positionDeltaCount = 0;
        brightnessSum = 0;
        brightnessSqSum = 0;
        brightnessCount = 0;
        lastBrightness = -1;
        brightnessDeltaSum = 0;
        brightnessDeltaCount = 0;
        Log.d(TAG, "质量评估器已重置");
    }

    /**
     * 更新帧统计（每帧调用）
     *
     * @param faceDetected 是否检测到人脸
     * @param faceBounds   人脸边界框（归一化坐标 0-1），可为null
     * @param roiBrightness ROI区域平均亮度（0-255），-1表示无数据
     */
    public void updateFrame(boolean faceDetected, RectF faceBounds, float roiBrightness) {
        totalFrames++;

        if (faceDetected) {
            validFrames++;

            // 统计人脸面积
            if (faceBounds != null) {
                float faceArea = faceBounds.width() * faceBounds.height();
                faceAreaSum += faceArea;
                faceAreaCount++;

                // 统计位置变化
                float centerX = faceBounds.centerX();
                float centerY = faceBounds.centerY();
                if (lastCenterX >= 0) {
                    double delta = Math.sqrt(
                        Math.pow(centerX - lastCenterX, 2) +
                        Math.pow(centerY - lastCenterY, 2)
                    );
                    positionDeltaSum += delta;
                    positionDeltaCount++;
                }
                lastCenterX = centerX;
                lastCenterY = centerY;
            }

            // 统计亮度
            if (roiBrightness >= 0) {
                brightnessSum += roiBrightness;
                brightnessSqSum += roiBrightness * roiBrightness;
                brightnessCount++;

                // 帧间亮度变化
                if (lastBrightness >= 0) {
                    double delta = Math.abs(roiBrightness - lastBrightness);
                    brightnessDeltaSum += delta;
                    brightnessDeltaCount++;
                }
                lastBrightness = roiBrightness;
            }
        }
    }

    /**
     * 简化版更新（只需要人脸检测结果和边界框）
     */
    public void updateFrame(boolean faceDetected, RectF faceBounds) {
        updateFrame(faceDetected, faceBounds, -1);
    }

    /**
     * 计算并返回质量评估结果
     */
    public QualityResult evaluate() {
        QualityResult result = new QualityResult();
        result.totalFrames = totalFrames;
        result.validFrames = validFrames;

        // 1. 人脸检测率
        result.faceDetectionRate = totalFrames > 0 ? (float) validFrames / totalFrames : 0;

        // 2. 平均人脸面积占比
        result.avgFaceAreaRatio = faceAreaCount > 0 ? (float) (faceAreaSum / faceAreaCount) : 0;

        // 3. 位置稳定性 (将平均位移转换为稳定性分数)
        // 平均位移越小越稳定，使用sigmoid映射到0-1
        if (positionDeltaCount > 0) {
            double avgDelta = positionDeltaSum / positionDeltaCount;
            // avgDelta=0 -> stability=1, avgDelta=0.1 -> stability≈0.5
            result.positionStability = (float) (1.0 / (1.0 + avgDelta * 20));
        } else {
            result.positionStability = 1.0f;
        }

        // 4. 平均亮度
        result.avgBrightness = brightnessCount > 0 ? (float) (brightnessSum / brightnessCount) : 128;

        // 5. 亮度稳定性
        if (brightnessDeltaCount > 0) {
            double avgBrightnessDelta = brightnessDeltaSum / brightnessDeltaCount;
            // 亮度变化越小越稳定
            result.brightnessStability = (float) Math.max(0, 1.0 - avgBrightnessDelta / 30.0);
        } else {
            result.brightnessStability = 1.0f;
        }

        // 6. 综合评分 (加权平均)
        // 权重：人脸检测率40% + 位置稳定性25% + 亮度稳定性20% + 面积占比15%
        float detectionScore = result.faceDetectionRate;
        float stabilityScore = result.positionStability;
        float brightnessScore = result.brightnessStability;
        // 面积评分：面积在10%-40%之间最佳
        float areaScore = calculateAreaScore(result.avgFaceAreaRatio);

        result.overallScore = (
            detectionScore * 40 +
            stabilityScore * 25 +
            brightnessScore * 20 +
            areaScore * 15
        );

        // 7. 质量等级
        if (result.overallScore >= 80) {
            result.qualityLevel = "EXCELLENT";
        } else if (result.overallScore >= 60) {
            result.qualityLevel = "GOOD";
        } else if (result.overallScore >= 40) {
            result.qualityLevel = "FAIR";
        } else {
            result.qualityLevel = "POOR";
        }

        Log.d(TAG, result.toString());
        return result;
    }

    /**
     * 计算面积评分
     * 面积太小（<5%）或太大（>60%）都不好
     * 最佳范围：10%-40%
     */
    private float calculateAreaScore(float areaRatio) {
        if (areaRatio < 0.05f) {
            return areaRatio / 0.05f;  // 0-5% -> 0-1
        } else if (areaRatio <= 0.40f) {
            return 1.0f;  // 5-40% -> 1 (最佳)
        } else if (areaRatio <= 0.60f) {
            return 1.0f - (areaRatio - 0.40f) / 0.20f;  // 40-60% -> 1-0
        } else {
            return 0.0f;  // >60% -> 0
        }
    }

    /**
     * 从Bitmap计算ROI区域的平均亮度
     * 用于在没有其他亮度数据时计算
     */
    public static float calculateBrightness(Bitmap bitmap, RectF bounds) {
        if (bitmap == null || bounds == null) return -1;

        int left = (int) (bounds.left * bitmap.getWidth());
        int top = (int) (bounds.top * bitmap.getHeight());
        int right = (int) (bounds.right * bitmap.getWidth());
        int bottom = (int) (bounds.bottom * bitmap.getHeight());

        // 边界检查
        left = Math.max(0, left);
        top = Math.max(0, top);
        right = Math.min(bitmap.getWidth(), right);
        bottom = Math.min(bitmap.getHeight(), bottom);

        if (right <= left || bottom <= top) return -1;

        // 采样计算亮度（每隔几个像素采样一次，提高效率）
        int step = Math.max(1, (right - left) / 20);
        long sum = 0;
        int count = 0;

        for (int y = top; y < bottom; y += step) {
            for (int x = left; x < right; x += step) {
                int pixel = bitmap.getPixel(x, y);
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                // 使用亮度公式 Y = 0.299R + 0.587G + 0.114B
                int brightness = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                sum += brightness;
                count++;
            }
        }

        return count > 0 ? (float) sum / count : -1;
    }

    // Getters for current stats
    public int getTotalFrames() { return totalFrames; }
    public int getValidFrames() { return validFrames; }
    public float getCurrentDetectionRate() {
        return totalFrames > 0 ? (float) validFrames / totalFrames : 0;
    }
}
