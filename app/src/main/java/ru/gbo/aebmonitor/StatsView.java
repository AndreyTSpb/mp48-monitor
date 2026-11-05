package ru.gbo.aebmonitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.Locale;

/** Статистика: поездка и «с заправки». */
public final class StatsView extends View {
    private Stats stats;
    private boolean gpsPermission, gpsOn;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Typeface bold = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private final Locale ru = new Locale("ru");

    public StatsView(Context c) {
        super(c);
        setBackgroundColor(Theme.BG);
    }

    public void setStats(Stats s) { stats = s; invalidate(); }

    public void setGps(boolean permission, boolean on) {
        gpsPermission = permission;
        gpsOn = on;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || stats == null) return;
        float u = Math.min(w, h) / 100f, pad = 1.6f * u;
        boolean land = w > h * 1.15f;

        // строка состояния GPS
        float headH = 6 * u;
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(headH * 0.6f);
        p.setTextAlign(Paint.Align.LEFT);
        String gps;
        int gc;
        if (!gpsPermission) { gps = "GPS: нет разрешения — пробег не считается (Меню → Разрешить GPS)"; gc = Theme.WAIT; }
        else if (!gpsOn) { gps = "GPS недоступен — пробег не считается"; gc = Theme.WAIT; }
        else if (!stats.gpsFresh()) { gps = "GPS: поиск спутников…"; gc = Theme.LABEL; }
        else { gps = Float.isNaN(stats.speedKmh) ? "GPS: есть" : String.format(ru, "GPS: есть · %.0f км/ч", stats.speedKmh); gc = Theme.GAS; }
        p.setColor(gc);
        c.drawText(gps, pad, pad + headH * 0.7f, p);

        float top = pad + headH + pad * 0.5f;
        float cw = land ? (w - 3 * pad) / 2 : w - 2 * pad;
        float chh = land ? h - top - pad : (h - top - 2 * pad) / 2;

        Stats.Acc t = stats.trip, rf = stats.refill;
        String[][] trip = {
                {"Время на газу", dur(t.secGas)},
                {"Время на бензине", dur(t.secPetrol)},
                {"Доля газа", pct(t.gasShare())},
                {"Пробег на газу", km(t.kmGas)},
                {"Пробег на бензине", km(t.kmPetrol)},
                {"Расход газа", per100(stats.per100(t))},
        };
        double lit = stats.litersSinceRefill();
        double left = stats.gasLeft(), range = stats.rangeKm();
        String gasLeft = stats.tankLiters <= 0 ? "нет объёма (Меню → Заправка)"
                : Double.isNaN(left) ? "нет калибровки"
                : String.format(ru, "≈ %.1f л (%.0f %%)", left, stats.gasLeftFrac() * 100);
        String[][] refill = {
                {"В баллоне", gasLeft},
                {"Запас хода на газу", Double.isNaN(range) ? "—" : String.format(ru, "≈ %.0f км", range)},
                {"Израсходовано газа", Double.isNaN(lit) ? "нет калибровки" : String.format(ru, "≈ %.1f л", lit)},
                {"Пробег на газу", km(rf.kmGas)},
                {"Пробег на бензине", km(rf.kmPetrol)},
                {"Доля газа", pct(rf.gasShare())},
                {"Средний расход", per100(stats.per100(rf))},
        };
        drawCard(c, pad, top, pad + cw, top + chh, u, "Поездка (с запуска)", trip);
        if (land) drawCard(c, 2 * pad + cw, top, 2 * pad + 2 * cw, top + chh, u, "С последней заправки", refill);
        else drawCard(c, pad, top + chh + pad, pad + cw, top + 2 * chh + pad, u, "С последней заправки", refill);
        postInvalidateDelayed(1000);
    }

    private void drawCard(Canvas c, float x0, float y0, float x1, float y1, float u, String title, String[][] rows) {
        r.set(x0, y0, x1, y1);
        p.setColor(Theme.TILE);
        c.drawRoundRect(r, 2 * u, 2 * u, p);
        float ip = 2 * u;
        float lineH = (y1 - y0 - 2 * ip) / (rows.length + 1.2f);
        p.setTypeface(bold);
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(Theme.VALUE);
        p.setTextSize(lineH * 0.55f);
        c.drawText(title, x0 + ip, y0 + ip + lineH * 0.7f, p);
        for (int i = 0; i < rows.length; i++) {
            float y = y0 + ip + lineH * (i + 1.2f) + lineH * 0.7f;
            p.setTypeface(Typeface.DEFAULT);
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(Theme.LABEL);
            p.setTextSize(lineH * 0.45f);
            c.drawText(rows[i][0], x0 + ip, y, p);
            float labelW = p.measureText(rows[i][0]);
            p.setTypeface(bold);
            p.setTextAlign(Paint.Align.RIGHT);
            boolean na = rows[i][1].startsWith("—") || rows[i][1].startsWith("нет") || rows[i][1].startsWith("после");
            p.setColor(na ? Theme.DIM : Theme.VALUE);
            float vs = lineH * (na ? 0.42f : 0.55f);
            p.setTextSize(vs);
            float maxW = (x1 - x0 - 2 * ip) - labelW - 2 * u, vw = p.measureText(rows[i][1]);
            if (vw > maxW && maxW > 0) p.setTextSize(vs * maxW / vw);
            c.drawText(rows[i][1], x1 - ip, y, p);
            if (i < rows.length - 1) {
                p.setColor(Theme.GRID);
                c.drawRect(x0 + ip, y + lineH * 0.28f, x1 - ip, y + lineH * 0.28f + Math.max(1, u * 0.12f), p);
            }
        }
    }

    private String dur(double s) {
        long m = Math.round(s / 60);
        return m >= 60 ? String.format(ru, "%d ч %02d мин", m / 60, m % 60) : String.format(ru, "%d мин", m);
    }

    private String pct(double x) {
        return Double.isNaN(x) ? "—" : String.format(ru, "%.0f %%", x * 100);
    }

    private String km(double x) {
        return String.format(ru, x < 10 ? "%.1f км" : "%.0f км", x);
    }

    private String per100(double x) {
        return Double.isNaN(x) ? "—" : String.format(ru, "≈ %.1f л/100 км", x);
    }
}
