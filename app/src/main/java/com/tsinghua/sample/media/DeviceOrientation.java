package com.tsinghua.sample.media;

import android.app.Activity;
import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.util.Log;
import android.view.OrientationEventListener;

/** Tracks physical pose even when the activity is portrait-locked or letterboxed. */
public final class DeviceOrientation extends OrientationEventListener {
    private final Activity activity;
    private final CameraManager cameraManager;
    private volatile int deviceDegrees = ORIENTATION_UNKNOWN;

    public DeviceOrientation(Activity activity) {
        super(activity);
        this.activity = activity;
        cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
    }

    @Override
    public void onOrientationChanged(int orientation) {
        // A flat device has no useful gravity direction; retain the last known pose.
        if (orientation != ORIENTATION_UNKNOWN) {
            deviceDegrees = VideoOrientation.roundDeviceOrientation(orientation);
        }
    }

    /** Snapshot once before preparing each video. An MP4 has one orientation for the whole clip. */
    public int videoRotation(String cameraId) throws CameraAccessException {
        CameraCharacteristics camera = cameraManager.getCameraCharacteristics(cameraId);
        Integer sensor = camera.get(CameraCharacteristics.SENSOR_ORIENTATION);
        Integer facing = camera.get(CameraCharacteristics.LENS_FACING);
        if (sensor == null || facing == null) {
            throw new IllegalStateException("Camera orientation is unavailable: " + cameraId);
        }
        int pose = deviceDegrees;
        if (pose == ORIENTATION_UNKNOWN) {
            pose = VideoOrientation.deviceDegreesFromDisplayRotation(
                    activity.getWindowManager().getDefaultDisplay().getRotation());
        }
        int rotation = VideoOrientation.rotationDegrees(sensor, pose,
                facing == CameraCharacteristics.LENS_FACING_FRONT);
        Log.d("VideoOrientation", "camera=" + cameraId + ", sensor=" + sensor
                + ", device=" + pose + ", video=" + rotation);
        return rotation;
    }
}
