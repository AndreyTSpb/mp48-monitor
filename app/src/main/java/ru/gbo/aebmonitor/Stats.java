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
     * Заправка: liters — сколько залито до полного (= израсходовано с прошлой полной заправки).
     * @return текст результата
     */
    public String onRefill(double liters) {
        String msg;
        if (refill.units > 0.5 && liters > 0) {
            k = liters / refill.units;
            msg = String.format(RU, "Калибровка обновлена: %.1f л на %.0f км газа",
                    liters, refill.kmGas);
        } else {
            msg = "Отсчёт с заправки начат. Калибровка появится после следующей заправки до полного.";
        }
        refill.reset();
        save();
        return msg;
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
        e.apply();
    }
}
