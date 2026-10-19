package com.tsinghua.sample.media;

import android.content.Context;
import android.content.res.Configuration;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/** A fixed camera buffer displayed without cropping or stretching, independently of video rotation. */
public final class CameraPreviewView extends SurfaceView {
    private int bufferWidth;
    private int bufferHeight;
    private int sensorOrientation;
    private Runnable pendingReady;

    public CameraPreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) { dispatchReady(); }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
                dispatchReady();
            }
            @Override public void surfaceDestroyed(SurfaceHolder holder) {}
        });
    }

    /** Call on the UI thread before opening the camera; never resize the buffer during a session. */
    public static void configure(SurfaceView view, CameraCharacteristics camera,
                                 int preferredWidth, int preferredHeight) {
        if (!(view instanceof CameraPreviewView)) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Configure camera preview on the UI thread");
        }
        CameraPreviewView preview = (CameraPreviewView) view;
        StreamConfigurationMap map = camera.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Integer sensor = camera.get(CameraCharacteristics.SENSOR_ORIENTATION);
        Size[] sizes = map == null ? null : map.getOutputSizes(SurfaceHolder.class);
        if (sizes == null || sizes.length == 0 || sensor == null) {
            throw new IllegalStateException("Camera does not expose supported preview sizes/orientation");
        }
        Size size = selectSize(sizes, preferredWidth, preferredHeight);
        preview.sensorOrientation = sensor;
        if (preview.bufferWidth != size.getWidth() || preview.bufferHeight != size.getHeight()) {
            preview.bufferWidth = size.getWidth();
            preview.bufferHeight = size.getHeight();
            preview.getHolder().setFixedSize(preview.bufferWidth, preview.bufferHeight);
        }
        preview.requestLayout();
    }

    /** Session creation must wait for setFixedSize to reach the native Surface. */
    public static void whenReady(SurfaceView view, Runnable action) {
        view.post(() -> {
            if (view instanceof CameraPreviewView) {
                CameraPreviewView preview = (CameraPreviewView) view;
                preview.pendingReady = action;
                preview.dispatchReady();
            } else if (view.getHolder().getSurface().isValid()) {
                action.run();
            }
        });
    }

    private void dispatchReady() {
        if (pendingReady == null || !getHolder().getSurface().isValid()
                || getHolder().getSurfaceFrame().width() != bufferWidth
                || getHolder().getSurfaceFrame().height() != bufferHeight) return;
        Runnable action = pendingReady;
        pendingReady = null;
        action.run();
    }

    private static Size selectSize(Size[] sizes, int width, int height) {
        Size best = null;
        double bestAspectError = Double.MAX_VALUE;
        long bestArea = 0;
        Size smallest = sizes[0];
        for (Size size : sizes) {
            if (size.getWidth() == width && size.getHeight() == height) return size;
            long area = (long) size.getWidth() * size.getHeight();
            if (area < (long) smallest.getWidth() * smallest.getHeight()) smallest = size;
            if (size.getWidth() > width || size.getHeight() > height) continue;
            double error = Math.abs(size.getWidth() / (double) size.getHeight() - width / (double) height);
            if (error < bestAspectError || (error == bestAspectError && area > bestArea)) {
                best = size;
                bestAspectError = error;
                bestArea = area;
            }
        }
        return best != null ? best : smallest;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int rotation = getDisplay() == null ? Surface.ROTATION_0 : getDisplay().getRotation();
        float[] scale = PreviewGeometry.fitCenterScale(getMeasuredWidth(), getMeasuredHeight(),
                bufferWidth, bufferHeight, sensorOrientation, rotation);
        // SurfaceView already rotates sensor buffers for the display. Correct only its scaling.
        // Retaining the layout bounds also avoids fighting ConstraintLayout's EXACTLY specs.
        setScaleX(scale[0]);
        setScaleY(scale[1]);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        requestLayout();
    }

    @Override
    protected void onDetachedFromWindow() {
        pendingReady = null;
        super.onDetachedFromWindow();
    }
}
