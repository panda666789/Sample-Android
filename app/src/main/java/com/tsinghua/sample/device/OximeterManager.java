package com.tsinghua.sample.device;

import android.content.Context;
import android.hardware.usb.*;
import android.util.Log;

import com.tsinghua.sample.device.model.OximeterData;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Semaphore;

public class OximeterManager {

    private static final String TAG = "OximeterManager";
    private static final int VENDOR_ID = 0x1234; // 替换为真实 VID
    private static final int PRODUCT_ID = 0x5678; // 替换为真实 PID
    private final List<OximeterData> preview = new ArrayList<>();
    private static final int USB_RECIP_INTERFACE = 0x01; // 接口接收方

    private UsbManager usbManager;
    private UsbDeviceConnection connection;
    private UsbEndpoint endpointIn, endpointOut;

    private final List<OximeterData> buf = new ArrayList<>();
    private final Semaphore lock = new Semaphore(0);

    private boolean alive = false;
    private boolean recording = false;

    private FileWriter hrWriter, spo2Writer, bvpWriter;
    private OximeterDataListener listener;

    public OximeterManager(Context context) {
        usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public boolean isConnected() {
        return alive;
    }

    public void setDataListener(OximeterDataListener l) {
        this.listener = l;

    }

    public void connectAndStart() {
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            UsbInterface usbInterface = device.getInterface(0);

            for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
                UsbEndpoint ep = usbInterface.getEndpoint(i);
                Log.e("USB", "Endpoint " + i + ": type=" + ep.getType() + ", dir=" + ep.getDirection());

                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT &&
                        ep.getDirection() == UsbConstants.USB_DIR_IN) {
                    endpointIn = ep;
                }
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT &&
                        ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                    endpointOut = ep;
                }
            }

            connection = usbManager.openDevice(device);
            if (connection == null) return;

            boolean claimed = connection.claimInterface(usbInterface, true);
            Log.e(TAG, "接口 claim 状态: " + claimed);
            if (!claimed) return;

            byte[] initCmd1 = new byte[]{(byte)0x8e, 0x03, 0x11, 0x00};
            byte[] initCmd2 = new byte[]{0x00, (byte)0x8e, 0x03, 0x11};
            byte[] init1= new byte[]{(byte) 0x81, 0x01, 0x00, 0x00};
            byte[] init2 = new byte[]{0x00, (byte) 0x81, 0x01, 0x00};

            int requestTypeSetReport = UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_CLASS | USB_RECIP_INTERFACE;
            int requestTypeGetReport = UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_CLASS | USB_RECIP_INTERFACE;

            int tag = connection.controlTransfer(
                    requestTypeSetReport,
                    0x09, // SET_REPORT
                    0x200, // Report ID=0 (Output Report)
                    usbInterface.getId(), // 接口ID
                    initCmd1,
                    initCmd1.length,
                    300
            );
            Log.e(TAG,String.valueOf(tag));
            connection.controlTransfer(
                    requestTypeSetReport,
                    0x09, // SET_REPORT
                    0x200, // Report ID=0 (Output Report)
                    usbInterface.getId(), // 接口ID
                    initCmd2,
                    initCmd2.length,
                    300
            );
            connection.controlTransfer(
                    requestTypeSetReport,
                    0x09, // SET_REPORT
                    0x200, // Report ID=0 (Output Report)
                    usbInterface.getId(), // 接口ID
                    init1,
                    init1.length,
                    300
            );
            connection.controlTransfer(
                    requestTypeSetReport,
                    0x09, // SET_REPORT
                    0x200, // Report ID=0 (Output Report)
                    usbInterface.getId(), // 接口ID
                    init2,
                    init2.length,
                    300
            );

            byte[] infoBuffer = new byte[32];
            int len = connection.controlTransfer(
                    requestTypeGetReport,
                    0x01, // GET_REPORT
                    0x101, // Report ID=0 (Input Report)
                    usbInterface.getId(), // 接口ID
                    infoBuffer,
                    infoBuffer.length,
                    300
            );

            Log.e(TAG,String.valueOf(len));
            if (len > 0) {
                String devId = new String(infoBuffer, 0, len).trim();
                Log.d(TAG, "设备ID: " + devId);
            }

            for (int i = 0; i < 2; i++) {
                connection.bulkTransfer(endpointOut, new byte[]{0x00, (byte) 0x9b, 0x01, 0x1c}, 4, 100);
                connection.bulkTransfer(endpointOut, new byte[]{0x00, (byte) 0x9b, 0x00, 0x1b}, 4, 100);
            }

            alive = true;
            new Thread(this::ping).start();
            new Thread(this::connect).start();
            break;
        }
    }


    private void ping() {
        try {
            while (alive) {
                connection.bulkTransfer(endpointOut, new byte[]{0x00, (byte) 0x9b, 0x01, 0x1c}, 4, 300);
                connection.bulkTransfer(endpointOut, new byte[]{0x00, (byte) 0x9b, 0x00, 0x1b}, 4, 300);
                Thread.sleep(20000);
            }
        } catch (Exception e) {
            Log.e(TAG, "Ping error: " + e);
            alive = false;
        }
    }

    private void connect() {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(64);
            while (alive) {
                int len = connection.bulkTransfer(endpointIn, buffer.array(), buffer.capacity(), 300);
                if (len > 0) {
                    long t = System.currentTimeMillis();
                    byte[] recv = Arrays.copyOf(buffer.array(), len);

                    Integer hr = null, spo2 = null;
                    List<Integer> bvpList = new ArrayList<>();

                    for (int i = 0; i < len - 4; i++) {
                        if ((recv[i] & 0xFF) == 0xEB && recv[i + 1] == 0x00) {
                            bvpList.add(recv[i + 3] & 0xFF);
                        }
                        if ((recv[i] & 0xFF) == 0xEB && recv[i + 1] == 0x01 && recv[i + 2] == 0x05) {
                            hr = recv[i + 3] & 0xFF;
                            spo2 = recv[i + 4] & 0xFF;
                        }
                    }

                    if (!bvpList.isEmpty()) {
                        int last = bvpList.get(bvpList.size() - 1);
                        OximeterData data = new OximeterData(t);
                        data.bvp = last;
                        buf.add(data);
                        preview.add(data);
                        lock.release();
                    }

                    if (spo2 != null) {
                        OximeterData data = new OximeterData(t);
                        data.spo2 = spo2;
                        buf.add(data);
                        preview.add(data);
                        lock.release();
                    }

                    if (hr != null) {
                        OximeterData data = new OximeterData(t);
                        data.hr = hr;
                        buf.add(data);
                        preview.add(data);
                        lock.release();
                        Log.d(TAG, "HR = " + hr + ", SpO₂ = " + spo2);
                    }

                    while (preview.size() > 10000) preview.remove(0);
                    while (buf.size() > (recording ? 10000 : 1)) {
                        buf.remove(0);
                        lock.acquire();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "接收异常", e);
        } finally {
            buf.clear();
            lock.release();
            if (connection != null) connection.close();
        }
    }

    public void startRecording(String path) {
        if (recording) return;

        File dir = new File(path);
        if (!dir.exists()) dir.mkdirs();

        try {
            hrWriter = new FileWriter(new File(dir, "hr.csv"));
            spo2Writer = new FileWriter(new File(dir, "spo2.csv"));
            bvpWriter = new FileWriter(new File(dir, "bvp.csv"));

            hrWriter.write("timestamp,hr\\n");
            spo2Writer.write("timestamp,spo2\\n");
            bvpWriter.write("timestamp,bvp\\n");

            recording = true;

            new Thread(() -> {
                while (recording) {
                    try {
                        lock.acquire();
                        if (buf.isEmpty()) continue;

                        OximeterData d = buf.remove(0);
                        if (d.hr >= 0) hrWriter.write(d.timestamp + "," + d.hr + "\\n");
                        if (d.spo2 >= 0) spo2Writer.write(d.timestamp + "," + d.spo2 + "\\n");
                        if (d.bvp >= 0) bvpWriter.write(d.timestamp + "," + d.bvp + "\\n");
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                try {
                    hrWriter.close();
                    spo2Writer.close();
                    bvpWriter.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }).start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void stopRecording() {
        recording = false;
    }

    public void disconnect() {
        alive = false;
        if (connection != null) connection.close();
    }
}