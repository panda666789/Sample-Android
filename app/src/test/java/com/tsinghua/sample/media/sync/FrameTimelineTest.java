package com.tsinghua.sample.media.sync;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class FrameTimelineTest {
    @Test
    public void delayedCaptureMatchesAlreadyWrittenSampleWithoutChangingVideoPts() {
        FrameTimeline timeline = new FrameTimeline();
        timeline.addSample(1_234_567L, 0, true);
        List<FrameTimeline.Frame> beforeCapture = timeline.snapshot();
        assertFalse(beforeCapture.get(0).matched);

        timeline.addCapture(42, 1_234_567_891L);
        FrameTimeline.Frame frame = timeline.snapshot().get(0);
        assertTrue(frame.matched);
        assertEquals(42, frame.cameraFrameNumber);
        assertEquals(1_234_567_891L, frame.sensorTimestampNs);
        assertEquals(1_234_567L, frame.codecPtsUs);
        assertEquals(0, frame.videoPtsUs);
        assertEquals(0, frame.sampleIndex);
        assertTrue(frame.keyFrame);
        assertFalse(beforeCapture.get(0).matched);
    }

    @Test
    public void capturesCanArriveOutOfOrderAndUnencodedCapturesAreNotSamples() {
        FrameTimeline timeline = new FrameTimeline();
        timeline.addCapture(3, 300_999L);
        timeline.addCapture(1, 100_999L);
        timeline.addCapture(2, 200_999L);
        timeline.addSample(100, 0, true);
        timeline.addSample(300, 200, false);

        List<FrameTimeline.Frame> frames = timeline.snapshot();
        assertEquals(3, timeline.captureCount());
        assertEquals(2, timeline.sampleCount());
        assertEquals(2, frames.size());
        assertEquals(1, frames.get(0).cameraFrameNumber);
        assertEquals(3, frames.get(1).cameraFrameNumber);
        assertEquals(1, frames.get(1).sampleIndex);
        assertFalse(frames.get(1).keyFrame);
    }

    @Test
    public void microsecondTolerancePreservesOriginalNanoseconds() {
        FrameTimeline timeline = new FrameTimeline();
        timeline.addCapture(1, 100_999L);
        timeline.addCapture(2, 200_001L);
        timeline.addCapture(3, 300_500L);
        timeline.addSample(101, 0, true);
        timeline.addSample(199, 98, false);
        timeline.addSample(302, 201, false);
        List<FrameTimeline.Frame> frames = timeline.snapshot();
        assertEquals(100_999L, frames.get(0).sensorTimestampNs);
        assertTrue(frames.get(1).matched);
        assertFalse(frames.get(2).matched);
        assertEquals(FrameTimeline.MatchStatus.NO_CAPTURE, frames.get(2).matchStatus);
    }

    @Test
    public void differentClockOffsetIsNotGuessed() {
        FrameTimeline timeline = new FrameTimeline();
        timeline.addCapture(1, 9_000_000_000L);
        timeline.addCapture(2, 9_033_333_333L);
        timeline.addSample(0, 0, true);
        timeline.addSample(33_333, 33_333, false);
        for (FrameTimeline.Frame frame : timeline.snapshot()) {
            assertFalse(frame.matched);
            assertEquals(-1, frame.cameraFrameNumber);
            assertEquals(-1, frame.sensorTimestampNs);
        }
    }

    @Test
    public void duplicateCodecPtsInvalidateBothSamples() {
        FrameTimeline timeline = new FrameTimeline();
        timeline.addCapture(1, 100_000L);
        timeline.addSample(100, 0, true);
        assertTrue(timeline.snapshot().get(0).matched);
        timeline.addSample(100, 1, false);
        for (FrameTimeline.Frame frame : timeline.snapshot()) {
            assertFalse(frame.matched);
            assertEquals(FrameTimeline.MatchStatus.AMBIGUOUS, frame.matchStatus);
        }
    }

    @Test
    public void twoCaptureCandidatesAreAmbiguousEvenWithOneExactMatch() {
        FrameTimeline timeline = new FrameTimeline();
        timeline.addCapture(1, 100_000L);
        timeline.addCapture(2, 101_000L);
        timeline.addSample(100, 0, true);
        assertEquals(FrameTimeline.MatchStatus.AMBIGUOUS,
                timeline.snapshot().get(0).matchStatus);
    }

    @Test
    public void nearbySamplesCannotBothUseOneCapture() {
        FrameTimeline timeline = new FrameTimeline();
        timeline.addCapture(1, 100_000L);
        timeline.addSample(99, 0, true);
        timeline.addSample(101, 2, false);
        assertFalse(timeline.snapshot().get(0).matched);
        assertFalse(timeline.snapshot().get(1).matched);
    }

    @Test
    public void duplicateCaptureTimestampIsAmbiguous() {
        FrameTimeline timeline = new FrameTimeline();
        timeline.addCapture(1, 100_000L);
        timeline.addCapture(2, 100_999L);
        timeline.addSample(100, 0, true);
        assertEquals(FrameTimeline.MatchStatus.AMBIGUOUS,
                timeline.snapshot().get(0).matchStatus);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void snapshotsCannotBeMutated() {
        new FrameTimeline().snapshot().clear();
    }

    @Test
    public void invalidNegativeTimestampsCannotBecomeMatchedFrames() {
        FrameTimeline timeline = new FrameTimeline();
        assertThrows(IllegalArgumentException.class, () -> timeline.addCapture(0, -1));
        assertThrows(IllegalArgumentException.class, () -> timeline.addSample(-1, 0, true));
        assertThrows(IllegalArgumentException.class, () -> timeline.addSample(1, -1, true));
        assertEquals(0, timeline.captureCount());
        assertEquals(0, timeline.sampleCount());
    }

    @Test
    public void separateCallbackThreadsProduceCompleteFinalSnapshot() throws InterruptedException {
        FrameTimeline timeline = new FrameTimeline();
        Thread captures = new Thread(() -> {
            for (int i = 999; i >= 0; i--) timeline.addCapture(i, i * 33_333_333L);
        });
        Thread samples = new Thread(() -> {
            for (int i = 0; i < 1_000; i++) {
                long ptsUs = i * 33_333_333L / 1_000;
                timeline.addSample(ptsUs, ptsUs, i == 0);
            }
        });
        captures.start();
        samples.start();
        timeline.snapshot();
        captures.join();
        samples.join();
        assertEquals(1_000, timeline.snapshot().size());
        for (FrameTimeline.Frame frame : timeline.snapshot()) {
            assertTrue(frame.matched);
            assertEquals(frame.sampleIndex, frame.cameraFrameNumber);
        }
    }

    @Test
    public void writtenSampleIdentitySurvivesLateCaptureMapping() {
        FrameTimeline timeline = new FrameTimeline();
        timeline.addSample(100, 0, true, "abc123");
        assertEquals("abc123", timeline.snapshot().get(0).sampleSha256);
        timeline.addCapture(9, 100_999);
        assertTrue(timeline.snapshot().get(0).matched);
        assertEquals("abc123", timeline.snapshot().get(0).sampleSha256);
    }

    @Test
    public void legacyAndNullDigestsRemainExplicitlyAbsent() {
        FrameTimeline timeline = new FrameTimeline();
        timeline.addCapture(1, 100_000);
        timeline.addCapture(2, 200_000);
        timeline.addSample(100, 0, true);
        timeline.addSample(200, 100, false, null);
        for (FrameTimeline.Frame frame : timeline.snapshot()) {
            assertTrue(frame.matched);
            assertEquals("", frame.sampleSha256);
        }
    }
}
