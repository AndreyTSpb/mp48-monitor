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
