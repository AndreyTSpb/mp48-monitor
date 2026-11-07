package ru.gbo.aebmonitor;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;

/**
 * Поток обмена с блоком: чтение текущих параметров и (по нажатию кнопки)
 * переключение газ/бензин той же последовательностью, что и штатная программа.
 * Других команд записи (37 и т. п.) не отправляется.
 */
public final class AebLink extends Thread {

    public interface Listener {
        void onLinkState(AebLink src, int state, String text);
        void onFrame(AebLink src, Protocol.Frame f);
        void onFuelResult(AebLink src, boolean ok, String text);
        void onMap(AebLink src, KMap map, String error);
    }

    private static final int BAUD = 9600;
    private static final long POLL_MS = 100;       // 10 запросов в секунду
    private static final int REPLY_TIMEOUT = 350;

    private final UsbManager mgr;
    private final UsbDevice device;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean running = true;
    private volatile int fuelRequest = -1;          // -1 нет, 0 бензин, 1 газ
    private volatile boolean switching;
    private volatile boolean mapRequest = true;     // прочитать карту K при подключении
    private final byte[] rx = new byte[256];

    public AebLink(UsbManager mgr, UsbDevice device, Listener l) {
        super("AebLink");
        this.mgr = mgr;
        this.device = device;
        this.listener = l;
        setDaemon(true);
    }

    /** Запросить переключение топлива (выполняется в потоке обмена). */
    public void requestFuel(boolean gas) {
        if (!switching) fuelRequest = gas ? 1 : 0;
    }

    public boolean isSwitching() {
        return switching || fuelRequest >= 0;
    }

    /** Перечитать карту K из блока. */
    public void requestMap() {
        mapRequest = true;
    }

    public void shutdown() {
        running = false;
        interrupt();
    }

    @Override
    public void run() {
        Pl2303 port = new Pl2303(device);
        try {
            state(LinkState.OPENING, "Открываю порт…");
            port.open(mgr, BAUD);
            while (running) {
                // Проверка связи
                if (!handshake(port)) {
                    state(LinkState.NO_ECU, "Блок не отвечает — зажигание включено?");
                    sleepMs(1000);
                    continue;
                }
                xfer(port, Protocol.CMD_SESSION);   // как Pride; результат не важен
                state(LinkState.ONLINE, "Связь есть");
                int errors = 0;
                while (running && errors < 10) {
                    if (mapRequest) {
                        mapRequest = false;
                        readMap(port);
                    }
                    int req = fuelRequest;
                    if (req >= 0) {
                        fuelRequest = -1;
                        switchFuel(port, req == 1);
                    }
                    long t0 = SystemClock.uptimeMillis();
                    if (pollOnce(port)) errors = 0; else errors++;
                    long left = POLL_MS - (SystemClock.uptimeMillis() - t0);
                    if (left > 0) sleepMs(left);
                }
            }
            // Аккуратное завершение сеанса, как в Pride
            try { xfer(port, Protocol.CMD_END); } catch (IOException ignored) { }
        } catch (IOException e) {
            if (running) state(LinkState.ERROR, "Ошибка: " + e.getMessage());
        } catch (RuntimeException e) {
            if (running) state(LinkState.ERROR, "Сбой: " + e);
        } finally {
            port.close();
            if (!running) state(LinkState.CLOSED, "Отключено");
        }
    }

    /** Один запрос текущих параметров; true — кадр получен. */
    private boolean pollOnce(Pl2303 port) throws IOException {
        Protocol.Reply r = xfer(port, Protocol.CMD_REALTIME);
        if (r != null && r.kind == Protocol.Reply.OK && r.data.length >= Protocol.REALTIME_LEN) {
            final Protocol.Frame f = Protocol.decode(r.data, System.currentTimeMillis());
            ui.post(new Runnable() {
                @Override public void run() { listener.onFrame(AebLink.this, f); }
            });
            return true;
        }
        return false;
    }

    /** Продолжать опрос параметров в течение ms (между шагами переключения). */
    private void pollFor(Pl2303 port, long ms) throws IOException {
        long end = SystemClock.uptimeMillis() + ms;
        while (running && SystemClock.uptimeMillis() < end) {
            long t0 = SystemClock.uptimeMillis();
            pollOnce(port);
            long left = Math.min(POLL_MS - (SystemClock.uptimeMillis() - t0), end - SystemClock.uptimeMillis());
            if (left > 0) sleepMs(left);
        }
    }

    /** Ответ с данными нужной длины (до 3 попыток) или null. */
    private byte[] readData(Pl2303 port, byte[] tx, int len) throws IOException {
        for (int i = 0; i < 3 && running; i++) {
            Protocol.Reply r = xfer(port, tx);
            if (r != null && r.kind == Protocol.Reply.OK && r.data.length >= len) return r.data;
        }
        return null;
    }

    /** Прочитать карту K: две оси и 13 строк (только чтение, как штатная программа). */
    private void readMap(Pl2303 port) throws IOException {
        int[] tb = Protocol.axis(readData(port, Protocol.readVar(Protocol.MAP_AXIS_TB), Protocol.MAP_COLS * 2));
        int[] rp = Protocol.axis(readData(port, Protocol.readVar(Protocol.MAP_AXIS_RPM), Protocol.MAP_COLS * 2));
        byte[][] rows = new byte[Protocol.MAP_ROWS][];
        for (int r = 0; r < Protocol.MAP_ROWS && running; r++) {
            rows[r] = readData(port, Protocol.readMapRow(r), Protocol.MAP_COLS);
            if (r % 4 == 3) pollOnce(port);                // не замораживать табло во время чтения
        }
        final KMap m = KMap.fromRaw(tb, rp, rows);
        final String err = m == null ? "Не удалось прочитать карту K из блока" : null;
        ui.post(new Runnable() {
            @Override public void run() { listener.onMap(AebLink.this, m, err); }
        });
    }

    private boolean writeOk(Pl2303 port, byte[] v) throws IOException {
        for (int i = 0; i < 2; i++) {
            Protocol.Reply r = xfer(port, Protocol.writeVar(Protocol.VAR_FUEL, v));
            if (r != null && r.kind == Protocol.Reply.OK) return true;
        }
        return false;
    }

    /**
     * Переключение топлива: прочитать переменную 3, записать 4 значения как штатная программа.
     * Меняются только биты 0x10 и 0x08 байта 1; остальные байты сохраняются как прочитаны.
     */
    private void switchFuel(Pl2303 port, boolean toGas) throws IOException {
        switching = true;
        try {
            byte[] cur = null;
            for (int i = 0; i < 3 && cur == null; i++) {
                Protocol.Reply r = xfer(port, Protocol.readVar(Protocol.VAR_FUEL));
                if (r != null && r.kind == Protocol.Reply.OK && r.data.length == Protocol.FUEL_VAR_LEN) cur = r.data;
            }
            if (cur == null) {
                fuel(false, "Не удалось прочитать режим блока — переключение отменено");
                return;
            }
            // «Уже выбран» определяем по реальному режиму из кадра параметров (как видит пользователь
            // на табло), а не по переменной 3: они расходятся в переходные моменты. Ожидание газа
            // считаем газовой стороной.
            boolean onGas;
            Protocol.Reply rt = xfer(port, Protocol.CMD_REALTIME);
            if (rt != null && rt.kind == Protocol.Reply.OK && rt.data.length >= Protocol.REALTIME_LEN) {
                onGas = Protocol.decode(rt.data, System.currentTimeMillis()).fuel != Protocol.FUEL_PETROL;
            } else {
                onGas = Protocol.fuelVarIsGas(cur);
            }
            if (onGas == toGas) {
                fuel(true, toGas ? "Газ уже выбран" : "Бензин уже выбран");
                return;
            }
            byte[][] seq = Protocol.fuelSequence(cur, toGas);
            long[] pause = {0, 500, 500, 0};
            for (int i = 0; i < seq.length; i++) {
                if (!writeOk(port, seq[i])) {
                    // откат: вернуть исходное значение (снять строб)
                    writeOk(port, cur);
                    fuel(false, "Блок не принял команду — переключение отменено");
                    return;
                }
                if (pause[i] > 0) pollFor(port, pause[i]);
            }
            fuel(true, toGas ? "Команда «Газ» отправлена" : "Команда «Бензин» отправлена");
        } finally {
            switching = false;
        }
    }

    private void fuel(final boolean ok, final String text) {
        ui.post(new Runnable() {
            @Override public void run() { listener.onFuelResult(AebLink.this, ok, text); }
        });
    }

    private boolean handshake(Pl2303 port) throws IOException {
        for (int i = 0; i < 3 && running; i++) {
            Protocol.Reply r = xfer(port, Protocol.CMD_IDENT);
            if (r != null && r.kind == Protocol.Reply.OK) return true;
            sleepMs(100);
        }
        return false;
    }

    /** Отправить запрос и дождаться ответа (с учётом эха). null — тайм-аут. */
    private Protocol.Reply xfer(Pl2303 port, byte[] tx) throws IOException {
        // выбросить мусор, оставшийся в линии
        while (port.read(rx, 0, 2) > 0) { /* drain */ }
        port.write(tx);
        int len = 0;
        long deadline = SystemClock.uptimeMillis() + REPLY_TIMEOUT;
        while (running) {
            long left = deadline - SystemClock.uptimeMillis();
            if (left <= 0) return null;
            int n = port.read(rx, len, (int) Math.min(left, 50));
            if (n > 0) {
                len += n;
                Protocol.Reply r = Protocol.parse(tx, rx, len);
                if (r != null) return r;
                if (len >= rx.length) return null;
            }
        }
        return null;
    }

    private void state(final int st, final String text) {
        ui.post(new Runnable() {
            @Override public void run() { listener.onLinkState(AebLink.this, st, text); }
        });
    }

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
