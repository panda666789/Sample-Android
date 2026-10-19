package com.tsinghua.sample.media;

/** Display-only scaling: camera buffer and encoded video dimensions remain unchanged. */
public final class PreviewGeometry {
    private PreviewGeometry() {}

    public static float[] fitCenterScale(int viewWidth, int viewHeight, int bufferWidth,
                                         int bufferHeight, int sensorOrientation,
                                         int displayRotation) {
        if (viewWidth <= 0 || viewHeight <= 0 || bufferWidth <= 0 || bufferHeight <= 0) {
            return new float[]{1f, 1f};
        }
        // Only the parity matters for aspect ratio; front/back rotation signs yield the same swap.
        boolean swap = (sensorOrientation - displayRotation * 90 + 360) % 180 != 0;
        int displayWidth = swap ? bufferHeight : bufferWidth;
        int displayHeight = swap ? bufferWidth : bufferHeight;
        float fit = Math.min(viewWidth / (float) displayWidth, viewHeight / (float) displayHeight);
        return new float[]{Math.min(1f, displayWidth * fit / viewWidth),
                Math.min(1f, displayHeight * fit / viewHeight)};
    }
}
