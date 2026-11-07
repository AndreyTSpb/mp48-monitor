package ru.gbo.aebmonitor;

import java.util.Locale;

/**
 * Формат файла записи: шапка и одна строка кадра.
 * Чистая Java без Android — формат проверяется юнит-тестами.
 *
 * Первые 11 колонок повторяют txt-лог штатной программы, дальше — данные приложения
 * (скорость GPS, эко-индикатор, уровень газа).
 */
public final class CsvFormat {
    private CsvFormat() {}

    public static final String HEADER =
            "TEMPO\tGIRI\tLAMBDA\tGAS\tBENZ\tPRESS\tMAP\tT.RID\tT.GAS\tSTATO\tRAW"
                    + "\tSPEED\tL100\tLH\tECO_RATIO\tECO_LEVEL\tGAS_L\tRANGE_KM";

    public static final int COLUMNS = 18;

    /**
     * Строка кадра без перевода строки.
     *
     * @param relMs время от начала записи, мс
     * @param eco   эко-индикатор или null (тогда колонки пустые)
     */
    public static String line(long relMs, Protocol.Frame f, float speedKmh, Eco eco, double gasL, double rangeKm) {
        StringBuilder b = new StringBuilder(360);
        b.append(String.format(Locale.US, "%d\t%d\t%.2f\t%.2f\t%.2f\t%.3f\t%.3f\t%.1f\t%.1f\t%02X\t%s",
                relMs, f.rpm, f.lambdaV, f.gasMs, f.benzMs, f.pressBar, f.mapBar,
                f.tRid, f.tGas, f.status, Protocol.hex(f.raw)));
        b.append('\t').append(num(speedKmh, "%.1f"));
        b.append('\t').append(eco == null ? "" : num(eco.l100, "%.1f"));
        b.append('\t').append(eco == null ? "" : num(eco.lh, "%.2f"));
        b.append('\t').append(eco == null ? "" : num(eco.ratio, "%.3f"));
        b.append('\t').append(eco == null ? "" : String.valueOf(eco.level));
        b.append('\t').append(num(gasL, "%.2f"));
        b.append('\t').append(num(rangeKm, "%.0f"));
        return b.toString();
    }

    /** Число с форматом; NaN — пустая колонка (данных нет). */
    private static String num(double v, String fmt) {
        return Double.isNaN(v) ? "" : String.format(Locale.US, fmt, v);
    }
}
