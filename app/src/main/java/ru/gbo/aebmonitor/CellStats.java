package ru.gbo.aebmonitor;

/**
 * Накопитель статистики по видимым ячейкам карты K за поездку.
 * Кормится устойчивыми кадрами на газу; ячейка выбирается по ближайшему узлу карты.
 */
public final class CellStats {
    private static final int R = KMap.VISIBLE_ROWS, C = Protocol.MAP_COLS;

    private final double[][] sec = new double[R][C];
    private final double[][] sumMult = new double[R][C];
    private final int[][] nMult = new int[R][C];
    private final double[][] sumPress = new double[R][C];
    private final int[][] nRich = new int[R][C];
    private final int[][] n = new int[R][C];
    private long lastMs;

    /** Кадр (должен быть устойчивым, на газу); map нужна для привязки к ячейке. */
    public void onFrame(Protocol.Frame f, KMap map) {
        if (f == null || map == null || f.fuel != Protocol.FUEL_GAS || f.isTransient()) { lastMs = 0; return; }
        KMap.Point p = map.locate(f.benzMs, f.rpm);
        int ri = Math.min(R - 1, p.fr < 0.5f ? p.r0 : p.r0 + 1);
        int cj = Math.min(C - 1, p.fc < 0.5f ? p.c0 : p.c0 + 1);
        double dt = lastMs > 0 ? Math.min(0.5, (f.timeMs - lastMs) / 1000.0) : 0;
        lastMs = f.timeMs;
        if (dt > 0) sec[ri][cj] += dt;
        n[ri][cj]++;
        sumPress[ri][cj] += f.pressBar;
        if (f.lambdaV > 0.45f) nRich[ri][cj]++;
        // В среднее множителя берём только физичные значения: карта ГБО лежит в районе
        // 1,0–1,8, а на сбросе время бензина падает до ~2 мс и даёт ложное деление (gas/benz → 5+).
        float m = f.multiplier();
        if (!Float.isNaN(m) && m >= 0.7f && m <= 2.2f) { sumMult[ri][cj] += m; nMult[ri][cj]++; }
    }

    public void reset() {
        for (int r = 0; r < R; r++) for (int c = 0; c < C; c++) {
            sec[r][c] = sumMult[r][c] = sumPress[r][c] = 0;
            nMult[r][c] = n[r][c] = nRich[r][c] = 0;
        }
        lastMs = 0;
    }

    public double seconds(int r, int c) { return sec[r][c]; }
    public int count(int r, int c) { return n[r][c]; }
    public float avgMult(int r, int c) { return nMult[r][c] > 0 ? (float) (sumMult[r][c] / nMult[r][c]) : Float.NaN; }
    public float avgPress(int r, int c) { return n[r][c] > 0 ? (float) (sumPress[r][c] / n[r][c]) : Float.NaN; }
    public float richFrac(int r, int c) { return n[r][c] > 0 ? nRich[r][c] / (float) n[r][c] : Float.NaN; }

    public double maxSeconds() {
        double m = 0;
        for (int r = 0; r < R; r++) for (int c = 0; c < C; c++) if (sec[r][c] > m) m = sec[r][c];
        return m;
    }

    public double totalSeconds() {
        double s = 0;
        for (int r = 0; r < R; r++) for (int c = 0; c < C; c++) s += sec[r][c];
        return s;
    }
}
