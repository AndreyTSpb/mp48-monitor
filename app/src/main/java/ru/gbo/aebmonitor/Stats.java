package ru.gbo.aebmonitor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Статистика: время и пробег на газу/бензине, оценка расхода газа, уровень в баллоне.
 *
 * Расход оценивается по суммарному времени открытия газовых форсунок:
 *   единицы = Σ Tгаз[с] × (обороты / 120) × 4 цилиндра × dt   («секунды открытия»)
 * и калибруется по заправке: литры = k × единицы. Это оценка, не измерение.
 *
 * Класс не зависит от Android: хранилище счётчиков задаётся интерфейсом {@link Store},
 * координаты приходят числами — поэтому статистика проверяется юнит-тестами на ПК.
 */
public final class Stats {
    private static final java.util.Locale RU = new java.util.Locale("ru");
    private static final int CYL = 4;
    private static final float ENGINE_ON_RPM = 300;

    /** Скорость GPS считается устаревшей через столько миллисекунд без фикса. */
    private static final long GPS_STALE_MS = 5000;
    /** Фиксы с точностью хуже этого не двигают одометр (но дают скорость). */
    private static final float GPS_MAX_ACCURACY_M = 50;
    /** Правдоподобный предел: быстрее 250 км/ч — это скачок GPS, а не движение. */
    private static final float MAX_SPEED_KMH = 250;

    /** Набор счётчиков (поездка или «с заправки»). */
    public static final class Acc {
        public double secGas, secPetrol, kmGas, kmPetrol, units;

        void reset() { secGas = secPetrol = kmGas = kmPetrol = units = 0; }

        public double gasShare() {
            double t = secGas + secPetrol;
            return t > 0 ? secGas / t : Double.NaN;
        }
    }

    /** Хранилище счётчиков между запусками (SharedPreferences в приложении). */
    public interface Store {
        float get(String key, float def);
        void putAll(Map<String, Float> values);
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
    public float speedKmh = Float.NaN;      // последняя известная скорость; NaN — нет данных или GPS потерян
    public long lastFixAt;
    private double lastLat, lastLon;
    private long lastFixMs;

    private long lastFrameAt;
    private int lastFuel = -1;
    private boolean engineOn;
    private long lastSave;
    private final Store store;

    public Stats(Store store) {
        this.store = store;
        refill.secGas = store.get("r_secGas", 0f);
        refill.secPetrol = store.get("r_secPetrol", 0f);
        refill.kmGas = store.get("r_kmGas", 0f);
        refill.kmPetrol = store.get("r_kmPetrol", 0f);
        refill.units = store.get("r_units", 0f);
        k = store.get("k", 0f);
        tankLiters = store.get("tankL", 0f);
        levelBase = store.get("lvlBase", 0f);
        levelUnits = store.get("lvlUnits", 0f);
        levelInit = store.get("lvlInit", 0f) > 0;
        levelFromFull = store.get("lvlFull", 0f) > 0;
        partialLiters = store.get("r_partial", 0f);
        lastL100 = store.get("lastL100", 0f);
    }

    public void onFrame(Protocol.Frame f) {
        long now = f.timeMs;
        // GPS пропал (туннель, глушилка, эмулятор без скорости) — старое значение скорости
        // не должно жить вечно: иначе эко-индикатор считает л/100 км по устаревшей скорости.
        if (lastFixAt > 0 && now - lastFixAt > GPS_STALE_MS) speedKmh = Float.NaN;
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

    /**
     * Новый фикс GPS.
     * <p>Скорость берётся из фикса; если приёмник её не отдаёт (частая беда автомобильных
     * головных устройств) или отдаёт нулевую на ходу — считается по расстоянию между фиксами.
     *
     * @param lat        широта, °
     * @param lon        долгота, °
     * @param fixTimeMs  время фикса (UTC, мс)
     * @param speedMps   скорость из фикса, м/с
     * @param hasSpeed   приёмник сообщил скорость
     * @param accuracyM  точность, м (0 — неизвестна)
     */
    public void onFix(double lat, double lon, long fixTimeMs, float speedMps, boolean hasSpeed, float accuracyM) {
        lastFixAt = System.currentTimeMillis();
        double dist = Double.NaN, dt = 0;
        if (lastFixMs > 0) {
            dist = distanceM(lastLat, lastLon, lat, lon);
            dt = (fixTimeMs - lastFixMs) / 1000.0;
        }
        // скорость по перемещению: отбрасываем слишком короткие и слишком длинные интервалы и скачки
        float derived = (!Double.isNaN(dist) && dt > 0.2 && dt < 10 && dist / dt * 3.6 < MAX_SPEED_KMH)
                ? (float) (dist / dt * 3.6) : Float.NaN;
        float spd = hasSpeed ? Math.max(0f, speedMps * 3.6f) : Float.NaN;
        if (Float.isNaN(spd) || (spd < 1f && derived > 3f)) spd = derived;   // 0 от приёмника на ходу — не верим
        speedKmh = spd;

        if (accuracyM > GPS_MAX_ACCURACY_M) return;      // координату не запоминаем, одометр не двигаем

        if (!Double.isNaN(dist) && engineOn && lastFuel >= 0) {
            boolean plausible = dist / dt < 70;                            // < 250 км/ч
            boolean moving = dist > 2 || (!Float.isNaN(spd) && spd > 1);
            if (plausible && moving) {
                double km = dist / 1000.0;
                if (lastFuel == Protocol.FUEL_GAS) { trip.kmGas += km; refill.kmGas += km; }
                else { trip.kmPetrol += km; refill.kmPetrol += km; }
            }
        }
        lastLat = lat;
        lastLon = lon;
        lastFixMs = fixTimeMs;
    }

    /** Расстояние по дуге большого круга, м (сфера; расхождение с WGS84 на поездке < 0,5 %). */
    static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        final double r = 6371000.0;
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    public boolean gpsFresh() {
        return System.currentTimeMillis() - lastFixAt < GPS_STALE_MS;
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
     * считаем, что последняя отмеченная заправка была до полного, а доливки после неё — учтёнными.
     */
    public void setTank(double liters) {
        tankLiters = Math.max(0, liters);
        if (tankLiters > 0 && (!levelInit || levelFromFull)) {
            if (!levelInit) {
                // С момента последней полной заправки сожжено k × units литров, но доливки
                // добавили partialLiters — на них расход меньше.
                double burned = refill.units;
                if (k > 0) burned -= partialLiters / k;
                levelUnits = Math.max(0, burned);
            }
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
        Map<String, Float> v = new LinkedHashMap<>();
        v.put("r_secGas", (float) refill.secGas);
        v.put("r_secPetrol", (float) refill.secPetrol);
        v.put("r_kmGas", (float) refill.kmGas);
        v.put("r_kmPetrol", (float) refill.kmPetrol);
        v.put("r_units", (float) refill.units);
        v.put("k", (float) k);
        v.put("tankL", (float) tankLiters);
        v.put("lvlBase", (float) levelBase);
        v.put("lvlUnits", (float) levelUnits);
        v.put("lvlInit", levelInit ? 1f : 0f);
        v.put("lvlFull", levelFromFull ? 1f : 0f);
        v.put("r_partial", (float) partialLiters);
        v.put("lastL100", (float) lastL100);
        store.putAll(v);
    }
}
