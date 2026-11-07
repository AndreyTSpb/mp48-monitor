package ru.gbo.aebmonitor;

/**
 * Оценка экономичности в реальном времени по данным блока и скорости GPS.
 *
 * Поток топлива ∝ время впрыска × обороты × цилиндры (как в Stats.units). «Стоимость» = поток/скорость
 * (расход топлива на километр) сравнивается с базовой линией за поездку: ниже базы — экономично.
 * Абсолютный расход (л/100 км, л/ч) считается при заданной калибровке k (Stats.onRefill).
 */
public final class Eco {
    private static final int CYL = 4;
    private static final float ENGINE_ON_RPM = 300;
    private static final float MIN_SPEED = 5f;        // ниже — считаем стоянкой
    private static final float FLOW_TAU = 3f;         // сглаживание потока, с
    private static final float BASE_TAU = 120f;       // базовая линия стоимости, с

    public static final int LEVEL_NONE = -1, LEVEL_BAD = 0, LEVEL_OK = 1, LEVEL_GOOD = 2;

    private float flowEma = Float.NaN;    // units/s
    private float costEma = Float.NaN;    // units/км (сглаженная)
    private float baseline = Float.NaN;   // базовая стоимость за поездку
    private long lastMs;

    // наружу
    public float l100 = Float.NaN;        // л/100 км (k>0, на ходу)
    public float lh = Float.NaN;          // л/ч (k>0)
    public float ratio = Float.NaN;       // стоимость / базовая (1 = как обычно)
    public int level = LEVEL_NONE;
    public boolean moving;
    public boolean onGas;
    public boolean speedKnown;            // GPS дал скорость (свою или по перемещению)
    public String hint = "";
    public double goodSec, gasSec;        // для эко-счёта поездки

    public void reset() {
        flowEma = costEma = baseline = Float.NaN;
        l100 = lh = ratio = Float.NaN;
        level = LEVEL_NONE;
        speedKnown = false;
        goodSec = gasSec = 0;
        lastMs = 0;
    }

    public void onFrame(Protocol.Frame f, float speedKmh, double k) {
        long now = f.timeMs;
        double dt = lastMs > 0 ? Math.min(1.0, (now - lastMs) / 1000.0) : 0;
        lastMs = now;
        boolean running = f.rpm > ENGINE_ON_RPM;
        onGas = f.fuel == Protocol.FUEL_GAS;
        speedKnown = !Float.isNaN(speedKmh);
        moving = speedKnown && speedKmh >= MIN_SPEED;

        // мгновенный поток топлива (units/s): на газу — по времени газа, иначе по бензину
        float inj = onGas ? f.gasMs : f.benzMs;
        float flow = (running && inj > 0 && !f.isTransient()) ? inj / 1000f * (f.rpm / 120f) * CYL : 0f;
        flowEma = ema(flowEma, flow, dt, FLOW_TAU);

        // абсолютный расход при калибровке
        if (k > 0 && onGas && flowEma > 0) {
            lh = (float) (k * flowEma * 3600);
            l100 = moving ? (float) (k * flowEma / (speedKmh / 3600f) * 100) : Float.NaN;
        } else {
            lh = l100 = Float.NaN;
        }

        // относительная экономичность
        if (onGas && running && moving && flowEma > 0) {
            float cost = flowEma / (speedKmh / 3600f);     // units на км
            costEma = ema(costEma, cost, dt, FLOW_TAU);
            baseline = Float.isNaN(baseline) ? cost : ema(baseline, cost, dt, BASE_TAU);
            ratio = costEma / baseline;
            level = ratio < 0.90f ? LEVEL_GOOD : (ratio <= 1.15f ? LEVEL_OK : LEVEL_BAD);
            if (dt > 0) {
                gasSec += dt;
                if (level == LEVEL_GOOD) goodSec += dt;
            }
        } else {
            ratio = Float.NaN;
            level = LEVEL_NONE;
        }

        hint = hint(f, speedKmh);
    }

    /** Доля экономичного времени за поездку, 0…1, или NaN. */
    public float score() {
        return gasSec > 10 ? (float) (goodSec / gasSec) : Float.NaN;
    }

    private String hint(Protocol.Frame f, float speedKmh) {
        if (f.engineStopped()) return "двигатель заглушён";
        if (!onGas) return f.fuel == Protocol.FUEL_PETROL ? "на бензине" : "ожидание перехода на газ";
        if (f.rpm <= ENGINE_ON_RPM) return "двигатель заглушён";
        if (!speedKnown) return "нет скорости GPS — подождите спутники";
        if (f.gasMs == 0 && (f.status & Protocol.ST_CUTOFF) != 0) return "накат — расход нулевой";
        if (f.mapBar > 0.6f) return "разгон — отпустите педаль для экономии";
        if (f.rpm > 2500 && f.mapBar < 0.5f) return "высокие обороты — передачу выше";
        if (level == LEVEL_GOOD) return "оптимально";
        if (level == LEVEL_BAD) return "расход выше обычного";
        if (!moving) return "стоянка";
        return "ровный ход";
    }

    private static float ema(float prev, float x, double dt, float tau) {
        if (Float.isNaN(prev)) return x;
        if (dt <= 0) return prev;
        float a = (float) (dt / (tau + dt));
        return prev + a * (x - prev);
    }
}
