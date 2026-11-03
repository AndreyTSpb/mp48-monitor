package ru.gbo.aebmonitor;

/** Кольцевой буфер параметров для графиков (до 5 минут при 10 кадрах/с). */
public final class Trend {
    public static final int MULT = 0, PRESS = 1, LAMBDA = 2, BENZ = 3, GAS = 4, RPM = 5, MAP = 6, STEADY = 7;
    public static final int CHANNELS = 8;
    private static final int CAP = 3600;

    private final long[] t = new long[CAP];
    private final float[][] v = new float[CHANNELS][CAP];
    private int head, size;

    public synchronized void add(Protocol.Frame f) {
        t[head] = f.timeMs;
        v[MULT][head] = f.multiplier();
        v[PRESS][head] = f.pressBar;
        v[LAMBDA][head] = f.lambdaV;
        v[BENZ][head] = f.benzMs;
        v[GAS][head] = f.fuel == Protocol.FUEL_GAS ? f.gasMs : Float.NaN;
        v[RPM][head] = f.rpm;
        v[MAP][head] = f.mapBar;
        v[STEADY][head] = f.isTransient() ? 0f : 1f;
        head = (head + 1) % CAP;
        if (size < CAP) size++;
    }

    public synchronized void clear() {
        head = 0;
        size = 0;
    }

    /** Индекс i-й точки по порядку (0 — самая старая). */
    private int idx(int i) {
        return (head - size + i + CAP) % CAP;
    }

    /** Скопировать точки за последние windowMs (по порядку времени); возвращает их число. */
    public synchronized int snapshot(long nowMs, long windowMs, long[] outT, float[][] outV) {
        int first = size;
        while (first > 0 && nowMs - t[idx(first - 1)] <= windowMs) first--;
        int k = 0;
        for (int i = first; i < size; i++) {
            int j = idx(i);
            outT[k] = t[j];
            for (int c = 0; c < CHANNELS; c++) outV[c][k] = v[c][j];
            k++;
        }
        return k;
    }

    public static int capacity() { return CAP; }
}
