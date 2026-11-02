package ru.gbo.aebmonitor;

import java.util.Locale;

/**
 * Предупреждения по кадрам параметров (чистая логика, без Android).
 *  - низкое давление газа на газу (дольше 2 с);
 *  - блок сам ушёл с газа на бензин при низком давлении (кончается газ?);
 *  - газ выбран, но переход не происходит дольше N минут (редуктор не прогревается).
 */
public final class Alerts {

    public static final class Alert {
        public final int type;
        public final String text;     // полный текст
        public final String shortText; // для мини-панели
        public final long since;

        Alert(int type, String text, String shortText, long since) {
            this.type = type;
            this.text = text;
            this.shortText = shortText;
            this.since = since;
        }
    }

    private static final Locale RU = new Locale("ru");

    public static final int LOW_PRESS = 1, FALLBACK = 2, NOT_WARMING = 3;

    private static final long LOW_PRESS_DELAY = 2000;
    private static final long FALLBACK_SHOW = 20000;
    private static final long PRESS_HISTORY = 5000;
    private static final long COMMAND_GRACE = 6000;

    private float lowPress = 0.90f;
    private int waitMinutes = 6;

    private long lowSince = -1, okSince = -1;
    private boolean lowActive;
    private Alert fallback;
    private long waitSince = -1;
    private boolean warmAlerted;
    private Alert warm;
    private int prevFuel = -1;
    private long commandAt = -1;

    // минимальное давление за последние секунды на газу
    private final long[] pt = new long[64];
    private final float[] pv = new float[64];
    private int ph;

    public void configure(float lowPress, int waitMinutes) {
        this.lowPress = lowPress;
        this.waitMinutes = waitMinutes;
    }

    /** Приложение само отправило команду смены топлива — не считать это аварийным переходом. */
    public void onCommand(long now) {
        commandAt = now;
    }

    /**
     * Обработать кадр.
     * @return новое предупреждение, требующее звукового сигнала, или null
     */
    public Alert onFrame(Protocol.Frame f) {
        long now = f.timeMs;
        Alert fresh = null;
        boolean onGas = f.fuel == Protocol.FUEL_GAS;
        boolean running = f.rpm > 300;

        // история давления на газу
        if (onGas) {
            pt[ph] = now;
            pv[ph] = f.pressBar;
            ph = (ph + 1) % pt.length;
        }

        // 1. Низкое давление
        if (onGas && running && f.pressBar < lowPress) {
            okSince = -1;
            if (lowSince < 0) lowSince = now;
            if (!lowActive && now - lowSince >= LOW_PRESS_DELAY) {
                lowActive = true;
                fresh = new Alert(LOW_PRESS,
                        String.format(RU, "Низкое давление газа: %.2f бар", f.pressBar),
                        String.format(RU, "P %.2f!", f.pressBar), now);
            }
        } else {
            lowSince = -1;
            if (lowActive) {
                if (!onGas || f.pressBar >= lowPress + 0.03f) {
                    if (okSince < 0) okSince = now;
                    if (now - okSince >= LOW_PRESS_DELAY || !onGas) lowActive = false;
                }
            }
        }

        // 2. Аварийный переход на бензин
        if (prevFuel == Protocol.FUEL_GAS && f.fuel == Protocol.FUEL_PETROL
                && (commandAt < 0 || now - commandAt > COMMAND_GRACE)) {
            float minP = minPress(now);
            if (!Float.isNaN(minP) && minP < lowPress) {
                fallback = new Alert(FALLBACK,
                        String.format(RU, "Переход на бензин: давление падало до %.2f бар — кончается газ?", minP),
                        "Бензин! Газ?", now);
                fresh = fallback;
            }
        }
        if (fallback != null && (now - fallback.since > FALLBACK_SHOW || onGas)) fallback = null;

        // 3. Редуктор не прогревается
        if (f.fuel == Protocol.FUEL_GAS_WAIT && running) {
            if (waitSince < 0) waitSince = now;
            if (!warmAlerted && now - waitSince >= waitMinutes * 60000L) {
                warmAlerted = true;
                warm = new Alert(NOT_WARMING,
                        String.format(RU, "Нет перехода на газ %d мин: редуктор %.0f °C", waitMinutes, f.tRid),
                        String.format(RU, "Ред. %.0f°", f.tRid), now);
                fresh = warm;
            }
        } else {
            waitSince = -1;
            warmAlerted = false;
            warm = null;
        }

        prevFuel = f.fuel;
        return fresh;
    }

    private float minPress(long now) {
        float m = Float.NaN;
        for (int i = 0; i < pt.length; i++) {
            if (pt[i] == 0 || now - pt[i] > PRESS_HISTORY) continue;
            if (Float.isNaN(m) || pv[i] < m) m = pv[i];
        }
        return m;
    }

    /** Текущее (самое важное) активное предупреждение или null. */
    public Alert current(Protocol.Frame f) {
        if (fallback != null) return fallback;
        if (lowActive && f != null)
            return new Alert(LOW_PRESS, String.format(RU, "Низкое давление газа: %.2f бар", f.pressBar),
                    String.format(RU, "P %.2f!", f.pressBar), lowSince);
        if (warm != null) return warm;
        return null;
    }

    public void reset() {
        lowSince = okSince = waitSince = -1;
        lowActive = warmAlerted = false;
        fallback = warm = null;
        prevFuel = -1;
        for (int i = 0; i < pt.length; i++) pt[i] = 0;
    }
}
