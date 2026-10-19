package com.tsinghua.sample.media.sync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Relates actual MP4 samples to capture results without assuming callback ordering. */
public final class FrameTimeline {
    private final List<Capture> captures = new ArrayList<>();
    private final List<Sample> samples = new ArrayList<>();

    public synchronized void addCapture(long cameraFrameNumber, long sensorTimestampNs) {
        if (sensorTimestampNs < 0) throw new IllegalArgumentException("Negative sensor timestamp");
        captures.add(new Capture(cameraFrameNumber, sensorTimestampNs));
    }

    /** Call only after writing this sample to the muxer; videoPtsUs is never rewritten here. */
    public synchronized void addSample(long codecPtsUs, long videoPtsUs, boolean keyFrame) {
        addSample(codecPtsUs, videoPtsUs, keyFrame, "");
    }

    /** Empty or null identity means the sample's content has not been verified. */
    public synchronized void addSample(long codecPtsUs, long videoPtsUs, boolean keyFrame,
            String sampleSha256) {
        if (codecPtsUs < 0 || videoPtsUs < 0) {
            throw new IllegalArgumentException("Negative video timestamp");
        }
        samples.add(new Sample(codecPtsUs, videoPtsUs, keyFrame,
                sampleSha256 == null ? "" : sampleSha256));
    }

    public synchronized int captureCount() {
        return captures.size();
    }

    public synchronized int sampleCount() {
        return samples.size();
    }

    /**
     * Matches sensor nanoseconds truncated to microseconds against the codec's original PTS,
     * with at most 1 us tolerance. Both directions must have exactly one candidate. A later
     * callback can resolve a missing capture or reveal an ambiguity in a subsequent snapshot.
     * No clock offset is estimated, and a previous snapshot never changes.
     */
    public synchronized List<Frame> snapshot() {
        Map<Long, List<Capture>> capturesByUs = new HashMap<>();
        for (Capture capture : captures) {
            capturesByUs.computeIfAbsent(capture.sensorTimestampNs / 1_000,
                    ignored -> new ArrayList<>()).add(capture);
        }
        Map<Long, Integer> samplesByUs = new HashMap<>();
        for (Sample sample : samples) {
            samplesByUs.merge(sample.codecPtsUs, 1, Integer::sum);
        }
        List<Frame> frames = new ArrayList<>(samples.size());
        for (int i = 0; i < samples.size(); i++) {
            Sample sample = samples.get(i);
            int candidates = 0;
            Capture candidate = null;
            for (int offset = -1; offset <= 1; offset++) {
                List<Capture> nearby = capturesByUs.get(sample.codecPtsUs + offset);
                if (nearby != null) {
                    candidates += nearby.size();
                    candidate = nearby.get(0);
                }
            }
            MatchStatus status = MatchStatus.AMBIGUOUS;
            if (candidates == 0 && samplesByUs.get(sample.codecPtsUs) == 1) {
                status = MatchStatus.NO_CAPTURE;
            } else if (candidates == 1) {
                long captureUs = candidate.sensorTimestampNs / 1_000;
                int candidateSamples = 0;
                for (int offset = -1; offset <= 1; offset++) {
                    candidateSamples += samplesByUs.getOrDefault(captureUs + offset, 0);
                }
                if (candidateSamples == 1) status = MatchStatus.MATCHED;
            }
            frames.add(new Frame(i, sample, status == MatchStatus.MATCHED ? candidate : null,
                    status));
        }
        return Collections.unmodifiableList(frames);
    }

    public enum MatchStatus { MATCHED, NO_CAPTURE, AMBIGUOUS }

    public static final class Frame {
        public final int sampleIndex;
        public final long cameraFrameNumber;
        public final long sensorTimestampNs;
        public final long codecPtsUs;
        public final long videoPtsUs;
        public final boolean keyFrame;
        public final String sampleSha256;
        public final boolean matched;
        public final MatchStatus matchStatus;

        private Frame(int sampleIndex, Sample sample, Capture capture, MatchStatus matchStatus) {
            this.sampleIndex = sampleIndex;
            this.cameraFrameNumber = capture == null ? -1 : capture.cameraFrameNumber;
            this.sensorTimestampNs = capture == null ? -1 : capture.sensorTimestampNs;
            this.codecPtsUs = sample.codecPtsUs;
            this.videoPtsUs = sample.videoPtsUs;
            this.keyFrame = sample.keyFrame;
            this.sampleSha256 = sample.sampleSha256;
            this.matchStatus = matchStatus;
            this.matched = matchStatus == MatchStatus.MATCHED;
        }
    }

    private static final class Capture {
        final long cameraFrameNumber;
        final long sensorTimestampNs;

        Capture(long cameraFrameNumber, long sensorTimestampNs) {
            this.cameraFrameNumber = cameraFrameNumber;
            this.sensorTimestampNs = sensorTimestampNs;
        }
    }

    private static final class Sample {
        final long codecPtsUs;
        final long videoPtsUs;
        final boolean keyFrame;
        final String sampleSha256;

        Sample(long codecPtsUs, long videoPtsUs, boolean keyFrame, String sampleSha256) {
            this.codecPtsUs = codecPtsUs;
            this.videoPtsUs = videoPtsUs;
            this.keyFrame = keyFrame;
            this.sampleSha256 = sampleSha256;
        }
    }
}
