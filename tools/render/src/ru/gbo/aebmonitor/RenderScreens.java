package ru.gbo.aebmonitor;

import android.graphics.Canvas;
import android.view.View;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * Офлайн-рендер экранов в PNG без магнетолы и Android SDK.
 *
 * Компилируется вместе с заглушками android.graphics/android.view из tools/render/src
 * и рисует настоящие классы экранов (DashView, TrendView, MapView, …) в BufferedImage.
 * Это приблизительный предпросмотр: шрифты AWT чуть отличаются от Android.
 */
public final class RenderScreens {
    private static final int W = 1024, H = 536;

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "docs/screens");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Не создать " + dir);

        World w = World.build();
        // Демо-кадр для табло: газ, ~2500 об/мин, лямбда 0,55 В.
        Protocol.Frame dashFrame = Protocol.decode(Demo.frame(25.82), System.currentTimeMillis());

        DashView dash = new DashView(null);
        dash.setLink(LinkState.ONLINE, "Связь есть");
        dash.setDemo(false);
        dash.setRaw(false);
        dash.setLogging(true);
        dash.setEco(w.eco);
        dash.setStats(w.stats);
        dash.setFrame(dashFrame, 10f);
        save(dash, new File(dir, "dash.png"));

        EcoView eco = new EcoView(null);
        eco.setEco(w.eco);
        eco.setStats(w.stats);
        eco.setFrame(w.touch());
        save(eco, new File(dir, "eco.png"));

        TrendView trend = new TrendView(null);
        trend.setTrend(w.trend);
        trend.setLowPress(0.9f);
        trend.setWindowSec(60);
        save(trend, new File(dir, "trend.png"));

        MapView map = new MapView(null);
        map.setMap(w.map, null);
        map.setTrend(w.trend);
        map.setCells(w.cells);
        map.setMode(MapView.MODE_MULT);
        map.setFrame(w.touch());
        save(map, new File(dir, "map.png"));

        MultCurveView curve = new MultCurveView(null);
        curve.setMap(w.map);
        curve.setCells(w.cells);
        curve.setFrame(w.touch());
        save(curve, new File(dir, "mcurve.png"));

        StatsView stats = new StatsView(null);
        stats.setStats(w.stats);
        stats.setGps(true, true);
        save(stats, new File(dir, "stats.png"));

        System.out.println("Готово: " + dir.getAbsolutePath());
    }

    private static void save(View v, File f) throws Exception {
        v.setSize(W, H);
        Canvas c = new Canvas(W, H);
        v.render(c);
        ImageIO.write(c.image, "png", f);
        System.out.println("  " + f.getName());
    }

    /** Полчаса ровной езды на газу: статистика, эко-индикатор, графики и ячейки карты. */
    private static final class World {
        Stats stats;
        Eco eco;
        Trend trend;
        CellStats cells;
        KMap map;
        Protocol.Frame last;

        static World build() {
            World w = new World();
            w.map = KMap.demo();
            w.stats = new Stats(new Stats.Store() {
                private final Map<String, Float> values = new HashMap<>();
                @Override public float get(String key, float def) {
                    Float v = values.get(key);
                    return v == null ? def : v;
                }
                @Override public void putAll(Map<String, Float> v) { values.putAll(v); }
            });
            w.eco = new Eco();
            w.trend = new Trend();
            w.cells = new CellStats();
            w.stats.k = 0.0035;                  // л на «секунду открытия»
            w.stats.setTank(42);

            long t0 = System.currentTimeMillis() - 1_800_000L;
            double lat = 59.94;
            for (int i = 0; i <= 18_000; i++) {
                long t = t0 + i * 100L;
                double ph = i * 0.004;
                Protocol.Frame f = new Protocol.Frame();
                f.timeMs = t;
                f.rpm = (int) Math.round(1800 + 700 * Math.sin(ph));
                f.benzMs = (float) (5.2 + 1.0 * Math.sin(ph + 0.6));
                f.gasMs = (float) (f.benzMs * (1.35 + 0.25 * Math.sin(ph * 0.7 + 1.1)));
                f.pressBar = (float) (1.18 + 0.02 * Math.sin(ph * 1.3));
                f.mapBar = (float) (0.30 + 0.20 * (f.rpm / 2500.0));
                f.tRid = 52f;
                f.tGas = 28f;
                f.lambdaV = 0.55f;
                f.status = 0xB0;
                f.fuel = Protocol.FUEL_GAS;
                f.raw = new byte[Protocol.REALTIME_LEN];

                w.stats.onFrame(f);
                if (i % 10 == 0) {                    // GPS раз в секунду
                    w.stats.onFix(lat, 30.31, t, 20f, true, 5f);
                    lat += 20.0 / 111194.9;
                    w.cells.onFrame(f, w.map);
                }
                w.eco.onFrame(f, w.stats.speedKmh, w.stats.k);
                w.trend.add(f);
                w.last = f;
            }
            // последний кадр должен быть «свежим» для экранов: симуляция заняла какое-то время
            w.last.timeMs = System.currentTimeMillis();
            return w;
        }

        /** Сделать последний кадр «только что полученным» перед рендером экрана. */
        Protocol.Frame touch() {
            last.timeMs = System.currentTimeMillis();
            return last;
        }
    }
}
