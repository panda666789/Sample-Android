package com.tsinghua.sample.media;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;

public class IMURecorder implements SensorEventListener {
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor gyroscope;
    private boolean isRecording;
    public File outputDirectory;
    private Context context;
    private final String TAG = "IMURecorder";
    private OnDataUpdateListener dataUpdateListener;
    private static final int UPDATE_INTERVAL = 100;
    private int accelerometerDataCount = 0;
    private int gyroscopeDataCount = 0;

    // 流式写入相关
    private BufferedWriter accelerometerWriter;
    private BufferedWriter gyroscopeWriter;
    private File accelerometerFile;
    private File gyroscopeFile;

    // 用于UI更新的最新数据
    private String lastAccelData = "";
    private String lastGyroData = "";

    // 缓冲区大小，可以根据需要调整
    private static final int BUFFER_SIZE = 8192;

    public IMURecorder(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        isRecording = false;
        this.context = context;
    }

    public void startRecording() {
        if (isRecording) {
            Log.w(TAG, "Recording is already in progress");
            return;
        }

        // 初始化文件和写入器
        if (!initializeFiles()) {
            Log.e(TAG, "Failed to initialize files for recording");
            return;
        }

        // 重置计数器
        accelerometerDataCount = 0;
        gyroscopeDataCount = 0;

        // 开始监听传感器
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST);
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST);
        isRecording = true;

        Log.d(TAG, "IMU recording started");
    }

    public void stopRecording() {
        if (!isRecording) {
            Log.w(TAG, "No recording in progress");
            return;
        }

        // 停止传感器监听
        sensorManager.unregisterListener(this);
        isRecording = false;

        // 关闭文件写入器
        closeFiles();

        Log.d(TAG, "IMU recording stopped. Files saved to: " + outputDirectory.getAbsolutePath());
    }

    private boolean initializeFiles() {
        try {
            // 获取实验 ID 和文件存储路径
            SharedPreferences prefs = context.getSharedPreferences("AppSettings", MODE_PRIVATE);
            String experimentId = prefs.getString("experiment_id", "default");

            // 设置文件存储路径
            String dirPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                    + "/Sample/" + experimentId + "/";
            File dir = new File(dirPath);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            // 生成一个新的输出目录
            String timestamp = generateTimestamp();
            outputDirectory = new File(dir, "Sample_IMU_" + timestamp);

            if (!outputDirectory.exists()) {
                outputDirectory.mkdirs();
            }

            // 创建文件
            accelerometerFile = new File(outputDirectory, "imu_accelerometer_data.csv");
            gyroscopeFile = new File(outputDirectory, "imu_gyroscope_data.csv");

            // 创建缓冲写入器
            accelerometerWriter = new BufferedWriter(new FileWriter(accelerometerFile), BUFFER_SIZE);
            gyroscopeWriter = new BufferedWriter(new FileWriter(gyroscopeFile), BUFFER_SIZE);

            // 写入CSV头部
            accelerometerWriter.write("sensor_timestamp,system_timestamp,accel_x,accel_y,accel_z\n");
            gyroscopeWriter.write("sensor_timestamp,system_timestamp,gyro_x,gyro_y,gyro_z\n");

            // 立即刷新头部到文件
            accelerometerWriter.flush();
            gyroscopeWriter.flush();

            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error initializing files: " + e.getMessage());
            e.printStackTrace();
            closeFiles(); // 清理已创建的资源
            return false;
        }
    }

    private void closeFiles() {
        try {
            if (accelerometerWriter != null) {
                accelerometerWriter.flush();
                accelerometerWriter.close();
                accelerometerWriter = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing accelerometer file: " + e.getMessage());
        }

        try {
            if (gyroscopeWriter != null) {
                gyroscopeWriter.flush();
                gyroscopeWriter.close();
                gyroscopeWriter = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing gyroscope file: " + e.getMessage());
        }
    }

    public void setOnDataUpdateListener(OnDataUpdateListener listener) {
        this.dataUpdateListener = listener;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isRecording || accelerometerWriter == null || gyroscopeWriter == null) {
            return;
        }

        long sensorTimestamp = event.timestamp;
        long systemTimestamp = System.currentTimeMillis();
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        try {
            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                // 直接写入到文件
                String data = String.format(Locale.getDefault(), "%d,%d,%f,%f,%f\n",
                        sensorTimestamp, systemTimestamp, x, y, z);
                accelerometerWriter.write(data);

                // 保存最新数据用于UI更新
                lastAccelData = String.format(Locale.getDefault(), "x: %f, y: %f, z: %f", x, y, z);

                accelerometerDataCount++;
                if (accelerometerDataCount >= UPDATE_INTERVAL) {
                    accelerometerWriter.flush(); // 定期刷新到磁盘
                    notifyDataUpdate();
                    accelerometerDataCount = 0;
                }

            } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
                // 直接写入到文件
                String data = String.format(Locale.getDefault(), "%d,%d,%f,%f,%f\n",
                        sensorTimestamp, systemTimestamp, x, y, z);
                gyroscopeWriter.write(data);

                // 保存最新数据用于UI更新
                lastGyroData = String.format(Locale.getDefault(), "x: %f, y: %f, z: %f", x, y, z);

                gyroscopeDataCount++;
                if (gyroscopeDataCount >= UPDATE_INTERVAL) {
                    gyroscopeWriter.flush(); // 定期刷新到磁盘
                    notifyDataUpdate();
                    gyroscopeDataCount = 0;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error writing sensor data: " + e.getMessage());
            // 发生IO错误时停止录制
            stopRecording();
        }
    }

    private void notifyDataUpdate() {
        if (dataUpdateListener != null && !lastAccelData.isEmpty() && !lastGyroData.isEmpty()) {
            dataUpdateListener.onDataUpdate(lastAccelData, lastGyroData);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Handle changes in sensor accuracy if needed
        Log.d(TAG, "Sensor accuracy changed: " + sensor.getName() + ", accuracy: " + accuracy);
    }

    private String generateTimestamp() {
        return String.valueOf(System.currentTimeMillis());
    }

    // 工具方法保留
    public static String extractLastDigits(String input, int numberOfDigits) {
        if (input == null || input.length() < numberOfDigits) {
            return null;
        }
        return input.substring(input.length() - numberOfDigits);
    }

    public static String extractFirstDigits(String input, int numberOfDigits) {
        if (input == null || input.length() < numberOfDigits || numberOfDigits < 0) {
            return null;
        }
        return input.substring(0, numberOfDigits);
    }

    // 获取当前录制状态
    public boolean isRecording() {
        return isRecording;
    }

    // 强制刷新缓冲区到磁盘（可在需要时调用）
    public void flushToDisk() {
        if (isRecording) {
            try {
                if (accelerometerWriter != null) {
                    accelerometerWriter.flush();
                }
                if (gyroscopeWriter != null) {
                    gyroscopeWriter.flush();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error flushing data to disk: " + e.getMessage());
            }
        }
    }

    public interface OnDataUpdateListener {
        void onDataUpdate(String accelData, String gyroData);
    }
}