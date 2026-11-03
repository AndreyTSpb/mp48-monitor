package ru.gbo.aebmonitor;

/** Карта коэффициентов K блока (12 × 12 видимых ячеек + скрытая 13-я строка) с осями. */
public final class KMap {
    public final float[] tbMs = new float[Protocol.MAP_COLS];     // ось строк: время бензина, мс
    public final int[] rpm = new int[Protocol.MAP_COLS];          // ось столбцов: обороты
    public final int[][] k = new int[Protocol.MAP_ROWS][Protocol.MAP_COLS]; // K × 100
    public long readAt;

    public static final int VISIBLE_ROWS = 12;

    /** Результат поиска точки на карте. */
    public static final class Point {
        public int r0, c0;          // левая верхняя ячейка из четырёх
        public float fr, fc;        // доли между строками/столбцами 0…1
        public float k;             // интерполированный K
        public float row, col;      // дробные индексы (для рисования курсора)
    }

    public static KMap fromRaw(int[] tbTicks, int[] rpmAxis, byte[][] rows) {
        if (tbTicks == null || rpmAxis == null || rows == null) return null;
        KMap m = new KMap();
        for (int i = 0; i < Protocol.MAP_COLS; i++) {
            m.tbMs[i] = tbTicks[i] / 390.625f;
            m.rpm[i] = rpmAxis[i];
        }
        for (int r = 0; r < Protocol.MAP_ROWS; r++) {
            if (rows[r] == null || rows[r].length < Protocol.MAP_COLS) return null;
            for (int c = 0; c < Protocol.MAP_COLS; c++) m.k[r][c] = rows[r][c] & 0xFF;
        }
        if (!increasing(m.tbMs) || !increasing(m.rpm)) return null;
        m.readAt = System.currentTimeMillis();
        return m;
    }

    private static boolean increasing(float[] a) {
        for (int i = 1; i < a.length; i++) if (a[i] <= a[i - 1]) return false;
        return true;
    }

    private static boolean increasing(int[] a) {
        for (int i = 1; i < a.length; i++) if (a[i] <= a[i - 1]) return false;
        return true;
    }

    /** Билинейная интерполяция по Tб (мс) и оборотам, с ограничением краями карты. */
    public Point locate(float tb, int rpmNow) {
        Point p = new Point();
        float[] rr = seg(tbMs, tb);
        float[] cc = seg(toF(rpm), rpmNow);
        p.r0 = (int) rr[0];
        p.fr = rr[1];
        p.c0 = (int) cc[0];
        p.fc = cc[1];
        p.row = p.r0 + p.fr;
        p.col = p.c0 + p.fc;
        float k00 = k[p.r0][p.c0], k01 = k[p.r0][p.c0 + 1];
        float k10 = k[p.r0 + 1][p.c0], k11 = k[p.r0 + 1][p.c0 + 1];
        float top = k00 + (k01 - k00) * p.fc, bot = k10 + (k11 - k10) * p.fc;
        p.k = (top + (bot - top) * p.fr) / 100f;
        return p;
    }

    private static float[] toF(int[] a) {
        float[] f = new float[a.length];
        for (int i = 0; i < a.length; i++) f[i] = a[i];
        return f;
    }

    /** {индекс левой точки отрезка, доля} с ограничением по краям. */
    private static float[] seg(float[] axis, float x) {
        int n = axis.length;
        if (x <= axis[0]) return new float[]{0, 0};
        if (x >= axis[n - 1]) return new float[]{n - 2, 1};
        for (int i = 0; i < n - 1; i++) {
            if (x <= axis[i + 1]) return new float[]{i, (x - axis[i]) / (axis[i + 1] - axis[i])};
        }
        return new float[]{n - 2, 1};
    }

    public int min() {
        int m = Integer.MAX_VALUE;
        for (int r = 0; r < VISIBLE_ROWS; r++) for (int c = 0; c < Protocol.MAP_COLS; c++) m = Math.min(m, k[r][c]);
        return m;
    }

    public int max() {
        int m = Integer.MIN_VALUE;
        for (int r = 0; r < VISIBLE_ROWS; r++) for (int c = 0; c < Protocol.MAP_COLS; c++) m = Math.max(m, k[r][c]);
        return m;
    }

    /** Карта для демо-режима (типичная форма). */
    public static KMap demo() {
        int[] tb = {781, 977, 1172, 1367, 1758, 2344, 3125, 3906, 4687, 5469, 6250, 7031};
        int[] rp = {500, 1000, 1500, 2000, 2500, 3000, 3500, 4000, 4500, 5000, 5500, 6000};
        int[] base = {136, 138, 150, 165, 170, 162, 155, 150, 145, 135, 133, 130, 130};
        byte[][] rows = new byte[Protocol.MAP_ROWS][Protocol.MAP_COLS];
        for (int r = 0; r < Protocol.MAP_ROWS; r++)
            for (int c = 0; c < Protocol.MAP_COLS; c++)
                rows[r][c] = (byte) (base[r] + (c > 5 ? (c - 5) * 3 : 0));
        return fromRaw(tb, rp, rows);
    }
}
