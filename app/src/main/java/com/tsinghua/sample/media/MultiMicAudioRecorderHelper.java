package com.tsinghua.sample.media;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class MultiMicAudioRecorderHelper {

    private static final int SAMPLE_RATE_IN_HZ = 44100;  // 采样率 44.1kHz
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;  // 16-bit 编码格式
    public File outputDirectory;

    private AudioRecord audioRecord1, audioRecord2;  // 假设我们使用两个麦克风
    private boolean isRecording = false;
    private Thread recordingThread1, recordingThread2;
    private FileOutputStream fos1, fos2, timestampFos1, timestampFos2;

    // 方法：开始录音
    public void startRecording() {
        // 获取最小缓冲区大小
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_IN_HZ, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            throw new IllegalArgumentException("Invalid buffer size");
        }

        try {
            // 初始化 AudioRecord 实例，分别对应不同的麦克风
            audioRecord1 = new AudioRecord(
                    MediaRecorder.AudioSource.MIC, // 麦克风 1
                    SAMPLE_RATE_IN_HZ,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
            );
            audioRecord2 = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, // 麦克风 2（如果支持）
                    SAMPLE_RATE_IN_HZ,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
            );

            // 创建文件输出流，用于存储音频数据
            fos1 = new FileOutputStream(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "mic1_audio_record.pcm"));
            fos2 = new FileOutputStream(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "mic2_audio_record.pcm"));

            // 创建文件输出流，用于存储时间戳
            timestampFos1 = new FileOutputStream(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "mic1_timestamp.txt"));
            timestampFos2 = new FileOutputStream(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "mic2_timestamp.txt"));

            // 开始录音
            audioRecord1.startRecording();
            audioRecord2.startRecording();
            isRecording = true;

            // 启动线程分别读取录音数据
            recordingThread1 = new Thread(new AudioRecordRunnable(audioRecord1, fos1, timestampFos1, bufferSize));
            recordingThread2 = new Thread(new AudioRecordRunnable(audioRecord2, fos2, timestampFos2, bufferSize));
            recordingThread1.start();
            recordingThread2.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // 方法：停止录音
    public void stopRecording() {
        if (audioRecord1 != null && isRecording) {
            isRecording = false;
            audioRecord1.stop();
            audioRecord2.stop();
            audioRecord1.release();
            audioRecord2.release();

            try {
                if (recordingThread1 != null) {
                    recordingThread1.join();
                }
                if (recordingThread2 != null) {
                    recordingThread2.join();
                }

                if (fos1 != null) {
                    fos1.close();
                }
                if (fos2 != null) {
                    fos2.close();
                }
                if (timestampFos1 != null) {
                    timestampFos1.close();
                }
                if (timestampFos2 != null) {
                    timestampFos2.close();
                }
                if (outputDirectory != null) {
                    File mic1AudioFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "mic1_audio_record.pcm");
                    File mic2AudioFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "mic2_audio_record.pcm");
                    File mic1TimestampFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "mic1_timestamp.txt");
                    File mic2TimestampFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "mic2_timestamp.txt");

                    // Move audio and timestamp files to the output directory
                    moveFileToDirectory(mic1AudioFile, outputDirectory);
                    moveFileToDirectory(mic2AudioFile, outputDirectory);
                    moveFileToDirectory(mic1TimestampFile, outputDirectory);
                    moveFileToDirectory(mic2TimestampFile, outputDirectory);
                }
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    // 内部线程类：负责读取录音数据并写入文件
    private class AudioRecordRunnable implements Runnable {
        private AudioRecord audioRecord;
        private FileOutputStream fos;
        private FileOutputStream timestampFos;
        private int bufferSize;

        public AudioRecordRunnable(AudioRecord audioRecord, FileOutputStream fos, FileOutputStream timestampFos, int bufferSize) {
            this.audioRecord = audioRecord;
            this.fos = fos;
            this.timestampFos = timestampFos;
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
                        // 7104/4=1776
                        long timestamp = System.nanoTime();  // 获取当前时间戳
                        timestampFos.write(("" + timestamp + "\n").getBytes());  // 写入时间戳到文件

                        fos.write(buffer, 0, bytesRead);  // 写入音频数据到文件

                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    // 获取文件路径
    private void moveFileToDirectory(File sourceFile, File destinationDir) {
        if (sourceFile.exists()) {
            File destinationFile = new File(destinationDir, sourceFile.getName());
            if (sourceFile.renameTo(destinationFile)) {
                Log.d("AudioRecorder", "Moved file to: " + destinationFile.getAbsolutePath());
            } else {
                Log.e("AudioRecorder", "Failed to move file: " + sourceFile.getAbsolutePath());
            }
        } else {
            Log.e("AudioRecorder", "Source file doesn't exist: " + sourceFile.getAbsolutePath());
        }
    }

}
