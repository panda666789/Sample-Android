package com.tsinghua.sample.media.sync;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Measures software frame pairing, not simultaneous exposure or hardware synchronization. */
public final class FrameAlignment {
    private FrameAlignment() {}

    public static Metrics analyze(List<FrameTimeline.Frame> front, List<FrameTimeline.Frame> back) {
        if (!isComplete(front) || !isComplete(back)) {
            return new Metrics(false, front.size(), back.size(), -1, -1, -1,
                    Collections.emptyList(), -1, -1, -1, 0, -1, -1);
        }
        List<FrameTimeline.Frame> sortedFront = sorted(front);
        List<FrameTimeline.Frame> sortedBack = sorted(back);
        long frontStart = sortedFront.get(0).sensorTimestampNs;
        long backStart = sortedBack.get(0).sensorTimestampNs;
        long commonStart = Math.max(frontStart, backStart);
        long commonEnd = Math.min(sortedFront.get(sortedFront.size() - 1).sensorTimestampNs,
                sortedBack.get(sortedBack.size() - 1).sensorTimestampNs);
        List<FrameTimeline.Frame> commonBack = new ArrayList<>();
        for (FrameTimeline.Frame frame : sortedBack) {
            if (frame.sensorTimestampNs >= commonStart && frame.sensorTimestampNs <= commonEnd) {
                commonBack.add(frame);
            }
        }
        List<Pair> pairs = new ArrayList<>();
        int backPosition = 0;
        int lastBackPosition = -1;
        int repeatedBackFrames = 0;
        if (!commonBack.isEmpty()) {
            for (FrameTimeline.Frame frame : sortedFront) {
                if (frame.sensorTimestampNs < commonStart || frame.sensorTimestampNs > commonEnd) {
                    continue;
                }
                while (backPosition + 1 < commonBack.size()
                        && distance(frame, commonBack.get(backPosition + 1))
                        < distance(frame, commonBack.get(backPosition))) {
                    backPosition++;
                }
                FrameTimeline.Frame match = commonBack.get(backPosition);
                pairs.add(new Pair(frame.sampleIndex, match.sampleIndex,
                        match.sensorTimestampNs - frame.sensorTimestampNs));
                if (backPosition == lastBackPosition) repeatedBackFrames++;
                lastBackPosition = backPosition;
            }
        }
        long median = -1;
        long p95 = -1;
        long max = -1;
        if (!pairs.isEmpty()) {
            long[] errors = new long[pairs.size()];
            for (int i = 0; i < pairs.size(); i++) errors[i] = Math.abs(pairs.get(i).deltaNs);
            Arrays.sort(errors);
            int middle = errors.length / 2;
            median = errors.length % 2 == 1 ? errors[middle]
                    : errors[middle - 1] + (errors[middle] - errors[middle - 1]) / 2;
            p95 = errors[(int) Math.ceil(errors.length * 0.95) - 1];
            max = errors[errors.length - 1];
        }
        return new Metrics(true, front.size(), back.size(), backStart - frontStart,
                commonStart, commonEnd, pairs, median, p95, max, repeatedBackFrames,
                maxGap(sortedFront), maxGap(sortedBack));
    }

    private static boolean isComplete(List<FrameTimeline.Frame> frames) {
        if (frames.isEmpty()) return false;
        for (FrameTimeline.Frame frame : frames) {
            if (!frame.matched) return false;
        }
        return true;
    }

    private static List<FrameTimeline.Frame> sorted(List<FrameTimeline.Frame> frames) {
        List<FrameTimeline.Frame> result = new ArrayList<>(frames);
        result.sort(Comparator.comparingLong(frame -> frame.sensorTimestampNs));
        return result;
    }

    private static long distance(FrameTimeline.Frame a, FrameTimeline.Frame b) {
        return Math.abs(a.sensorTimestampNs - b.sensorTimestampNs);
    }

    private static long maxGap(List<FrameTimeline.Frame> sortedFrames) {
        long max = 0;
        for (int i = 1; i < sortedFrames.size(); i++) {
            max = Math.max(max, sortedFrames.get(i).sensorTimestampNs
                    - sortedFrames.get(i - 1).sensorTimestampNs);
        }
        return max;
    }

    public static final class Pair {
        public final int frontIndex;
        public final int backIndex;
        /** Back sensor timestamp minus front sensor timestamp, in nanoseconds. */
        public final long deltaNs;

        private Pair(int frontIndex, int backIndex, long deltaNs) {
            this.frontIndex = frontIndex;
            this.backIndex = backIndex;
            this.deltaNs = deltaNs;
        }
    }

    public static final class Metrics {
        public final boolean mappingComplete;
        public final int frontSamples;
        public final int backSamples;
        /** Back first frame minus front first frame; valid only when mappingComplete. */
        public final long startOffsetNs;
        public final long commonStartNs;
        public final long commonEndNs;
        public final List<Pair> pairs;
        /** Error statistics are -1 if no pairs exist. P95 uses the nearest-rank definition. */
        public final long medianAbsNs;
        public final long p95AbsNs;
        public final long maxAbsNs;
        /** Number of pairs that reuse a back frame already selected for an earlier front frame. */
        public final int repeatedBackFrames;
        /** Largest consecutive sensor gap over each complete stream; -1 if mapping is incomplete. */
        public final long maxFrontGapNs;
        public final long maxBackGapNs;

        private Metrics(boolean mappingComplete, int frontSamples, int backSamples,
                long startOffsetNs, long commonStartNs, long commonEndNs, List<Pair> pairs,
                long medianAbsNs, long p95AbsNs, long maxAbsNs, int repeatedBackFrames,
                long maxFrontGapNs, long maxBackGapNs) {
            this.mappingComplete = mappingComplete;
            this.frontSamples = frontSamples;
            this.backSamples = backSamples;
            this.startOffsetNs = startOffsetNs;
            this.commonStartNs = commonStartNs;
            this.commonEndNs = commonEndNs;
            this.pairs = Collections.unmodifiableList(pairs);
            this.medianAbsNs = medianAbsNs;
            this.p95AbsNs = p95AbsNs;
            this.maxAbsNs = maxAbsNs;
            this.repeatedBackFrames = repeatedBackFrames;
            this.maxFrontGapNs = maxFrontGapNs;
            this.maxBackGapNs = maxBackGapNs;
        }
    }
}
