package ru.gbo.aebmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** Статистика: расход по времени впрыска, калибровка заправкой, уровень в баллоне, GPS. */
public class StatsTest {

    private static final long T0 = 1_000_000L;      // условное время кадров, мс
    private static final double M_PER_DEG = 111194.9;   // метр на градус широты при R = 6 371 000

    /** Хранилище в памяти вместо SharedPreferences. */
    static final class Mem implements Stats.Store {
        final Map<String, Float> m = new HashMap<>();
        @Override public float get(String k, float def) { Float v = m.get(k); return v == null ? def : v; }
        @Override public void putAll(Map<String, Float> v) { m.putAll(v); }
    }

    private static Protocol.Frame frame(long t, int fuel, int rpm, float gasMs) {
        Protocol.Frame f = new Protocol.Frame();
        f.timeMs = t;
        f.fuel = fuel;
        f.rpm = rpm;
        f.gasMs = gasMs;
        f.benzMs = 4.0f;
        f.status = fuel == Protocol.FUEL_GAS ? 0xB0 : 0xA0;
        f.pressBar = 1.2f;
        f.mapBar = 0.4f;
        f.tRid = 50f;
        f.tGas = 30f;
        f.lambdaV = 0.5f;
        f.raw = new byte[Protocol.REALTIME_LEN];
        return f;
    }

    /** Кадры на газу: rpm 2400, Tгаз 4 мс → полезное 2,5 мс → 0,2 «секунды открытия» в секунду. */
    private static void drive(Stats s, long fromMs, double seconds) {
        int n = (int) Math.round(seconds * 10);
        for (int i = 0; i <= n; i++) s.onFrame(frame(fromMs + i * 100L, Protocol.FUEL_GAS, 2400, 4f));
    }

    /** Фиксы по 200 м с шагом 5 с (144 км/ч): 50 отрезков = 10 км. */
    private static void driveKm(Stats s, int segments) {
        long t = T0;
        for (int i = 0; i <= segments; i++) {
            s.onFix(i * 200.0 / M_PER_DEG, 0, t, 40f, true, 5f);
            t += 5000;
        }
    }

    @Test
    public void unitsUseGasTimeRpmAndCylinders() {
        Stats s = new Stats(new Mem());
        drive(s, T0, 1.0);
        assertEquals(1.0, s.trip.secGas, 1e-6);
        assertEquals(0.2, s.trip.units, 1e-6);
        assertEquals(1.0, s.trip.gasShare(), 1e-6);
    }

    @Test
    public void engineStoppedDoesNotAccumulate() {
        Stats s = new Stats(new Mem());
        for (int i = 0; i <= 20; i++) s.onFrame(frame(T0 + i * 100L, Protocol.FUEL_PETROL, 0, 0f));
        assertEquals(0, s.trip.secPetrol, 1e-9);
        assertEquals(0, s.trip.units, 1e-9);
    }

    @Test
    public void gasAtDeadTimeAddsNothing() {
        Stats s = new Stats(new Mem());
        for (int i = 0; i <= 10; i++) s.onFrame(frame(T0 + i * 100L, Protocol.FUEL_GAS, 2400, 1.2f));
        assertEquals(0, s.trip.units, 1e-12);        // короче мёртвого времени — топлива нет
        assertEquals(1.0, s.trip.secGas, 1e-6);      // но время на газу идёт
    }

    @Test
    public void fullRefillCalibratesK() {
        Stats s = new Stats(new Mem());
        drive(s, T0, 60);                     // 60 × 0,2 = 12 единиц
        String msg = s.onRefill(10, true);
        assertTrue(msg.startsWith("Калибровка обновлена"));
        assertEquals(10 / 12.0, s.k, 1e-6);
        assertEquals(0, s.refill.units, 1e-9);       // счётчик с заправки начат заново
        assertEquals(0, s.litersSinceRefill(), 1e-9);
    }

    @Test
    public void firstRefillOnlyStartsCounting() {
        Stats s = new Stats(new Mem());
        String msg = s.onRefill(10, true);
        assertTrue(msg.startsWith("Отсчёт с заправки начат"));
        assertEquals(0, s.k, 1e-9);
    }

    @Test
    public void kilometersCountOnlyWhileEngineRuns() {
        Stats cold = new Stats(new Mem());
        cold.onFix(0, 0, T0, 0f, false, 5f);        // до первого кадра двигатель неизвестен
        cold.onFix(200 / M_PER_DEG, 0, T0 + 5000, 0f, false, 5f);
        assertEquals(0, cold.trip.kmGas, 1e-9);

        Stats s = new Stats(new Mem());
        drive(s, T0, 0.5);                          // кадр на газу → двигатель работает
        s.onFix(0, 0, T0 + 30_000, 40f, true, 5f);
        s.onFix(200 / M_PER_DEG, 0, T0 + 35_000, 40f, true, 5f);
        assertEquals(0.2, s.trip.kmGas, 1e-3);
    }

    @Test
    public void per100UsesCalibratedUnitsAndDistance() {
        Stats s = new Stats(new Mem());
        s.k = 0.5;                                  // 0,5 л на единицу
        drive(s, T0, 10);                           // 2 единицы = 1 л
        driveKm(s, 50);                             // 10 км
        assertEquals(10.0, s.trip.kmGas, 1e-3);
        assertEquals(10.0, s.per100(s.trip), 1e-3); // 1 л на 10 км
    }

    @Test
    public void tankLevelFollowsConsumption() {
        Stats s = new Stats(new Mem());
        s.k = 0.5;
        s.setTank(40);
        assertEquals(40.0, s.gasLeft(), 1e-6);
        assertEquals(1.0, s.gasLeftFrac(), 1e-6);

        drive(s, T0, 12.5);                         // 2,5 единицы = 1,25 л
        assertEquals(38.75, s.gasLeft(), 1e-6);
        assertEquals(0.96875, s.gasLeftFrac(), 1e-6);
    }

    @Test
    public void partialRefillAddsLiters() {
        Stats s = new Stats(new Mem());
        s.k = 0.5;
        s.setTank(40);
        drive(s, T0, 25);                           // 5 единиц = 2,5 л → 37,5 л
        assertEquals(37.5, s.gasLeft(), 1e-6);
        assertTrue(s.onRefill(2, false).startsWith("Доливка"));
        assertEquals(39.5, s.gasLeft(), 1e-6);
        assertEquals(0.5, s.k, 1e-6);               // калибровка доливкой не меняется
    }

    @Test
    public void partialRefillBeforeTankKnownIsNotLost() {
        Stats s = new Stats(new Mem());
        s.k = 0.5;
        drive(s, T0, 25);                           // 2,5 л сожжено (5 единиц)
        s.onRefill(2, false);                       // доливка при неизвестном объёме
        assertEquals(0, s.tankLiters, 1e-9);
        s.setTank(40);                              // объём задан позже
        // 40 − 2,5 л сожжено + 2 л долито = 39,5
        assertEquals(39.5, s.gasLeft(), 1e-6);
    }

    @Test
    public void fullRefillRestoresTankLevel() {
        Stats s = new Stats(new Mem());
        s.k = 0.5;
        s.setTank(40);
        drive(s, T0, 25);
        assertTrue(s.gasLeft() < 40);
        s.onRefill(5, true);
        assertEquals(40.0, s.gasLeft(), 1e-6);
    }

    @Test
    public void speedDerivedWhenReceiverDoesNotReportIt() {
        Stats s = new Stats(new Mem());
        s.onFix(0, 0, T0, 0f, false, 5f);
        s.onFix(100 / M_PER_DEG, 0, T0 + 5000, 0f, false, 5f);   // 100 м за 5 с
        assertEquals(72.0, s.speedKmh, 0.5);
        assertTrue(s.gpsFresh());
    }

    @Test
    public void zeroReportedSpeedReplacedWhileMoving() {
        Stats s = new Stats(new Mem());
        s.onFix(0, 0, T0, 0f, true, 5f);
        s.onFix(100 / M_PER_DEG, 0, T0 + 5000, 0f, true, 5f);
        assertEquals(72.0, s.speedKmh, 0.5);
    }

    @Test
    public void speedBecomesUnknownWhenGpsLost() {
        Stats s = new Stats(new Mem());
        s.onFix(0, 0, T0, 20f, true, 5f);
        assertEquals(72.0, s.speedKmh, 0.1);
        // через 6 с без фикса кадр больше не должен показывать старую скорость
        s.onFrame(frame(System.currentTimeMillis() + 6000, Protocol.FUEL_GAS, 2400, 4f));
        assertTrue(Float.isNaN(s.speedKmh));
    }

    @Test
    public void accuracyFilterDropsJumps() {
        Stats s = new Stats(new Mem());
        drive(s, T0, 0.5);
        s.onFix(0, 0, T0 + 10_000, 10f, true, 5f);
        s.onFix(5000 / M_PER_DEG, 0, T0 + 15_000, 10f, true, 5f);  // 5 км за 5 с → 3600 км/ч
        assertEquals(0, s.trip.kmGas, 1e-9);
    }

    @Test
    public void standstillJitterDoesNotMoveOrShowSpeed() {
        Stats s = new Stats(new Mem());
        drive(s, T0, 0.5);                          // двигатель работает, газ
        double lat = 59.94;
        long t = T0 + 1000;
        double[] off = {0, 1.2, -0.8, 0.9, -1.1, 0.6, -0.7, 1.3, -1.2, 0.5};
        for (int i = 0; i < 25; i++) {              // 25 с дрожания вокруг точки
            s.onFix(lat + off[i % off.length] / M_PER_DEG, 30.0, t, 0.3f, true, 5f);
            t += 1000;
        }
        assertEquals(0f, s.speedKmh, 0.001f);
        assertTrue(s.trip.kmGas < 0.002);
    }

    @Test
    public void standstillIgnoresReceiverSpeedBursts() {
        Stats s = new Stats(new Mem());
        drive(s, T0, 0.5);
        long t = T0 + 1000;
        for (int i = 0; i < 20; i++) {
            float kmh = (i >= 8 && i < 13) ? 5.6f : 0.4f;   // всплески приёмника на стоянке
            s.onFix(59.94, 30.0, t, kmh / 3.6f, true, 5f);
            t += 1000;
        }
        assertEquals(0f, s.speedKmh, 0.001f);
        assertEquals(0, s.trip.kmGas, 1e-6);
    }

    @Test
    public void slowCreepIsCounted() {
        Stats s = new Stats(new Mem());
        drive(s, T0, 0.5);
        long t = T0 + 1000;
        double lat = 59.94;
        for (int i = 0; i < 20; i++) {              // 1 м/с = 3,6 км/ч
            s.onFix(lat, 30.0, t, 1.0f, true, 5f);
            lat += 1.0 / M_PER_DEG;
            t += 1000;
        }
        assertEquals(0.02, s.trip.kmGas, 0.003);
        assertEquals(3.6, s.speedKmh, 0.2);
    }

    @Test
    public void countersSurviveRestart() {
        Mem mem = new Mem();
        Stats s = new Stats(mem);
        s.k = 0.5;
        s.setTank(40);
        drive(s, T0, 12.5);
        s.save();

        Stats again = new Stats(mem);
        assertEquals(0.5, again.k, 1e-6);
        assertEquals(40.0, again.tankLiters, 1e-6);
        assertEquals(38.75, again.gasLeft(), 1e-4);
        assertEquals(0.96875, again.gasLeftFrac(), 1e-4);
    }
}
