package com.tsinghua.sample.media;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class AudioRecorderHelper {

    private static final int SAMPLE_RATE_IN_HZ = 44100;  // 采样率 44.1kHz
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;  // 16-bit 编码格式

    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private Thread recordingThread;
    private FileOutputStream fos;

    // 方法：开始录音
    public void startRecording() {
        // 获取最小缓冲区大小
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_IN_HZ, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            throw new IllegalArgumentException("Invalid buffer size");
        }

        // 初始化 AudioRecord 实例
        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC, // 麦克风作为音频源
                SAMPLE_RATE_IN_HZ,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
        );

        // 创建录音文件路径
        File outputFile = new File(Environment.getExternalStorageDirectory(), "audio_record.pcm");
        try {
            fos = new FileOutputStream(outputFile);
            audioRecord.startRecording();
            isRecording = true;

            // 启动一个线程来读取音频数据
            recordingThread = new Thread(new AudioRecordRunnable(fos, bufferSize));
            recordingThread.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // 方法：停止录音
    public void stopRecording() {
        if (audioRecord != null && isRecording) {
            isRecording = false;
            audioRecord.stop();
            audioRecord.release();

            // 确保在录音结束后关闭文件流
            try {
                if (recordingThread != null) {
                    recordingThread.join();  // 等待录音线程结束
                }
                if (fos != null) {
                    fos.close();  // 关闭文件输出流
                }
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    // 内部线程类：负责读取录音数据并写入文件
    private class AudioRecordRunnable implements Runnable {
        private FileOutputStream fos;
        private int bufferSize;

        public AudioRecordRunnable(FileOutputStream fos, int bufferSize) {
            this.fos = fos;
            this.bufferSize = bufferSize;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[bufferSize];
            int bytesRead;

            while (isRecording) {
                bytesRead = audioRecord.read(buffer, 0, buffer.length);
                if (bytesRead > 0 && fos != null) {
                    try {
                        fos.write(buffer, 0, bytesRead);  // 写入音频数据到文件
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    // 获取文件路径
    public String getOutputFilePath() {
        return new File(Environment.getExternalStorageDirectory(), "audio_record.pcm").getAbsolutePath();
    }
}
