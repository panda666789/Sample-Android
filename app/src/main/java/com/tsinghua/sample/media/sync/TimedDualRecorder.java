package com.tsinghua.sample.media.sync;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.RequiresApi;

import com.tsinghua.sample.core.FrameMetadataRecorder;
import com.tsinghua.sample.media.CameraPreviewView;
import com.tsinghua.sample.media.DeviceOrientation;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/** Ordinary dual-camera capture with an auditable sensor-to-encoded-frame timeline. */
@RequiresApi(33)
public final class TimedDualRecorder {
    public interface Listener {
        void onStarted();
        void onFinished(DualRecordingResultWriter.Result result);
    }

    private enum State { NEW, OPENING, CONFIGURING, WARMING, RECORDING, STOPPING, FINISHED }
    private static final int WARMUP_FRAMES = 15;
    private static final String RECORD_TAG = "timed-dual-record";
    private final CameraManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("TimedDualCamera");
    private final Handler cameraHandler;
    private final Executor cameraExecutor;
    private final File sessionDir;
    private final Listener listener;
    private final Track front;
    private final Track back;
    private final List<Track> tracks;
    private final long clockSensorNs;
    private final long clockWallMs;
    private State state = State.NEW; // All transitions on cameraHandler.
    private String error;
    private boolean finishing;

    private static final class Track {
        final String id;
        final CameraCharacteristics characteristics;
        final SurfaceView preview;
        final Size size;
        final int orientation;
        final File video;
        final File metadata;
        final FrameTimeline timeline = new FrameTimeline();
        CameraDevice camera;
        CameraCaptureSession session;
        SessionConfiguration configuration;
        TimestampedVideoEncoder encoder;
        FrameMetadataRecorder captureLog;
        boolean surfaceReady;
        boolean sequenceDone;
        boolean sessionIdle;
        int sequenceId = -1;
        int warmupFrames;

        Track(String id, CameraCharacteristics characteristics, SurfaceView preview,
              int orientation, File directory, String name, long suffix) throws Exception {
            this.id = id;
            this.characteristics = characteristics;
            this.preview = preview;
            this.orientation = orientation;
            this.size = chooseSize(characteristics);
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IllegalStateException("无法创建录像目录: " + directory);
            }
            video = new File(directory, name + "_camera_" + suffix + ".mp4");
            metadata = new File(directory, "frame_metadata_" + name + "_" + suffix + ".csv");
        }
    }

    /** Construct/start on the UI thread, after other capture modules have initialized their clock. */
    public TimedDualRecorder(Context context, SurfaceView frontView, SurfaceView backView,
                             DeviceOrientation orientation, File sessionDir, Listener listener)
            throws Exception {
        this.manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.sessionDir = sessionDir;
        this.listener = listener;
        String frontId = null, backId = null;
        for (String id : manager.getCameraIdList()) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT && frontId == null) frontId = id;
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK && backId == null) backId = id;
        }
        if (frontId == null || backId == null) throw new IllegalStateException("需要前后两个摄像头");
        boolean concurrent = false;
        for (Set<String> ids : manager.getConcurrentCameraIds()) {
            if (ids.contains(frontId) && ids.contains(backId)) concurrent = true;
        }
        if (!concurrent) throw new IllegalStateException("设备未声明支持前后摄像头并发录像");
        CameraCharacteristics frontInfo = manager.getCameraCharacteristics(frontId);
        CameraCharacteristics backInfo = manager.getCameraCharacteristics(backId);
        requireRealtime(frontInfo);
        requireRealtime(backInfo);
        clockSensorNs = SystemClock.elapsedRealtimeNanos();
        clockWallMs = System.currentTimeMillis();
        long suffix = clockWallMs;
        front = new Track(frontId, frontInfo, frontView, orientation.videoRotation(frontId),
                new File(sessionDir, "front"), "front", suffix);
        back = new Track(backId, backInfo, backView, orientation.videoRotation(backId),
                new File(sessionDir, "back"), "back", suffix);
        tracks = Arrays.asList(front, back);
        thread.start();
        cameraHandler = new Handler(thread.getLooper());
        cameraExecutor = command -> cameraHandler.post(command);
    }

    public String getFrontVideoPath() { return front.video.getAbsolutePath(); }
    public String getFrontDir() { return front.video.getParent(); }

    public void start() {
        for (Track track : tracks) {
            CameraPreviewView.configure(track.preview, track.characteristics,
                    track.size.getWidth(), track.size.getHeight());
            CameraPreviewView.whenReady(track.preview, () -> cameraHandler.post(() -> {
                track.surfaceReady = true;
                configureWhenReady();
            }));
        }
        cameraHandler.post(this::openBoth);
    }

    @SuppressLint("MissingPermission")
    private void openBoth() {
        if (state != State.NEW) return;
        state = State.OPENING;
        cameraHandler.postDelayed(() -> {
            if (state == State.OPENING || state == State.CONFIGURING || state == State.WARMING) {
                fail("双摄准备超时");
            }
        }, 12000);
        try {
            // Open both devices before configuring either session (Camera2 concurrent contract).
            for (Track track : tracks) {
                manager.openCamera(track.id, new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice camera) {
                        // Delivery on main remains valid even if our worker was closed during open.
                        if (!cameraHandler.post(() -> {
                            if (state != State.OPENING) { camera.close(); return; }
                            track.camera = camera;
                            configureWhenReady();
                        })) camera.close();
                    }
                    @Override public void onDisconnected(CameraDevice camera) {
                        camera.close();
                        cameraHandler.post(() -> fail("摄像头断开: " + track.id));
                    }
                    @Override public void onError(CameraDevice camera, int code) {
                        camera.close();
                        cameraHandler.post(() -> fail("摄像头错误: " + track.id + " / " + code));
                    }
                }, main);
            }
        } catch (Exception e) { fail("打开双摄失败: " + e); }
    }

    private void configureWhenReady() {
        if (state != State.OPENING || front.camera == null || back.camera == null
                || !front.surfaceReady || !back.surfaceReady) return;
        state = State.CONFIGURING;
        try {
            Map<String, SessionConfiguration> configurations = new HashMap<>();
            for (Track track : tracks) {
                track.captureLog = new FrameMetadataRecorder(track.metadata);
                track.encoder = new TimestampedVideoEncoder(track.video, track.size.getWidth(),
                        track.size.getHeight(), track.orientation, track.timeline,
                        e -> cameraHandler.post(() -> fail("视频编码失败: " + e)));
                OutputConfiguration preview = new OutputConfiguration(track.preview.getHolder().getSurface());
                OutputConfiguration recording = new OutputConfiguration(track.encoder.getInputSurface());
                recording.setTimestampBase(OutputConfiguration.TIMESTAMP_BASE_SENSOR);
                if (Build.VERSION.SDK_INT >= 34) recording.setReadoutTimestampEnabled(false);
                track.configuration = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                        Arrays.asList(preview, recording), cameraExecutor,
                        new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(CameraCaptureSession session) {
                                if (state != State.CONFIGURING) { session.close(); return; }
                                track.session = session;
                                warmBothWhenReady();
                            }
                            @Override public void onConfigureFailed(CameraCaptureSession session) {
                                session.close();
                                fail("双摄录像配置不受支持: " + track.id);
                            }
                            @Override public void onActive(CameraCaptureSession session) {
                                if (track.session == session) track.sessionIdle = false;
                            }
                            @Override public void onReady(CameraCaptureSession session) {
                                if (track.session != session) return;
                                track.sessionIdle = true;
                                finishWhenDrained();
                            }
                        });
                configurations.put(track.id, track.configuration);
            }
            if (!manager.isConcurrentSessionConfigurationSupported(configurations)) {
                throw new IllegalStateException("设备不支持当前双摄分辨率和预览组合");
            }
            for (Track track : tracks) track.camera.createCaptureSession(track.configuration);
        } catch (SecurityException e) {
            fail("相机权限已撤销，无法准备双摄录像");
        } catch (Exception e) { fail("准备双摄录像失败: " + e); }
    }

    private void warmBothWhenReady() {
        if (state != State.CONFIGURING || front.session == null || back.session == null) return;
        state = State.WARMING;
        try {
            for (Track track : tracks) {
                track.sessionIdle = false;
                track.session.setRepeatingRequest(request(track, false), callback(track), cameraHandler);
            }
        } catch (Exception e) { fail("双摄预热失败: " + e); }
    }

    private CaptureRequest request(Track track, boolean record) throws CameraAccessException {
        CaptureRequest.Builder builder = track.camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        builder.addTarget(track.preview.getHolder().getSurface());
        if (record) {
            builder.addTarget(track.encoder.getInputSurface());
            builder.setTag(RECORD_TAG);
        }
        Range<Integer>[] ranges = track.characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges != null) {
            for (Range<Integer> range : ranges) {
                if (range.getLower() == 30 && range.getUpper() == 30) {
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range);
                    break;
                }
            }
        }
        if (track == back && Boolean.TRUE.equals(track.characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) {
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
        }
        return builder.build();
    }

    private CameraCaptureSession.CaptureCallback callback(Track track) {
        return new CameraCaptureSession.CaptureCallback() {
            @Override public void onCaptureCompleted(CameraCaptureSession session,
                    CaptureRequest request, TotalCaptureResult result) {
                if (RECORD_TAG.equals(request.getTag())) {
                    Long sensorNs = result.get(TotalCaptureResult.SENSOR_TIMESTAMP);
                    if (sensorNs != null) {
                        track.timeline.addCapture(result.getFrameNumber(), sensorNs);
                        if (track.captureLog != null) track.captureLog.record(sensorNs, result.getFrameNumber());
                    }
                } else if (state == State.WARMING) {
                    track.warmupFrames++;
                    if (front.warmupFrames >= WARMUP_FRAMES && back.warmupFrames >= WARMUP_FRAMES) beginRecording();
                }
            }
            @Override public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request,
                                                   CaptureFailure failure) {
                if (RECORD_TAG.equals(request.getTag()) && state == State.RECORDING) {
                    fail("双摄丢失采集结果: " + track.id + " / " + failure.getFrameNumber());
                }
            }
            @Override public void onCaptureSequenceCompleted(CameraCaptureSession session, int id, long lastFrame) {
                sequenceEnded(track, id);
            }
            @Override public void onCaptureSequenceAborted(CameraCaptureSession session, int id) {
                if (id == track.sequenceId && state == State.RECORDING) fail("录像采集请求中断: " + track.id);
                sequenceEnded(track, id);
            }
        };
    }

    private void beginRecording() {
        if (state != State.WARMING) return;
        state = State.RECORDING;
        try {
            for (Track track : tracks) {
                track.sessionIdle = false;
                track.sequenceId = track.session.setRepeatingRequest(request(track, true), callback(track), cameraHandler);
            }
            main.post(listener::onStarted);
        } catch (Exception e) { fail("启动双摄录像失败: " + e); }
    }

    public void stop() { cameraHandler.post(() -> stopInternal(null)); }

    private void fail(String reason) {
        if (state == State.FINISHED || finishing) return;
        stopInternal(reason);
    }

    private void stopInternal(String reason) {
        if (state == State.FINISHED || finishing) return;
        if (reason != null && error == null) error = reason;
        if (state == State.STOPPING) return;
        if (state != State.RECORDING && error == null) error = "录制在双摄准备完成前被停止";
        state = State.STOPPING;
        for (Track track : tracks) {
            track.sequenceDone = track.sequenceId < 0;
            if (track.session != null) {
                try { track.session.stopRepeating(); }
                catch (Exception e) { track.sequenceDone = true; if (error == null) error = "停止采集失败: " + e; }
            }
        }
        cameraHandler.postDelayed(() -> {
            if (state == State.STOPPING && !finishing) {
                if (error == null) error = "等待双摄最后一帧超时";
                finishFiles();
            }
        }, 2500);
        finishWhenDrained();
    }

    private void sequenceEnded(Track track, int id) {
        if (id == track.sequenceId) track.sequenceDone = true;
        finishWhenDrained();
    }

    private void finishWhenDrained() {
        if (state != State.STOPPING || finishing) return;
        for (Track track : tracks) {
            if (!track.sequenceDone) return;
            // Sequence completion covers metadata; onReady also covers outstanding outputs.
            if (track.sequenceId >= 0 && !track.sessionIdle) return;
        }
        finishFiles();
    }

    private void finishFiles() {
        if (finishing) return;
        finishing = true;
        // Both capture results and session outputs have drained, or timeout is an explicit error.
        for (Track track : tracks) {
            if (track.session != null) track.session.close();
            if (track.camera != null) track.camera.close();
        }
        new Thread(() -> {
            String failure = error;
            for (Track track : tracks) {
                try {
                    if (track.encoder != null) {
                        if (track.sequenceId >= 0) track.encoder.finish(); else track.encoder.abort();
                    }
                } catch (Exception e) { if (failure == null) failure = "结束编码失败: " + e; }
                if (track.captureLog != null) track.captureLog.close();
            }
            DualRecordingResultWriter.Result result = DualRecordingResultWriter.write(sessionDir,
                    front.video, back.video, front.timeline, back.timeline, clockSensorNs, clockWallMs,
                    front.orientation, back.orientation, failure);
            cameraHandler.post(() -> {
                state = State.FINISHED;
                main.post(() -> listener.onFinished(result));
                thread.quitSafely();
            });
        }, "DualVideoFinalize").start();
    }

    private static void requireRealtime(CameraCharacteristics info) {
        Integer source = info.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        if (source == null || source != CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) {
            throw new IllegalStateException("摄像头未提供可比较的时间基准，不能验证双摄同步");
        }
    }

    private static Size chooseSize(CameraCharacteristics info) {
        Size[] sizes = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(MediaRecorder.class);
        for (Size size : sizes) if (size.getWidth() == 1920 && size.getHeight() == 1080) return size;
        for (Size size : sizes) if (size.getWidth() == 1280 && size.getHeight() == 720) return size;
        throw new IllegalStateException("摄像头不支持 1080p 或 720p 录像");
    }
}
