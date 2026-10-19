package com.tsinghua.sample.media.sync;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class PairedClipSelectionTest {
    @Test public void finalFileFpsUsesTheInferenceEnginesExactBounds() {
        assertTrue(PairedClipSelection.acceptsInferenceFps(25.0));
        assertTrue(PairedClipSelection.acceptsInferenceFps(35.0));
        assertFalse(PairedClipSelection.acceptsInferenceFps(1000 * 1_000_000.0 / 40_000_001));
        assertFalse(PairedClipSelection.acceptsInferenceFps(1000 * 1_000_000.0 / 28_571_428));
    }

    @Test public void keepsRealStartDifferenceAndLimitsDuration() {
        List<FrameTimeline.Frame> front = frames(1_000_000_000L, 1500, 30, 30, 0);
        List<FrameTimeline.Frame> back = frames(1_020_000_000L, 1500, 30, 30, 0);
        PairedClipSelection.Selection clip = PairedClipSelection.select(front, back);
        assertEquals(0, clip.frontStart);
        assertEquals(0, clip.backStart);
        assertEquals(20_000_000L, clip.startOffsetNs);
        assertTrue(clip.commonEndNs - clip.commonStartNs >= 18_000_000_000L);
        assertTrue(front.get(clip.frontEnd).sensorTimestampNs
                - front.get(clip.frontStart).sensorTimestampNs <= 30_000_000_000L);
        assertTrue(back.get(clip.backEnd).sensorTimestampNs
                - back.get(clip.backStart).sensorTimestampNs <= 30_000_000_000L);
        assertTrue("MP4's final sample also has a duration",
                durationUs(front, clip.frontStart, clip.frontEnd) <= 30_000_000L);
        assertTrue(durationUs(back, clip.backStart, clip.backEnd) <= 30_000_000L);
    }

    @Test public void searchesLaterKeyframesInsteadOfInventingSynchronizedStart() {
        List<FrameTimeline.Frame> front = frames(1_000_000_000L, 1200, 30, 30, 0);
        List<FrameTimeline.Frame> back = frames(1_400_000_000L, 1200, 30, 18, 0);
        PairedClipSelection.Selection clip = PairedClipSelection.select(front, back);
        assertEquals(30, clip.frontStart);
        assertEquals(18, clip.backStart);
        assertEquals(0, clip.startOffsetNs);
        assertTrue(front.get(clip.frontStart).keyFrame);
        assertTrue(back.get(clip.backStart).keyFrame);
    }

    @Test public void rejectsKeyframesTooFarApart() {
        reject(frames(1_000_000_000L, 1200, 30, 30, 0),
                frames(1_400_000_000L, 1200, 30, 30, 0), "关键帧");
    }

    @Test public void rejectsUnderEighteenSecondsOfOverlap() {
        reject(frames(1_000_000_000L, 540, 30, 30, 0),
                frames(1_020_000_000L, 540, 30, 30, 0), "18");
    }

    @Test public void acceptsExactlyEighteenSecondsWithIdenticalStarts() {
        List<FrameTimeline.Frame> frames = frames(1_000_000_000L, 541, 30, 30, 0);
        assertEquals(18_000_000_000L,
                PairedClipSelection.select(frames, frames).commonEndNs - 1_000_000_000L);
    }

    @Test public void rejectsUnsupportedSourceFrameRate() {
        reject(frames(1_000_000_000L, 1200, 24, 24, 0),
                frames(1_000_000_000L, 1200, 30, 30, 0), "25–35");
    }

    @Test public void rejectsUnmatchedSourceSamples() {
        reject(frames(1_000_000_000L, 1200, 30, 30, 1),
                frames(1_000_000_000L, 1200, 30, 30, 0), "映射");
    }

    @Test public void handlesDifferentFrameRatesWithinLimit() {
        PairedClipSelection.Selection clip = PairedClipSelection.select(
                frames(1_000_000_000L, 1400, 25, 25, 0),
                frames(1_010_000_000L, 1800, 35, 35, 0));
        assertTrue(clip.frontEnd - clip.frontStart + 1 <= 1800);
        assertTrue(clip.backEnd - clip.backStart + 1 <= 1800);
        assertEquals(25, clip.frontFps, 0.0001);
        assertEquals(35, clip.backFps, 0.0001);
    }

    @Test public void rejectsVideoTimelineThatDoesNotFollowSensorTime() {
        FrameTimeline timeline = new FrameTimeline();
        for (int i = 0; i < 1200; i++) {
            long sensorNs = 1_000_000_000L + i * 1_000_000_000L / 30;
            timeline.addCapture(i, sensorNs);
            timeline.addSample(sensorNs / 1000, i * 40_000L, i % 30 == 0);
        }
        reject(timeline.snapshot(), frames(1_000_000_000L, 1200, 30, 30, 0), "时间轴");
    }

    @Test public void searchesPastSlowOpeningAlthoughWholeSourceAverageIsSupported() {
        List<FrameTimeline.Frame> frames = slowOpening(true);
        PairedClipSelection.Selection clip = PairedClipSelection.select(frames, frames);
        assertTrue("First 30 seconds are only 20 FPS", clip.frontStart > 0);
        double selectedFps = (clip.frontEnd - clip.frontStart) * 1_000_000.0
                / (frames.get(clip.frontEnd).videoPtsUs - frames.get(clip.frontStart).videoPtsUs);
        assertTrue("The actual selected interval must meet 25–35 FPS", selectedFps >= 25);
        assertTrue(selectedFps <= 35);
    }

    @Test public void rejectsSlowClipWhenThereIsNoLaterKeyframe() {
        List<FrameTimeline.Frame> frames = slowOpening(false);
        reject(frames, frames, "25–35");
    }

    private static List<FrameTimeline.Frame> slowOpening(boolean laterKeys) {
        FrameTimeline timeline = new FrameTimeline();
        // First 30 seconds: 20 FPS; next 60 seconds: 30 FPS; whole source: ~26.67 FPS.
        for (int i = 0; i < 2400; i++) {
            long relativeNs = i < 600 ? i * 50_000_000L
                    : 30_000_000_000L + (i - 600) * 1_000_000_000L / 30;
            long sensorNs = 1_000_000_000L + relativeNs;
            boolean key = i == 0 || laterKeys && (i < 600 ? i % 20 == 0 : (i - 600) % 30 == 0);
            timeline.addCapture(i, sensorNs);
            timeline.addSample(sensorNs / 1000, relativeNs / 1000, key);
        }
        return timeline.snapshot();
    }

    private static void reject(List<FrameTimeline.Frame> front,
            List<FrameTimeline.Frame> back, String reason) {
        try {
            PairedClipSelection.select(front, back);
            fail("Expected export rejection");
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage(), error.getMessage().contains(reason));
        }
    }

    private static long durationUs(List<FrameTimeline.Frame> frames, int start, int end) {
        return frames.get(end).videoPtsUs - frames.get(start).videoPtsUs
                + frames.get(end).videoPtsUs - frames.get(end - 1).videoPtsUs;
    }

    private static List<FrameTimeline.Frame> frames(long startNs, int count, int fps,
            int keyPeriod, int missingCapture) {
        FrameTimeline timeline = new FrameTimeline();
        for (int i = 0; i < count; i++) {
            long sensorNs = startNs + i * 1_000_000_000L / fps;
            if (missingCapture == 0 || i != missingCapture) timeline.addCapture(i, sensorNs);
            timeline.addSample(sensorNs / 1000, sensorNs / 1000 - startNs / 1000,
                    i % keyPeriod == 0);
        }
        return timeline.snapshot();
    }
}
