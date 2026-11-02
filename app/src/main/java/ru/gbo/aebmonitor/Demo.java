package ru.gbo.aebmonitor;

/** Синтетические кадры в формате блока (34 байта) — для проверки экрана без машины. */
public final class Demo {
    private Demo() {}

    /** t — секунды с начала демо. Цикл 60 с: бензин → ожидание → газ, подгазовки, провал давления. */
    public static byte[] frame(double t) {
        double cyc = t % 60;
        boolean rev = cyc > 20 && cyc < 30;
        double s = rev ? Math.sin((cyc - 20) / 10 * Math.PI) : 0;
        double rpm = rev ? 850 + 1700 * s : 850 + 15 * Math.sin(t * 3);
        double map = rev ? 0.30 + 0.25 * s : 0.36;
        double benz = rev ? 3.2 + 3 * s : 4.5 + 0.05 * Math.sin(t * 2);
        int stage = cyc < 5 ? 0 : (cyc < 8 ? 1 : 2);          // бензин → ожидание → газ
        double mult = 1.55 + 0.08 * Math.sin(t * 0.7) - 0.15 * s;
        double gas = stage == 2 ? benz * mult : 0;
        double press = 1.21 - 0.08 * s;
        if (cyc > 40 && cyc < 47) press = 0.84;                // провал давления — проверка предупреждения
        double lambda = 0.45 + 0.4 * Math.sin(t * 2.2);
        byte[] d = new byte[34];
        put16(d, 0, (int) rpm);
        d[2] = (byte) Math.round(lambda * 255 / 5);
        put16(d, 6, (int) Math.round(gas * 390.625));
        put16(d, 8, (int) Math.round(benz * 390.625));
        d[11] = (byte) (stage == 2 ? 0xB0 : stage == 1 ? 0xA8 : 0xA0);
        d[12] = 57;                                           // ~55 °C
        put16(d, 14, (int) Math.round(press * 1000));
        d[16] = (byte) 160;                                   // ~13 °C
        put16(d, 17, (int) Math.round(map * 1000));
        put16(d, 24, (int) Math.round(gas * 390.625));
        put16(d, 28, (int) Math.round(benz * 390.625));
        return d;
    }

    private static void put16(byte[] d, int i, int v) {
        d[i] = (byte) v;
        d[i + 1] = (byte) (v >> 8);
    }
}
