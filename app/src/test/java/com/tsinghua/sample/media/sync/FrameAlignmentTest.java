package com.tsinghua.sample.media.sync;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class FrameAlignmentTest {
    @Test
    public void startupOffsetDoesNotBecomeEveryPairOffset() {
        long ms = 1_000_000L;
        FrameAlignment.Metrics result = FrameAlignment.analyze(
                frames(0, 33 * ms, 66 * ms, 99 * ms, 132 * ms, 165 * ms),
                frames(93 * ms, 126 * ms, 159 * ms, 192 * ms));
        assertTrue(result.mappingComplete);
        assertEquals(6, result.frontSamples);
        assertEquals(4, result.backSamples);
        assertEquals(93 * ms, result.startOffsetNs);
        assertEquals(93 * ms, result.commonStartNs);
        assertEquals(165 * ms, result.commonEndNs);
        assertEquals(3, result.pairs.size());
        assertEquals(3, result.pairs.get(0).frontIndex);
        assertEquals(0, result.pairs.get(0).backIndex);
        assertEquals(-6 * ms, result.pairs.get(0).deltaNs);
        assertEquals(6 * ms, result.medianAbsNs);
        assertEquals(6 * ms, result.p95AbsNs);
        assertEquals(6 * ms, result.maxAbsNs);
        assertEquals(0, result.repeatedBackFrames);
    }

    @Test
    public void droppedFramesRemainVisibleAsGapsAndRepeatedNearestMatches() {
        long ms = 1_000_000L;
        FrameAlignment.Metrics result = FrameAlignment.analyze(
                frames(0, 10 * ms, 20 * ms, 30 * ms, 40 * ms),
                frames(0, 30 * ms, 40 * ms));
        assertEquals(5, result.pairs.size());
        assertEquals(2, result.repeatedBackFrames);
        assertEquals(10 * ms, result.maxFrontGapNs);
        assertEquals(30 * ms, result.maxBackGapNs);
        assertEquals(0, result.medianAbsNs);
        assertEquals(10 * ms, result.p95AbsNs);
        assertEquals(10 * ms, result.maxAbsNs);
    }

    @Test
    public void pairsUseOnlySamplesInsideInclusiveCommonInterval() {
        FrameAlignment.Metrics result = FrameAlignment.analyze(
                frames(0, 10_000, 20_000, 30_000),
                frames(10_000, 20_000));
        assertEquals(2, result.pairs.size());
        assertEquals(1, result.pairs.get(0).frontIndex);
        assertEquals(2, result.pairs.get(1).frontIndex);
        assertEquals(0, result.pairs.get(0).deltaNs);
        assertEquals(0, result.pairs.get(1).deltaNs);
    }

    @Test
    public void nanosecondDifferenceIsNotRoundedToMicroseconds() {
        FrameAlignment.Metrics result = FrameAlignment.analyze(
                frames(0, 20_001, 40_000),
                frames(0, 20_899, 40_000));
        assertEquals(898, result.pairs.get(1).deltaNs);
        assertEquals(898, result.maxAbsNs);
    }

    @Test
    public void negativeStartOffsetAndTieChooseEarlierBackSample() {
        FrameAlignment.Metrics result = FrameAlignment.analyze(
                frames(10_000, 20_000, 30_000),
                frames(0, 10_000, 15_000, 25_000, 30_000));
        assertEquals(-10_000, result.startOffsetNs);
        assertEquals(2, result.pairs.get(1).backIndex);
        assertEquals(-5_000, result.pairs.get(1).deltaNs);
    }

    @Test
    public void incompleteMappingHasNoPairsOrFabricatedTimingMetrics() {
        FrameTimeline timeline = new FrameTimeline();
        timeline.addSample(100, 0, true);
        FrameAlignment.Metrics result = FrameAlignment.analyze(
                timeline.snapshot(), frames(0, 10_000));
        assertFalse(result.mappingComplete);
        assertEquals(1, result.frontSamples);
        assertEquals(2, result.backSamples);
        assertTrue(result.pairs.isEmpty());
        assertEquals(-1, result.startOffsetNs);
        assertEquals(-1, result.commonStartNs);
        assertEquals(-1, result.medianAbsNs);
        assertEquals(-1, result.maxFrontGapNs);
    }

    @Test
    public void emptyInputIsIncomplete() {
        FrameAlignment.Metrics result = FrameAlignment.analyze(
                frames(), frames(10_000));
        assertFalse(result.mappingComplete);
        assertTrue(result.pairs.isEmpty());
    }

    @Test
    public void noOverlapIsNotReportedAsZeroError() {
        FrameAlignment.Metrics result = FrameAlignment.analyze(
                frames(0, 10_000), frames(20_000, 30_000));
        assertTrue(result.mappingComplete);
        assertTrue(result.pairs.isEmpty());
        assertEquals(-1, result.medianAbsNs);
        assertEquals(-1, result.p95AbsNs);
        assertEquals(-1, result.maxAbsNs);
    }

    @Test
    public void outOfOrderSamplesKeepEncodedIndices() {
        FrameAlignment.Metrics result = FrameAlignment.analyze(
                frames(20_000, 0, 10_000), frames(0, 10_000, 20_000));
        assertEquals(1, result.pairs.get(0).frontIndex);
        assertEquals(2, result.pairs.get(1).frontIndex);
        assertEquals(0, result.pairs.get(2).frontIndex);
        assertEquals(10_000, result.maxFrontGapNs);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void pairListIsImmutable() {
        FrameAlignment.analyze(frames(10_000), frames(10_000)).pairs.clear();
    }

    @Test
    public void percentileUsesNearestRankAndEvenMedianUsesBothMiddleValues() {
        long[] frontNs = new long[100];
        long[] backNs = new long[100];
        for (int i = 0; i < 100; i++) {
            frontNs[i] = i * 100_000L;
            backNs[i] = frontNs[i] + (i == 99 ? 0 : i * 100L);
        }
        FrameAlignment.Metrics result = FrameAlignment.analyze(frames(frontNs), frames(backNs));
        assertEquals(100, result.pairs.size());
        assertEquals(4_850, result.medianAbsNs);
        assertEquals(9_300, result.p95AbsNs);
        assertEquals(9_800, result.maxAbsNs);
    }

    @Test
    public void overlappingIntervalsWithoutBackSamplesDoNotCreatePairs() {
        FrameAlignment.Metrics result = FrameAlignment.analyze(
                frames(5_000, 10_000), frames(0, 20_000));
        assertTrue(result.mappingComplete);
        assertEquals(5_000, result.commonStartNs);
        assertEquals(10_000, result.commonEndNs);
        assertTrue(result.pairs.isEmpty());
        assertEquals(-1, result.maxAbsNs);
    }

    private static List<FrameTimeline.Frame> frames(long... sensorNs) {
        FrameTimeline timeline = new FrameTimeline();
        for (int i = 0; i < sensorNs.length; i++) {
            timeline.addCapture(i, sensorNs[i]);
            timeline.addSample(sensorNs[i] / 1_000, i * 33_333L, i == 0);
        }
        return timeline.snapshot();
    }
}
