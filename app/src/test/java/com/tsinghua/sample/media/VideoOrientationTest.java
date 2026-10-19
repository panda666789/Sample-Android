package com.tsinghua.sample.media;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class VideoOrientationTest {
    @Test
    public void frontCameraSupportsPortraitAndBothLandscapePositions() {
        assertEquals(270, VideoOrientation.rotationDegrees(270, 0, true));
        assertEquals(180, VideoOrientation.rotationDegrees(270, 90, true));
        assertEquals(90, VideoOrientation.rotationDegrees(270, 180, true));
        // Pad 7 held in landscape: raw landscape video needs no extra quarter turn.
        assertEquals(0, VideoOrientation.rotationDegrees(270, 270, true));
    }

    @Test
    public void backCameraUsesTheOppositeDeviceRotationSign() {
        assertEquals(90, VideoOrientation.rotationDegrees(90, 0, false));
        assertEquals(180, VideoOrientation.rotationDegrees(90, 90, false));
        assertEquals(270, VideoOrientation.rotationDegrees(90, 180, false));
        assertEquals(0, VideoOrientation.rotationDegrees(90, 270, false));
    }

    @Test
    public void camerasWithOtherSensorMountingsAreNotAssumedToBePhones() {
        assertEquals(0, VideoOrientation.rotationDegrees(0, 0, true));
        assertEquals(270, VideoOrientation.rotationDegrees(0, 90, true));
        assertEquals(90, VideoOrientation.rotationDegrees(180, 270, false));
        assertEquals(0, VideoOrientation.rotationDegrees(90, 90, true));
    }

    @Test
    public void physicalOrientationIsRoundedAtTheQuadrantBoundaries() {
        assertEquals(0, VideoOrientation.roundDeviceOrientation(44));
        assertEquals(90, VideoOrientation.roundDeviceOrientation(45));
        assertEquals(90, VideoOrientation.roundDeviceOrientation(134));
        assertEquals(180, VideoOrientation.roundDeviceOrientation(135));
        assertEquals(270, VideoOrientation.roundDeviceOrientation(225));
        assertEquals(0, VideoOrientation.roundDeviceOrientation(315));
        assertEquals(0, VideoOrientation.roundDeviceOrientation(359));
    }

    @Test
    public void displayRotationIsConvertedToClockwisePhysicalRotation() {
        assertEquals(0, VideoOrientation.deviceDegreesFromDisplayRotation(0));
        assertEquals(270, VideoOrientation.deviceDegreesFromDisplayRotation(1));
        assertEquals(180, VideoOrientation.deviceDegreesFromDisplayRotation(2));
        assertEquals(90, VideoOrientation.deviceDegreesFromDisplayRotation(3));
    }
}
