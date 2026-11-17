package ru.gbo.aebmonitor;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;

/**
 * Драйвер USB-serial на CH340/CH341 (VID 0x1A86) через USB Host API.
 * Настройка порта повторяет драйвер CH340 для Windows байт в байт (USBPcap OpenDiag
 * 09–10.11.2026): те же запросы A1/95/9A/A4, в том же порядке и с теми же значениями.
 * Формула скорости для прочих скоростей — по usb-serial-for-android (MIT).
 */
public final class Ch340 {

    public static final int VID_QINHENG = 0x1A86;
    public static final int PID_CH340 = 0x7523;
    public static final int PID_CH341 = 0x5523;

    private static final int TIMEOUT = 500;

    private final UsbDevice device;
    private UsbDeviceConnection conn;
    private UsbInterface intf;
    private UsbEndpoint epIn, epOut;
    private final byte[] rxPacket = new byte[64];

    public Ch340(UsbDevice device) {
        this.device = device;
    }

    public static boolean isSupported(UsbDevice d) {
        if (d == null || d.getVendorId() != VID_QINHENG) return false;
        int pid = d.getProductId();
        return pid == PID_CH340 || pid == PID_CH341;
    }

    public void open(UsbManager mgr, int baud) throws IOException {
        conn = mgr.openDevice(device);
        if (conn == null) throw new IOException("Нет доступа к CH340");
        intf = device.getInterface(0);
        if (!conn.claimInterface(intf, true)) throw new IOException("Не удалось захватить интерфейс CH340");
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) epIn = ep;
            else epOut = ep;
        }
        if (epIn == null || epOut == null) throw new IOException("У CH340 не найдены bulk-точки");

        initWindows(baud);
    }

    /**
     * Открытие порта как у драйвера Windows (запись 77777.pcapng):
     * <pre>
     * A1 C39C/D98A · 95 2C2C · 9A 0F2C=0007 · A4 00DF · A4 009F · 95 0706 · 9A 2727=0000
     * 9A 1312=B882 · 95 2C2C · A4 00DF · A4 00FF · 95 0706 · 9A 2727=0000
     * </pre>
     * Итог: 10 400 8N1 (LCR C3), DTR и RTS выключены. 0F2C пишется только если
     * регистр 0x2C отличается от нужного — так делает драйвер Windows.
     */
    private void initWindows(int baud) throws IOException {
        mustOut(0xA1, 0xC39C, 0xD98A);
        checkIn(0x95, 0x2C2C, 2);
        mustOut(0x9A, 0x0F2C, 0x0007);
        mustOut(0xA4, 0x00DF, 0);
        mustOut(0xA4, 0x009F, 0);
        checkIn(0x95, 0x0706, 2);
        mustOut(0x9A, 0x2727, 0x0000);

        int[] r = baudRegisters(baud);
        mustOut(0x9A, 0x1312, r[0]);
        byte[] c = new byte[2];
        if (controlIn(0x95, 0x2C2C, c) < 0) throw new IOException("CH340 не ответил на 95 2C2C");
        if ((c[0] & 0xFF) != r[1]) mustOut(0x9A, 0x0F2C, r[1]);
        mustOut(0xA4, 0x00DF, 0);
        mustOut(0xA4, 0x00FF, 0);
        checkIn(0x95, 0x0706, 2);
        mustOut(0x9A, 0x2727, 0x0000);
        currentBaud = baud;
    }

    private int currentBaud = -1;

    /**
     * Регистры скорости CH340: {0x1312, 0x0F2C}. Для 10 400 и 205 — значения драйвера
     * Windows из записи OpenDiag (B882/07 и C680/FC); прочие — по формуле.
     */
    static int[] baudRegisters(int baud) {
        return Ch340Baud.windows(baud);
    }

    /**
     * Смена скорости на ходу — последовательность драйвера Windows (Method 1, opendiggg.pcapng):
     * 9A 1312 · 95 2C2C · 9A 0F2C (если отличается) · 95 0706 · 9A 2727=0000.
     * Если скорость уже такая — ничего не шлёт.
     */
    public void setBaud(int baud) throws IOException {
        if (baud == currentBaud) return;
        int[] r = baudRegisters(baud);
        mustOut(0x9A, 0x1312, r[0]);
        byte[] c = new byte[2];
        if (controlIn(0x95, 0x2C2C, c) < 0) throw new IOException("CH340 не ответил на 95 2C2C");
        if ((c[0] & 0xFF) != r[1]) mustOut(0x9A, 0x0F2C, r[1]);
        checkIn(0x95, 0x0706, 2);
        mustOut(0x9A, 0x2727, 0x0000);
        currentBaud = baud;
    }

    /** DTR/RTS: как у Windows — старший байт 0 (00FF, 00DF, 009F). */
    public void setControlLines(boolean dtr, boolean rts) {
        int mask = (dtr ? 0x20 : 0) | (rts ? 0x40 : 0);
        controlOut(0xA4, (~mask) & 0xFF, 0);
    }

    private void mustOut(int request, int value, int index) throws IOException {
        if (controlOut(request, value, index) < 0) {
            throw new IOException(String.format("CH340: ошибка запроса %02X %04X/%04X", request, value, index));
        }
    }

    /** Аппаратный BREAK: удерживает K-line в низком уровне (для wakeup). */
    public void setBreak(boolean value) {
        byte[] req = new byte[2];
        if (controlIn(0x95, 0x1805, req) < 0) return;
        if (value) {
            req[0] &= ~1;
            req[1] &= ~0x40;
        } else {
            req[0] |= 1;
            req[1] |= 0x40;
        }
        int val = ((req[1] & 0xFF) << 8) | (req[0] & 0xFF);
        controlOut(0x9A, 0x1805, val);
    }

    private int controlOut(int request, int value, int index) {
        return conn.controlTransfer(UsbConstants.USB_TYPE_VENDOR | UsbConstants.USB_DIR_OUT,
                request, value, index, null, 0, TIMEOUT);
    }

    private int controlIn(int request, int value, byte[] buf) {
        return conn.controlTransfer(UsbConstants.USB_TYPE_VENDOR | UsbConstants.USB_DIR_IN,
                request, value, 0, buf, buf.length, TIMEOUT);
    }

    private void checkIn(int request, int value, int len) throws IOException {
        byte[] buf = new byte[len];
        if (controlIn(request, value, buf) < 0) {
            throw new IOException("CH340 не ответил на запрос 0x" + Integer.toHexString(request));
        }
    }

    /** Прочитать 2-байтовый регистр CH340 (0x95); null — если чтение не удалось. */
    public byte[] readRegBytes(int reg) {
        byte[] b = new byte[2];
        int r = controlIn(0x95, reg, b);
        return r < 0 ? null : b;
    }

    public void write(byte[] data) throws IOException {
        int r = conn.bulkTransfer(epOut, data, data.length, TIMEOUT);
        if (r != data.length) throw new IOException("Ошибка передачи CH340 (" + r + ")");
    }

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
