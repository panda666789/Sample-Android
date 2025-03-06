package com.tsinghua.sample.utils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

public class NotificationHandler {

    public static String handleNotification(byte[] data) {
        if (data == null || data.length < 4) {
            return "Invalid data";
        }

        StringBuilder result = new StringBuilder();

        // 获取数据包类型
        int packetType = data[3] & 0xFF;  // 确保是无符号值

        switch (packetType) {
            case 0x01: // 时间响应包
                if (data.length >= 12) {
                    long timestamp = ByteBuffer.wrap(data, 4, 8)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .getLong();
                    String receivedTime = formatTimestamp(timestamp);
                    String localTime = formatTimestamp(System.currentTimeMillis());
                    result.append("Timestamp: ").append(receivedTime).append("\n")
                            .append("Local time: ").append(localTime);
                } else {
                    result.append("Invalid time response packet");
                }
                break;

            case 0x02: // 波形响应包
                if (data.length >= 6) {
                    int numDataPoints = data[5] & 0xFF;
                    result.append("Waveform Data:\n");
                    for (int i = 0; i < numDataPoints; i++) {
                        int start = 6 + i * 18;
                        if (start + 18 <= data.length) {
                            int green = getUnsignedInt(data, start);
                            int ir = getUnsignedInt(data, start + 4);
                            int red = getUnsignedInt(data, start + 8);
                            short x = getShort(data, start + 12);
                            short y = getShort(data, start + 14);
                            short z = getShort(data, start + 16);

                            result.append(String.format("green:%d;ir:%d;red:%d;x:%d;y:%d;z:%d\n",
                                    green, ir, red, x, y, z));
                        } else {
                            result.append("Incomplete waveform data\n");
                            break;
                        }
                    }
                } else {
                    result.append("Invalid waveform response packet");
                }
                break;

            case 0xFF: // 进度响应包
                if (data.length >= 5) {
                    int progress = data[4] & 0xFF;
                    result.append("Progress: ").append(progress).append("%");
                } else {
                    result.append("Invalid progress response packet");
                }
                break;

            default:
                result.append("Unknown data received: ");
                for (byte b : data) {
                    result.append(String.format("%02X ", b));
                }
        }

        return result.toString();
    }

    private static String formatTimestamp(long timestampMillis) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd.HH:mm:ss.SSS");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(timestampMillis));
    }

    private static int getUnsignedInt(byte[] data, int start) {
        return ByteBuffer.wrap(data, start, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt() & 0xFFFFFFFF;
    }

    private static short getShort(byte[] data, int start) {
        return ByteBuffer.wrap(data, start, 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getShort();
    }
}
