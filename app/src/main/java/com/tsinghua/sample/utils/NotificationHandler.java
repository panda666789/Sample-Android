package com.tsinghua.sample.utils;

import com.github.AAChartModel.AAChartCore.AAChartCreator.AAChartModel;
import com.github.AAChartModel.AAChartCore.AAChartCreator.AAChartView;
import com.github.AAChartModel.AAChartCore.AAChartCreator.AASeriesElement;
import com.tsinghua.sample.PlotView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.*;

public class NotificationHandler {
    private static AAChartView aaChartViewG;       // 用于 green, ir, red
    private static AAChartView aaChartViewI;       // 用于 green, ir, red

    private static AAChartView aaChartViewR;       // 用于 green, ir, red

    private static AAChartView aaChartViewXYZ;    // 用于 x, y, z
    private static PlotView plotViewG,plotViewI;
    private static PlotView plotViewR,plotViewX;

    private static PlotView plotViewY,plotViewZ;


    // **全局数据存储**
    private static final Deque<Integer> greenData = new ArrayDeque<>();
    private static final Deque<Integer> irData = new ArrayDeque<>();
    private static final Deque<Integer> redData = new ArrayDeque<>();

    private static final Deque<Integer> xData = new ArrayDeque<>();
    private static final Deque<Integer> yData = new ArrayDeque<>();
    private static final Deque<Integer> zData = new ArrayDeque<>();

    private static final int MAX_DATA_POINTS = 1000;
    private static final int REFRESH_INTERVAL = 500;  // 图表刷新间隔：500ms
    private static long lastRefreshTime = 0;


    public static void setPlotViewG(PlotView chartView) {
        plotViewG = chartView;
    }
    public static void setPlotViewI(PlotView chartView) {
        plotViewI = chartView;
    }
    public static void setPlotViewR(PlotView chartView) {
        plotViewR = chartView;
    }

    public static void setPlotViewX(PlotView chartView) {
        plotViewX = chartView;
    }
    public static void setPlotViewY(PlotView chartView) {
        plotViewY = chartView;
    } public static void setPlotViewZ(PlotView chartView) {
        plotViewZ = chartView;
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

//                    List<Integer> newGreenData = new ArrayList<>(numDataPoints);
//                    List<Integer> newIrData = new ArrayList<>(numDataPoints);
//                    List<Integer> newRedData = new ArrayList<>(numDataPoints);
//                    List<Integer> newXData = new ArrayList<>(numDataPoints);
//                    List<Integer> newYData = new ArrayList<>(numDataPoints);
//                    List<Integer> newZData = new ArrayList<>(numDataPoints);

                    for (int i = 0; i < numDataPoints; i++) {
                        int start = 6 + i * 18;
                        if (start + 18 <= data.length) {
                            int green = buffer.getInt(start);
                            int ir = buffer.getInt(start + 4);
                            int red = buffer.getInt(start + 8);
                            short x = buffer.getShort(start + 12);
                            short y = buffer.getShort(start + 14);
                            short z = buffer.getShort(start + 16);
                            plotViewG.addValue(green);  // 为 green 数据系列添加数据
                            plotViewI.addValue(ir);     // 为 ir 数据系列添加数据
                            plotViewR.addValue(red);    // 为 red 数据系列添加数据
                            plotViewX.addValue(x);    // 为 red 数据系列添加数据
                            plotViewY.addValue(y);    // 为 red 数据系列添加数据
                            plotViewZ.addValue(z);    // 为 red 数据系列添加数据

                            result.append(String.format("green:%d;ir:%d;red:%d;x:%d;y:%d;z:%d\n",
                                    green, ir, red, x, y, z));
                        } else {
                            result.append("Incomplete waveform data\n");
                            break;
                        }
                    }

                    // 延时刷新图表
//                    updateChartData(newGreenData, newIrData, newRedData, newXData, newYData, newZData);
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
        // 将新数据添加到队列中
        appendData(greenData, newGreenData);
        appendData(irData, newIrData);
        appendData(redData, newRedData);
        appendData(xData, newXData);
        appendData(yData, newYData);
        appendData(zData, newZData);

        long currentTime = System.currentTimeMillis();

        // 判断是否到达刷新间隔
            // 添加数据点到每个图表系列
            if (aaChartViewG != null && !greenData.isEmpty()) {
                Integer greenLatest = greenData.peekLast();
                aaChartViewG.aa_addPointToChartSeriesElement(0, greenLatest,true); // 添加 green 数据点
            }
            if (aaChartViewI != null && !irData.isEmpty()) {
                Integer irLatest = irData.peekLast();
                aaChartViewI.aa_addPointToChartSeriesElement(0, irLatest,true); // 添加 ir 数据点
            }
            if (aaChartViewR != null && !redData.isEmpty()) {
                Integer redLatest = redData.peekLast();
                aaChartViewR.aa_addPointToChartSeriesElement(0, redLatest,true); // 添加 red 数据点
            }
            if (aaChartViewXYZ != null) {
                if (!xData.isEmpty()) {
                    Integer xLatest = xData.peekLast();
                    aaChartViewXYZ.aa_addPointToChartSeriesElement(0, xLatest); // 添加 x 数据点
                }
                if (!yData.isEmpty()) {
                    Integer yLatest = yData.peekLast();
                    aaChartViewXYZ.aa_addPointToChartSeriesElement(1, yLatest); // 添加 y 数据点
                }
                if (!zData.isEmpty()) {
                    Integer zLatest = zData.peekLast();
                    aaChartViewXYZ.aa_addPointToChartSeriesElement(2, zLatest); // 添加 z 数据点
                }
            }

    }

    private static void appendData(Deque<Integer> oldData, List<Integer> newData) {
        if (oldData.size() + newData.size() > MAX_DATA_POINTS) {
            oldData.clear(); // 清空旧数据
        }
        oldData.addAll(newData); // 添加新数据
    }

    private static void refreshCharts() {
        // 获取数据点的最新值
        Integer[] greenArray = greenData.toArray(new Integer[0]);
        Integer[] irArray = irData.toArray(new Integer[0]);
        Integer[] redArray = redData.toArray(new Integer[0]);
        Integer[] xArray = xData.toArray(new Integer[0]);
        Integer[] yArray = yData.toArray(new Integer[0]);
        Integer[] zArray = zData.toArray(new Integer[0]);

        if (aaChartViewG != null) {
            aaChartViewG.aa_onlyRefreshTheChartDataWithChartOptionsSeriesArray(
                    new AASeriesElement[]{
                            new AASeriesElement().name("green").data(greenArray),
                    });
        }
        if (aaChartViewI != null) {
            aaChartViewI.aa_onlyRefreshTheChartDataWithChartOptionsSeriesArray(
                    new AASeriesElement[]{
                            new AASeriesElement().name("ir").data(irArray),
                    });
        }
        if (aaChartViewR != null) {
            aaChartViewR.aa_onlyRefreshTheChartDataWithChartOptionsSeriesArray(
                    new AASeriesElement[]{
                            new AASeriesElement().name("red").data(redArray)
                    });
        }

        if (aaChartViewXYZ != null) {
            aaChartViewXYZ.aa_onlyRefreshTheChartDataWithChartOptionsSeriesArray(
                    new AASeriesElement[]{
                            new AASeriesElement().name("x").data(xArray),
                            new AASeriesElement().name("y").data(yArray),
                            new AASeriesElement().name("z").data(zArray)
                    });
        }
    }
}
