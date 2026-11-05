package ru.gbo.aebmonitor;

import android.content.SharedPreferences;
import android.location.Location;

/**
 * Статистика: время и пробег на газу/бензине, оценка расхода газа.
 *
 * Расход оценивается по суммарному времени открытия газовых форсунок:
 *   единицы = Σ Tгаз[с] × (обороты / 120) × 4 цилиндра × dt   («секунды открытия»)
 * и калибруется по заправке: литры = k × единицы. Это оценка, не измерение.
 */
public final class Stats {
    private static final java.util.Locale RU = new java.util.Locale("ru");
    private static final int CYL = 4;
    private static final float ENGINE_ON_RPM = 300;

    /** Набор счётчиков (поездка или «с заправки»). */
    public static final class Acc {
        public double secGas, secPetrol, kmGas, kmPetrol, units;

        void reset() { secGas = secPetrol = kmGas = kmPetrol = units = 0; }

        public double gasShare() {
            double t = secGas + secPetrol;
            return t > 0 ? secGas / t : Double.NaN;
        }
    }

    public final Acc trip = new Acc();      // с запуска приложения
    public final Acc refill = new Acc();    // с последней заправки (хранится)
    public double k;                        // литров на «секунду открытия»; 0 — не откалибровано

    // Уровень газа в баллоне: литры на момент последней заправки (полной или частичной) + расход с неё.
    public double tankLiters;               // полезный объём баллона (входит от пустого до полного), 0 — не задан
    private double levelBase;               // литров в баллоне на момент последней заправки
    private double levelUnits;              // «секунды открытия» с последней заправки (любой)
    private boolean levelInit;              // уровень уже привязан к заправке
    private boolean levelFromFull;          // база уровня — полная заправка (без доливок после неё)
    private double partialLiters;           // долито частичными заправками с последней полной
    private double lastL100;                // средний расход за прошлый интервал между полными заправками
    private static final double RANGE_MIN_KM = 20;

    // GPS
    public float speedKmh = Float.NaN;
    public long lastFixAt;
    private Location lastLoc;

    private long lastFrameAt;
    private int lastFuel = -1;
    private boolean engineOn;
    private long lastSave;
    private final Prefs prefs;

    public Stats(Prefs prefs) {
        this.prefs = prefs;
        refill.secGas = prefs.getF("r_secGas");
        refill.secPetrol = prefs.getF("r_secPetrol");
        refill.kmGas = prefs.getF("r_kmGas");
        refill.kmPetrol = prefs.getF("r_kmPetrol");
        refill.units = prefs.getF("r_units");
        k = prefs.getF("k");
        tankLiters = prefs.getF("tankL");
        levelBase = prefs.getF("lvlBase");
        levelUnits = prefs.getF("lvlUnits");
        levelInit = prefs.getF("lvlInit") > 0;
        levelFromFull = prefs.getF("lvlFull") > 0;
        partialLiters = prefs.getF("r_partial");
        lastL100 = prefs.getF("lastL100");
    }

    public void onFrame(Protocol.Frame f) {
        long now = f.timeMs;
        engineOn = f.rpm > ENGINE_ON_RPM;
        lastFuel = f.fuel;
        if (lastFrameAt > 0) {
            double dt = Math.min(1.0, (now - lastFrameAt) / 1000.0);
            if (dt > 0 && engineOn) {
                boolean gas = f.fuel == Protocol.FUEL_GAS;
                if (gas) { trip.secGas += dt; refill.secGas += dt; }
                else { trip.secPetrol += dt; refill.secPetrol += dt; }
                if (gas && f.gasMs > 0) {
                    double u = (f.gasMs / 1000.0) * (f.rpm / 120.0) * CYL * dt;
                    trip.units += u;
                    refill.units += u;
                    levelUnits += u;
                }
            }
        }
        lastFrameAt = now;
        if (now - lastSave > 10000) save();
    }

    public void onLocation(Location loc) {
        if (loc == null) return;
        lastFixAt = System.currentTimeMillis();
        speedKmh = loc.hasSpeed() ? loc.getSpeed() * 3.6f : Float.NaN;
        if (loc.hasAccuracy() && loc.getAccuracy() > 50) return;
        if (lastLoc != null) {
            float m = lastLoc.distanceTo(loc);
            double dt = Math.max(0.001, (loc.getTime() - lastLoc.getTime()) / 1000.0);
            boolean plausible = m / dt < 70;               // < 250 км/ч
            boolean moving = m > 2 || (loc.hasSpeed() && loc.getSpeed() > 1);
            if (plausible && moving && engineOn && lastFuel >= 0) {
                double km = m / 1000.0;
                if (lastFuel == Protocol.FUEL_GAS) { trip.kmGas += km; refill.kmGas += km; }
                else { trip.kmPetrol += km; refill.kmPetrol += km; }
            }
        }
        lastLoc = loc;
    }

    public boolean gpsFresh() {
        return System.currentTimeMillis() - lastFixAt < 5000;
    }

    /** Литры газа с заправки (оценка) или NaN. */
    public double litersSinceRefill() {
        return k > 0 ? k * refill.units : Double.NaN;
    }

    /** Расход на газу, л/100 км, для набора счётчиков или NaN. */
    public double per100(Acc a) {
        if (k <= 0 || a.kmGas < 1) return Double.NaN;
        return k * a.units / a.kmGas * 100.0;
    }

    /**
     * Заправка до полного: liters — сколько вошло (= израсходовано с прошлой полной заправки,
     * за вычетом частичных доливок между ними).
     * @return текст результата
     */
    public String onRefill(double liters) {
        return onRefill(liters, true);
    }

    /**
     * Заправка. full — до полного (калибровка + уровень = объём баллона);
     * иначе частичная доливка: уровень += liters, калибровка не меняется.
     */
    public String onRefill(double liters, boolean full) {
        String msg;
        if (!full) {
            if (liters <= 0) return "Не указано, сколько литров залито";
            double now = gasLeft();
            double base = Double.isNaN(now) ? Double.NaN : now + liters;
            if (!Double.isNaN(base)) {
                levelBase = tankLiters > 0 ? Math.min(tankLiters, base) : base;
                levelUnits = 0;
                levelInit = true;
                levelFromFull = false;
            }
            partialLiters += liters;
            save();
            return Double.isNaN(base)
                    ? String.format(RU, "Доливка %.1f л учтена в калибровке", liters)
                    : String.format(RU, "Доливка %.1f л: в баллоне ≈ %.1f л", liters, levelBase);
        }
        double used = liters + partialLiters;
        if (refill.units > 0.5 && liters > 0) {
            k = used / refill.units;
            if (refill.kmGas >= RANGE_MIN_KM) lastL100 = used / refill.kmGas * 100.0;
            msg = String.format(RU, "Калибровка обновлена: %.1f л на %.0f км газа",
                    used, refill.kmGas);
        } else {
            msg = "Отсчёт с заправки начат. Калибровка появится после следующей заправки до полного.";
        }
        refill.reset();
        partialLiters = 0;
        levelBase = tankLiters;
        levelUnits = 0;
        levelInit = tankLiters > 0;          // объём не задан — привяжем уровень, когда его зададут
        levelFromFull = true;
        save();
        return msg;
    }

    /**
     * Задать полезный объём баллона. Если уровень ещё ни разу не привязывался к заправке,
     * считаем, что последняя отмеченная заправка была до полного.
     */
    public void setTank(double liters) {
        tankLiters = Math.max(0, liters);
        if (tankLiters > 0 && (!levelInit || levelFromFull)) {
            if (!levelInit) levelUnits = refill.units;   // с последней отмеченной полной заправки
            levelBase = tankLiters;
            levelInit = true;
            levelFromFull = true;
        }
        save();
    }

    /** Газа в баллоне, л (≥ 0), или NaN — нет калибровки или не задан объём. */
    public double gasLeft() {
        if (k <= 0 || tankLiters <= 0 || !levelInit) return Double.NaN;
        return Math.max(0, Math.min(tankLiters, levelBase - k * levelUnits));
    }

    /** Доля газа в баллоне 0…1 или NaN. */
    public double gasLeftFrac() {
        double l = gasLeft();
        return Double.isNaN(l) ? Double.NaN : Math.min(1, l / tankLiters);
    }

    /** Расход, по которому считается запас хода, л/100 км, или NaN. */
    public double rangeL100() {
        double cur = per100(refill);
        if (!Double.isNaN(cur) && refill.kmGas >= RANGE_MIN_KM) return cur;
        if (lastL100 > 0) return lastL100;
        if (!Double.isNaN(cur)) return cur;
        return Double.NaN;
    }

    /** Запас хода на газу, км, или NaN. */
    public double rangeKm() {
        double l = gasLeft(), r = rangeL100();
        if (Double.isNaN(l) || Double.isNaN(r) || r <= 0) return Double.NaN;
        return l / r * 100.0;
    }

    public void resetTrip() {
        trip.reset();
    }

    public void save() {
        lastSave = System.currentTimeMillis();
        SharedPreferences.Editor e = prefs.edit();
        prefs.putF(e, "r_secGas", refill.secGas);
        prefs.putF(e, "r_secPetrol", refill.secPetrol);
        prefs.putF(e, "r_kmGas", refill.kmGas);
        prefs.putF(e, "r_kmPetrol", refill.kmPetrol);
        prefs.putF(e, "r_units", refill.units);
        prefs.putF(e, "k", k);
        prefs.putF(e, "tankL", tankLiters);
        prefs.putF(e, "lvlBase", levelBase);
        prefs.putF(e, "lvlUnits", levelUnits);
        prefs.putF(e, "lvlInit", levelInit ? 1 : 0);
        prefs.putF(e, "lvlFull", levelFromFull ? 1 : 0);
        prefs.putF(e, "r_partial", partialLiters);
        prefs.putF(e, "lastL100", lastL100);
        e.apply();
    }
}
