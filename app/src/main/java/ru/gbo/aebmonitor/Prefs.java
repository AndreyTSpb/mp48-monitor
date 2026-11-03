package ru.gbo.aebmonitor;

import android.content.Context;
import android.content.SharedPreferences;

/** Настройки приложения (SharedPreferences). */
public final class Prefs {
    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    // --- предупреждения ---
    public float lowPress() { return sp.getFloat("lowPress", 0.90f); }
    public int waitMinutes() { return sp.getInt("waitMinutes", 6); }
    public boolean sound() { return sp.getBoolean("sound", true); }

    // --- прочее ---
    public boolean overlay() { return sp.getBoolean("overlay", false); }
    public int overlayX() { return sp.getInt("overlayX", 40); }
    public int overlayY() { return sp.getInt("overlayY", 120); }
    public boolean autoLog() { return sp.getBoolean("autoLog", false); }
    public int trendWindowSec() { return sp.getInt("trendWindow", 60); }
    public boolean locationAsked() { return sp.getBoolean("locationAsked", false); }

    // --- ночной режим: 0 авто (по времени), 1 день, 2 ночь ---
    public static final int NIGHT_AUTO = 0, NIGHT_DAY = 1, NIGHT_NIGHT = 2;
    public int nightMode() { return sp.getInt("nightMode", NIGHT_AUTO); }
    public void setNightMode(int m) { sp.edit().putInt("nightMode", m).apply(); }
    /** Яркость подсветки ночью, 0.02…1.0; 0 — не трогать системную. */
    public float nightBrightness() { return sp.getFloat("nightBright", 0.12f); }
    public void setNightBrightness(float b) { sp.edit().putFloat("nightBright", b).apply(); }
    /** Сила затемнения интерфейса ночью, 0…0.7. */
    public float nightDim() { return sp.getFloat("nightDim", 0.45f); }
    public void setNightDim(float d) { sp.edit().putFloat("nightDim", d).apply(); }

    // --- экран «Карта K»: режим раскраски 0 K, 1 множитель, 2 время ---
    public int mapCellMode() { return sp.getInt("mapCellMode", 0); }
    public void setMapCellMode(int m) { sp.edit().putInt("mapCellMode", m).apply(); }

    public void setAlerts(float lowPress, int waitMinutes, boolean sound) {
        sp.edit().putFloat("lowPress", lowPress).putInt("waitMinutes", waitMinutes)
                .putBoolean("sound", sound).apply();
    }

    public void setOverlay(boolean on) { sp.edit().putBoolean("overlay", on).apply(); }
    public void setOverlayPos(int x, int y) { sp.edit().putInt("overlayX", x).putInt("overlayY", y).apply(); }
    public void setAutoLog(boolean on) { sp.edit().putBoolean("autoLog", on).apply(); }
    public void setTrendWindowSec(int s) { sp.edit().putInt("trendWindow", s).apply(); }
    public void setLocationAsked() { sp.edit().putBoolean("locationAsked", true).apply(); }

    // --- накопители статистики (переживают перезапуск) ---
    public float getF(String k) { return sp.getFloat(k, 0f); }
    public void putF(SharedPreferences.Editor e, String k, double v) { e.putFloat(k, (float) v); }
    public SharedPreferences.Editor edit() { return sp.edit(); }
}
