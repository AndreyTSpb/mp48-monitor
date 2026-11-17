package ru.gbo.aebmonitor;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.util.Arrays;

/**
 * Чтение блока параметров ЭБУ Bosch ME17.9.7 по K-line (KWP2000) через KKL на CH340.
 *
 * Только чтение: StartCommunication, ReadDataByLocalIdentifier (0x21 0x01),
 * StopCommunication. Никаких записей, SecurityAccess и исполнительных команд.
 *
 * Быстрая инициализация ISO 14230 повторяет OpenDiag (разбор USBPcap 77777/opendiggg):
 * на линии 25 мс низкого уровня (TiniL) + 25 мс высокого (TiniH), затем сразу Start.
 * Method 2 — BREAK через регистр 0x1805; Method 1 — байт F0 на ~205 бод
 * (старт-бит + 4 нуля ≈ 24,4 мс low), длительность задаёт сам чип.
 * Обмен по линии тоже как у OpenDiag: после открытия порта ~600 мс тишины, первым
 * идёт Stop (его эхо — проверка KKL), никаких тестовых байт. Дамп регистров CH340 —
 * только после неудачи, чтобы до инициализации не было лишних запросов.
 * В отладочный лог пишутся tx/rx, эхо и фактические тайминги каждой попытки.
 */
public final class KLineLink extends Thread {

    public interface Listener {
        void onState(int state, String text);
        void onBlock(EcuBlock block, byte[] raw);
    }

    public static final int STATE_OPENING = 0;
    public static final int STATE_INIT = 1;
    public static final int STATE_ONLINE = 2;
    public static final int STATE_NO_ECU = 3;
    public static final int STATE_ERROR = 4;

    private static final int BAUD = 10400;
    /** Тишина после настройки порта до первого Stop (OpenDiag: ~604 мс). */
    private static final int IDLE_AFTER_OPEN_MS = 600;
    /** Пауза от отправки Stop до начала wakeup (OpenDiag Method 2: 648 мс; ISO: W5 ≥ 300 мс). */
    private static final int IDLE_BEFORE_WAKE_MS = 648;
    /** Method 1: от Stop до смены скорости на 205 бод (OpenDiag: 597 мс, F0 — на 601). */
    private static final int IDLE_BEFORE_F0_MS = 597;
    /** TiniL: низкий уровень wakeup, 25 ± 1 мс. */
    private static final int TINI_L_MS = 25;
    /** TiniH: высокий уровень после wakeup до Start. */
    private static final int TINI_H_MS = 25;
    /** Method 1: скорость, на которой байт F0 даёт ~24,4 мс low + ~24,4 мс high. */
    private static final int F0_BAUD = 205;
    /** Method 1: ждать после записи F0, пока байт целиком уйдёт в линию (10 бит × 4,88 мс). */
    private static final int F0_HOLD_MS = 52;
    private static final int START_TIMEOUT_MS = 1000;
    private static final int BLOCK_TIMEOUT_MS = 600;
    private static final long POLL_MS = 250;

    private static final int WAKE_BREAK = 1;
    private static final int WAKE_F0 = 2;

    private final UsbManager mgr;
    private final UsbDevice device;
    private final Listener listener;
    private volatile boolean running = true;
    private final byte[] buf = new byte[512];
    private int polls;
    private int workWake = WAKE_BREAK;
    private int workHigh = TINI_H_MS;
    private int lastStopEcho = -1;

    public KLineLink(UsbManager mgr, UsbDevice device, Listener listener) {
        super("KLineLink");
        this.mgr = mgr;
        this.device = device;
        this.listener = listener;
        setDaemon(true);
    }

    public void shutdown() {
        running = false;
        interrupt();
    }

    @Override
    public void run() {
        Ch340 port = new Ch340(device);
        try {
            state(STATE_OPENING, "Открываю KKL…");
            port.open(mgr, BAUD);
            DebugLog.log("kline", "CH340 открыт как в Windows: 10400 8N1, DTR/RTS выкл.");
            pause(IDLE_AFTER_OPEN_MS);
            if (!init(port)) {
                if (lastStopEcho == 0) {
                    dumpRegs(port);
                    state(STATE_NO_ECU, "KKL не видит K-line (нет эха Stop) — проверьте OBD-разъём и +12 В");
                    return;
                }
                if (!sweep(port)) {
                    dumpRegs(port);
                    state(STATE_NO_ECU, "ЭБУ не отвечает: перебраны BREAK 25/25 и F0 на 205 бод");
                    return;
                }
            }
            state(STATE_ONLINE, "K-line: связь с ЭБУ (" + wakeName(workWake) + ")");
            while (running) {
                boolean log = polls < 3;
                Kwp2000.Frame f = transact(port, blockRequest(), log, BLOCK_TIMEOUT_MS);
                polls++;
                if (f != null && f.service() == 0x61 && EcuBlock.looksLikeBlock(f.data, 0)) {
                    EcuBlock b = EcuBlock.decode(f.data, 0);
                    listener.onBlock(b, f.data);
                } else {
                    state(STATE_NO_ECU, "Нет ответа на 21 01 — повторная инициализация");
                    if (!init(port)) {
                        pause(1000);
                    }
                }
                pause(POLL_MS);
            }
        } catch (IOException e) {
            state(STATE_ERROR, "K-line: " + e.getMessage());
        } finally {
            port.close();
        }
    }

    private static void dumpRegs(Ch340 port) {
        DebugLog.log("kline", "CH340 regs: 1312=" + regHex(port, 0x1312)
                + " 2C2C=" + regHex(port, 0x2C2C)
                + " 2518=" + regHex(port, 0x2518)
                + " 1805=" + regHex(port, 0x1805)
                + " 0706=" + regHex(port, 0x0706));
    }

    private static String regHex(Ch340 port, int reg) {
        byte[] b = port.readRegBytes(reg);
        return b == null ? "нет" : hex(b);
    }

    private boolean init(Ch340 port) throws IOException {
        state(STATE_INIT, "Stop, wakeup, StartCommunication…");
        return attempt(port, workWake, workHigh, true);
    }

    /**
     * Stop → пауза 648 мс → wakeup (25 мс low) → TiniH → StartCommunication.
     * Тайминги отсчитываются по System.nanoTime() и пишутся в лог фактически.
     */
    private boolean attempt(Ch340 port, int wakeMode, int highMs, boolean log) throws IOException {
        port.setBaud(BAUD);
        if (log) {
            DebugLog.log("kline", "попытка: wakeup " + wakeName(wakeMode) + ", high " + highMs + " мс");
        }
        long tStop = System.nanoTime();
        lastStopEcho = send(port, Kwp2000.request(Kwp2000.ECU_ADDRESS, Kwp2000.TESTER_ADDRESS,
                Kwp2000.STOP_COMMUNICATION));
        if (lastStopEcho == 0) return false;
        sleepUntil(tStop + ms(wakeMode == WAKE_F0 ? IDLE_BEFORE_F0_MS : IDLE_BEFORE_WAKE_MS));
        if (!running) return false;

        long tWake;
        long lowNs;
        if (wakeMode == WAKE_F0) {
            port.setBaud(F0_BAUD);
            tWake = System.nanoTime();
            port.write(new byte[]{(byte) 0xF0});   // OpenDiag: F0 через ~4 мс после смены скорости
            sleepUntil(tWake + ms(F0_HOLD_MS));
            port.setBaud(BAUD);
            lowNs = -1;   // задаёт сам чип: ~24,4 мс
        } else {
            // как драйвер Windows: чтение+запись 0x1805 на вкл и на выкл;
            // отсчёт от начала каждого вызова — задержки обоих одинаковы
            tWake = System.nanoTime();
            port.setBreak(true);
            sleepUntil(tWake + ms(TINI_L_MS));
            long tOff = System.nanoTime();
            port.setBreak(false);
            lowNs = tOff - tWake;
            sleepUntil(tOff + ms(highMs));
        }
        long tStart = System.nanoTime();
        byte[] start = Kwp2000.request(Kwp2000.ECU_ADDRESS, Kwp2000.TESTER_ADDRESS,
                Kwp2000.START_COMMUNICATION);
        // Start пишем сразу, без лога до записи: флеш лога — это 9–32 мс, а окно TiniH ~25 мс.
        Kwp2000.Frame f = transact(port, start, false, START_TIMEOUT_MS);
        if (log) {
            DebugLog.log("kline", "tx " + hex(start));
            DebugLog.log("kline", String.format(java.util.Locale.US,
                    "тайминг: wakeup на %.1f мс, low %s, Start на %.1f мс после Stop",
                    (tWake - tStop) / 1e6,
                    lowNs < 0 ? "≈24,4 мс (F0@205)" : String.format(java.util.Locale.US, "%.1f мс", lowNs / 1e6),
                    (tStart - tStop) / 1e6));
            DebugLog.log("kline", f == null
                    ? "StartCommunication: ответа нет"
                    : "StartCommunication: сервис 0x" + Integer.toHexString(f.service()));
        }
        return f != null && f.service() == 0xC1;
    }

    /** Перебор: Method 1 (F0 на 205 бод), затем BREAK 25 мс с другой паузой TiniH. */
    private boolean sweep(Ch340 port) throws IOException {
        int[][] variants = {
                {WAKE_F0, 0},
                {WAKE_BREAK, 20},
                {WAKE_BREAK, 30},
        };
        for (int[] v : variants) {
            if (!running) return false;
            if (attempt(port, v[0], v[1], true)) {
                workWake = v[0];
                workHigh = v[1];
                return true;
            }
        }
        return false;
    }

    private static String wakeName(int mode) {
        return mode == WAKE_F0 ? "F0@205 (Method 1)" : "BREAK 25 мс (Method 2)";
    }

    private static byte[] blockRequest() {
        return Kwp2000.request(Kwp2000.ECU_ADDRESS, Kwp2000.TESTER_ADDRESS,
                Kwp2000.READ_DATA_BY_LOCAL_IDENTIFIER, 0x01);
    }

    /** Отправляет кадр и вычитывает эхо, чтобы оно не смешало следующий ответ; возвращает длину эха. */
    private int send(Ch340 port, byte[] tx) throws IOException {
        DebugLog.log("kline", "tx " + hex(tx));
        port.write(tx);
        int len = 0;
        long end = System.currentTimeMillis() + 120;
        while (System.currentTimeMillis() < end && len < tx.length) {
            int n = port.read(buf, len, 20);
            if (n > 0) len += n;
        }
        DebugLog.log("kline", "эхо: " + (len == 0 ? "нет" : hex(Arrays.copyOf(buf, len))));
        return len;
    }

    /**
     * Отправляет кадр и ищет в принятом ответ KWP2000 с верной контрольной суммой.
     * Кадры с адресом получателя ECU (0x10) — это эхо запроса, они пропускаются.
     */
    private Kwp2000.Frame transact(Ch340 port, byte[] tx, boolean log, int timeoutMs) throws IOException {
        if (log) DebugLog.log("kline", "tx " + hex(tx));
        port.write(tx);
        int len = 0;
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end && len < buf.length) {
            int n = port.read(buf, len, 30);
            if (n > 0) {
                len += n;
                Kwp2000.Frame f = findFrame(buf, len);
                if (f != null) {
                    if (log) DebugLog.log("kline", "rx " + hex(Arrays.copyOf(buf, len)));
                    return f;
                }
            }
        }
        if (log) {
            DebugLog.log("kline", len == 0
                    ? "rx: нет данных"
                    : "rx без кадра: " + hex(Arrays.copyOf(buf, len)));
        }
        return null;
    }

    private static Kwp2000.Frame findFrame(byte[] data, int len) {
        for (int i = 0; i + 4 <= len; i++) {
            int fmt = data[i] & 0xFF;
            if ((fmt & 0x80) == 0) continue;
            // ответ идёт от ECU (0x10) тестеру (0xF1); эхо запроса — наоборот
            if ((data[i + 1] & 0xFF) != Kwp2000.TESTER_ADDRESS) continue;
            int dataLen = fmt & 0x3F;
            int pos = i + 3;
            if (dataLen == 0) {
                if (i + 4 > len) break;
                dataLen = data[i + 3] & 0xFF;
                pos = i + 4;
            }
            int end = pos + dataLen;
            if (end + 1 > len) break;
            int sum = 0;
            for (int k = i; k < end; k++) sum += data[k] & 0xFF;
            if ((sum & 0xFF) != (data[end] & 0xFF)) continue;
            byte[] payload = Arrays.copyOfRange(data, pos, end);
            return new Kwp2000.Frame(data[i + 1] & 0xFF, data[i + 2] & 0xFF,
                    payload, data[end] & 0xFF, true);
        }
        return null;
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (byte b : data) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    private void state(int s, String text) {
        if (listener != null) listener.onState(s, text);
    }

    private static long ms(long v) {
        return v * 1_000_000L;
    }

    /**
     * Точное ожидание до момента deadline (System.nanoTime): sleep до последней
     * миллисекунды, затем короткий активный цикл — Thread.sleep на магнитоле
     * может «проспать» на 1–2 мс, а окно TiniL всего ±1 мс.
     */
    private void sleepUntil(long deadline) {
        while (running) {
            long left = deadline - System.nanoTime();
            if (left <= 0) return;
            if (left > 2_000_000L) {
                try {
                    Thread.sleep((left - 1_500_000L) / 1_000_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } else {
                Thread.yield();
            }
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
