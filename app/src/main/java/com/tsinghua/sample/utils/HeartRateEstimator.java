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

    /**
     * 心率更新回调接口
     */
    public interface OnHeartRateListener {
        void onHeartRateUpdated(float heartRate);
    }

    private OnHeartRateListener heartRateListener;

    public void setOnHeartRateListener(OnHeartRateListener listener) {
        this.heartRateListener = listener;
    }

    /**
     * 设置PlotView（用于预加载后更新）
     */
    public void setPlotView(PlotView plotView) {
        this.plotView = plotView;
    }

    /**
     * 设置日志输出目录（用于预加载后更新日志路径）
     * @param outDir 新的输出目录路径
     */
    public void setLogDirectory(String outDir) {
        try {
            // 关闭旧的 writer
            if (csvWriter != null) {
                csvWriter.flush();
                csvWriter.close();
            }
            // 创建新的 writer
            File d = new File(outDir);
            if (!d.exists()) d.mkdirs();
            File csv = new File(d, "hr_log.csv");
            boolean isNew = !csv.exists();
            csvWriter = new BufferedWriter(new FileWriter(csv, true));
            if (isNew) {
                csvWriter.write("timestamp,output,hr\n");
                csvWriter.flush();
            }
            Log.d("HeartRateEstimator", "日志路径已更新: " + csv.getAbsolutePath());
        } catch (IOException e) {
            Log.e("HeartRateEstimator", "更新日志路径失败", e);
        }
    }

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
    private final Deque<Long> timeStamps = new ArrayDeque<>(); // 存最近 SIGNAL_BUFFER_SIZE 帧的时间戳(毫秒)
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private KalmanFilter1D kfOutput;
    private KalmanFilter1D kfHR;
    private int welchCount;
    private ImageView imageView;

    private final FloatBuffer dtBuffer = FloatBuffer.allocate(1);
    private OnnxTensor dtTensor = null;

    // 信号缓冲区 - 不设上限，使用动态ArrayList
    // 最小采样数：至少需要 MIN_SAMPLES 帧才能进行心率计算
    private static final int MIN_SAMPLES = 300;  // 约10秒的数据
    private static final int CALC_INTERVAL = 150; // 每90帧计算一次心率

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
    private BufferedWriter csvWriter;  // 非final，允许更新日志路径

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
        welchCount = 0; // 初始化
    }

    public Float estimateFromFrame(float[][][] frame, long nowMs) throws Exception {



        Float hrResult = null;   // 本帧推断出的 HR；若未达到缓冲窗口则保持 null
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

            /* ---------- 6. 滤波、绘图、信号缓冲 ---------- */
            output = (kfOutput == null) ? (kfOutput = new KalmanFilter1D(1f, 0.5f, output, 1f)).update(output)
                    : kfOutput.update(output);

            signalOutput.add(output);
            if (signalOutput.size() > 300) signalOutput.remove(0);
            if (plotView != null) {
                plotView.addValue(output);
            }

            welchCount++;
            if (signalOutput.size() == 300 && welchCount >= 300) {
                welchCount = 150;                 // 重置计数
                hrResult   = estimateHRFromSignal(signalOutput);

                // 通知监听器心率更新
                if (hrResult != null && heartRateListener != null) {
                    final float hr = hrResult;
                    mainHandler.post(() -> heartRateListener.onHeartRateUpdated(hr));
                }
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


    /**
     * 使用 Welch 功率谱密度方法计算心率
     * 对应 Python: def get_hr(y, sr=30, min=30, max=180):
     *     p, q = welch(y, sr, nfft=2e4, nperseg=np.min((len(y)-1, 256/30*sr)))
     *     return p[(p>min/60)&(p<max/60)][np.argmax(q[(p>min/60)&(p<max/180)])]*60
     */
    private float estimateHRFromSignal(List<Float> signal) throws Exception {
        final long t1 = System.nanoTime();

        final int size = signal.size();
        double[] y = new double[size];
        for (int i = 0; i < size; i++) {
            y[i] = signal.get(i);
        }

        // 计算实际采样率（基于时间戳）
        double sr = 30.0;  // 默认30fps
        if (timeStamps.size() >= 2) {
            long firstMs = timeStamps.getFirst();
            long lastMs = timeStamps.getLast();
            double durationSec = (lastMs - firstMs) / 1000.0;
            if (durationSec > 0) {
                sr = (timeStamps.size() - 1) / durationSec;
            }
        }

        Log.d("HeartRateEstimator", String.format("使用 %d 帧数据计算心率, 实际采样率: %.2f fps", size, sr));

        // 去除均值（scipy.welch 默认 detrend='constant'）
        double mean = 0;
        for (double v : y) mean += v;
        mean /= size;
        for (int i = 0; i < size; i++) y[i] -= mean;

        // Welch 参数 - 与 Python 完全一致
        // nfft=2e4, nperseg=np.min((len(y)-1, 256/30*sr))
        int nfft = 20000;  // 与 Python 一致
        int nperseg = (int) Math.min(size - 1, 256.0 / 30.0 * sr);
        nperseg = Math.max(nperseg, 64);  // 最小段长度

        // 计算 Welch PSD（内部会将 nfft 调整为2的幂次）
        double[][] welchResult = welchPSD(y, sr, nperseg, nfft);
        double[] freqs = welchResult[0];
        double[] psd = welchResult[1];

        // 心率范围: 30-180 bpm -> 0.5-3.0 Hz
        double minFreq = 30.0 / 60.0;  // 0.5 Hz
        double maxFreq = 180.0 / 60.0; // 3.0 Hz

        // 找到心率范围内的最大峰值
        double maxPower = -1;
        double peakFreq = 0;
        for (int i = 0; i < freqs.length; i++) {
            if (freqs[i] > minFreq && freqs[i] < maxFreq) {
                if (psd[i] > maxPower) {
                    maxPower = psd[i];
                    peakFreq = freqs[i];
                }
            }
        }

        float hr = (float) (peakFreq * 60.0);  // 转换为 BPM

        Log.d("HeartRateEstimator", String.format("Welch计算结果: 峰值频率=%.4f Hz, 心率=%.1f bpm", peakFreq, hr));

        // 卡尔曼滤波平滑
        if (kfHR == null) {
            kfHR = new KalmanFilter1D(1f, 2f, hr, 1f);
        } else {
            hr = kfHR.update(hr);
        }

        final long t2 = System.nanoTime();
        Log.d("HeartRateEstimator", String.format("计算心率耗时: %.2f ms", (t2 - t1) / 1e6));

        return hr;
    }

    /**
     * Welch 功率谱密度估计
     * @param signal 输入信号
     * @param fs 采样频率
     * @param nperseg 每段长度
     * @param nfftRequested 请求的 FFT 点数（会调整为2的幂次）
     * @return [频率数组, PSD数组]
     */
    private double[][] welchPSD(double[] signal, double fs, int nperseg, int nfftRequested) {
        // FFT 需要 2 的幂次，向上取整
        int nfft = nextPowerOf2(Math.max(nfftRequested, nperseg));

        int noverlap = nperseg / 2;  // 50% 重叠（scipy 默认）
        int step = nperseg - noverlap;

        // 计算段数
        int nSegments = Math.max(1, (signal.length - noverlap) / step);

        // Hann 窗（scipy 默认）
        double[] window = hannWindow(nperseg);
        double windowSum = 0;
        for (double w : window) windowSum += w * w;

        // 输出频率点数（单边谱）
        int nFreqs = nfft / 2 + 1;
        double[] psdSum = new double[nFreqs];
        int actualSegments = 0;

        // 对每段计算周期图并累加
        for (int seg = 0; seg < nSegments; seg++) {
            int start = seg * step;
            if (start + nperseg > signal.length) break;

            // 提取段并应用窗函数，补零到 nfft
            double[] segmentReal = new double[nfft];
            double[] segmentImag = new double[nfft];
            for (int i = 0; i < nperseg; i++) {
                segmentReal[i] = signal[start + i] * window[i];
            }
            // 剩余部分已经是0（补零）

            // 计算 FFT
            fft(segmentReal, segmentImag);

            // 计算功率谱密度（单边）
            for (int i = 0; i < nFreqs; i++) {
                double power = (segmentReal[i] * segmentReal[i] + segmentImag[i] * segmentImag[i]);
                // scipy 的 scaling='density' 归一化
                power /= (fs * windowSum);
                if (i > 0 && i < nfft / 2) {
                    power *= 2;  // 单边谱，除 DC 和 Nyquist 外乘2
                }
                psdSum[i] += power;
            }
            actualSegments++;
        }

        // 平均所有段
        if (actualSegments > 0) {
            for (int i = 0; i < nFreqs; i++) {
                psdSum[i] /= actualSegments;
            }
        }

        // 生成频率数组
        double[] freqs = new double[nFreqs];
        for (int i = 0; i < nFreqs; i++) {
            freqs[i] = i * fs / nfft;
        }

        Log.d("HeartRateEstimator", String.format("Welch: nfft=%d, nperseg=%d, segments=%d, freqRes=%.4f Hz",
                nfft, nperseg, actualSegments, fs / nfft));

        return new double[][]{freqs, psdSum};
    }

    /**
     * Hann 窗函数
     */
    private double[] hannWindow(int length) {
        double[] window = new double[length];
        for (int i = 0; i < length; i++) {
            window[i] = 0.5 * (1 - Math.cos(2 * Math.PI * i / (length - 1)));
        }
        return window;
    }

    /**
     * 快速傅里叶变换 (Cooley-Tukey 算法)
     * 输入输出都是实部和虚部数组
     */
    private void fft(double[] real, double[] imag) {
        int n = real.length;

        // 位反转排列
        int bits = (int) (Math.log(n) / Math.log(2));
        for (int i = 0; i < n; i++) {
            int j = bitReverse(i, bits);
            if (j > i) {
                double tempR = real[i];
                double tempI = imag[i];
                real[i] = real[j];
                imag[i] = imag[j];
                real[j] = tempR;
                imag[j] = tempI;
            }
        }

        // Cooley-Tukey FFT
        for (int size = 2; size <= n; size *= 2) {
            int halfSize = size / 2;
            double angle = -2 * Math.PI / size;

            for (int i = 0; i < n; i += size) {
                for (int j = 0; j < halfSize; j++) {
                    double wr = Math.cos(angle * j);
                    double wi = Math.sin(angle * j);

                    int idx1 = i + j;
                    int idx2 = i + j + halfSize;

                    double tr = wr * real[idx2] - wi * imag[idx2];
                    double ti = wr * imag[idx2] + wi * real[idx2];

                    real[idx2] = real[idx1] - tr;
                    imag[idx2] = imag[idx1] - ti;
                    real[idx1] = real[idx1] + tr;
                    imag[idx1] = imag[idx1] + ti;
                }
            }
        }
    }

    /**
     * 位反转
     */
    private int bitReverse(int x, int bits) {
        int result = 0;
        for (int i = 0; i < bits; i++) {
            result = (result << 1) | (x & 1);
            x >>= 1;
        }
        return result;
    }

    /**
     * 获取大于等于 n 的最小 2 的幂次
     */
    private int nextPowerOf2(int n) {
        int power = 1;
        while (power < n) {
            power *= 2;
        }
        return power;
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
