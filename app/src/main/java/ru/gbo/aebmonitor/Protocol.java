package ru.gbo.aebmonitor;

/**
 * Протокол AEB MP48 (Pride). Чистая Java, без Android — тестируется на ПК.
 *
 * Линия 9600 8N1, однопроводная: всё отправленное приходит эхом.
 * Запрос:  [cmd][params...][cs]          cs = сумма байт mod 256
 * Ответ:   53 [n] [n байт] [cs]          cs = сумма 53, n и данных
 * Ошибка:  CA 01 [code] [cs]
 */
public final class Protocol {
    private Protocol() {}

    public static final byte[] CMD_IDENT = req(0x00, 0x02);          // 00 02 02
    public static final byte[] CMD_SESSION = req(0x01, 0x00, 0x3A);  // 01 00 3A 3B
    public static final byte[] CMD_REALTIME = req(0x48, 0x01);       // 48 01 49
    public static final byte[] CMD_END = req(0x00, 0x01);            // 00 01 01

    public static final int REALTIME_LEN = 34;

    // ---------- Переключение топлива (как в штатной программе) ----------
    // Переменная 3 (4 байта, например 84 24 09 00). В байте 1: бит 0x10 — выбран газ,
    // бит 0x08 — «строб» смены. Штатная программа пишет 4 значения подряд:
    //   текущее → текущее|08 → (цель)|08 → цель, с паузами ~0,5 с между 2-м, 3-м и 4-м.

    public static final int VAR_FUEL = 3;
    public static final int FUEL_VAR_LEN = 4;
    public static final int FUEL_BIT_GAS = 0x10;
    public static final int FUEL_BIT_STROBE = 0x08;

    /** Чтение блока/переменной: 29 lo hi cs. */
    public static byte[] readVar(int id) {
        return req(0x29, id & 0xFF, (id >> 8) & 0xFF);
    }

    /** Запись 4-байтной переменной: 35 lo hi b0 b1 b2 b3 cs. */
    public static byte[] writeVar(int id, byte[] v) {
        return req(0x35, id & 0xFF, (id >> 8) & 0xFF, v[0] & 0xFF, v[1] & 0xFF, v[2] & 0xFF, v[3] & 0xFF);
    }

    // ---------- Карта K (только чтение) ----------
    // Ось Tб: блок 0x37 (12 × u16, тики 2,56 мкс); ось оборотов: блок 0x3D (12 × u16, об/мин);
    // строки карты: 2A 54 00 i (i = 0…12, по 12 байт, K × 100). Строка 12 — скрытая (= последней).

    public static final int MAP_AXIS_TB = 0x37, MAP_AXIS_RPM = 0x3D, MAP_ROWS = 13, MAP_COLS = 12;

    public static byte[] readMapRow(int row) {
        return req(0x2A, 0x54, 0x00, row & 0xFF);
    }

    /** Разобрать ось из 12 значений u16. */
    public static int[] axis(byte[] d) {
        if (d == null || d.length < MAP_COLS * 2) return null;
        int[] a = new int[MAP_COLS];
        for (int i = 0; i < MAP_COLS; i++) a[i] = u16(d, i * 2);
        return a;
    }

    public static boolean fuelVarIsGas(byte[] v) {
        return (v[1] & FUEL_BIT_GAS) != 0;
    }

    /** Последовательность из 4 значений переменной 3 для перехода на gas/бензин. */
    public static byte[][] fuelSequence(byte[] cur, boolean toGas) {
        byte[] a = cur.clone();
        byte[] b = cur.clone();
        b[1] = (byte) (b[1] | FUEL_BIT_STROBE);
        byte[] d = cur.clone();
        d[1] = (byte) ((d[1] & ~(FUEL_BIT_GAS | FUEL_BIT_STROBE)) | (toGas ? FUEL_BIT_GAS : 0));
        byte[] c = d.clone();
        c[1] = (byte) (c[1] | FUEL_BIT_STROBE);
        return new byte[][]{a, b, c, d};
    }

    /** Собрать запрос с контрольной суммой. */
    public static byte[] req(int... b) {
        byte[] out = new byte[b.length + 1];
        int s = 0;
        for (int i = 0; i < b.length; i++) {
            out[i] = (byte) b[i];
            s += b[i];
        }
        out[b.length] = (byte) s;
        return out;
    }

    public static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < len; i++) {
            if (i > 0) sb.append(' ');
            int v = b[off + i] & 0xFF;
            sb.append(Character.forDigit(v >> 4, 16)).append(Character.forDigit(v & 15, 16));
        }
        return sb.toString().toUpperCase();
    }

    public static String hex(byte[] b) {
        return b == null ? "" : hex(b, 0, b.length);
    }

    /** Результат разбора ответа. */
    public static final class Reply {
        public static final int OK = 0, ERROR = 1, BAD = 2;
        public final int kind;
        public final byte[] data;   // для OK
        public final int errCode;   // для ERROR

        Reply(int kind, byte[] data, int errCode) {
            this.kind = kind;
            this.data = data;
            this.errCode = errCode;
        }
    }

    /**
     * Разбор накопленного приёма на запрос tx.
     * @return null — нужно ещё данных; иначе Reply.
     */
    public static Reply parse(byte[] tx, byte[] buf, int len) {
        int p = 0;
        // Эхо запроса (на однопроводной линии всегда есть; на всякий случай допускаем отсутствие)
        if (len > 0 && (buf[0] & 0xFF) == (tx[0] & 0xFF)) {
            int n = Math.min(len, tx.length);
            for (int i = 0; i < n; i++) {
                if (buf[i] != tx[i]) return new Reply(Reply.BAD, null, 0);
            }
            if (len < tx.length) return null;
            p = tx.length;
        }
        if (len - p < 2) return null;
        int head = buf[p] & 0xFF;
        int n = buf[p + 1] & 0xFF;
        if (head != 0x53 && head != 0xCA) return new Reply(Reply.BAD, null, 0);
        if (len - p < n + 3) return null;
        int s = 0;
        for (int i = 0; i < n + 2; i++) s += buf[p + i] & 0xFF;
        if ((s & 0xFF) != (buf[p + n + 2] & 0xFF)) return new Reply(Reply.BAD, null, 0);
        byte[] data = new byte[n];
        System.arraycopy(buf, p + 2, data, 0, n);
        if (head == 0xCA) return new Reply(Reply.ERROR, data, n > 0 ? data[0] & 0xFF : -1);
        return new Reply(Reply.OK, data, 0);
    }

    // ---------- Кадр текущих параметров (ответ на 48 01) ----------

    public static final int FUEL_PETROL = 0, FUEL_GAS_WAIT = 1, FUEL_GAS = 2;

    /** Биты байта состояния (11). 0x10 — на газу, 0x08 — газ выбран. */
    public static final int ST_SHORT_PETROL = 0x01;  // короткий бензиновый импульс (< ~1,5 мс) в переходе, газ держится прежним
    public static final int ST_CUTOFF = 0x04;        // отсечка топлива (сброс газа), Tб = Tг = 0
    public static final int ST_AFTER_START = 0x20;   // сброшен первые ~40–90 с после пуска (смысл не выяснен)

    public static final class Frame {
        public long timeMs;       // System.currentTimeMillis() получения
        public byte[] raw;        // 34 байта
        public int rpm;
        public float lambdaV;
        public float gasMs, benzMs, gas2Ms, benz2Ms;
        public float pressBar, mapBar;
        public float tRid, tGas;
        public int rawRid, rawGas;
        public int status;        // байт 11
        public int fuel;          // FUEL_*

        /** Множитель GAS/BENZ (NaN, если не на газу). */
        public float multiplier() {
            if (fuel != FUEL_GAS || gasMs <= 0 || benzMs <= 0 || isTransient()) return Float.NaN;
            return gasMs / benzMs;
        }

        /** Переходный кадр (отсечка или короткий бензиновый импульс): Tб не отражает нагрузку. */
        public boolean isTransient() {
            return (status & (ST_SHORT_PETROL | ST_CUTOFF)) != 0;
        }
    }

    private static int u16(byte[] d, int i) {
        return (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8);
    }

    /** Тик времени впрыска 2,56 мкс: 390,625 тика = 1 мс. */
    private static final float TICKS_PER_MS = 390.625f;

    public static Frame decode(byte[] d, long timeMs) {
        if (d == null || d.length < REALTIME_LEN) return null;
        Frame f = new Frame();
        f.timeMs = timeMs;
        f.raw = d.clone();
        f.rpm = u16(d, 0);
        f.lambdaV = (d[2] & 0xFF) * 5f / 255f;
        f.gasMs = u16(d, 6) / TICKS_PER_MS;
        f.benzMs = u16(d, 8) / TICKS_PER_MS;
        f.status = d[11] & 0xFF;
        f.rawRid = d[12] & 0xFF;
        f.pressBar = u16(d, 14) / 1000f;
        f.rawGas = d[16] & 0xFF;
        f.mapBar = u16(d, 17) / 1000f;
        f.gas2Ms = u16(d, 24) / TICKS_PER_MS;
        f.benz2Ms = u16(d, 28) / TICKS_PER_MS;
        f.tRid = ntc(f.rawRid);
        f.tGas = ntc(f.rawGas);
        if ((f.status & 0x10) != 0) f.fuel = FUEL_GAS;
        else if ((f.status & 0x08) != 0) f.fuel = FUEL_GAS_WAIT;
        else f.fuel = FUEL_PETROL;
        return f;
    }

    /** Таблица NTC блока (EcuTempMotore0..29): сырое значение для −20, −15 … 125 °C. */
    private static final int[] NTC = {231, 224, 216, 206, 195, 183, 170, 156, 142, 128, 114, 101, 89, 78,
            68, 59, 51, 44, 38, 33, 29, 25, 22, 19, 16, 14, 12, 11, 10, 8};

    public static float ntc(int raw) {
        if (raw >= NTC[0]) return -20f;
        if (raw <= NTC[NTC.length - 1]) return 125f;
        for (int i = 1; i < NTC.length; i++) {
            if (raw >= NTC[i]) {
                float a = NTC[i - 1], b = NTC[i];
                float t0 = -20 + 5 * (i - 1);
                return t0 + 5f * (a - raw) / (a - b);
            }
        }
        return 125f;
    }
}
