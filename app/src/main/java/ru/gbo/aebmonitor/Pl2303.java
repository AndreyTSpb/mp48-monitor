package ru.gbo.aebmonitor;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;

/**
 * Минимальный драйвер Prolific PL2303 (HX и совместимые) через USB Host API.
 * Последовательность инициализации — как у драйвера Windows/Linux (видна в записи USBPcap).
 */
public final class Pl2303 {
    public static final int VID_PROLIFIC = 0x067B;

    private static final int VENDOR_OUT = 0x40, VENDOR_IN = 0xC0, VENDOR_REQ = 0x01;
    private static final int CLASS_OUT = 0x21, SET_LINE_CODING = 0x20, SET_CONTROL_LINE_STATE = 0x22;
    private static final int TIMEOUT = 300;

    private final UsbDevice device;
    private UsbDeviceConnection conn;
    private UsbInterface intf;
    private UsbEndpoint epIn, epOut;
    private final byte[] rxPacket = new byte[64];

    public Pl2303(UsbDevice device) {
        this.device = device;
    }

    public static boolean isSupported(UsbDevice d) {
        return d != null && d.getVendorId() == VID_PROLIFIC;
    }

    public void open(UsbManager mgr, int baud) throws IOException {
        conn = mgr.openDevice(device);
        if (conn == null) throw new IOException("Нет доступа к USB-устройству");
        intf = device.getInterface(0);
        if (!conn.claimInterface(intf, true)) throw new IOException("Не удалось захватить интерфейс USB");
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) epIn = ep;
            else epOut = ep;
        }
        if (epIn == null || epOut == null) throw new IOException("У PL2303 не найдены bulk-точки");

        // «Магическая» последовательность Prolific (HX). Ошибки отдельных шагов не фатальны.
        boolean hx = isHx();
        vIn(0x8484); vOut(0x0404, 0); vIn(0x8484); vIn(0x8383);
        vIn(0x8484); vOut(0x0404, 1); vIn(0x8484); vIn(0x8383);
        vOut(0, 1); vOut(1, 0); vOut(2, hx ? 0x44 : 0x24);

        setLineCoding(baud);
        setControlLines(false, false);   // как Pride: DTR = 0, RTS = 0
        purge();
    }

    /** HX определяется по размеру пакета нулевой точки 64 (у старого PL2303 — 8). */
    private boolean isHx() {
        byte[] raw = conn.getRawDescriptors();
        return raw == null || raw.length < 8 || (raw[7] & 0xFF) == 64;
    }

    private void vIn(int value) {
        byte[] b = new byte[1];
        conn.controlTransfer(VENDOR_IN, VENDOR_REQ, value, 0, b, 1, TIMEOUT);
    }

    private void vOut(int value, int index) {
        conn.controlTransfer(VENDOR_OUT, VENDOR_REQ, value, index, null, 0, TIMEOUT);
    }

    private void setLineCoding(int baud) throws IOException {
        byte[] lc = {
                (byte) baud, (byte) (baud >> 8), (byte) (baud >> 16), (byte) (baud >> 24),
                0,   // 1 стоп-бит
                0,   // без чётности
                8    // 8 бит
        };
        int r = conn.controlTransfer(CLASS_OUT, SET_LINE_CODING, 0, 0, lc, lc.length, TIMEOUT);
        if (r < 0) throw new IOException("Не удалось задать скорость порта");
    }

    public void setControlLines(boolean dtr, boolean rts) {
        int v = (dtr ? 1 : 0) | (rts ? 2 : 0);
        conn.controlTransfer(CLASS_OUT, SET_CONTROL_LINE_STATE, v, 0, null, 0, TIMEOUT);
    }

    /** Сброс буферов чипа (HX: vendor 8 и 9). */
    public void purge() {
        vOut(8, 0);
        vOut(9, 0);
    }

    public void write(byte[] data) throws IOException {
        int r = conn.bulkTransfer(epOut, data, data.length, TIMEOUT);
        if (r != data.length) throw new IOException("Ошибка передачи USB (" + r + ")");
    }

    /**
     * Прочитать то, что пришло, в dst начиная с off.
     * @return число байт (0 — за timeoutMs ничего не пришло)
     */
    public int read(byte[] dst, int off, int timeoutMs) {
        int n = conn.bulkTransfer(epIn, rxPacket, rxPacket.length, Math.max(1, timeoutMs));
        if (n <= 0) return 0;
        n = Math.min(n, dst.length - off);
        System.arraycopy(rxPacket, 0, dst, off, n);
        return n;
    }

    public void close() {
        try {
            if (conn != null) {
                if (intf != null) conn.releaseInterface(intf);
                conn.close();
            }
        } catch (Exception ignored) {
        }
        conn = null;
    }
}
