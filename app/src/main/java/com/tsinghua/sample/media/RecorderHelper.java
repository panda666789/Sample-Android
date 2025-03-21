package com.tsinghua.sample.media;

import static android.content.Context.MODE_PRIVATE;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class RecorderHelper {
    private static final String TAG = "RecorderHelper";
    private CameraHelper cameraHelper;
    private MediaRecorder mediaRecorderFront, mediaRecorderBack;
    private ImageReader imageReaderFront, imageReaderBack;
    public String startTimestamp;
    public String stopTimestamp;
    public File outputDirectory;
    private List<String> frameDataFront = new ArrayList<>();
    private List<String> frameDataBack = new ArrayList<>();
    public File newOutputDirectory;
    private int recordingTimeInSeconds;
    private int recordingTimeInMicroseconds;
    private long initialRecordingTime;
    private long startTime;
    public String flashOnTime= "";
    public String flashStopTime = "";
     public boolean isFlashlightOn = true;
     public boolean isFirstTime = true;
    private Context context;
    public RecorderHelper(CameraHelper cameraHelper, Context context) {
        this.cameraHelper = cameraHelper;
        this.context = context;
        SharedPreferences sharedPreferences = context.getSharedPreferences("SettingsPrefs", MODE_PRIVATE);
        isFlashlightOn = sharedPreferences.getBoolean("flashlight", false);
    }

    public void setupRecording() {

        startTimestamp = generateTimestamp();
        SharedPreferences prefs;
        prefs = context.getSharedPreferences("AppSettings", MODE_PRIVATE);
        String savedExperimentId = prefs.getString("experiment_id", "");
        String directoryPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES) + "/Sample/"+savedExperimentId+"/";
        File directory = new File(directoryPath);
        if (!directory.exists()) {
            if (directory.mkdirs()) {
                Log.d("FileSave", "目录创建成功：" + directoryPath);
            } else {
                Log.e("FileSave", "目录创建失败：" + directoryPath);
                Toast.makeText(context, "无法创建目录，保存失败", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        outputDirectory = new File(directory, "Sample_" + startTimestamp);

        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs();
        }

//      File frontOutputFile = new File(outputDirectory, "front_camera_" + startTimestamp + ".mp4");
        File backOutputFile = new File(outputDirectory, "back_camera_" + startTimestamp + ".mp4");

//      setupMediaRecorder(frontOutputFile.getAbsolutePath(), true, 270);
        setupMediaRecorder(backOutputFile.getAbsolutePath(), false, 90);

        try {
//          Surface frontPreviewSurface = cameraHelper.getSurfaceViewFront().getHolder().getSurface();
            Surface backPreviewSurface = cameraHelper.getSurfaceViewBack().getHolder().getSurface();
//            List<Surface> frontSurfaces = Arrays.asList(frontPreviewSurface, mediaRecorderFront.getSurface());
//            cameraHelper.getCameraDeviceFront().createCaptureSession(frontSurfaces, new CameraCaptureSession.StateCallback() {
//                @Override
//                public void onConfigured(@NonNull CameraCaptureSession session) {
//                    cameraHelper.setCaptureSessionFront(session);
//                    try {
//                        CaptureRequest.Builder builder = cameraHelper.getCameraDeviceFront().createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
//                        builder.addTarget(frontPreviewSurface);
//                        builder.addTarget(mediaRecorderFront.getSurface());
//                        session.setRepeatingRequest(builder.build(), new CameraCaptureSession.CaptureCallback() {
//                            @Override
//                            public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
//                                long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
//                                long frameNumber = result.getFrameNumber();
//                                if(isFirstTime){
//                                    frameDataFront.add(String.valueOf(System.nanoTime())+" "+ timestamp);
//                                    isFirstTime = false;
//                                }
//                                frameDataFront.add(String.format(Locale.getDefault(), "%d,%d", timestamp, frameNumber));
//                            }
//                        }, null);
//                    } catch (CameraAccessException e) {
//                        e.printStackTrace();
//                    }
//                }
//
//                @Override
//                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
//                    Log.e(TAG, "Front camera capture session configuration failed.");
//                }
//            }, null);

            List<Surface> backSurfaces = Arrays.asList(backPreviewSurface, mediaRecorderBack.getSurface());
            cameraHelper.getCameraDeviceBack().createCaptureSession(backSurfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    cameraHelper.setCaptureSessionBack(session);
                    try {
                        CaptureRequest.Builder builder = cameraHelper.getCameraDeviceBack().createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        builder.addTarget(backPreviewSurface);
                        builder.addTarget(mediaRecorderBack.getSurface());
                        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
                        if (isFlashlightOn) {
                            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
                        } else {
                            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                        }
                        session.setRepeatingRequest(builder.build(), new CameraCaptureSession.CaptureCallback() {
                            @Override
                            public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {

                                long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                                long frameNumber = result.getFrameNumber();
                                frameDataBack.add(String.format(Locale.getDefault(), "%d,%d", timestamp, frameNumber));
                            }
                        }, null);

                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                    startTicker();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "Back camera capture session configuration failed.");
                }
            }, null);
//          mediaRecorderFront.start();
            mediaRecorderBack.start();
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private String generateTimestamp() {
        return String.valueOf(System.nanoTime());
    }
    private void setupMediaRecorder(String outputPath, boolean isFront, int rotate) {
        MediaRecorder mediaRecorder = new MediaRecorder();
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo selectedDevice = null;

        if (audioManager != null) {
            AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
            for (AudioDeviceInfo device : devices) {
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    if(isFront) {
                        selectedDevice = device;
                        break;
                    }
                    if(!isFront){
                        selectedDevice = device;
                    }
                }
            }
        }
        if (selectedDevice != null) {
            mediaRecorder.setPreferredDevice(selectedDevice);
            Log.d(TAG, "Using microphone: " + selectedDevice.getId());
        }
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setOutputFile(outputPath);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setVideoSize(1920, 1080);
        mediaRecorder.setVideoFrameRate(30);
        mediaRecorder.setVideoEncodingBitRate(10000000);
        mediaRecorder.setOrientationHint(rotate);

        try {
            mediaRecorder.prepare();
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "Error during MediaRecorder preparation: " + e.getMessage());
        }

        if (isFront) {
            mediaRecorderFront = mediaRecorder;
        } else {
            mediaRecorderBack = mediaRecorder;
        }
    }

    public void stopRecording() {
        try {
            if (mediaRecorderBack != null && mediaRecorderFront != null) {
                cameraHelper.getCaptureSessionBack().stopRepeating();
                mediaRecorderBack.stop();

//               cameraHelper.getCaptureSessionFront().stopRepeating();
//               mediaRecorderFront.stop();

                mediaRecorderBack.reset();
//                mediaRecorderFront.reset();
                mediaRecorderBack.release();
//                mediaRecorderFront.release();

                mediaRecorderBack = null;
//                mediaRecorderFront = null;
            }
        } catch (RuntimeException | CameraAccessException e) {
            e.printStackTrace();
        }

        if (cameraHelper.isRecording()) return;

        stopTimestamp = generateTimestamp();
        SharedPreferences prefs;
        prefs = context.getSharedPreferences("AppSettings", MODE_PRIVATE);
        String savedExperimentId = prefs.getString("experiment_id", "");
        String directoryPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES) + "/Sample/"+savedExperimentId+"/";
        File directory = new File(directoryPath);
        if (!directory.exists()) {
            if (directory.mkdirs()) {
                Log.d("FileSave", "目录创建成功：" + directoryPath);
            } else {
                Log.e("FileSave", "目录创建失败：" + directoryPath);
                Toast.makeText(context, "无法创建目录，保存失败", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        newOutputDirectory = new File(directory, "Sample_" + startTimestamp + "_" + stopTimestamp);
        if (outputDirectory.renameTo(newOutputDirectory)) {
            Log.d(TAG, "Folder renamed to: " + newOutputDirectory.getAbsolutePath());
        } else {
            Log.e(TAG, "Failed to rename folder.");
        }

//      saveDataToFile(newOutputDirectory, "front_camera_data_" + startTimestamp + ".txt", frameDataFront);
        saveDataToFile(newOutputDirectory, "back_camera_data_" + startTimestamp + ".txt", frameDataBack);

//      cameraHelper.startPreview(cameraHelper.getCameraDeviceFront(), cameraHelper.getSurfaceViewFront().getHolder().getSurface(), true);
        cameraHelper.startPreview(cameraHelper.getCameraDeviceBack(), cameraHelper.getSurfaceViewBack().getHolder().getSurface(), false);
//        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
//        String cameraId;
//        try {
//            // 获取支持闪光灯的摄像头 ID
//            for (String id : cameraManager.getCameraIdList()) {
//                if (cameraManager.getCameraCharacteristics(id)
//                        .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE)) {
//                    cameraId = id;
//                    cameraManager.setTorchMode(cameraId, false);
//                    break;
//                }
//            }
//        } catch (CameraAccessException e) {
//            e.printStackTrace();
//        }
    }

    private void saveDataToFile(File directory, String filename, List<String> data) {
        File externalFile = new File(directory, filename);
        Log.e(TAG, "Saving to: " + externalFile.getAbsolutePath());
        try (FileOutputStream fos = new FileOutputStream(externalFile);
             OutputStreamWriter writer = new OutputStreamWriter(fos)) {
            for (String line : data) {
                writer.write(line);
                writer.write("\n");
            }
            writer.write(Integer.toString(data.size()));
            writer.write("\n");
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "Error saving data to file: " + e.getMessage());
        }
    }

    private void startTicker() {
        startTime = System.currentTimeMillis();
        cameraHelper.getCalHandler().post(cameraHelper.getTicker());
    }

    public Runnable createTicker(Handler mCalHandler, TextView textViewTimer, Button btnRecord,IMURecorder imuRecorder,MultiMicAudioRecorderHelper multiMicAudioRecorderHelper) {
        return new Runnable() {
            @Override
            public void run() {
                if (cameraHelper.isRecording()) {
                    long elapsedTime = System.currentTimeMillis() - startTime;
                    long remainingTime = initialRecordingTime - (elapsedTime * 1000);
                    if (remainingTime <= 0) {
                        cameraHelper.stopRecording(imuRecorder, multiMicAudioRecorderHelper, new CameraHelper.RecordingStopCallback() {
                            @Override
                            public void onRecordingStopped() {

                            }
                        });
                        return;
                    }

                    int remainingMilliseconds = (int) (remainingTime / 1000);
                    int remainingSeconds = remainingMilliseconds / 1000;
                    int minutes = remainingSeconds / 60;
                    int seconds = remainingSeconds % 60;
//                  cameraHelper.getActivity().runOnUiThread(() -> textViewTimer.setText(String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)));

                    mCalHandler.postDelayed(this, 1000);
                }
            }
        };
    }

}
