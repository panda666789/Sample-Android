package com.tsinghua.sample.media.sync;

import java.util.ArrayList;
import java.util.List;

/** Chooses independently decodable clips while retaining their real start-time difference. */
final class PairedClipSelection {
    static final long MAX_START_OFFSET_NS = 100_000_000L;
    static final long MIN_OVERLAP_NS = 18_000_000_000L;
    static final long MAX_DURATION_NS = 30_000_000_000L;
    static final int MAX_FRAMES = 1800;

    static Selection select(List<FrameTimeline.Frame> front, List<FrameTimeline.Frame> back) {
        double frontFps = validate(front, "前摄");
        double backFps = validate(back, "后摄");
        long availableEnd = Math.min(lastTime(front), lastTime(back));
        long availableStart = Math.max(front.get(0).sensorTimestampNs,
                back.get(0).sensorTimestampNs);
        if (availableEnd - availableStart < MIN_OVERLAP_NS) {
            throw new IllegalArgumentException("两路共同数据不足 18 秒");
        }
        List<Integer> backKeys = new ArrayList<>();
        for (int b = 0; b < back.size(); b++) if (back.get(b).keyFrame) backKeys.add(b);
        int firstCandidate = 0;
        for (int f = 0; f < front.size(); f++) {
            FrameTimeline.Frame firstFront = front.get(f);
            if (!firstFront.keyFrame) continue;
            if (availableEnd - firstFront.sensorTimestampNs < MIN_OVERLAP_NS) break;
            while (firstCandidate < backKeys.size()
                    && back.get(backKeys.get(firstCandidate)).sensorTimestampNs
                    < firstFront.sensorTimestampNs - MAX_START_OFFSET_NS) firstCandidate++;
            for (int candidate = firstCandidate; candidate < backKeys.size(); candidate++) {
                int b = backKeys.get(candidate);
                FrameTimeline.Frame firstBack = back.get(b);
                long offset = firstBack.sensorTimestampNs - firstFront.sensorTimestampNs;
                if (offset > MAX_START_OFFSET_NS) break;
                long commonStart = Math.max(firstFront.sensorTimestampNs,
                        firstBack.sensorTimestampNs);
                long endLimit = Math.min(availableEnd,
                        Math.min(firstFront.sensorTimestampNs, firstBack.sensorTimestampNs)
                                + MAX_DURATION_NS);
                int frontEnd = endIndex(front, f, endLimit);
                int backEnd = endIndex(back, b, endLimit);
                long commonEnd = Math.min(front.get(frontEnd).sensorTimestampNs,
                        back.get(backEnd).sensorTimestampNs);
                if (commonEnd - commonStart < MIN_OVERLAP_NS) continue;
                double frontClipFps = (frontEnd - f) * 1_000_000.0
                        / (front.get(frontEnd).videoPtsUs - firstFront.videoPtsUs);
                double backClipFps = (backEnd - b) * 1_000_000.0
                        / (back.get(backEnd).videoPtsUs - firstBack.videoPtsUs);
                if (!supportedFps(frontClipFps) || !supportedFps(backClipFps)) continue;
                return new Selection(f, frontEnd, b, backEnd, offset, commonStart, commonEnd,
                        frontFps, backFps);
            }
        }
        throw new IllegalArgumentException("找不到首帧差不超过 100 ms、共同覆盖 18 秒且片段帧率为 25–35 FPS 的关键帧对");
    }

    private static double validate(List<FrameTimeline.Frame> frames, String name) {
        if (frames.size() < 2) throw new IllegalArgumentException(name + "有效映射帧不足");
        FrameTimeline.Frame first = frames.get(0);
        long previousSensorNs = -1;
        long previousPtsUs = -1;
        for (int i = 0; i < frames.size(); i++) {
            FrameTimeline.Frame frame = frames.get(i);
            if (!frame.matched || frame.sampleIndex != i) {
                throw new IllegalArgumentException(name + "存在未确认的样本映射");
            }
            if (frame.sensorTimestampNs <= previousSensorNs || frame.videoPtsUs <= previousPtsUs
                    || Math.abs((frame.videoPtsUs - first.videoPtsUs)
                    - (frame.sensorTimestampNs / 1000 - first.sensorTimestampNs / 1000)) > 2) {
                throw new IllegalArgumentException(name + "视频时间轴与传感器时间轴不一致");
            }
            previousSensorNs = frame.sensorTimestampNs;
            previousPtsUs = frame.videoPtsUs;
        }
        double fps = (frames.size() - 1) * 1_000_000_000.0
                / (lastTime(frames) - first.sensorTimestampNs);
        if (!supportedFps(fps)) {
            throw new IllegalArgumentException(name + "平均帧率不在 25–35 FPS 范围内");
        }
        return fps;
    }

    /** Allows only sub-microsecond endpoint rounding at the inclusive FPS limits. */
    static boolean supportedFps(double fps) {
        return Double.isFinite(fps) && fps >= 25 - 0.0001 && fps <= 35 + 0.0001;
    }

    static boolean acceptsInferenceFps(double fps) {
        // OfflineEngine.Video compares the actual decoded file's FPS without rounding.
        return Double.isFinite(fps) && fps >= 25 && fps <= 35;
    }

    private static long lastTime(List<FrameTimeline.Frame> frames) {
        return frames.get(frames.size() - 1).sensorTimestampNs;
    }

    private static int endIndex(List<FrameTimeline.Frame> frames, int start, long endNs) {
        int end = start;
        int max = Math.min(frames.size() - 1, start + MAX_FRAMES - 1);
        while (end < max && frames.get(end + 1).sensorTimestampNs <= endNs) end++;
        // Muxer repeats the previous interval for the final sample's duration. Reserve 1 ms
        // for rounding; the final remuxed duration is checked again because stts can smooth PTS.
        while (end > start && frames.get(end).videoPtsUs - frames.get(start).videoPtsUs
                + frames.get(end).videoPtsUs - frames.get(end - 1).videoPtsUs
                > MAX_DURATION_NS / 1000 - 1000) end--;
        return end;
    }

    static final class Selection {
        final int frontStart, frontEnd, backStart, backEnd;
        final long startOffsetNs, commonStartNs, commonEndNs;
        final double frontFps, backFps;

        Selection(int frontStart, int frontEnd, int backStart, int backEnd, long startOffsetNs,
                long commonStartNs, long commonEndNs, double frontFps, double backFps) {
            this.frontStart = frontStart;
            this.frontEnd = frontEnd;
            this.backStart = backStart;
            this.backEnd = backEnd;
            this.startOffsetNs = startOffsetNs;
            this.commonStartNs = commonStartNs;
            this.commonEndNs = commonEndNs;
            this.frontFps = frontFps;
            this.backFps = backFps;
        }
    }
}
