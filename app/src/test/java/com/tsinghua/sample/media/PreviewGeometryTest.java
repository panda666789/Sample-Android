package com.tsinghua.sample.media;

import org.junit.Test;

import static org.junit.Assert.*;

public class PreviewGeometryTest {
    @Test
    public void portraitImageIsNotStretchedAcrossAWideShortContainer() {
        float[] scale = PreviewGeometry.fitCenterScale(1982, 583, 1920, 1080, 270, 0);
        assertEquals(9f / 16f, 1982 * scale[0] / (583 * scale[1]), .001f);
        assertEquals(1f, scale[1], .001f);
        assertTrue("Portrait preview must leave side margins", scale[0] < .2f);
    }

    @Test
    public void landscapeImageFillsMatchingLandscapeContainer() {
        float[] scale = PreviewGeometry.fitCenterScale(1600, 900, 1920, 1080, 270, 1);
        assertArrayEquals(new float[]{1f, 1f}, scale, .001f);
    }

    @Test
    public void inferenceAndPipRespectFourByThreeBuffers() {
        float[] scale = PreviewGeometry.fitCenterScale(104, 72, 640, 480, 90, 0);
        assertEquals(3f / 4f, 104 * scale[0] / (72 * scale[1]), .001f);
        scale = PreviewGeometry.fitCenterScale(1600, 900, 640, 480, 270, 1);
        assertEquals(4f / 3f, 1600 * scale[0] / (900 * scale[1]), .001f);
    }

    @Test
    public void allDisplayRotationsFitInsideBoundsWithoutCropping() {
        for (int rotation = 0; rotation < 4; rotation++) {
            float[] scale = PreviewGeometry.fitCenterScale(777, 500, 1920, 1080, 90, rotation);
            assertTrue(scale[0] > 0 && scale[0] <= 1f);
            assertTrue(scale[1] > 0 && scale[1] <= 1f);
            float ratio = rotation % 2 == 0 ? 9f / 16f : 16f / 9f;
            assertEquals(ratio, 777 * scale[0] / (500 * scale[1]), .001f);
        }
    }

    @Test
    public void unmeasuredViewDoesNotGenerateInvalidScales() {
        assertArrayEquals(new float[]{1f, 1f},
                PreviewGeometry.fitCenterScale(0, 0, 1920, 1080, 270, 0), 0f);
    }
}
