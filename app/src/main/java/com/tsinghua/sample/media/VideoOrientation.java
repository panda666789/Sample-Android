package com.tsinghua.sample.media;

/** Rotation in clockwise degrees, as stored in an MP4 display matrix. */
public final class VideoOrientation {
    private VideoOrientation() {}

    public static int rotationDegrees(int sensorOrientation, int deviceOrientation,
                                      boolean frontFacing) {
        // deviceOrientation is OrientationEventListener's clockwise angle, not Display rotation.
        // Android camera-samples/CameraUtils OrientationLiveData uses opposite signs per lens.
        int deviceCorrection = frontFacing ? -deviceOrientation : deviceOrientation;
        return (sensorOrientation + deviceCorrection + 360) % 360;
    }

    public static int roundDeviceOrientation(int degrees) {
        return ((degrees + 45) / 90 * 90) % 360;
    }

    public static int deviceDegreesFromDisplayRotation(int surfaceRotation) {
        return (360 - surfaceRotation * 90) % 360;
    }
}
