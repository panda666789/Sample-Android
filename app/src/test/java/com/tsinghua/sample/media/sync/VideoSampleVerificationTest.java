package com.tsinghua.sample.media.sync;

import org.junit.Test;
import static org.junit.Assert.*;

public class VideoSampleVerificationTest {
    @Test public void acceptsMuxerTimingAdjustmentWhenEverySampleIdentityMatches() {
        PairedVideoExporter.Verification check = expected("aa", "bb");
        check.acceptSample(0, true, 0, "aa");
        check.acceptSample(40_059, false, 0, "bb");
        check.finish();
        assertTrue(check.errors.toString(), check.valid);
        assertEquals(40_059, check.actualPtsUs[1]);
        assertEquals(7059, check.maxPtsErrorUs);
    }

    @Test public void rejectsMissingExpectedDigestEvenWhenPtsMatch() {
        PairedVideoExporter.Verification check = expected(null, "bb");
        check.acceptSample(0, true, 0, "aa");
        check.acceptSample(33_000, false, 0, "bb");
        check.finish();
        assertFalse(check.valid);
    }

    @Test public void rejectsChangedPayloadEvenWhenCountKeysAndPtsMatch() {
        PairedVideoExporter.Verification check = expected("aa", "bb");
        check.acceptSample(0, true, 0, "aa");
        check.acceptSample(33_000, false, 0, "different");
        check.finish();
        assertFalse(check.valid);
    }

    @Test public void rejectsReorderedPayloads() {
        PairedVideoExporter.Verification check = expected("aa", "bb");
        check.acceptSample(0, true, 0, "bb");
        check.acceptSample(33_000, false, 0, "aa");
        check.finish();
        assertFalse(check.valid);
    }

    @Test public void rejectsMissingSample() {
        PairedVideoExporter.Verification check = expected("aa", "bb");
        check.acceptSample(0, true, 0, "aa");
        check.finish();
        assertFalse(check.valid);
    }

    @Test public void rejectsChangedKeyFlag() {
        PairedVideoExporter.Verification check = expected("aa", "bb");
        check.acceptSample(0, true, 0, "aa");
        check.acceptSample(33_000, true, 0, "bb");
        check.finish();
        assertFalse(check.valid);
    }

    @Test public void rejectsNonMonotonicActualPtsDespiteMatchingContent() {
        PairedVideoExporter.Verification check = expected("aa", "bb");
        check.acceptSample(0, true, 0, "aa");
        check.acceptSample(0, false, 0, "bb");
        check.finish();
        assertFalse(check.valid);
    }

    private static PairedVideoExporter.Verification expected(String first, String second) {
        return new PairedVideoExporter.Verification(new long[]{0, 33_000},
                new boolean[]{true, false}, new String[]{first, second});
    }
}
