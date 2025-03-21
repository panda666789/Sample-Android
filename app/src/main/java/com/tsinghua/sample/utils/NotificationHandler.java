package com.tsinghua.sample.utils;

import com.github.AAChartModel.AAChartCore.AAChartCreator.AAChartModel;
import com.github.AAChartModel.AAChartCore.AAChartCreator.AAChartView;
import com.github.AAChartModel.AAChartCore.AAChartCreator.AASeriesElement;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.*;

public class NotificationHandler {
    private static AAChartView aaChartView;       // 用于 green, ir, red
    private static AAChartView aaChartViewXYZ;    // 用于 x, y, z

    // **全局数据存储**
    private static final Deque<Integer> greenData = new ArrayDeque<>();
    private static final Deque<Integer> irData = new ArrayDeque<>();
    private static final Deque<Integer> redData = new ArrayDeque<>();

    private static final Deque<Integer> xData = new ArrayDeque<>();
    private static final Deque<Integer> yData = new ArrayDeque<>();
    private static final Deque<Integer> zData = new ArrayDeque<>();

    private static final int MAX_DATA_POINTS = 200;

    public static void setAAChartView(AAChartView chartView) {
        aaChartView = chartView;
    }

    public static void setAAChartViewXYZ(AAChartView chartView) {
        aaChartViewXYZ = chartView;
    }

    public static void clearAllData() {
        greenData.clear();
        irData.clear();
        redData.clear();
        xData.clear();
        yData.clear();
        zData.clear();
        refreshCharts();
    }

    public static String handleNotification(byte[] data) {
        if (data == null || data.length < 4) {
            return "Invalid data";
        }

        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int packetType = data[3] & 0xFF;

        StringBuilder result = new StringBuilder();
        switch (packetType) {
            case 0x01: // 时间响应包
                if (data.length >= 12) {
                    long timestamp = buffer.getLong(4);
                    result.append("Timestamp: ").append(formatTimestamp(timestamp))
                            .append("\nLocal time: ").append(formatTimestamp(System.currentTimeMillis()));
                } else {
                    result.append("Invalid time response packet");
                }
                break;

            case 0x02: // 波形响应包
                if (data.length >= 6) {
                    int numDataPoints = data[5] & 0xFF;
                    result.append("Waveform Data:\n");

                    List<Integer> newGreenData = new ArrayList<>(numDataPoints);
                    List<Integer> newIrData = new ArrayList<>(numDataPoints);
                    List<Integer> newRedData = new ArrayList<>(numDataPoints);
                    List<Integer> newXData = new ArrayList<>(numDataPoints);
                    List<Integer> newYData = new ArrayList<>(numDataPoints);
                    List<Integer> newZData = new ArrayList<>(numDataPoints);

                    for (int i = 0; i < numDataPoints; i++) {
                        int start = 6 + i * 18;
                        if (start + 18 <= data.length) {
                            int green = buffer.getInt(start);
                            int ir = buffer.getInt(start + 4);
                            int red = buffer.getInt(start + 8);
                            short x = buffer.getShort(start + 12);
                            short y = buffer.getShort(start + 14);
                            short z = buffer.getShort(start + 16);

                            newGreenData.add(green);
                            newIrData.add(ir);
                            newRedData.add(red);
                            newXData.add((int) x);
                            newYData.add((int) y);
                            newZData.add((int) z);

                            result.append(String.format("green:%d;ir:%d;red:%d;x:%d;y:%d;z:%d\n",
                                    green, ir, red, x, y, z));
                        } else {
                            result.append("Incomplete waveform data\n");
                            break;
                        }
                    }

                    updateChartData(newGreenData, newIrData, newRedData, newXData, newYData, newZData);
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

    public static void updateChartData(List<Integer> newGreenData, List<Integer> newIrData, List<Integer> newRedData,
                                       List<Integer> newXData, List<Integer> newYData, List<Integer> newZData) {
        appendData(greenData, newGreenData);
        appendData(irData, newIrData);
        appendData(redData, newRedData);
        appendData(xData, newXData);
        appendData(yData, newYData);
        appendData(zData, newZData);
        refreshCharts();
    }

    private static void appendData(Deque<Integer> oldData, List<Integer> newData) {
        if (oldData.size() + newData.size() > MAX_DATA_POINTS) {
            oldData.clear(); // 先清空
        }
        oldData.addAll(newData); // 再添加新数据
    }

    private static void refreshCharts() {
        if (aaChartView != null) {
            aaChartView.aa_onlyRefreshTheChartDataWithChartOptionsSeriesArray(
                    new AASeriesElement[]{
//                            new AASeriesElement().name("green").data(greenData.toArray(new Object[greenData.size()])),
//                            new AASeriesElement().name("ir").data(irData.toArray(new Object[irData.size()])),
                            new AASeriesElement().name("red").data(redData.toArray(new Object[redData.size()]))
                    });
        }

        if (aaChartViewXYZ != null) {
            aaChartViewXYZ.aa_onlyRefreshTheChartDataWithChartOptionsSeriesArray(
                    new AASeriesElement[]{
                            new AASeriesElement().name("x").data(xData.toArray(new Object[xData.size()])),
                            new AASeriesElement().name("y").data(yData.toArray(new Object[yData.size()])),
                            new AASeriesElement().name("z").data(zData.toArray(new Object[zData.size()]))
                    });
        }
    }
}
