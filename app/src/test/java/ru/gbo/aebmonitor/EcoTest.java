package ru.gbo.aebmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Эко-индикатор: расход по потоку топлива, эко-шкала, подсказки, счёт поездки. */
public class EcoTest {

    private static final long T0 = 1000L;

    private static Protocol.Frame frame(int fuel, int rpm, float gasMs, int status) {
        Protocol.Frame f = new Protocol.Frame();
        f.fuel = fuel;
        f.rpm = rpm;
        f.gasMs = gasMs;
        f.benzMs = 4f;
        f.status = status;
        f.pressBar = 1.2f;
        f.mapBar = 0.4f;
        f.tRid = 50f;
        f.tGas = 30f;
        f.lambdaV = 0.5f;
        f.raw = new byte[Protocol.REALTIME_LEN];
        return f;
    }

    /** Ровный ход на газу: rpm 2000, Tгаз 5 мс, 72 км/ч. Возвращает время следующего кадра. */
    private static long cruise(Eco e, int frames, float speedKmh, long fromMs) {
        Protocol.Frame f = frame(Protocol.FUEL_GAS, 2000, 5f, 0xB0);
        for (int i = 0; i < frames; i++) {
            f.timeMs = fromMs + i * 100L;
            e.onFrame(f, speedKmh, 0.001);
        }
        return fromMs + frames * 100L;
    }

    @Test
    public void litersPer100AndPerHour() {
        Eco e = new Eco();
        cruise(e, 300, 72f, T0);                   // 30 с — сглаживание успевает сойтись
        // поток = 0,005 × (2000/120) × 4 = 0,3333 ед/с; k = 0,001 л/ед
        assertEquals(1.2, e.lh, 0.02);             // 0,3333 × 0,001 × 3600
        assertEquals(1.667, e.l100, 0.05);         // 1,2 л/ч ÷ 72 км/ч × 100
        assertTrue(e.moving);
        assertTrue(e.speedKnown);
        assertEquals(Eco.LEVEL_OK, e.level);
    }

    @Test
    public void standstillShowsPerHourOnly() {
        Eco e = new Eco();
        cruise(e, 100, 0f, T0);
        assertTrue(Float.isNaN(e.l100));
        assertEquals(1.2, e.lh, 0.05);
        assertEquals(Eco.LEVEL_NONE, e.level);
        assertEquals("стоянка", e.hint);
    }

    @Test
    public void noGpsSpeedMeansNoEco() {
        Eco e = new Eco();
        cruise(e, 100, Float.NaN, T0);
        assertEquals(false, e.speedKnown);
        assertEquals(false, e.moving);
        assertTrue(Float.isNaN(e.l100));
        assertTrue(Float.isNaN(e.ratio));
        assertEquals(Eco.LEVEL_NONE, e.level);
        assertTrue(e.hint.contains("нет скорости GPS"));
    }

    @Test
    public void cutoffFrameIsCoasting() {
        Eco e = new Eco();
        Protocol.Frame f = frame(Protocol.FUEL_GAS, 2000, 0f, 0xB4);   // отсечка
        for (int i = 0; i < 50; i++) {
            f.timeMs = T0 + i * 100L;
            e.onFrame(f, 72f, 0.001);
        }
        assertTrue(Float.isNaN(e.l100));
        assertEquals(Eco.LEVEL_NONE, e.level);
        assertEquals("накат — расход нулевой", e.hint);
    }

    @Test
    public void scoreAppearsAfterTenSecondsOnGas() {
        Eco e = new Eco();
        cruise(e, 50, 72f, T0);                    // 5 с
        assertTrue(Float.isNaN(e.score()));
        cruise(e, 100, 72f, T0 + 5000);            // всего 15 с
        float sc = e.score();
        assertTrue(!Float.isNaN(sc) && sc >= 0f && sc <= 1f);
    }

    @Test
    public void lowerFuelFlowRaisesEconomyLevel() {
        Eco e = new Eco();
        cruise(e, 300, 72f, T0);                   // базовая линия за 30 с
        Protocol.Frame f = frame(Protocol.FUEL_GAS, 1000, 5f, 0xB0);   // вдвое меньше потока
        for (int i = 0; i < 600; i++) {
            f.timeMs = T0 + 30_000 + i * 100L;
            e.onFrame(f, 72f, 0.001);
        }
        assertEquals(Eco.LEVEL_GOOD, e.level);
        assertTrue(e.ratio < 0.9f);
        assertTrue(e.score() > 0f);
    }
}
