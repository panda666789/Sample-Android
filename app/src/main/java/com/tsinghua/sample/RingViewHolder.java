package com.tsinghua.sample;

import static android.content.Context.MODE_PRIVATE;
import static com.tsinghua.sample.MainActivity.hexStringToByteArray;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import com.lm.sdk.LmAPI;
import com.lm.sdk.LogicalApi;
import com.lm.sdk.inter.ICustomizeCmdListener;
import com.lm.sdk.utils.BLEUtils;
import com.tsinghua.sample.activity.ListActivity;
import com.tsinghua.sample.utils.NotificationHandler;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TimeZone;

public class RingViewHolder extends RecyclerView.ViewHolder {
    TextView deviceName;
    Button startBtn;
    ImageButton settingsBtn;
    private LinearLayout infoLayout;
    private boolean infoVisible = false;
    private TextView tvLog;
    Button connectBtn;

    // 文件操作按钮
    Button requestFileListBtn;
    Button downloadFilesBtn;

    // 新增：时间相关按钮
    Button timeSyncBtn;
    Button timeUpdateBtn;

    // 新增：单个文件下载相关UI
    EditText fileNameInput;
    Button downloadSingleBtn;

    private BufferedWriter logWriter;
    private boolean isRecordingRing = false;
    private PlotView plotViewG, plotViewI;
    private PlotView plotViewR, plotViewX;
    private PlotView plotViewY, plotViewZ;

    // 文件操作相关
    private List<FileInfo> fileList = new ArrayList<>();
    private boolean isDownloadingFiles = false;
    private int currentDownloadIndex = 0;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    // 时间相关操作状态
    private boolean isTimeSyncing = false;
    private boolean isTimeUpdating = false;
    private long timeSyncRequestTime = 0;
    private int timeSyncFrameId = 0;
    private int timeUpdateFrameId = 0;

    // 文件信息类
    public static class FileInfo {
        public String fileName;
        public int fileSize;
        public int fileType;
        public String userId;
        public long timestamp;

        public FileInfo(String fileName, int fileSize) {
            this.fileName = fileName;
            this.fileSize = fileSize;
            parseFileName();
        }

        private void parseFileName() {
            // 解析文件名格式: 010203040506_1722909000000000_3.txt
            String[] parts = fileName.replace(".txt", "").split("_");
            if (parts.length >= 3) {
                this.userId = parts[0];
                this.timestamp = Long.parseLong(parts[1]);
                this.fileType = Integer.parseInt(parts[2]);
            }
        }

        public String getFileTypeDescription() {
            switch (fileType) {
                case 1: return "三轴数据";
                case 2: return "六轴数据";
                case 3: return "PPG数据红外+红色+三轴(spo2)";
                case 4: return "PPG数据绿色";
                case 5: return "PPG数据红外";
                case 6: return "温度数据红外";
                case 7: return "红外+红色+绿色+温度+三轴";
                case 8: return "PPG数据绿色+三轴(hr)";
                default: return "未知类型";
            }
        }
    }
    private ICustomizeCmdListener fileTransferCmdListener = new ICustomizeCmdListener() {
        @Override
        public void cmdData(String responseData) {
            // 将十六进制字符串转换为字节数组
            byte[] responseBytes = hexStringToByteArray(responseData);

            // 记录原始响应
            recordLog("收到自定义指令响应: " + responseData);

            // 根据响应内容判断类型并分发处理
            handleCustomizeResponse(responseBytes);
        }
    };
    private void handleCustomizeResponse(byte[] data) {
        try {
            if (data == null || data.length < 4) {
                recordLog("自定义指令响应数据长度不足");
                return;
            }

            // 解析帧头 [Frame Type][Frame ID][Cmd][Subcmd]
            int frameType = data[0] & 0xFF;
            int frameId = data[1] & 0xFF;
            int cmd = data[2] & 0xFF;
            int subcmd = data[3] & 0xFF;

            recordLog(String.format("响应解析: FrameType=0x%02X, FrameID=0x%02X, Cmd=0x%02X, Subcmd=0x%02X",
                    frameType, frameId, cmd, subcmd));

            // 根据命令类型分发处理
            if (frameType == 0x00) {
                if (cmd == 0x36) {
                    if (subcmd == 0x10) {
                        // 文件列表响应
                        recordLog("识别为文件列表响应");
                        handleFileListResponse(data);
                    } else if (subcmd == 0x11) {
                        // 文件数据响应
                        recordLog("识别为文件数据响应");
                        handleFileDataResponse(data);
                    }
                } else if (cmd == 0x10) {
                    if (subcmd == 0x00) {
                        // 时间更新响应
                        recordLog("识别为时间更新响应");
                        handleTimeUpdateResponse(data);
                    } else if (subcmd == 0x02) {
                        // 时间校准响应
                        recordLog("识别为时间校准响应");
                        handleTimeSyncResponse(data);
                    }
                }
            }

            // 如果是其他类型的响应，可以在这里添加更多处理逻辑

        } catch (Exception e) {
            recordLog("处理自定义指令响应失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    public RingViewHolder(View itemView) {
        super(itemView);

        deviceName = itemView.findViewById(R.id.deviceName);
        startBtn = itemView.findViewById(R.id.startBtn);
        settingsBtn = itemView.findViewById(R.id.settingsBtn);
        infoLayout = itemView.findViewById(R.id.infoLayout);
        tvLog = itemView.findViewById(R.id.tvLog);
        connectBtn = itemView.findViewById(R.id.connectBtn);
        requestFileListBtn = itemView.findViewById(R.id.requestFileListBtn);
        downloadFilesBtn = itemView.findViewById(R.id.downloadFilesBtn);

        // 时间操作按钮初始化
        timeSyncBtn = itemView.findViewById(R.id.timeSyncBtn);
        timeUpdateBtn = itemView.findViewById(R.id.timeUpdateBtn);

        // 单个文件下载UI初始化
        fileNameInput = itemView.findViewById(R.id.editText_file_name);
        downloadSingleBtn = itemView.findViewById(R.id.btn_download_single_file);

        connectBtn.setOnClickListener(v -> connectToDevice(itemView.getContext()));

        // 文件操作按钮事件
        requestFileListBtn.setOnClickListener(v -> requestFileList(itemView.getContext()));
        downloadFilesBtn.setOnClickListener(v -> startDownloadAllFiles(itemView.getContext()));

        // 时间操作按钮事件
        timeUpdateBtn.setOnClickListener(v -> updateRingTime(itemView.getContext()));
        timeSyncBtn.setOnClickListener(v -> performTimeSync(itemView.getContext()));

        // 单个文件下载按钮事件
        if (downloadSingleBtn != null) {
            downloadSingleBtn.setOnClickListener(v -> {
                if (fileNameInput != null) {
                    String fileName = fileNameInput.getText().toString();
                    downloadFileByName(itemView.getContext(), fileName);
                }
            });
        }

        // 初始化图表
        initializePlotViews(itemView);

        // 设置NotificationHandler的回调
        setupNotificationCallback();
    }

    private void initializePlotViews(View itemView) {
        plotViewG = itemView.findViewById(R.id.plotViewG);
        plotViewI = itemView.findViewById(R.id.plotViewI);
        plotViewR = itemView.findViewById(R.id.plotViewR);
        plotViewX = itemView.findViewById(R.id.plotViewX);
        plotViewY = itemView.findViewById(R.id.plotViewY);
        plotViewZ = itemView.findViewById(R.id.plotViewZ);

        if (plotViewG != null) plotViewG.setPlotColor(Color.parseColor("#00FF00"));
        if (plotViewI != null) plotViewI.setPlotColor(Color.parseColor("#0000FF"));
        if (plotViewR != null) plotViewR.setPlotColor(Color.parseColor("#FF0000"));
        if (plotViewX != null) plotViewX.setPlotColor(Color.parseColor("#FFFF00"));
        if (plotViewY != null) plotViewY.setPlotColor(Color.parseColor("#FF00FF"));
        if (plotViewZ != null) plotViewZ.setPlotColor(Color.parseColor("#00FFFF"));

        NotificationHandler.setPlotViewG(plotViewG);
        NotificationHandler.setPlotViewI(plotViewI);
        NotificationHandler.setPlotViewR(plotViewR);
        NotificationHandler.setPlotViewX(plotViewX);
        NotificationHandler.setPlotViewY(plotViewY);
        NotificationHandler.setPlotViewZ(plotViewZ);
    }

    private void setupNotificationCallback() {
        // 设置数据接收回调，用于处理文件列表、文件数据和时间校准响应
        NotificationHandler.setFileResponseCallback(new NotificationHandler.FileResponseCallback() {
            @Override
            public void onFileListReceived(byte[] data) {
                handleFileListResponse(data);
            }

            @Override
            public void onFileDataReceived(byte[] data) {
                handleFileDataResponse(data);
            }
        });

        // 时间相关响应回调
        NotificationHandler.setTimeSyncCallback(new NotificationHandler.TimeSyncCallback() {
            @Override
            public void onTimeSyncResponse(byte[] data) {
                handleTimeSyncResponse(data);
            }

            @Override
            public void onTimeUpdateResponse(byte[] data) {
                handleTimeUpdateResponse(data);
            }
        });
    }

    // ==================== 时间同步相关方法 ====================

    // 更新戒指实时时间
    public void updateRingTime(Context context) {
        if (isTimeUpdating) {
            Toast.makeText(context, "时间更新正在进行中，请等待", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            isTimeUpdating = true;

            // 生成随机Frame ID
            timeUpdateFrameId = generateRandomFrameId();

            recordLog("【开始更新戒指时间】使用自定义指令");

            // 获取当前时间和时区
            long currentTime = System.currentTimeMillis();
            TimeZone timeZone = TimeZone.getDefault();
            int timezoneOffset = timeZone.getRawOffset() / (1000 * 60 * 60); // 转换为小时

            recordLog("主机当前时间: " + currentTime + " ms");
            recordLog("当前时区偏移: UTC" + (timezoneOffset >= 0 ? "+" : "") + timezoneOffset);

            // 构建时间更新命令: 00 [Frame ID] 10 00 [8字节时间戳] [1字节时区]
            StringBuilder hexCommand = new StringBuilder();
            hexCommand.append(String.format("00%02X1000", timeUpdateFrameId));

            // 将时间戳转换为8字节的小端序十六进制
            for (int i = 0; i < 8; i++) {
                hexCommand.append(String.format("%02X", (currentTime >> (i * 8)) & 0xFF));
            }

            // 添加时区字节（处理负时区）
            int timezoneValue = timezoneOffset;
            if (timezoneValue < 0) {
                timezoneValue = 256 + timezoneValue; // 转换为无符号字节表示
            }
            hexCommand.append(String.format("%02X", timezoneValue & 0xFF));

            byte[] data = hexStringToByteArray(hexCommand.toString());
            recordLog("发送时间更新命令: " + hexCommand.toString());

            // 更新UI状态
            timeUpdateBtn.setText("更新中...");
            timeUpdateBtn.setEnabled(false);

            // 使用自定义指令发送
            LmAPI.CUSTOMIZE_CMD(data, fileTransferCmdListener);

        } catch (Exception e) {
            recordLog("发送时间更新命令失败: " + e.getMessage());
            e.printStackTrace();

            // 恢复UI状态
            mainHandler.post(() -> {
                timeUpdateBtn.setText("更新时间");
                timeUpdateBtn.setEnabled(true);
            });
            isTimeUpdating = false;
        }
    }
    // 处理时间更新响应
    private void handleTimeUpdateResponse(byte[] data) {
        try {
            if (data == null || data.length < 4) {
                recordLog("时间更新响应数据长度不足: " + (data != null ? data.length : "null"));
                return;
            }

            // 验证响应格式
            int frameType = data[0] & 0xFF;
            int frameId = data[1] & 0xFF;
            int cmd = data[2] & 0xFF;
            int subcmd = data[3] & 0xFF;

            if (frameType != 0x00 || cmd != 0x10 || subcmd != 0x00) {
                recordLog("时间更新响应格式错误");
                recordLog(String.format("期望: FrameType=0x00, Cmd=0x10, Subcmd=0x00"));
                recordLog(String.format("实际: FrameType=0x%02X, Cmd=0x%02X, Subcmd=0x%02X",
                        frameType, cmd, subcmd));
                return;
            }

            if (frameId != timeUpdateFrameId) {
                recordLog("时间更新响应Frame ID不匹配");
                recordLog(String.format("期望: 0x%02X, 实际: 0x%02X", timeUpdateFrameId, frameId));
                return;
            }

            recordLog("原始时间更新响应: " + bytesToHexString(data));

            if (data.length != 4) {
                recordLog("警告: 时间更新响应长度异常，期望4字节，实际" + data.length + "字节");
            }

            recordLog("【时间更新完成】");
            recordLog("✓ 戒指时间已成功更新");

            // 更新UI状态
            mainHandler.post(() -> {
                timeUpdateBtn.setText("更新时间");
                timeUpdateBtn.setEnabled(true);
                Toast.makeText(itemView.getContext(), "戒指时间更新成功", Toast.LENGTH_SHORT).show();
            });

        } catch (Exception e) {
            recordLog("解析时间更新响应失败: " + e.getMessage());
            e.printStackTrace();

            // 恢复UI状态
            mainHandler.post(() -> {
                timeUpdateBtn.setText("更新时间");
                timeUpdateBtn.setEnabled(true);
            });
        } finally {
            isTimeUpdating = false;
        }
    }

    // 执行时间校准同步
    public void performTimeSync(Context context) {
        if (isTimeSyncing) {
            Toast.makeText(context, "时间校准正在进行中，请等待", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            isTimeSyncing = true;
            timeSyncRequestTime = System.currentTimeMillis();

            // 生成随机Frame ID
            timeSyncFrameId = generateRandomFrameId();

            recordLog("【开始时间校准同步】使用自定义指令");
            recordLog("主机发送时间: " + timeSyncRequestTime + " ms");

            // 构建时间校准命令: 00 [Frame ID] 10 02 [8字节时间戳]
            StringBuilder hexCommand = new StringBuilder();
            hexCommand.append(String.format("00%02X1002", timeSyncFrameId));

            // 将时间戳转换为8字节的小端序十六进制
            long timestamp = timeSyncRequestTime;
            for (int i = 0; i < 8; i++) {
                hexCommand.append(String.format("%02X", (timestamp >> (i * 8)) & 0xFF));
            }

            byte[] data = hexStringToByteArray(hexCommand.toString());
            recordLog("发送时间校准命令: " + hexCommand.toString());

            // 更新UI状态
            timeSyncBtn.setText("校准中...");
            timeSyncBtn.setEnabled(false);

            // 使用自定义指令发送
            LmAPI.CUSTOMIZE_CMD(data, fileTransferCmdListener);

        } catch (Exception e) {
            recordLog("发送时间校准命令失败: " + e.getMessage());
            e.printStackTrace();

            // 恢复UI状态
            mainHandler.post(() -> {
                timeSyncBtn.setText("时间校准");
                timeSyncBtn.setEnabled(true);
            });
            isTimeSyncing = false;
        }
    }

    // 辅助方法：生成随机Frame ID
    private int generateRandomFrameId() {
        Random random = new Random();
        return random.nextInt(256);
    }


    // 处理时间校准响应
    private void handleTimeSyncResponse(byte[] data) {
        try {
            if (data == null || data.length < 28) { // 4字节帧头 + 24字节数据
                recordLog("时间校准响应数据长度不足: " + (data != null ? data.length : "null"));
                return;
            }

            // 验证响应格式
            int frameType = data[0] & 0xFF;
            int frameId = data[1] & 0xFF;
            int cmd = data[2] & 0xFF;
            int subcmd = data[3] & 0xFF;

            if (frameType != 0x00 || cmd != 0x10 || subcmd != 0x02) {
                recordLog("时间校准响应格式错误");
                recordLog(String.format("期望: FrameType=0x00, Cmd=0x10, Subcmd=0x02"));
                recordLog(String.format("实际: FrameType=0x%02X, Cmd=0x%02X, Subcmd=0x%02X",
                        frameType, cmd, subcmd));
                return;
            }

            if (frameId != timeSyncFrameId) {
                recordLog("时间校准响应Frame ID不匹配");
                recordLog(String.format("期望: 0x%02X, 实际: 0x%02X", timeSyncFrameId, frameId));
                return;
            }

            recordLog("原始时间校准响应: " + bytesToHexString(data));

            // 解析时间数据 (小端序)
            int offset = 4; // 跳过帧头

            // [0:7] 主机下发时间
            long hostSentTime = readUInt64LE(data, offset);
            offset += 8;

            // [8:15] 戒指接收时间
            long ringReceivedTime = readUInt64LE(data, offset);
            offset += 8;

            // [16:23] 戒指上传时间
            long ringUploadTime = readUInt64LE(data, offset);

            // 计算延迟和时差
            long currentTime = System.currentTimeMillis();
            long roundTripTime = currentTime - timeSyncRequestTime;
            long oneWayDelay = roundTripTime / 2;
            long timeDifference = ringReceivedTime - hostSentTime;

            recordLog("【时间校准结果】");
            recordLog(String.format("主机发送时间: %d ms (%s)", hostSentTime, formatTimestamp(hostSentTime)));
            recordLog(String.format("戒指接收时间: %d ms (%s)", ringReceivedTime, formatTimestamp(ringReceivedTime)));
            recordLog(String.format("戒指上传时间: %d ms (%s)", ringUploadTime, formatTimestamp(ringUploadTime)));
            recordLog(String.format("往返延迟: %d ms", roundTripTime));
            recordLog(String.format("单程延迟估计: %d ms", oneWayDelay));
            recordLog(String.format("时间差: %d ms", timeDifference));

            // 验证时间戳的合理性
            if (hostSentTime != timeSyncRequestTime) {
                recordLog("警告: 戒指返回的主机时间与发送时间不匹配");
                recordLog(String.format("发送: %d, 返回: %d, 差值: %d ms",
                        timeSyncRequestTime, hostSentTime, hostSentTime - timeSyncRequestTime));
            }

            long ringProcessingTime = ringUploadTime - ringReceivedTime;
            recordLog(String.format("戒指处理时间: %d ms", ringProcessingTime));

            // 评估时间同步质量
            if (Math.abs(timeDifference) < 50) {
                recordLog("✓ 时间同步良好 (差值 < 50ms)");
            } else if (Math.abs(timeDifference) < 200) {
                recordLog("⚠ 时间同步一般 (差值 < 200ms)");
            } else {
                recordLog("✗ 时间同步较差 (差值 >= 200ms)");
            }

            // 更新UI状态
            mainHandler.post(() -> {
                timeSyncBtn.setText("时间校准");
                timeSyncBtn.setEnabled(true);
                Toast.makeText(itemView.getContext(),
                        String.format("时间校准完成\n时间差: %d ms\n延迟: %d ms", timeDifference, roundTripTime),
                        Toast.LENGTH_LONG).show();
            });

        } catch (Exception e) {
            recordLog("解析时间校准响应失败: " + e.getMessage());
            e.printStackTrace();

            // 恢复UI状态
            mainHandler.post(() -> {
                timeSyncBtn.setText("时间校准");
                timeSyncBtn.setEnabled(true);
            });
        } finally {
            isTimeSyncing = false;
        }
    }

    // ==================== 文件操作相关方法 ====================

    // 请求文件列表
    public void requestFileList(Context context) {
        recordLog("【请求文件列表】使用自定义指令");

        try {
            // 构建请求文件列表命令: 00 [ID] 36 10
            String hexCommand = String.format("00%02X3610", generateRandomFrameId());
            byte[] data = hexStringToByteArray(hexCommand);

            recordLog("发送文件列表命令: " + hexCommand);

            // 使用自定义指令发送
            LmAPI.CUSTOMIZE_CMD(data, fileTransferCmdListener);

            // 清空之前的文件列表
            fileList.clear();
            downloadFilesBtn.setEnabled(false);
            downloadFilesBtn.setText("下载文件 (0)");

        } catch (Exception e) {
            recordLog("发送文件列表请求失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 处理文件列表响应 - 修正版本，对齐Python逻辑
    private void handleFileListResponse(byte[] data) {
        try {
            if (data == null || data.length < 4) {
                recordLog("文件列表响应数据长度不足: " + (data != null ? data.length : "null"));
                return;
            }

            // 验证命令格式 (Frame Type + Frame ID + Cmd + Subcmd)
            if (data[0] != 0x00 || data[2] != 0x36 || data[3] != 0x10) {
                recordLog("文件列表响应格式错误");
                recordLog("期望: Frame Type=0x00, Cmd=0x36, Subcmd=0x10");
                recordLog("实际: Frame Type=0x" + String.format("%02X", data[0]) +
                        ", Cmd=0x" + String.format("%02X", data[2]) +
                        ", Subcmd=0x" + String.format("%02X", data[3]));
                return;
            }

            // 打印原始数据用于调试
            recordLog("原始响应数据: " + bytesToHexString(data));
            recordLog("Frame ID: 0x" + String.format("%02X", data[1]));

            int offset = 4; // 跳过帧头部分

            // 检查是否至少有文件结构的基本信息 (Total + Seq + Size = 12字节)
            if (data.length < offset + 12) {
                recordLog("数据长度不足，无法读取文件基本信息");
                recordLog("需要至少12字节，实际剩余: " + (data.length - offset));
                return;
            }

            // 读取文件总数 (4字节，小端序)
            int totalFiles = readUInt32LE(data, offset);
            offset += 4;

            // 读取当前序号 (4字节，小端序)
            int seqNum = readUInt32LE(data, offset);
            offset += 4;

            // 读取文件大小 (4字节，小端序)
            int fileSize = readUInt32LE(data, offset);
            offset += 4;

            recordLog(String.format("文件列表信息 - 总数: %d, 当前序号: %d, 文件大小: %d", totalFiles, seqNum, fileSize));

            // 处理文件数据
            if (totalFiles == 0) {
                recordLog("文件总数为0，没有文件数据");
                // 更新UI - 没有文件
                mainHandler.post(() -> {
                    downloadFilesBtn.setEnabled(false);
                    downloadFilesBtn.setText("下载文件 (0)");
                    requestFileListBtn.setText("获取文件列表");
                    requestFileListBtn.setEnabled(true);
                });
                return;
            }

            // 验证序号的合理性
            if (seqNum < 1 || seqNum > totalFiles) {
                recordLog("文件序号异常: " + seqNum + ", 总数: " + totalFiles);
                return;
            }

            // 检查是否有文件名数据（剩余的所有字节都是文件名）
            int remainingBytes = data.length - offset;
            if (remainingBytes <= 0) {
                recordLog("没有文件名数据");
                return;
            }

            recordLog("文件名数据长度: " + remainingBytes + " 字节");

            // 读取文件名（剩余的所有字节）
            byte[] fileNameBytes = new byte[remainingBytes];
            System.arraycopy(data, offset, fileNameBytes, 0, remainingBytes);

            // 处理文件名 - 可能包含null结束符，也可能没有
            String fileName = "";
            try {
                // 先尝试查找第一个0字节作为字符串结束
                int nameLength = 0;
                for (int i = 0; i < fileNameBytes.length; i++) {
                    if (fileNameBytes[i] == 0) {
                        nameLength = i;
                        break;
                    }
                }

                // 如果没有找到结束符，使用全部字节
                if (nameLength == 0) {
                    nameLength = fileNameBytes.length;
                }

                // 使用UTF-8解码
                fileName = new String(fileNameBytes, 0, nameLength, StandardCharsets.UTF_8).trim();

                // 如果还是为空，尝试直接转换所有字节
                if (fileName.isEmpty()) {
                    fileName = new String(fileNameBytes, StandardCharsets.UTF_8).trim();
                }

            } catch (Exception e) {
                recordLog("文件名解析失败: " + e.getMessage());
                // 作为备份，显示十六进制
                fileName = "HEX_" + bytesToHexString(fileNameBytes);
            }

            recordLog(String.format("解析文件信息:"));
            recordLog(String.format("  - 文件名: '%s'", fileName));
            recordLog(String.format("  - 文件大小: %d bytes", fileSize));
            recordLog(String.format("  - 文件名字节: %s", bytesToHexString(fileNameBytes)));
            recordLog(String.format("  - 文件名字节数: %d", fileNameBytes.length));

            // 调试：尝试手动转换文件名字节
            if (!fileName.isEmpty()) {
                try {
                    StringBuilder manual = new StringBuilder();
                    for (byte b : fileNameBytes) {
                        if (b == 0) break; // 遇到null结束符停止
                        manual.append((char)b);
                    }
                    String manualFileName = manual.toString();
                    recordLog(String.format("  - 手动转换结果: '%s'", manualFileName));

                    // 如果手动转换的结果更好，使用它
                    if (manualFileName.length() > fileName.length() && manualFileName.contains(".")) {
                        fileName = manualFileName;
                        recordLog("  - 使用手动转换结果");
                    }
                } catch (Exception e) {
                    recordLog("手动转换失败: " + e.getMessage());
                }
            }

            // 验证文件大小合理性
            if (fileSize < 0) {
                recordLog("警告：文件大小为负数: " + fileSize);
                fileSize = 0;
            } else if (fileSize > 100 * 1024 * 1024) { // 100MB限制
                recordLog("警告：文件大小过大: " + fileSize + " bytes");
            }

            // 添加到文件列表（避免重复添加）
            if (!fileName.isEmpty() && !fileName.startsWith("HEX_")) {
                // 检查是否已经存在相同文件
                boolean exists = false;
                for (FileInfo existingFile : fileList) {
                    if (existingFile.fileName.equals(fileName)) {
                        exists = true;
                        break;
                    }
                }

                if (!exists) {
                    FileInfo fileInfo = new FileInfo(fileName, fileSize);
                    fileList.add(fileInfo);
                    recordLog(String.format("成功添加文件到列表: %s (%d bytes)", fileName, fileSize));

                    // 解析文件名详细信息
                    parseFileNameDetails(fileName);

                } else {
                    recordLog("文件已存在，跳过: " + fileName);
                }
            } else {
                recordLog("文件名无效，跳过添加: " + fileName);
            }

            // 更新UI
            mainHandler.post(() -> {
                downloadFilesBtn.setEnabled(fileList.size() > 0);
                downloadFilesBtn.setText(String.format("下载文件 (%d)", fileList.size()));
                requestFileListBtn.setText("获取文件列表");
                requestFileListBtn.setEnabled(true);
            });

            // 如果这不是最后一个文件，可能需要继续请求下一个
            if (seqNum < totalFiles) {
                recordLog(String.format("当前是第 %d/%d 个文件，可能需要继续获取后续文件", seqNum, totalFiles));

                // 可以选择自动请求下一个文件
                // mainHandler.postDelayed(() -> requestFileList(itemView.getContext()), 500);
            } else {
                recordLog(String.format("文件列表获取完成，共 %d 个文件", fileList.size()));
            }

        } catch (Exception e) {
            recordLog("解析文件列表失败: " + e.getMessage());
            e.printStackTrace();

            // 恢复UI状态
            mainHandler.post(() -> {
                requestFileListBtn.setText("获取文件列表");
                requestFileListBtn.setEnabled(true);
            });
        }
    }
    private void parseFileNameDetails(String fileName) {
        try {
            recordLog("解析文件名详情: " + fileName);

            // 解析文件名格式：用户id_年_月_日时分秒_文件类型.扩展名
            // 例如：010203040506_2025_06_17:02:06:26_7.bin

            if (fileName.contains("_")) {
                String[] parts = fileName.split("_");
                if (parts.length >= 2) {
                    String userId = parts[0];
                    recordLog("  - 用户ID: " + userId);

                    if (parts.length >= 3) {
                        String year = parts[1];
                        String monthDay = parts[2];
                        recordLog("  - 年份: " + year);
                        recordLog("  - 月日: " + monthDay);
                    }

                    if (parts.length >= 4) {
                        String timeAndType = parts[3];
                        recordLog("  - 时间和类型: " + timeAndType);

                        // 进一步解析时间部分
                        if (timeAndType.contains(":")) {
                            String[] timeParts = timeAndType.split(":");
                            if (timeParts.length >= 3) {
                                recordLog("  - 时: " + timeParts[0]);
                                recordLog("  - 分: " + timeParts[1]);
                                if (timeParts[2].contains("_")) {
                                    String[] secType = timeParts[2].split("_");
                                    recordLog("  - 秒: " + secType[0]);
                                    if (secType.length > 1) {
                                        String typeAndExt = secType[1];
                                        if (typeAndExt.contains(".")) {
                                            String[] typeExt = typeAndExt.split("\\.");
                                            recordLog("  - 类型: " + typeExt[0]);
                                            recordLog("  - 扩展名: " + typeExt[1]);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (fileName.endsWith(".bin")) {
                recordLog("  - 文件格式: 二进制文件");
            } else if (fileName.endsWith(".txt")) {
                recordLog("  - 文件格式: 文本文件");
            }

        } catch (Exception e) {
            recordLog("解析文件名详情失败: " + e.getMessage());
        }
    }


    // 开始下载所有文件
    public void startDownloadAllFiles(Context context) {
        if (fileList.isEmpty()) {
            Toast.makeText(context, "没有可下载的文件", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isDownloadingFiles) {
            Toast.makeText(context, "正在下载中，请等待", Toast.LENGTH_SHORT).show();
            return;
        }

        isDownloadingFiles = true;
        currentDownloadIndex = 0;
        downloadFilesBtn.setText("下载中...");
        downloadFilesBtn.setEnabled(false);

        recordLog("【开始批量下载文件】");
        recordLog("文件总数: " + fileList.size());

        downloadNextFile(context);
    }

    // 下载下一个文件
    private void downloadNextFile(Context context) {
        if (currentDownloadIndex >= fileList.size()) {
            // 所有文件下载完成
            isDownloadingFiles = false;
            mainHandler.post(() -> {
                downloadFilesBtn.setText(String.format("下载文件 (%d)", fileList.size()));
                downloadFilesBtn.setEnabled(true);
                recordLog("【所有文件下载完成】");
                Toast.makeText(context, "所有文件下载完成", Toast.LENGTH_SHORT).show();
            });
            return;
        }

        FileInfo fileInfo = fileList.get(currentDownloadIndex);
        recordLog(String.format("下载文件 %d/%d: %s (%d bytes)",
                currentDownloadIndex + 1, fileList.size(), fileInfo.fileName, fileInfo.fileSize));

        // 使用修正后的请求方法
        requestFileData(context, fileInfo);
    }

    private void requestFileData(Context context, FileInfo fileInfo) {
        recordLog("请求文件数据: " + fileInfo.fileName);

        try {
            byte[] fileNameBytes = fileInfo.fileName.getBytes(StandardCharsets.UTF_8);
            int length = fileNameBytes.length;

            recordLog("文件名UTF-8编码长度: " + length + " 字节");
            recordLog("文件名字节数据: " + bytesToHexString(fileNameBytes));

            sendFileGetCommand(fileNameBytes, length);

        } catch (Exception e) {
            recordLog("请求文件数据失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void sendFileGetCommand(byte[] fileNameBytes, int length) {
        try {
            // 生成随机Frame ID
            int frameId = generateRandomFrameId();

            // 构建命令：00 [Frame ID] 36 11 [文件名数据]（注意：没有长度字节！）
            StringBuilder hexCommand = new StringBuilder();

            // 帧头部分: 帧类型 + 帧ID + 命令 + 子命令
            hexCommand.append(String.format("00%02X3611", frameId));



            // ✅ 直接添加文件名数据，不添加长度字节
            for (byte b : fileNameBytes) {
                hexCommand.append(String.format("%02X", b & 0xFF));
            }

            byte[] commandData = hexStringToByteArray(hexCommand.toString());

            recordLog("发送文件获取命令: " + hexCommand.toString());
            recordLog("命令结构:");
            recordLog("  - Frame ID: 0x" + String.format("%02X", frameId));
            recordLog("  - 文件名: " + new String(fileNameBytes, StandardCharsets.UTF_8));
            recordLog("  - 文件名字节数: " + length + " (协议中不传输此值)");
            recordLog("  - 与Python对齐的协议格式: 00 [ID] 36 11 [文件名UTF-8]");

            // 使用自定义指令发送
            LmAPI.CUSTOMIZE_CMD(commandData, fileTransferCmdListener);

        } catch (Exception e) {
            recordLog("发送文件获取命令失败: " + e.getMessage());
            e.printStackTrace();
        }
    }



    // 处理文件数据响应 - 修正版本，对齐Python代码逻辑
    private void handleFileDataResponse(byte[] data) {
        try {
            if (data.length < 4) {
                recordLog("文件数据响应长度不足");
                return;
            }

            // 解析响应: 00 [ID] 36 11 [文件数据结构]
            if (data[2] == 0x36 && data[3] == 0x11) {
                int offset = 4; // 跳过帧头 [Frame Type][Frame ID][Cmd][Subcmd]

                // 验证数据长度是否足够读取文件头信息 (25字节)
                if (data.length < offset + 25) {
                    recordLog("文件数据结构不完整，需要至少25字节头部信息");
                    recordLog("实际长度: " + (data.length - offset) + "字节");
                    return;
                }

                // 🔧 按照Python代码的结构解析文件头

                // file_status = ppg_file_data[0]
                int fileStatus = data[offset] & 0xFF;
                offset += 1;

                // file_size = int.from_bytes(ppg_file_data[1:5], byteorder='little')
                int fileSize = readUInt32LE(data, offset);
                offset += 4;

                // file_package_num = int.from_bytes(ppg_file_data[5:9], byteorder='little')
                int totalPackets = readUInt32LE(data, offset);
                offset += 4;

                // file_package_count = int.from_bytes(ppg_file_data[9:13], byteorder='little')
                int currentPacket = readUInt32LE(data, offset);
                offset += 4;

                // file_package_length = int.from_bytes(ppg_file_data[13:17], byteorder='little')
                int currentPacketLength = readUInt32LE(data, offset);
                offset += 4;

                // unix_ms = int.from_bytes(ppg_file_data[17:25], byteorder='little')
                long timestamp = readUInt64LE(data, offset);
                offset += 8;

                recordLog("文件数据包解析结果:");
                recordLog("  文件状态: " + fileStatus);
                recordLog("  文件大小: " + fileSize + " bytes");
                recordLog("  总包数: " + totalPackets);
                recordLog("  当前包号: " + currentPacket);
                recordLog("  当前包长度: " + currentPacketLength);
                recordLog("  时间戳: " + timestamp);

                // 🔧 关键修正：验证数据包的完整性
                // required_length = 25 + 5 * 30  (Python代码中的验证)
                int requiredLength = 25 + 5 * 30; // 25字节头部 + 5组×30字节数据
                int availableLength = data.length - 4; // 减去4字节帧头

                if (availableLength < requiredLength) {
                    recordLog("数据长度不足: " + availableLength + "，需要至少" + requiredLength + "字节");
                    recordLog("Python对应错误: 数据长度不足");
                    return;
                }

                recordLog("数据包解析结果 文件大小:" + fileSize + " 总包数: " + totalPackets +
                        " 当前包号: " + currentPacket + " 当前包长度: " + currentPacketLength +
                        " 时间戳:" + timestamp);

                // 🔧 关键修正：解析数据部分 - 完全对齐Python代码
                // data_num = 5 (Python)
                int dataNum = 5; // 固定5组数据，对应Python的data_num = 5

                // for group_idx in range(data_num): (Python)
                for (int groupIdx = 0; groupIdx < dataNum; groupIdx++) {
                    // 🔧 修正：offset = 25 + group_idx * 30 (Python中相对于纯文件数据)
                    // Java中需要考虑到data包含4字节帧头，所以实际偏移应该是：
                    int dataOffset = (4 + 25) + groupIdx * 30; // 4字节帧头 + 25字节文件头 + 数据偏移

                    if (dataOffset + 30 > data.length) {
                        recordLog("第" + (groupIdx + 1) + "组数据不完整");
                        break;
                    }

                    // 🔧 完全按照Python代码的顺序和方式读取数据

                    // green = int.from_bytes(ppg_file_data[offset:offset+4], byteorder='little')
                    long green = readUInt32LE(data, dataOffset);

                    // red = int.from_bytes(ppg_file_data[offset+4:offset+8], byteorder='little')
                    long red = readUInt32LE(data, dataOffset + 4);

                    // ir = int.from_bytes(ppg_file_data[offset+8:offset+12], byteorder='little')
                    long ir = readUInt32LE(data, dataOffset + 8);

                    // acc_x = int.from_bytes(ppg_file_data[offset+12:offset+14], byteorder='little', signed=True)
                    short accX = readInt16LE(data, dataOffset + 12);

                    // acc_y = int.from_bytes(ppg_file_data[offset+14:offset+16], byteorder='little', signed=True)
                    short accY = readInt16LE(data, dataOffset + 14);

                    // acc_z = int.from_bytes(ppg_file_data[offset+16:offset+18], byteorder='little', signed=True)
                    short accZ = readInt16LE(data, dataOffset + 16);

                    // gyro_x = int.from_bytes(ppg_file_data[offset+18:offset+20], byteorder='little', signed=True)
                    short gyroX = readInt16LE(data, dataOffset + 18);

                    // gyro_y = int.from_bytes(ppg_file_data[offset+20:offset+22], byteorder='little', signed=True)
                    short gyroY = readInt16LE(data, dataOffset + 20);

                    // gyro_z = int.from_bytes(ppg_file_data[offset+22:offset+24], byteorder='little', signed=True)
                    short gyroZ = readInt16LE(data, dataOffset + 22);

                    // temper0 = int.from_bytes(ppg_file_data[offset+24:offset+26], byteorder='little', signed=True)
                    short temper0 = readInt16LE(data, dataOffset + 24);

                    // temper1 = int.from_bytes(ppg_file_data[offset+26:offset+28], byteorder='little', signed=True)
                    short temper1 = readInt16LE(data, dataOffset + 26);

                    // temper2 = int.from_bytes(ppg_file_data[offset+28:offset+30], byteorder='little', signed=True)
                    short temper2 = readInt16LE(data, dataOffset + 28);

                    updatePlotViews(green, red, ir, accX, accY, accZ);

                    String logMsg = String.format("green:%d red:%d ir:%d " +
                                    "acc_x:%d acc_y:%d acc_z:%d " +
                                    "gyro_x:%d gyro_y:%d gyro_z:%d " +
                                    "temper0:%d temper1:%d temper2:%d",
                            green, red, ir,
                            accX, accY, accZ,
                            gyroX, gyroY, gyroZ,
                            temper0, temper1, temper2);

                    recordLog(logMsg);
                }

                // 保存文件数据
                if (currentDownloadIndex < fileList.size()) {
                    FileInfo fileInfo = fileList.get(currentDownloadIndex);
                    saveFileData(fileInfo, data, currentPacket, totalPackets);

                    // 如果是最后一包，继续下载下一个文件
                    if (currentPacket >= totalPackets) {
                        currentDownloadIndex++;
                        mainHandler.postDelayed(() -> downloadNextFile(itemView.getContext()), 500);
                    }
                }
            }
        } catch (Exception e) {
            recordLog("解析文件数据失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 手动输入文件名下载的方法（对应Python的UI功能）
    public void downloadFileByName(Context context, String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            Toast.makeText(context, "请输入文件名", Toast.LENGTH_SHORT).show();
            return;
        }

        recordLog("【手动下载文件】: " + fileName.trim());
        requestSpecificFile(context, fileName.trim());
    }

    // 请求特定文件，类似Python的pushButton_ppg_file_get_callback
    public void requestSpecificFile(Context context, String fileName) {
        recordLog("【请求特定文件】: " + fileName);

        try {
            // 按照Python代码逻辑处理
            byte[] fileNameBytes = fileName.getBytes(StandardCharsets.UTF_8);
            int length = fileNameBytes.length;

            recordLog("文件名: " + fileName);
            recordLog("UTF-8编码长度: " + length + " 字节");

            // 发送命令
            sendFileGetCommand(fileNameBytes, length);

        } catch (Exception e) {
            recordLog("请求文件失败: " + e.getMessage());
            e.printStackTrace();

            Toast.makeText(context, "请求文件失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 辅助方法 ====================

    // 读取4字节无符号整型（小端序）
    private int readUInt32LE(byte[] data, int offset) {
        if (offset + 4 > data.length) {
            throw new IndexOutOfBoundsException("数据不足以读取4字节整型");
        }
        return (data[offset] & 0xFF) |
                ((data[offset + 1] & 0xFF) << 8) |
                ((data[offset + 2] & 0xFF) << 16) |
                ((data[offset + 3] & 0xFF) << 24);
    }

    // 读取8字节无符号长整型（小端序）
    private long readUInt64LE(byte[] data, int offset) {
        if (offset + 8 > data.length) {
            throw new IndexOutOfBoundsException("数据不足以读取8字节时间戳");
        }
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result |= ((long)(data[offset + i] & 0xFF)) << (i * 8);
        }
        return result;
    }

    // 读取2字节有符号短整型（小端序）
    private short readInt16LE(byte[] data, int offset) {
        if (offset + 2 > data.length) {
            throw new IndexOutOfBoundsException("数据不足以读取2字节短整型");
        }
        return (short)((data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8));
    }

    // 格式化时间戳
    private String formatTimestamp(long timestampMillis) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault());
        return sdf.format(new java.util.Date(timestampMillis));
    }

    // 验证文件名格式
    private boolean isValidFileName(String fileName) {
        // 根据Python代码中的文件名格式验证
        // 格式：用户id_时间戳_文件类型.txt
        if (fileName == null || fileName.trim().isEmpty()) {
            return false;
        }

        String trimmedName = fileName.trim();

        // 基本格式检查
        if (!trimmedName.endsWith(".bin")) {
            recordLog("警告: 文件名不以.bin结尾");
            return false;
        }

        String nameWithoutExt = trimmedName.replace(".txt", "");
        String[] parts = nameWithoutExt.split("_");

        if (parts.length < 3) {
            recordLog("警告: 文件名格式不正确，应为 用户id_时间戳_文件类型.txt");
            return false;
        }

        return true;
    }

    // 更新波形图显示
    private void updatePlotViews(long green, long red, long ir, short accX, short accY, short accZ) {
        if (plotViewG != null) plotViewG.addValue((int)green);
        if (plotViewR != null) plotViewR.addValue((int)red);
        if (plotViewI != null) plotViewI.addValue((int)ir);
        if (plotViewX != null) plotViewX.addValue(accX);
        if (plotViewY != null) plotViewY.addValue(accY);
        if (plotViewZ != null) plotViewZ.addValue(accZ);
    }

    // 保存文件数据
    private void saveFileData(FileInfo fileInfo, byte[] data, int currentPacket, int totalPackets) {
        try {
            Context context = itemView.getContext();
            SharedPreferences prefs = context.getSharedPreferences("AppSettings", MODE_PRIVATE);
            String experimentId = prefs.getString("experiment_id", "");

            // 创建文件保存目录
            String directoryPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                    + "/Sample/" + experimentId + "/RingLog/DownloadedFiles/";
            File directory = new File(directoryPath);
            if (!directory.exists()) {
                directory.mkdirs();
            }

            // 创建文件
            File file = new File(directory, fileInfo.fileName);

            // 如果是第一包，创建新文件；否则追加
            boolean append = currentPacket > 1;

            try (FileWriter fileWriter = new FileWriter(file, append);
                 BufferedWriter writer = new BufferedWriter(fileWriter)) {

                if (currentPacket == 1) {
                    // 写入文件头信息
                    writer.write("# 文件信息\n");
                    writer.write("# 文件名: " + fileInfo.fileName + "\n");
                    writer.write("# 文件类型: " + fileInfo.getFileTypeDescription() + "\n");
                    writer.write("# 用户ID: " + fileInfo.userId + "\n");
                    writer.write("# 时间戳: " + fileInfo.timestamp + "\n");
                    writer.write("# 包信息: " + currentPacket + "/" + totalPackets + "\n");
                    writer.write("# 数据开始\n");
                }

                // 写入数据（这里可以根据文件类型进行解析）
                writer.write("# 包 " + currentPacket + " 数据:\n");
                writer.write(bytesToHexString(data) + "\n");

                if (fileInfo.fileType == 7) {
                    // 对于类型7的数据，可以进行详细解析
                    parseType7Data(data, writer);
                }

                writer.flush();
            }

            recordLog(String.format("文件数据已保存: %s (包 %d/%d)",
                    fileInfo.fileName, currentPacket, totalPackets));

        } catch (IOException e) {
            recordLog("保存文件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 解析类型7的数据（红外+红色+绿色+温度+三轴）
    private void parseType7Data(byte[] data, BufferedWriter writer) throws IOException {
        try {
            int offset = 25; // 跳过25字节文件头

            // 解析PPG和传感器数据点 (每组30字节，共5组)
            int pointIndex = 0;
            while (offset + 30 <= data.length && pointIndex < 5) {
                writer.write("数据点 " + (pointIndex + 1) + ":\n");

                // Green (4字节, 无符号整型，小端序)
                long green = readUInt32LE(data, offset);
                writer.write("  Green: " + green + "\n");
                offset += 4;

                // Red (4字节, 无符号整型，小端序)
                long red = readUInt32LE(data, offset);
                writer.write("  Red: " + red + "\n");
                offset += 4;

                // IR (4字节, 无符号整型，小端序)
                long ir = readUInt32LE(data, offset);
                writer.write("  IR: " + ir + "\n");
                offset += 4;

                // 加速度 (6字节, 3个有符号短整型，小端序)
                short accX = readInt16LE(data, offset);
                short accY = readInt16LE(data, offset + 2);
                short accZ = readInt16LE(data, offset + 4);
                writer.write(String.format("  加速度: X=%d, Y=%d, Z=%d\n", accX, accY, accZ));
                offset += 6;

                // 陀螺仪 (6字节, 3个有符号短整型，小端序)
                short gyroX = readInt16LE(data, offset);
                short gyroY = readInt16LE(data, offset + 2);
                short gyroZ = readInt16LE(data, offset + 4);
                writer.write(String.format("  陀螺仪: X=%d, Y=%d, Z=%d\n", gyroX, gyroY, gyroZ));
                offset += 6;

                // 温度 (6字节, 3个有符号短整型，小端序)
                short temp0 = readInt16LE(data, offset);
                short temp1 = readInt16LE(data, offset + 2);
                short temp2 = readInt16LE(data, offset + 4);
                writer.write(String.format("  温度: T0=%d, T1=%d, T2=%d\n", temp0, temp1, temp2));
                offset += 6;

                pointIndex++;
            }
        } catch (Exception e) {
            writer.write("数据解析错误: " + e.getMessage() + "\n");
        }
    }

    // 字节数组转十六进制字符串
    private String bytesToHexString(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    // ==================== 原有方法保持不变 ====================

    // 切换展开与收起的设备信息显示
    public void toggleInfo() {
        if (infoVisible) {
            infoLayout.animate()
                    .translationY(100)
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction(() -> infoLayout.setVisibility(View.GONE))
                    .start();
        } else {
            infoLayout.setAlpha(0f);
            infoLayout.setTranslationY(100);
            infoLayout.setVisibility(View.VISIBLE);
            infoLayout.animate()
                    .translationY(0)
                    .alpha(1f)
                    .setDuration(200)
                    .start();
        }
        infoVisible = !infoVisible;
    }

    // 连接蓝牙设备
    public void connectToDevice(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("AppSettings", MODE_PRIVATE);
        String macAddress = prefs.getString("mac_address", "");

        if (macAddress.isEmpty()) {
            Toast.makeText(context, "No device selected", Toast.LENGTH_SHORT).show();
            return;
        }

        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(macAddress);
        if (device != null) {
            BLEUtils.connectLockByBLE(context, device);
            recordLog("Connecting to device: " + macAddress);
        } else {
            Toast.makeText(context, "Invalid MAC address", Toast.LENGTH_SHORT).show();
        }
    }

    // 记录日志
    public void recordLog(String logMessage) {
        // 显示到UI
        mainHandler.post(() -> tvLog.setText(logMessage));

        // 写入文件
        if (isRecordingRing && logWriter != null) {
            try {
                logWriter.write(logMessage + "\n");
                logWriter.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        Log.d("RingViewHolder", logMessage);
    }

    // 启动指环数据录制
    public void startRingRecording(Context context) {
        if (!isRecordingRing) {
            isRecordingRing = true;
            startBtn.setText("停止指环");
            if (plotViewG != null) plotViewG.clearPlot();
            if (plotViewI != null) plotViewI.clearPlot();
            if (plotViewR != null) plotViewR.clearPlot();
            if (plotViewX != null) plotViewX.clearPlot();
            if (plotViewY != null) plotViewY.clearPlot();
            if (plotViewZ != null) plotViewZ.clearPlot();

            SharedPreferences prefs = context.getSharedPreferences("AppSettings", MODE_PRIVATE);
            int savedTime = prefs.getInt("time_parameter", 0);
            String hexData;
            if (savedTime == 0) {
                hexData = "00373c001e191414140101";
            } else {
                hexData = "00003C00" + Integer.toHexString(savedTime) + "001010100101";
            }
            byte[] data = hexStringToByteArray(hexData);
            LmAPI.SEND_CMD(data);

            // 创建日志文件夹
            try {
                String experimentId = prefs.getString("experiment_id", "");
                String directoryPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES) + "/Sample/" + experimentId + "/RingLog/";
                File directory = new File(directoryPath);
                if (!directory.exists()) {
                    if (directory.mkdirs()) {
                        Log.d("FileSave", "Directory created successfully: " + directoryPath);
                    } else {
                        Log.e("FileSave", "Failed to create directory: " + directoryPath);
                        return;
                    }
                }
                String fileName = "RingLog_" + System.currentTimeMillis() + ".txt";
                File logFile = new File(directory, fileName);
                logWriter = new BufferedWriter(new FileWriter(logFile, true));

                recordLog("【Ring Recording Started】");

            } catch (IOException e) {
                e.printStackTrace();
                Toast.makeText(context, "Failed to start logging", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // 停止指环数据录制
    public void stopRingRecording() {
        if (isRecordingRing) {
            isRecordingRing = false;
            startBtn.setText("开始指环");
            recordLog("【Ring Recording Stopped】");
            byte[] data = hexStringToByteArray("00003C04");

            LmAPI.SEND_CMD(data);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    isRecordingRing = false;
                    if (logWriter != null) {
                        logWriter.close();
                        logWriter = null;
                    }
                    recordLog("【日志记录结束】");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }, 1000);
        }
    }
}