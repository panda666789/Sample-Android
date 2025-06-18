package com.tsinghua.sample.utils;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageView;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

public class HeartRateEstimator {

    private final OrtEnvironment env;
    private final OrtSession signalSession;
    private final OrtSession welchSession;
    private final OrtSession hrSession;
    // 保存上一帧时间戳，类似 JS 中的 lastTimestamp
    private Long lastTimestamp = null;
    private PlotView plotView;


    // 保存隐藏状态的 Map，键为 state tensor 的名字，值为 OnnxTensor
    private final Map<String, OnnxTensor> state = new HashMap<>();
    private final Map<String, OnnxTensor> signalFeeds = new HashMap<>();
    private final Map<String, OnnxTensor> welchFeeds = new HashMap<>();
    private final Map<String, OnnxTensor> hrFeeds = new HashMap<>();
    //private WebSocketManager webSocketManager;
    private final List<Float> signalOutput = new ArrayList<>();
    private final Deque<Long> timeStamps = new ArrayDeque<>(); // 存最近 300 帧的时间戳(毫秒)
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private KalmanFilter1D kfOutput;
    private KalmanFilter1D kfHR;
    private int welchCount;
    private ImageView imageView;

    private final FloatBuffer dtBuffer = FloatBuffer.allocate(1);
    private OnnxTensor dtTensor = null;

    private final float[] signalArray300 = new float[300];

    // 用于统计 FPS 的时间队列
    private final Deque<Long> frameTimes = new ArrayDeque<>();
    // HeartRateEstimator 类中
    private OrtSession.Result lastResult = null; // 缓存上一帧的 result（避免被自动 close）

    private final FloatBuffer frameBuffer = ByteBuffer
            .allocateDirect(36 * 36 * 3 * 4)  // = 36*36*3 floats, each float 4 bytes
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();

    private String Id;
    private final long[] frameShape = {1, 1, 36, 36, 3};
    private final BufferedWriter csvWriter;

    // 构造函数
    public HeartRateEstimator(InputStream modelStream,
                              InputStream stateJsonStream,
                              InputStream welchModelStream,
                              InputStream hrModelStream,
                              PlotView plotView,
                              String outDir

    ) throws Exception {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(1);
        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
        options.setMemoryPatternOptimization(true);
        options.setCPUArenaAllocator(true);
        options.addConfigEntry("session.use_env_allocators", "1");
        this.plotView = plotView;
        File d = new File(outDir);
        if (!d.exists()) d.mkdirs();
        File csv = new File(d, "hr_log.csv");
        boolean isNew = !csv.exists();
        csvWriter = new BufferedWriter(new FileWriter(csv, true));
        if (isNew) {
            // 写入表头
            csvWriter.write("timestamp,output,hr\n");
            csvWriter.flush();
        }
        this.imageView = imageView;
        //this.webSocketManager = webSocketManager;
        signalSession = env.createSession(readAllBytesCompat(modelStream), options);
        welchSession = env.createSession(readAllBytesCompat(welchModelStream), options);
        hrSession = env.createSession(readAllBytesCompat(hrModelStream), options);
        loadInitialState(stateJsonStream);

        // 填充初始信号值
        for (int i = 0; i < 300; i++) {
            signalOutput.add(0f);
        }
        welchCount = 300 - 240; // 初始化
    }

    public Float estimateFromFrame(float[][][] frame, long nowMs) throws Exception {

        // 避免并发调用
        if (!isRunning.compareAndSet(false, true)) {
            return null;
        }

        Float hrResult = null;   // 本帧推断出的 HR；若未达到 300 帧窗口则保持 null
        float output    = 0f;    // 本帧瞬时信号输出

        try {
            /* ---------- 1. 更新时间戳 & 计算 Δt ---------- */
            timeStamps.addLast(nowMs);
            if (timeStamps.size() > 300) timeStamps.removeFirst();

            float dtSeconds = 1f / 30f;          // 默认假设 30 FPS
            if (timeStamps.size() > 1) {
                Long[] ts = timeStamps.toArray(new Long[0]);
                long prevMs = ts[ts.length - 2];
                dtSeconds = Math.max((nowMs - prevMs) / 1000f, 1f / 90f);  // 下限 11 ms
            }

            /* ---------- 2. 把 36×36×3 写入 frameBuffer ---------- */
            frameBuffer.clear();
            for (int y = 0; y < 36; y++) {
                for (int x = 0; x < 36; x++) {
                    float[] p = frame[y][x];
                    frameBuffer.put(p[0]).put(p[1]).put(p[2]);
                }
            }
            frameBuffer.flip();

            /* ---------- 3. 组装输入 & 运行信号模型 ---------- */
            // Δt tensor（标量）——局部变量，用完即关
            dtBuffer.put(0, dtSeconds).rewind();

            try (OnnxTensor inputTensor  = OnnxTensor.createTensor(env, frameBuffer, frameShape);
                 OnnxTensor dtTensor     = OnnxTensor.createTensor(env, dtBuffer, new long[]{})) {

                // 3-1 组装 feed：把上一帧隐藏状态 + 本帧输入放进去
                Map<String, OnnxTensor> feeds = new HashMap<>(state); // 这里的 state 来自 lastResult
                feeds.put("arg_0.1",      inputTensor);
                feeds.put("onnx::Mul_37", dtTensor);

                // 3-2 执行推理（Result 这次先不关闭，要把内部 tensor 留做下一帧隐藏状态）
                OrtSession.Result result = signalSession.run(feeds);

                /* ---------- 4. 读取输出 ---------- */
                float[][] outArr = (float[][]) result.get(0).getValue();
                output = outArr[0][0];

                /* ---------- 5. 更新隐藏状态 ---------- */
                // 先关闭上一帧 Result（它会连带关闭旧隐藏状态 tensor）
                if (lastResult != null) {
                    lastResult.close();
                }
                state.clear();

                // 把当前 Result 中除第 0 个以外的 tensor 存进 state
                List<String> inNames = new ArrayList<>(signalSession.getInputNames());
                for (int i = 1; i < result.size(); i++) {
                    state.put(inNames.get(i), (OnnxTensor) result.get(i));
                }
                // 缓存本帧 Result，留到下一帧再 close
                lastResult = result;
            }

            /* ---------- 6. 滤波、绘图、300 帧采样 ---------- */
            output = (kfOutput == null) ? (kfOutput = new KalmanFilter1D(1f, 0.5f, output, 1f)).update(output)
                    : kfOutput.update(output);

            signalOutput.add(output);
            if (signalOutput.size() > 300) signalOutput.remove(0);
            plotView.addValue(output);

            welchCount++;
            if (signalOutput.size() == 300 && welchCount >= 300) {
                welchCount = 270;                 // 重置计数
                hrResult   = estimateHRFromSignal(signalOutput);
            }

        } finally {
            isRunning.set(false);                 // 无论成功/异常都允许下一帧进入
        }

        /* ---------- 7. 日志写入 ---------- */
        try {
            csvWriter.write(nowMs + "," + output + (hrResult != null ? "," + hrResult : "") + '\n');
            csvWriter.flush();
        } catch (IOException e) {
            Log.e("HeartRateEstimator", "写入 CSV 失败", e);
        }

        return hrResult;
    }


    private float estimateHRFromSignal(List<Float> signal) throws Exception {
        final long t1 = System.nanoTime();

        // 复制信号到复用数组
        final int size = signal.size();
        for (int i = 0; i < size; i++) {
            signalArray300[i] = signal.get(i);
        }

        // 做 Welch
        try (OnnxTensor signalTensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(signalArray300, 0, size),
                new long[]{1, 1, size})
        ) {
            welchFeeds.clear();
            welchFeeds.put("input", signalTensor);

            try (OrtSession.Result psdResult = welchSession.run(welchFeeds)) {
                float[] freqs = (float[]) ((OnnxTensor) psdResult.get("freqs").orElseThrow()).getValue();
                float[] psd   = ((float[][]) ((OnnxTensor) psdResult.get("psd").orElseThrow()).getValue())[0];

                // 调 HR session
                try (OnnxTensor freqsTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(freqs), new long[]{freqs.length});
                     OnnxTensor psdTensor   = OnnxTensor.createTensor(env, FloatBuffer.wrap(psd),   new long[]{1, psd.length})
                ) {
                    hrFeeds.clear();
                    hrFeeds.put("freqs", freqsTensor);
                    hrFeeds.put("psd", psdTensor);

                    try (OrtSession.Result hrResult = hrSession.run(hrFeeds)) {
                        float hr = ((Number) ((OnnxTensor) hrResult.get("hr").orElseThrow()).getValue()).floatValue();

                        // 根据真实时长修正 HR (因为模型默认按30FPS)
                        if (timeStamps.size() >= 300) {
                            long firstMs = timeStamps.getFirst();
                            long lastMs  = timeStamps.getLast();
                            float durationSec = (lastMs - firstMs) / 1000f;
                            if (durationSec > 0f) {
                                float averageFps = 299f / durationSec; // 299 帧间隔
                                hr *= (averageFps / 30f);
                            }
                        }

                        // 卡尔曼滤波
                        if (kfHR == null) {
                            kfHR = new KalmanFilter1D(1f, 2f, hr, 1f);
                        } else {
                            hr = kfHR.update(hr);
                        }
                        final long t2 = System.nanoTime();
                        Log.e("PROFILE", "计算心率耗时(ms): " + (t2 - t1) / 1e6);
                        return hr;
                    }
                }
            }
        }
    }

    // 如果需要调试，可视化当前输入帧图像
    private void showInputPreview(float[][][] image) {
        int width = 36;
        int height = 36;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float r = image[y][x][0];
                float g = image[y][x][1];
                float b = image[y][x][2];
                int color = Color.rgb(
                        (int) (r * 255),
                        (int) (g * 255),
                        (int) (b * 255)
                );
                bitmap.setPixel(x, y, color);
            }
        }

        mainHandler.post(() -> imageView.setImageBitmap(bitmap));
    }

    // 加载初始隐藏状态
    private void loadInitialState(InputStream stateJsonStream) throws Exception {
        String json = new String(readAllBytesCompat(stateJsonStream), StandardCharsets.UTF_8);
        ObjectMapper mapper = new ObjectMapper();
        TypeReference<Map<String, Object>> typeRef = new TypeReference<Map<String, Object>>() {};
        Map<String, Object> parsed = mapper.readValue(json, typeRef);

        for (Map.Entry<String, Object> entry : parsed.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();

            float[] flat = flatten(value);
            long[] shape = shapeOf(value);

            OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape);

            state.put(name, tensor);
        }
    }


    // 读取流到 byte[]
    private static byte[] readAllBytesCompat(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[4096];
        int nRead;
        while ((nRead = inputStream.read(data)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }

    // 记录当前 FPS
    private void logCurrentFPS(long nowMs) {
        frameTimes.addLast(nowMs);
        while (frameTimes.size() > 1 && nowMs - frameTimes.getFirst() > 1000) {
            frameTimes.removeFirst();
        }
        int fps = frameTimes.size(); // 最近1秒内的帧数
        Log.d("HeartRateEstimator", "当前FPS: " + fps);
    }

    // 展平任意层级结构
    private float[] flatten(Object nested) {
        List<Float> flatList = new ArrayList<>();
        flattenRecursive(nested, flatList);
        float[] result = new float[flatList.size()];
        for (int i = 0; i < flatList.size(); i++) {
            result[i] = flatList.get(i);
        }
        return result;
    }

    private void flattenRecursive(Object o, List<Float> output) {
        if (o instanceof Number) {
            output.add(((Number) o).floatValue());
        } else if (o instanceof List<?>) {
            for (Object item : (List<?>) o) {
                flattenRecursive(item, output);
            }
        } else if (o instanceof float[]) {
            for (float f : (float[]) o) {
                output.add(f);
            }
        } else if (o instanceof Object[]) {
            for (Object item : (Object[]) o) {
                flattenRecursive(item, output);
            }
        } else {
            Log.e("FLATTEN", "Unexpected data type: " + o);
        }
    }

    // 递归推断嵌套结构的形状
    private long[] shapeOf(Object nested) {
        List<Long> shape = new ArrayList<>();
        Object current = nested;
        while (current instanceof List<?> && !((List<?>) current).isEmpty()) {
            shape.add((long) ((List<?>) current).size());
            current = ((List<?>) current).get(0);
        }
        return shape.stream().mapToLong(i -> i).toArray();
    }

    // 一维卡尔曼滤波
    public static class KalmanFilter1D {
        private final float processNoise;
        private final float measurementNoise;
        private float estimate;
        private float estimateError;

        public  KalmanFilter1D(float processNoise, float measurementNoise, float initialState, float initialEstimateError) {
            this.processNoise = processNoise;
            this.measurementNoise = measurementNoise;
            this.estimate = initialState;
            this.estimateError = initialEstimateError;
        }

        public float update(float measurement) {
            float prediction = estimate;
            float predictionError = estimateError + processNoise;
            float kalmanGain = predictionError / (predictionError + measurementNoise);
            estimate = prediction + kalmanGain * (measurement - prediction);
            estimateError = (1 - kalmanGain) * predictionError;
            return estimate;
        }
    }
}
