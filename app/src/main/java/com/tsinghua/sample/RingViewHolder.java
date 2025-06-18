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
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import com.lm.sdk.LmAPI;
import com.lm.sdk.LogicalApi;
import com.lm.sdk.utils.BLEUtils;
import com.tsinghua.sample.activity.ListActivity;
import com.tsinghua.sample.utils.NotificationHandler;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class RingViewHolder extends RecyclerView.ViewHolder {
    TextView deviceName;
    Button startBtn;
    ImageButton settingsBtn;
    private LinearLayout infoLayout;
    private boolean infoVisible = false;
    private TextView tvLog;
    Button connectBtn;

    // 新增按钮
    Button requestFileListBtn;
    Button downloadFilesBtn;

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

    public RingViewHolder(View itemView) {
        super(itemView);

        deviceName = itemView.findViewById(R.id.deviceName);
        startBtn = itemView.findViewById(R.id.startBtn);
        settingsBtn = itemView.findViewById(R.id.settingsBtn);
        infoLayout = itemView.findViewById(R.id.infoLayout);
        tvLog = itemView.findViewById(R.id.tvLog);
        connectBtn = itemView.findViewById(R.id.connectBtn);

        // 新增按钮初始化
        requestFileListBtn = itemView.findViewById(R.id.requestFileListBtn);
        downloadFilesBtn = itemView.findViewById(R.id.downloadFilesBtn);

        connectBtn.setOnClickListener(v -> connectToDevice(itemView.getContext()));

        // 新增按钮事件
        requestFileListBtn.setOnClickListener(v -> requestFileList(itemView.getContext()));
        downloadFilesBtn.setOnClickListener(v -> startDownloadAllFiles(itemView.getContext()));

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

        plotViewG.setPlotColor(Color.parseColor("#00FF00"));
        plotViewI.setPlotColor(Color.parseColor("#0000FF"));
        plotViewR.setPlotColor(Color.parseColor("#FF0000"));
        plotViewX.setPlotColor(Color.parseColor("#FFFF00"));
        plotViewY.setPlotColor(Color.parseColor("#FF00FF"));
        plotViewZ.setPlotColor(Color.parseColor("#00FFFF"));

        NotificationHandler.setPlotViewG(plotViewG);
        NotificationHandler.setPlotViewI(plotViewI);
        NotificationHandler.setPlotViewR(plotViewR);
        NotificationHandler.setPlotViewX(plotViewX);
        NotificationHandler.setPlotViewY(plotViewY);
        NotificationHandler.setPlotViewZ(plotViewZ);
    }

    private void setupNotificationCallback() {
        // 设置数据接收回调，用于处理文件列表和文件数据响应
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
    }

    // 请求文件列表
    public void requestFileList(Context context) {
        recordLog("【请求文件列表】");


        // 构建请求文件列表命令: 00 [ID] 36 10
        String hexCommand = String.format("00593610");
        byte[] data = hexStringToByteArray(hexCommand);

        recordLog("发送命令: " + hexCommand);
        LmAPI.SEND_CMD(data);

        // 清空之前的文件列表
        fileList.clear();
        downloadFilesBtn.setEnabled(false);
        downloadFilesBtn.setText("下载文件 (0)");
    }

    // 处理文件列表响应
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

            // 检查是否至少有文件结构的基本信息 (Total + Seq = 8字节)
            if (data.length < offset + 8) {
                recordLog("数据长度不足，无法读取文件总数和序号信息");
                recordLog("需要至少8字节，实际剩余: " + (data.length - offset));
                return;
            }

            // 读取文件总数 (4字节，小端序 - 根据示例数据分析)
            int totalFiles = (data[offset] & 0xFF) |
                    ((data[offset + 1] & 0xFF) << 8) |
                    ((data[offset + 2] & 0xFF) << 16) |
                    ((data[offset + 3] & 0xFF) << 24);
            offset += 4;

            // 读取当前序号 (4字节，小端序)
            int seqNum = (data[offset] & 0xFF) |
                    ((data[offset + 1] & 0xFF) << 8) |
                    ((data[offset + 2] & 0xFF) << 16) |
                    ((data[offset + 3] & 0xFF) << 24);
            offset += 4;

            recordLog(String.format("文件列表信息 - 总数: %d, 当前序号: %d", totalFiles, seqNum));

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

            // 检查是否有文件数据 (需要31字节: 4字节大小 + 27字节文件名)
            if (data.length < offset + 31) {
                recordLog("文件数据不完整");
                recordLog("需要31字节 (4字节大小 + 27字节文件名)，实际剩余: " + (data.length - offset));
                recordLog("可能是分包传输或数据截断");
                return;
            }

            // 读取文件大小 (4字节，小端序)
            int fileSize = (data[offset] & 0xFF) |
                    ((data[offset + 1] & 0xFF) << 8) |
                    ((data[offset + 2] & 0xFF) << 16) |
                    ((data[offset + 3] & 0xFF) << 24);
            offset += 4;

            // 读取文件名 (27字节)
            byte[] fileNameBytes = new byte[27];
            System.arraycopy(data, offset, fileNameBytes, 0, 27);

            // 处理文件名 - 查找字符串结束符或使用全部27字节
            String fileName = "";
            try {
                // 查找第一个0字节作为字符串结束
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

                fileName = new String(fileNameBytes, 0, nameLength, "UTF-8").trim();

            } catch (Exception e) {
                recordLog("文件名解析失败: " + e.getMessage());
                fileName = "PARSE_ERROR_" + bytesToHexString(fileNameBytes);
            }

            recordLog(String.format("解析文件信息:"));
            recordLog(String.format("  - 文件名: '%s'", fileName));
            recordLog(String.format("  - 文件大小: %d bytes", fileSize));
            recordLog(String.format("  - 文件名字节: %s", bytesToHexString(fileNameBytes)));

            // 解析文件名格式：用户id+时间戳+文件类型+后缀
            if (!fileName.isEmpty() && fileName.contains("_") && fileName.endsWith(".txt")) {
                try {
                    String[] parts = fileName.replace(".txt", "").split("_");
                    if (parts.length >= 3) {
                        String userId = parts[0];
                        String timestamp = parts[1];
                        String fileType = parts[2];

                        recordLog(String.format("  - 用户ID: %s", userId));
                        recordLog(String.format("  - 时间戳: %s", timestamp));
                        recordLog(String.format("  - 文件类型: %s (%s)", fileType));
                    }
                } catch (Exception e) {
                    recordLog("文件名格式解析失败: " + e.getMessage());
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
            if (!fileName.isEmpty()) {
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
                } else {
                    recordLog("文件已存在，跳过: " + fileName);
                }
            } else {
                recordLog("文件名为空，跳过添加");
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
        recordLog(String.format("下载文件 %d/%d: %s",
                currentDownloadIndex + 1, fileList.size(), fileInfo.fileName));

        requestFileData(context, fileInfo);
    }

    // 请求单个文件数据
    private void requestFileData(Context context, FileInfo fileInfo) {
        // 生成随机Frame ID
        Random random = new Random();
        int frameId = random.nextInt(256);

        // 构建请求文件数据命令: 00 [ID] 36 11 [27字节文件名]
        StringBuilder hexCommand = new StringBuilder();
        hexCommand.append(String.format("00%02X3611", frameId));

        // 将文件名转换为27字节的十六进制
        byte[] fileNameBytes = new byte[27];
        byte[] originalBytes = fileInfo.fileName.getBytes();
        System.arraycopy(originalBytes, 0, fileNameBytes, 0,
                Math.min(originalBytes.length, 27));

        for (byte b : fileNameBytes) {
            hexCommand.append(String.format("%02X", b & 0xFF));
        }

        byte[] data = hexStringToByteArray(hexCommand.toString());
        recordLog("请求文件数据: " + fileInfo.fileName);
        LmAPI.SEND_CMD(data);
    }

    // 处理文件数据响应
    private void handleFileDataResponse(byte[] data) {
        try {
            if (data.length < 4) {
                recordLog("文件数据响应长度不足");
                return;
            }

            // 解析响应: 00 [ID] 36 11 [文件数据结构]
            if (data[2] == 0x36 && data[3] == 0x11) {
                int offset = 4; // 跳过帧头

                if (data.length < offset + 17) {
                    recordLog("文件数据结构不完整");
                    return;
                }

                // 解析文件系统状态、文件大小、包信息
                int fileSystemStatus = data[offset] & 0xFF;
                offset += 1;

                int fileSize = ((data[offset] & 0xFF) << 24) |
                        ((data[offset + 1] & 0xFF) << 16) |
                        ((data[offset + 2] & 0xFF) << 8) |
                        (data[offset + 3] & 0xFF);
                offset += 4;

                int totalPackets = ((data[offset] & 0xFF) << 24) |
                        ((data[offset + 1] & 0xFF) << 16) |
                        ((data[offset + 2] & 0xFF) << 8) |
                        (data[offset + 3] & 0xFF);
                offset += 4;

                int currentPacket = ((data[offset] & 0xFF) << 24) |
                        ((data[offset + 1] & 0xFF) << 16) |
                        ((data[offset + 2] & 0xFF) << 8) |
                        (data[offset + 3] & 0xFF);
                offset += 4;

                int currentPacketLength = ((data[offset] & 0xFF) << 24) |
                        ((data[offset + 1] & 0xFF) << 16) |
                        ((data[offset + 2] & 0xFF) << 8) |
                        (data[offset + 3] & 0xFF);
                offset += 4;

                recordLog(String.format("文件状态: %d, 大小: %d, 包数: %d/%d, 当前包长: %d",
                        fileSystemStatus, fileSize, currentPacket, totalPackets, currentPacketLength));

                // 提取实际数据
                if (data.length > offset) {
                    byte[] fileData = new byte[data.length - offset];
                    System.arraycopy(data, offset, fileData, 0, fileData.length);

                    // 保存文件数据
                    saveFileData(fileList.get(currentDownloadIndex), fileData,
                            currentPacket, totalPackets);

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
            int offset = 0;

            // 读取时间戳 (8字节, uint64_t)
            if (data.length >= 8) {
                long timestamp = 0;
                for (int i = 0; i < 8; i++) {
                    timestamp |= ((long)(data[offset + i] & 0xFF)) << (i * 8);
                }
                writer.write("时间戳: " + timestamp + "\n");
                offset += 8;
            }

            // 解析PPG和传感器数据点 (每组30字节，共5组)
            int pointIndex = 0;
            while (offset + 30 <= data.length && pointIndex < 5) {
                writer.write("数据点 " + (pointIndex + 1) + ":\n");

                // Green (4字节, 无符号整型)
                int green = ((data[offset + 3] & 0xFF) << 24) |
                        ((data[offset + 2] & 0xFF) << 16) |
                        ((data[offset + 1] & 0xFF) << 8) |
                        (data[offset] & 0xFF);
                writer.write("  Green: " + green + "\n");
                offset += 4;

                // Red (4字节, 无符号整型)
                int red = ((data[offset + 3] & 0xFF) << 24) |
                        ((data[offset + 2] & 0xFF) << 16) |
                        ((data[offset + 1] & 0xFF) << 8) |
                        (data[offset] & 0xFF);
                writer.write("  Red: " + red + "\n");
                offset += 4;

                // IR (4字节, 无符号整型)
                int ir = ((data[offset + 3] & 0xFF) << 24) |
                        ((data[offset + 2] & 0xFF) << 16) |
                        ((data[offset + 1] & 0xFF) << 8) |
                        (data[offset] & 0xFF);
                writer.write("  IR: " + ir + "\n");
                offset += 4;

                // 加速度 (6字节, 3个有符号短整型)
                short accX = (short)(((data[offset + 1] & 0xFF) << 8) | (data[offset] & 0xFF));
                short accY = (short)(((data[offset + 3] & 0xFF) << 8) | (data[offset + 2] & 0xFF));
                short accZ = (short)(((data[offset + 5] & 0xFF) << 8) | (data[offset + 4] & 0xFF));
                writer.write(String.format("  加速度: X=%d, Y=%d, Z=%d\n", accX, accY, accZ));
                offset += 6;

                // 陀螺仪 (6字节, 3个有符号短整型)
                short gyroX = (short)(((data[offset + 1] & 0xFF) << 8) | (data[offset] & 0xFF));
                short gyroY = (short)(((data[offset + 3] & 0xFF) << 8) | (data[offset + 2] & 0xFF));
                short gyroZ = (short)(((data[offset + 5] & 0xFF) << 8) | (data[offset + 4] & 0xFF));
                writer.write(String.format("  陀螺仪: X=%d, Y=%d, Z=%d\n", gyroX, gyroY, gyroZ));
                offset += 6;

                // 温度 (6字节, 3个有符号短整型)
                short temp0 = (short)(((data[offset + 1] & 0xFF) << 8) | (data[offset] & 0xFF));
                short temp1 = (short)(((data[offset + 3] & 0xFF) << 8) | (data[offset + 2] & 0xFF));
                short temp2 = (short)(((data[offset + 5] & 0xFF) << 8) | (data[offset + 4] & 0xFF));
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
            plotViewG.clearPlot();
            plotViewI.clearPlot();
            plotViewR.clearPlot();
            plotViewX.clearPlot();
            plotViewY.clearPlot();
            plotViewZ.clearPlot();

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