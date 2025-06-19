package com.tsinghua.sample.utils;

import android.util.Log;
import com.tsinghua.sample.PlotView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.*;

public class NotificationHandler {
    private static final String TAG = "NotificationHandler";

    // 实时数据显示的PlotView
    private static PlotView plotViewG, plotViewI;
    private static PlotView plotViewR, plotViewX;
    private static PlotView plotViewY, plotViewZ;

    // 文件操作回调接口
    public interface FileResponseCallback {
        void onFileListReceived(byte[] data);
        void onFileDataReceived(byte[] data);
    }

    // 新增：时间操作回调接口（包含校准和更新）
    public interface TimeSyncCallback {
        void onTimeSyncResponse(byte[] data);
        void onTimeUpdateResponse(byte[] data);
    }

    private static FileResponseCallback fileResponseCallback;
    private static TimeSyncCallback timeSyncCallback; // 新增

    // 设置PlotView的方法
    public static void setPlotViewG(PlotView chartView) { plotViewG = chartView; }
    public static void setPlotViewI(PlotView chartView) { plotViewI = chartView; }
    public static void setPlotViewR(PlotView chartView) { plotViewR = chartView; }
    public static void setPlotViewX(PlotView chartView) { plotViewX = chartView; }
    public static void setPlotViewY(PlotView chartView) { plotViewY = chartView; }
    public static void setPlotViewZ(PlotView chartView) { plotViewZ = chartView; }

    // 设置文件响应回调
    public static void setFileResponseCallback(FileResponseCallback callback) {
        fileResponseCallback = callback;
        Log.d(TAG, "File response callback set");
    }

    // 新增：设置时间校准回调
    public static void setTimeSyncCallback(TimeSyncCallback callback) {
        timeSyncCallback = callback;
        Log.d(TAG, "Time sync callback set");
    }

    /**
     * 小端序读取4字节无符号整数 - 修复大小端问题
     */
    private static long readUInt32LE(byte[] data, int offset) {
        if (offset + 4 > data.length) {
            throw new IndexOutOfBoundsException("Not enough data to read uint32");
        }
        return ((long)(data[offset] & 0xFF)) |
                ((long)(data[offset + 1] & 0xFF) << 8) |
                ((long)(data[offset + 2] & 0xFF) << 16) |
                ((long)(data[offset + 3] & 0xFF) << 24);
    }

    /**
     * 小端序读取2字节有符号整数
     */
    private static short readInt16LE(byte[] data, int offset) {
        if (offset + 2 > data.length) {
            throw new IndexOutOfBoundsException("Not enough data to read int16");
        }
        return (short)(((data[offset + 1] & 0xFF) << 8) | (data[offset] & 0xFF));
    }

    /**
     * 小端序读取8字节时间戳
     */
    private static long readUInt64LE(byte[] data, int offset) {
        if (offset + 8 > data.length) {
            throw new IndexOutOfBoundsException("Not enough data to read uint64");
        }
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result |= ((long)(data[offset + i] & 0xFF)) << (i * 8);
        }
        return result;
    }

    /**
     * 主要数据处理入口
     */
    public static String handleNotification(byte[] data) {
        if (data == null || data.length < 4) {
            Log.w(TAG, "Invalid data: null or length < 4");
            return "Invalid data";
        }

        int frameType = data[0] & 0xFF;
        int frameId = data[1] & 0xFF;
        int cmd = data[2] & 0xFF;
        int subcmd = data[3] & 0xFF;

        Log.d(TAG, String.format("Received data: FrameType=0x%02X, FrameID=0x%02X, Cmd=0x%02X, Subcmd=0x%02X, Length=%d",
                frameType, frameId, cmd, subcmd, data.length));

        // 新增：时间校准处理 (Cmd = 0x10)
        if (cmd == 0x10) {
            return handleTimeSyncOperations(data, frameId, subcmd);
        }
        // 文件操作处理 (Cmd = 0x36)
        else if (cmd == 0x36) {
            return handleFileOperations(data, frameId, subcmd);
        }
        // 实时数据处理 (Cmd = 0x3C)
        else if (cmd == 0x3C) {
            return handleRealtimeData(data, frameId, subcmd);
        }
        // 其他命令
        else {
            String result = "Unknown command: 0x" + String.format("%02X", cmd);
            Log.w(TAG, result);
            return result;
        }
    }

    /**
     * 新增：处理时间操作相关的响应 (Cmd = 0x10)
     */
    private static String handleTimeSyncOperations(byte[] data, int frameId, int subcmd) {
        Log.d(TAG, String.format("Handling time operation: Subcmd=0x%02X", subcmd));

        switch (subcmd) {
            case 0x00: // 时间更新响应
                return handleTimeUpdateResponse(data, frameId);

            case 0x02: // 时间校准响应
                return handleTimeSyncResponse(data, frameId);

            default:
                String result = "Unknown time operation subcmd: 0x" + String.format("%02X", subcmd);
                Log.w(TAG, result);
                return result;
        }
    }

    /**
     * 新增：处理时间更新响应
     */
    private static String handleTimeUpdateResponse(byte[] data, int frameId) {
        Log.d(TAG, "Processing time update response");

        try {
            // 通知回调处理详细解析
            if (timeSyncCallback != null) {
                timeSyncCallback.onTimeUpdateResponse(data);
            } else {
                Log.w(TAG, "Time sync callback is null");
            }

            // 返回简单的状态信息
            String result = String.format("Time Update Response (Frame ID: %d): Success", frameId);
            Log.i(TAG, result);
            return result;

        } catch (Exception e) {
            String result = "Error processing time update response: " + e.getMessage();
            Log.e(TAG, result, e);
            return result;
        }
    }

    /**
     * 新增：处理时间校准响应
     */
    private static String handleTimeSyncResponse(byte[] data, int frameId) {
        Log.d(TAG, "Processing time sync response");

        try {
            // 通知回调处理详细解析
            if (timeSyncCallback != null) {
                timeSyncCallback.onTimeSyncResponse(data);
            } else {
                Log.w(TAG, "Time sync callback is null");
            }

            // 返回简单的状态信息 - 使用小端序读取
            if (data.length >= 28) { // 4字节帧头 + 24字节数据
                long hostSentTime = readUInt64LE(data, 4);
                long ringReceivedTime = readUInt64LE(data, 12);
                long ringUploadTime = readUInt64LE(data, 20);

                String result = String.format("Time Sync Response (Frame ID: %d): Host=%d, Ring RX=%d, Ring TX=%d",
                        frameId, hostSentTime, ringReceivedTime, ringUploadTime);
                Log.i(TAG, result);
                return result;
            } else {
                String result = "Invalid time sync response length: " + data.length;
                Log.e(TAG, result);
                return result;
            }

        } catch (Exception e) {
            String result = "Error processing time sync response: " + e.getMessage();
            Log.e(TAG, result, e);
            return result;
        }
    }

    /**
     * 处理文件操作相关的响应 (Cmd = 0x36)
     */
    private static String handleFileOperations(byte[] data, int frameId, int subcmd) {
        Log.d(TAG, String.format("Handling file operation: Subcmd=0x%02X", subcmd));

        switch (subcmd) {
            case 0x10: // 文件列表响应
                return handleFileListResponse(data, frameId);

            case 0x11: // 文件数据响应
                return handleFileDataResponse(data, frameId);

            default:
                String result = "Unknown file operation subcmd: 0x" + String.format("%02X", subcmd);
                Log.w(TAG, result);
                return result;
        }
    }

    /**
     * 处理文件列表响应 - 修复为小端序
     */
    private static String handleFileListResponse(byte[] data, int frameId) {
        Log.d(TAG, "Processing file list response");

        try {
            // 通知回调处理详细解析
            if (fileResponseCallback != null) {
                fileResponseCallback.onFileListReceived(data);
            } else {
                Log.w(TAG, "File response callback is null");
            }

            // 返回简单的状态信息 - 使用小端序读取
            if (data.length >= 12) {
                long totalFiles = readUInt32LE(data, 4);
                long seqNum = readUInt32LE(data, 8);

                String result = String.format("File List Response (Frame ID: %d): Total=%d, Seq=%d",
                        frameId, totalFiles, seqNum);
                Log.i(TAG, result);
                return result;
            } else {
                String result = "Invalid file list response length: " + data.length;
                Log.e(TAG, result);
                return result;
            }

        } catch (Exception e) {
            String result = "Error processing file list: " + e.getMessage();
            Log.e(TAG, result, e);
            return result;
        }
    }

    /**
     * 处理文件数据响应 - 修复为小端序
     */
    private static String handleFileDataResponse(byte[] data, int frameId) {
        Log.d(TAG, "Processing file data response");

        try {
            // 通知回调处理详细解析和保存
            if (fileResponseCallback != null) {
                fileResponseCallback.onFileDataReceived(data);
            } else {
                Log.w(TAG, "File response callback is null");
            }

            // 返回简单的状态信息 - 使用小端序读取
            if (data.length >= 21) {
                int fileSystemStatus = data[4] & 0xFF;
                long fileSize = readUInt32LE(data, 5);
                long totalPackets = readUInt32LE(data, 9);
                long currentPacket = readUInt32LE(data, 13);
                long currentPacketLength = readUInt32LE(data, 17);

                String result = String.format("File Data Response (Frame ID: %d): Status=%d, Size=%d, Packet=%d/%d, Length=%d",
                        frameId, fileSystemStatus, fileSize, currentPacket, totalPackets, currentPacketLength);
                Log.i(TAG, result);
                return result;
            } else {
                String result = "Invalid file data response length: " + data.length;
                Log.e(TAG, result);
                return result;
            }

        } catch (Exception e) {
            String result = "Error processing file data: " + e.getMessage();
            Log.e(TAG, result, e);
            return result;
        }
    }

    /**
     * 处理实时数据 (Cmd = 0x3C)
     */
    private static String handleRealtimeData(byte[] data, int frameId, int subcmd) {
        Log.d(TAG, String.format("Handling realtime data: Subcmd=0x%02X", subcmd));

        switch (subcmd) {
            case 0x01: // 实时数据或时间响应
                if (data.length == 13) {
                    // 时间响应包
                    return handleTimeResponse(data, frameId);
                } else if (data.length > 14) {
                    // 实时波形数据包
                    return handleRealtimeWaveformData(data, frameId);
                } else {
                    String result = "Invalid packet length for subcmd 0x01: " + data.length;
                    Log.w(TAG, result);
                    return result;
                }

            case 0x02: // 标准波形响应包
                return handleStandardWaveformResponse(data, frameId);

            case 0xFF: // 进度响应包
                return handleProgressResponse(data, frameId);

            default:
                String result = "Unknown realtime subcmd: 0x" + String.format("%02X", subcmd);
                Log.w(TAG, result);
                return result;
        }
    }

    /**
     * 处理时间响应 - 使用小端序
     */
    private static String handleTimeResponse(byte[] data, int frameId) {
        if (data.length < 13) {
            return "Invalid time response packet length";
        }

        StringBuilder result = new StringBuilder();
        result.append("Time Response (Frame ID: ").append(frameId).append("):\n");

        long timestamp = readUInt64LE(data, 4);
        int timezone = data[12] & 0xFF;

        result.append("UNIX Timestamp: ").append(timestamp).append(" ms\n");
        result.append("Formatted Time: ").append(formatTimestamp(timestamp)).append("\n");
        result.append("Timezone: ").append(timezone);

        Log.i(TAG, "Time response processed");
        return result.toString();
    }

    /**
     * 处理实时波形数据并更新图表 - 对齐Python逻辑
     */
    private static String handleRealtimeWaveformData(byte[] data, int frameId) {
        if (data.length < 14) {
            return "Invalid realtime waveform packet length";
        }

        StringBuilder result = new StringBuilder();
        result.append("Realtime Waveform (Frame ID: ").append(frameId).append("):\n");

        // 对齐Python: seq = ppg_led_data[0], data_num = ppg_led_data[1]
        int seq = data[4] & 0xFF;  // 偏移4字节后的第一个字节
        int dataNum = data[5] & 0xFF;  // 偏移4字节后的第二个字节

        result.append("Sequence: ").append(seq).append(", Data Count: ").append(dataNum).append("\n");

        // 对齐Python: 10字节头部 + data_num * 30字节数据
        int expectedLength = 4 + 10 + dataNum * 30;  // 4字节帧头 + 10字节数据头 + 数据
        if (data.length < expectedLength) {
            String error = "Incomplete realtime data. Expected: " + expectedLength + ", Got: " + data.length;
            Log.w(TAG, error);
            return result.append(error).toString();
        }

        // 读取时间戳 (对齐Python: unix_ms = int.from_bytes(ppg_led_data[2:10], byteorder='little'))
        long frameTimestamp = readUInt64LE(data, 6);  // 偏移4字节帧头 + 2字节(seq+data_num)
        result.append("Frame Time: ").append(formatTimestamp(frameTimestamp)).append("\n");

        // 处理实时数据点并更新图表 - 对齐Python逻辑
        int validPoints = 0;
        for (int i = 0; i < dataNum && i < 20; i++) { // 限制最大处理数量
            // 对齐Python: offset = 10 + group_idx * 30
            int offset = 4 + 10 + i * 30;  // 4字节帧头 + 10字节数据头 + i * 30

            if (offset + 30 <= data.length) {
                if (parseAndUpdateRealtimeDataPoint(data, offset)) {
                    validPoints++;
                }
            } else {
                Log.w(TAG, "Incomplete data point " + i);
                break;
            }
        }

        result.append("Updated ").append(validPoints).append(" data points to charts");
        Log.d(TAG, "Processed " + validPoints + " realtime data points");
        return result.toString();
    }

    /**
     * 解析单个实时数据点并更新图表 - 完全对齐Python逻辑
     */
    private static boolean parseAndUpdateRealtimeDataPoint(byte[] data, int offset) {
        try {
            // 对齐Python解析逻辑，使用小端序
            // PPG数据 (前12字节，每个4字节)
            long green = readUInt32LE(data, offset);
            long red = readUInt32LE(data, offset + 4);
            long ir = readUInt32LE(data, offset + 8);

            // 加速度计数据 (12-17字节，每个2字节有符号)
            short accX = readInt16LE(data, offset + 12);
            short accY = readInt16LE(data, offset + 14);
            short accZ = readInt16LE(data, offset + 16);

            // 陀螺仪数据 (18-23字节，每个2字节有符号)
            short gyroX = readInt16LE(data, offset + 18);
            short gyroY = readInt16LE(data, offset + 20);
            short gyroZ = readInt16LE(data, offset + 22);

            // 温度数据 (24-29字节，每个2字节有符号)
            short temp0 = readInt16LE(data, offset + 24);
            short temp1 = readInt16LE(data, offset + 26);
            short temp2 = readInt16LE(data, offset + 28);

            // 更新实时图表显示
            updateRealtimeCharts(green, red, ir, accX, accY, accZ);

            Log.v(TAG, String.format("Realtime point: G:%d, R:%d, IR:%d, AccX:%d, AccY:%d, AccZ:%d, GyroX:%d, GyroY:%d, GyroZ:%d, T0:%d, T1:%d, T2:%d",
                    green, red, ir, accX, accY, accZ, gyroX, gyroY, gyroZ, temp0, temp1, temp2));

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error parsing realtime data point", e);
            return false;
        }
    }

    /**
     * 更新实时图表
     */
    private static void updateRealtimeCharts(long green, long red, long ir, short accX, short accY, short accZ) {
        try {
            // 更新PPG数据图表
            if (plotViewG != null) plotViewG.addValue((int)green);
            if (plotViewR != null) plotViewR.addValue((int)red);
            if (plotViewI != null) plotViewI.addValue((int)ir);

            // 更新加速度图表
            if (plotViewX != null) plotViewX.addValue(accX);
            if (plotViewY != null) plotViewY.addValue(accY);
            if (plotViewZ != null) plotViewZ.addValue(accZ);

        } catch (Exception e) {
            Log.e(TAG, "Error updating realtime charts", e);
        }
    }

    /**
     * 处理标准波形响应
     */
    private static String handleStandardWaveformResponse(byte[] data, int frameId) {
        if (data.length < 6) {
            return "Invalid standard waveform response packet length";
        }

        StringBuilder result = new StringBuilder();
        result.append("Standard Waveform (Frame ID: ").append(frameId).append("):\n");

        int seq = data[4] & 0xFF;
        int dataNum = data[5] & 0xFF;

        result.append("Sequence: ").append(seq).append(", Data Count: ").append(dataNum).append("\n");

        int expectedLength = 4 + 10 + dataNum * 30;
        if (data.length < expectedLength) {
            String error = "Incomplete standard waveform. Expected: " + expectedLength + ", Got: " + data.length;
            Log.w(TAG, error);
            return result.append(error).toString();
        }

        long frameTimestamp = readUInt64LE(data, 6);
        result.append("Frame Time: ").append(formatTimestamp(frameTimestamp)).append("\n");

        // 处理数据点
        int validPoints = 0;
        for (int i = 0; i < dataNum; i++) {
            int offset = 4 + 10 + i * 30;
            if (offset + 30 <= data.length) {
                if (parseAndUpdateRealtimeDataPoint(data, offset)) {
                    validPoints++;
                }
            }
        }

        result.append("Processed ").append(validPoints).append(" standard waveform points");
        return result.toString();
    }

    /**
     * 处理进度响应
     */
    private static String handleProgressResponse(byte[] data, int frameId) {
        if (data.length < 5) {
            return "Invalid progress response packet length";
        }

        int progress = data[4] & 0xFF;
        String result = "Progress (Frame ID: " + frameId + "): " + progress + "%";
        Log.i(TAG, result);
        return result;
    }

    /**
     * 格式化时间戳
     */
    private static String formatTimestamp(long timestampMillis) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd.HH:mm:ss.SSS", Locale.getDefault());
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(timestampMillis));
    }

    /**
     * 清空所有图表数据
     */
    public static void clearAllCharts() {
        try {
            if (plotViewG != null) plotViewG.clearPlot();
            if (plotViewI != null) plotViewI.clearPlot();
            if (plotViewR != null) plotViewR.clearPlot();
            if (plotViewX != null) plotViewX.clearPlot();
            if (plotViewY != null) plotViewY.clearPlot();
            if (plotViewZ != null) plotViewZ.clearPlot();
            Log.d(TAG, "All charts cleared");
        } catch (Exception e) {
            Log.e(TAG, "Error clearing charts", e);
        }
    }

    /**
     * 获取当前是否有有效的图表连接
     */
    public static boolean hasValidCharts() {
        return plotViewG != null || plotViewI != null || plotViewR != null ||
                plotViewX != null || plotViewY != null || plotViewZ != null;
    }
}