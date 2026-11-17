package ru.gbo.aebmonitor;

/**
 * Разбор блока данных ЭБУ Bosch ME17.9.7, ответ на KWP2000-запрос
 * 82 10 F1 21 01 (ReadDataByLocalIdentifier, идентификатор 0x01).
 *
 * Ответ: 80 F1 10 83 61 01 <129 байт> CS.
 * Блок начинается с 61 01; смещения ниже считаются от начала блока.
 *
 * Подтверждено логом OpenDiag, скриншотами параметров 09.11.2026 и
 * совмещением с записью AEB по оборотам (см. docs/раскладка-блока-ME17.9.7.md).
 */
public final class EcuBlock {

    /** Полная длина блока вместе с 61 01. */
    public static final int LENGTH = 131;

    public static final int SERVICE = 0x21;
    public static final int IDENTIFIER = 0x01;

    public final float rpm;
    public final float desiredIdle;
    /** Сырое значение ошибки ХХ (u16 как в блоке); со знаком — {@link #idleError()}. */
    public final int idleErrorRaw;
    /** Сырой код датчика температуры ОЖ (NTC); перевод в градусы — отдельно. */
    public final int coolantRaw;
    /** УОЗ по цилиндрам 1, 3, 4, 2 в градусах (шаг 0,75°). */
    public final float[] uoz;
    public final float coilMs;
    public final float mafKgH;
    /** Сырое значение MAP (смещение 23, старший байт — 23, младший — 24). */
    public final int mapRaw;
    /** MAP в бар, приблизительно: 0,0000129 * raw + 0,3357. */
    public final float mapBar;
    /** Длительность впрыска, мс: сырое значение 27 / 154. */
    public final float injectionMs;

    private EcuBlock(float rpm, float desiredIdle, int idleErrorRaw, int coolantRaw,
                     float[] uoz, float coilMs, float mafKgH,
                     int mapRaw, float mapBar, float injectionMs) {
        this.rpm = rpm;
        this.desiredIdle = desiredIdle;
        this.idleErrorRaw = idleErrorRaw;
        this.coolantRaw = coolantRaw;
        this.uoz = uoz;
        this.coilMs = coilMs;
        this.mafKgH = mafKgH;
        this.mapRaw = mapRaw;
        this.mapBar = mapBar;
        this.injectionMs = injectionMs;
    }

    public static boolean looksLikeBlock(byte[] data, int offset) {
        return data != null && offset >= 0 && offset + LENGTH <= data.length
                && (data[offset] & 0xFF) == 0x61
                && (data[offset + 1] & 0xFF) == IDENTIFIER;
    }

    public static EcuBlock decode(byte[] data, int offset) {
        if (!looksLikeBlock(data, offset)) {
            throw new IllegalArgumentException("не блок ME17.9.7: " + describe(data, offset));
        }
        float rpm = u16(data, offset + 2) / 4f;
        float idle = u16(data, offset + 4) / 4f;
        int idleErr = u16(data, offset + 6);
        int coolant = u16(data, offset + 8);
        float[] uoz = new float[4];
        for (int i = 0; i < 4; i++) {
            uoz[i] = (data[offset + 13 + i] & 0xFF) * 0.75f;
        }
        float coil = u16(data, offset + 17) / 1000f;
        float maf = u16(data, offset + 19) / 10f;
        int mapRaw = u16(data, offset + 23);
        float mapBar = mapRaw * 0.0000129f + 0.3357f;
        float injection = u16(data, offset + 27) / 154f;
        return new EcuBlock(rpm, idle, idleErr, coolant, uoz, coil, maf, mapRaw, mapBar, injection);
    }

    /**
     * Ошибка регулирования ХХ, об/мин: знаковое s16 × 0,25 = желаемые − фактические.
     * Проверка: 0xFD7C → −161 при 1520 − 1681; 0xFFE3 → −7,25 при 860 − 867,25.
     * OpenDiag показывает то же число без знака (16223), это его особенность.
     */
    public float idleError() {
        return (short) idleErrorRaw * 0.25f;
    }

    private static int u16(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    private static String describe(byte[] data, int offset) {
        if (data == null) {
            return "null";
        }
        if (offset < 0 || offset + 2 > data.length) {
            return "offset " + offset + " вне массива";
        }
        return String.format("offset %d, %02X %02X", offset, data[offset] & 0xFF, data[offset + 1] & 0xFF);
    }
}
