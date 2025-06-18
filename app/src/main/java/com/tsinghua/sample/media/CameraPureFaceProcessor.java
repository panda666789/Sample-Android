package com.tsinghua.sample.media;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;


import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;
import com.google.mediapipe.solutions.facemesh.FaceMesh;
import com.google.mediapipe.solutions.facemesh.FaceMeshOptions;
import com.tsinghua.sample.utils.FacePreprocessor;
import com.tsinghua.sample.utils.HeartRateEstimator;
import com.tsinghua.sample.utils.PlotView;

import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.videoio.VideoWriter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;

public class CameraPureFaceProcessor {
    private static final String TAG = "CameraFaceProcessor";
    private static final boolean RUN_ON_GPU = true;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 200;
    private HeartRateEstimator heartRateEstimator;

    private FacePreprocessor facePreProcessor;


    private CameraDevice cameraDevice;
    private CaptureRequest.Builder captureRequestBuilder;
    private CameraCaptureSession cameraCaptureSession;
    private ImageReader imageReader;
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;

    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private VideoWriter writer;


    private FaceMesh faceMesh;

    // Context and lifecycle
    private Context activity;
    static public String outDir;
    private boolean isCameraRunning = false;

    // Callbacks
    private CameraFaceProcessorCallback callback;
    private MediaRecorder mediaRecorder;
    private Surface recorderSurface;
    private String currentVideoPath;
    private File tempDir;         // 用来存每帧的 PNG
    private int frameCount = 0;   // 帧计数
    private String outputVideoPath;
    private com.tsinghua.sample.utils.PlotView plotView;
    public interface CameraFaceProcessorCallback {
        void onCameraStarted();
        void onCameraStopped();
        void onError(String error);
        void onFaceDetected(Bitmap faceBitmap);
        void onFaceProcessingResult(Object result); // Replace Object with your specific result type
    }

    public CameraPureFaceProcessor(Context activity, SurfaceView surfaceView, PlotView plotView)  {

        this.activity = activity;
        this.surfaceView = surfaceView;
        this.callback = callback;
        this.plotView = plotView;
        setupSurfaceView();
        setupFaceMesh();
        startBackgroundThread();
    }

    private void setupSurfaceView() {
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                int width = surfaceView.getWidth();
                int height = surfaceView.getHeight();
                if (width > 0 && height > 0) {
                    setupImageReader(width, height);
//                    startCamera();
                } else {
                    Log.e(TAG, "SurfaceView dimensions are invalid");
                    if (callback != null) {
                        callback.onError("Invalid surface dimensions");
                    }
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                // Handle surface changes if needed
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                stopCamera();
            }
        });
    }

    private void setupFaceMesh() {
        faceMesh = new FaceMesh(
                activity,
                FaceMeshOptions.builder()
                        .setStaticImageMode(false)
                        .setRefineLandmarks(false)
                        .setRunOnGpu(RUN_ON_GPU)
                        .setMaxNumFaces(1)
                        .build());

        faceMesh.setErrorListener((message, e) -> {
            Log.e(TAG, "MediaPipe Face Mesh error: " + message);
            if (callback != null) {
                callback.onError("Face mesh error: " + message);
            }
        });
    }

    private void setupImageReader(int width, int height) {
        if (width > 0 && height > 0) {
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler);
        } else {
            Log.e(TAG, "Invalid dimensions for ImageReader: width=" + width + ", height=" + height);
            if (callback != null) {
                callback.onError("Invalid ImageReader dimensions");
            }
        }
    }

    private final ImageReader.OnImageAvailableListener onImageAvailableListener = reader -> {
        Image image = null;
        try {
            image = reader.acquireNextImage();
            if (image != null && isCameraRunning) {

                Bitmap bitmap = convertYUVToBitmap(image,270);

                long timestamp = System.nanoTime();

                faceMesh.send(bitmap, timestamp);
                faceMesh.setResultListener(result -> {
                    if (isCameraRunning) {
                        // 如果还需要原始方向的 bitmap 作后续处理，可以再 copy 一份
                        facePreProcessor.addFrameResults(result, bitmap.copy(Bitmap.Config.ARGB_8888, true));
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing image", e);
            if (callback != null) {
                callback.onError("Image processing error: " + e.getMessage());
            }
        } finally {
            if (image != null) {
                image.close();
            }
        }
    };


    public void startCamera() {
        if (!checkCameraPermission()) {
            return;
        }
        SharedPreferences prefs = activity.getSharedPreferences("AppSettings", Context.MODE_PRIVATE);
        String experimentId = prefs.getString("experiment_id", "default");
        String baseDir = Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                + "/Sample/" + experimentId + "/"+"Inference_"+System.currentTimeMillis()+"/";
        tempDir = new File(baseDir + "frames_" + System.currentTimeMillis() + "/");
        if (!tempDir.exists()) tempDir.mkdirs();
        frameCount = 0;
        String format = prefs.getString("video_format", "mp4");
        Log.e("TAG",format);
        String filename = System.currentTimeMillis() + (format.equals("avi") ? ".avi" : ".mp4");
        outputVideoPath = baseDir + filename;
        AssetManager assetManager = activity.getAssets();

        try {
            InputStream modelStream = assetManager.open("model.onnx");
            InputStream stateJsonStream = assetManager.open("state.json");
            InputStream welchModelStream = assetManager.open("welch_psd.onnx");
            InputStream hrModelStream = assetManager.open("get_hr.onnx");
            heartRateEstimator = new HeartRateEstimator(
                    modelStream,
                    stateJsonStream,
                    welchModelStream,
                    hrModelStream,
                    plotView,
                    baseDir
            );


        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        facePreProcessor = new FacePreprocessor(activity,heartRateEstimator);

        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = getFrontFacingCameraId(manager);
            if (cameraId == null) {
                Log.e(TAG, "No front-facing camera found.");
                if (callback != null) {
                    callback.onError("No front-facing camera found");
                }
                return;
            }

            manager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access exception", e);
            if (callback != null) {
                callback.onError("Camera access error: " + e.getMessage());
            }
        }
    }

    public void stopCamera() {
        isCameraRunning = false;

        if (cameraCaptureSession != null) {
            cameraCaptureSession.close();
            cameraCaptureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        if (callback != null) {
            callback.onCameraStopped();
        }
        //new Thread(this::generateAndUploadVideo).start();

    }
    private void generateAndUploadVideo() {
        try {
            // 先补全丢帧
            checkAndFillMissingFrames();

            // 1. 生成列表文件 frames.txt
            File listFile = new File(tempDir, "frames.txt");
            try (PrintWriter pw = new PrintWriter(listFile)) {
                File[] jpgs = tempDir.listFiles((d, n) -> n.endsWith(".jpg"));
                if (jpgs == null) throw new IOException("找不到帧文件");
                // 按文件名（即时间戳+序号）排序
                Arrays.sort(jpgs, Comparator.comparing(File::getName));
                for (File f : jpgs) {
                    // 注意：路径中如果有单引号，要转义
                    pw.println("file '" + f.getAbsolutePath().replace("'", "\\'") + "'");
                }
            }

            SharedPreferences prefs = activity.getSharedPreferences("AppSettings", Context.MODE_PRIVATE);
            String format = prefs.getString("video_format", "mp4");

            // 确定输出文件和 codec 参数
            String codecOpts;
            String outPath;
            if ("avi".equals(format)) {
                outPath = outputVideoPath.replaceAll("\\.mp4$", ".avi");
            } else {
                outPath = outputVideoPath.replaceAll("\\.avi$", ".mp4");
            }
            codecOpts = "-c:v mpeg4 -qscale:v 5";

            File outputVideo = new File(outPath);
            if (outputVideo.exists()) outputVideo.delete();

            String cmd = String.format(
                    "-f concat -safe 0 -i \"%s\" %s -pix_fmt yuv420p \"%s\"",
                    listFile.getAbsolutePath(),
                    codecOpts,
                    outputVideo.getAbsolutePath()
            );


            com.arthenica.ffmpegkit.FFmpegSession session = FFmpegKit.execute(cmd);
            if (ReturnCode.isSuccess(session.getReturnCode())) {
                Log.d(TAG, "视频生成成功: " + outputVideo.getAbsolutePath());
            } else {
                Log.e(TAG, "视频生成失败: " + session.getFailStackTrace());
            }
        } catch (Exception e) {
            Log.e(TAG, "拼接视频失败", e);
            if (callback != null) callback.onError("拼接视频失败: " + e.getMessage());
        }
    }


    private void checkAndFillMissingFrames() throws IOException {
        File[] files = tempDir.listFiles((d, n) -> n.endsWith(".jpg"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (int i = 1; i <= frameCount; i++) {
            File f = new File(tempDir, String.format("frame_%06d.jpg", i));
            if (!f.exists()) {
                Log.w(TAG, "帧丢失，生成占位: " + f.getName());
                Bitmap placeholder = generatePlaceholderBitmap(surfaceView.getWidth(), surfaceView.getHeight());
                try (FileOutputStream out = new FileOutputStream(f)) {
                    placeholder.compress(Bitmap.CompressFormat.JPEG, 80, out);
                }
            }
        }
    }
    private Bitmap generatePlaceholderBitmap(int widths,int heights) {
        int width = widths;  // 可以根据需要调整大小
        int height = heights;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                bitmap.setPixel(x, y, Color.BLACK); // 全黑图像
            }
        }
        return bitmap;
    }
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            if (surfaceView.getWidth() > 0 && surfaceView.getHeight() > 0) {
                setupImageReader(surfaceView.getWidth(), surfaceView.getHeight());
                createCameraPreview();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            stopCamera();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            stopCamera();
            if (callback != null) {
                callback.onError("Camera device error: " + error);
            }
        }
    };

    private void createCameraPreview() {
        try {
            if (surfaceHolder == null || !surfaceHolder.getSurface().isValid()) {
                Log.e(TAG, "SurfaceHolder is not valid.");
                if (callback != null) {
                    callback.onError("Surface holder is not valid");
                }
                return;
            }

            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(imageReader.getSurface());
            captureRequestBuilder.addTarget(surfaceHolder.getSurface());

            cameraDevice.createCaptureSession(
                    Arrays.asList(surfaceHolder.getSurface(), imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            cameraCaptureSession = session;
                            updatePreview();
                            isCameraRunning = true;
                            if (callback != null) {
                                callback.onCameraStarted();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Camera configuration failed.");
                            if (callback != null) {
                                callback.onError("Camera configuration failed");
                            }
                        }
                    }, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access exception in createCameraPreview", e);
            if (callback != null) {
                callback.onError("Camera preview error: " + e.getMessage());
            }
        }
    }

    private void updatePreview() {
        if (cameraDevice == null) return;

        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(30, 30));

        try {
            cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access exception in updatePreview", e);
            if (callback != null) {
                callback.onError("Preview update error: " + e.getMessage());
            }
        }
    }

    private String getFrontFacingCameraId(CameraManager cameraManager) {
        try {
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    return cameraId;
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error getting front camera ID", e);
        }
        return null;
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping background thread", e);
            }
        }
    }

    private boolean checkCameraPermission() {
        return ActivityCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    // Public methods for external control
    public boolean isCameraRunning() {
        return isCameraRunning;
    }
    public  Bitmap convertYUVToBitmap(Image image, int rotationDegrees) {
        if (image == null || image.getFormat() != ImageFormat.YUV_420_888) {
            Log.e(TAG, "Invalid image format or null image.");
            return null;
        }

        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();

        ByteBuffer yBuffer = planes[0].getBuffer(); // Y
        ByteBuffer uBuffer = planes[1].getBuffer(); // U
        ByteBuffer vBuffer = planes[2].getBuffer(); // V

        int yRowStride = planes[0].getRowStride();
        int uvRowStride = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();

        int[] argb = new int[width * height];

        byte[] yBytes = new byte[yBuffer.remaining()];
        yBuffer.get(yBytes);

        byte[] uBytes = new byte[uBuffer.remaining()];
        uBuffer.get(uBytes);

        byte[] vBytes = new byte[vBuffer.remaining()];
        vBuffer.get(vBytes);

        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int yIndex = row * yRowStride + col;
                int uvRow = row / 2;
                int uvCol = col / 2;
                int uvIndex = uvRow * uvRowStride + uvCol * uvPixelStride;

                int y = yBytes[yIndex] & 0xFF;
                int u = uBytes[uvIndex] & 0xFF;
                int v = vBytes[uvIndex] & 0xFF;

                // Convert YUV to RGB
                int r = (int)(y + 1.370705f * (v - 128));
                int g = (int)(y - 0.337633f * (u - 128) - 0.698001f * (v - 128));
                int b = (int)(y + 1.732446f * (u - 128));

                r = Math.min(255, Math.max(0, r));
                g = Math.min(255, Math.max(0, g));
                b = Math.min(255, Math.max(0, b));

                argb[row * width + col] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(argb, 0, width, 0, 0, width, height);
        return rotateBitmap(bitmap, rotationDegrees);
    }
    private Bitmap rotateBitmap(Bitmap bitmap, int rotationDegrees) {
        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    public static Bitmap convertJPEGToBitmap(Image image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }
}