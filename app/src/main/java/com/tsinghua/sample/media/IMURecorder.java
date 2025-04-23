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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class IMURecorder implements SensorEventListener {
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor gyroscope;
    private boolean isRecording;
    public File outputDirectory;
    private List<String> accelerometerData;
    private Context context;
    private List<String> gyroscopeData;
    private final String TAG = "IMURecorder";
    public IMURecorder(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        isRecording = false;
        accelerometerData = new ArrayList<>();
        gyroscopeData = new ArrayList<>();
        this.context = context;
    }

    public void startRecording() {
        accelerometerData.clear();
        gyroscopeData.clear();

        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST);
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST);
        isRecording = true;
    }

    public void stopRecording() {
        sensorManager.unregisterListener(this);
        isRecording = false;

        // Save data to file
        saveDataToFile();
    }
    public static String extractLastDigits(String input, int numberOfDigits) {
        if (input == null || input.length() < numberOfDigits) {
            return null; // 输入无效或长度不足
        }
        return input.substring(input.length() - numberOfDigits); // 提取最后的 numberOfDigits 位
    }
    public static String extractFirstDigits(String input, int numberOfDigits) {
        if (input == null || input.length() < numberOfDigits || numberOfDigits < 0) {
            return null; // 检查输入有效性
        }
        return input.substring(0, numberOfDigits);
    }
    public void saveDataToFile() {
        // 获取实验 ID 和文件存储路径
        SharedPreferences prefs = context.getSharedPreferences("AppSettings", MODE_PRIVATE);
        String experimentId = prefs.getString("experiment_id", "default");

        // 设置文件存储路径
        String dirPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                + "/Sample/" + experimentId + "/";
        File dir = new File(dirPath);
        if (!dir.exists()) dir.mkdirs();  // 确保目录存在

        // 生成一个新的输出目录
        String timestamp = generateTimestamp();
        File newOutputDirectory = new File(dir, "Sample_" + timestamp);

        if (!newOutputDirectory.exists()) {
            newOutputDirectory.mkdirs();  // 创建新目录
        }

        // 保存数据到文件
        File imuAccelerometerDataFile = new File(newOutputDirectory, "imu_accelerometer_data.csv");
        File imuGyroscopeDataFile = new File(newOutputDirectory, "imu_gyroscope_data.csv");

        try {
            // 写加速度计数据到文件
            FileWriter writerAc = new FileWriter(imuAccelerometerDataFile, true);
            writerAc.append("timestamp,accel_x,accel_y,accel_z\n");
            for (String accelData : accelerometerData) {
                writerAc.append(accelData).append("\n");
            }
            writerAc.flush();
            writerAc.close();

            // 写陀螺仪数据到文件
            FileWriter writerGy = new FileWriter(imuGyroscopeDataFile, true);
            writerGy.append("timestamp,gyro_x,gyro_y,gyro_z\n");
            for (String gyroData : gyroscopeData) {
                writerGy.append(gyroData).append("\n");
            }
            writerGy.flush();
            writerGy.close();

            Log.d(TAG, "IMU data saved to files: " + imuAccelerometerDataFile.getAbsolutePath() + " and " + imuGyroscopeDataFile.getAbsolutePath());

        } catch (IOException e) {
            Log.e(TAG, "Error saving IMU data to file: " + e.getMessage());
            e.printStackTrace();
        }
    }


    @Override
    public void onSensorChanged(SensorEvent event) {
        if (isRecording) {
            long timestamp = event.timestamp;
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];

            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                accelerometerData.add(String.format(Locale.getDefault(), "%d,%f,%f,%f", timestamp, x, y, z));
            } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
                gyroscopeData.add(String.format(Locale.getDefault(), "%d,%f,%f,%f", timestamp, x, y, z));
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Handle changes in sensor accuracy if needed
    }
    private String generateTimestamp() {
        return String.valueOf(System.nanoTime());
    }
}