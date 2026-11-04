package ru.gbo.aebmonitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.Locale;

/** Экран экономичности: мгновенный расход, эко-шкала, подсказка, эко-счёт поездки. */
public final class EcoView extends View {
    private Eco eco;
    private Stats stats;
    private Protocol.Frame frame;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Typeface bold = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private final Locale ru = new Locale("ru");

    public EcoView(Context c) {
        super(c);
        setBackgroundColor(Theme.BG);
    }

    public void setEco(Eco e) { eco = e; invalidate(); }
    public void setStats(Stats s) { stats = s; invalidate(); }
    public void setFrame(Protocol.Frame f) { frame = f; invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float u = Math.min(w, h) / 100f, pad = 2 * u;
        boolean fresh = frame != null && System.currentTimeMillis() - frame.timeMs < 1500;

        p.setTypeface(Typeface.DEFAULT);
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(Theme.LABEL);
        p.setTextSize(4 * u);
        c.drawText("Экономичность", pad, pad + 4 * u, p);

        boolean hasK = stats != null && stats.k > 0;
        int level = eco == null ? Eco.LEVEL_NONE : eco.level;
        int lc = levelColor(level);

        // ---- крупное значение ----
        float cy = h * 0.34f;
        p.setTextAlign(Paint.Align.CENTER);
        if (eco != null && fresh && hasK && !Float.isNaN(eco.l100)) {
            bigValue(c, w / 2f, cy, u, String.format(ru, "%.1f", eco.l100), "л/100 км", Theme.VALUE);
        } else if (eco != null && fresh && hasK && !Float.isNaN(eco.lh)) {
            bigValue(c, w / 2f, cy, u, String.format(ru, "%.1f", eco.lh), "л/ч · стоянка", Theme.VALUE);
        } else {
            String word;
            if (level == Eco.LEVEL_GOOD) word = "ЭКОНОМНО";
            else if (level == Eco.LEVEL_OK) word = "НОРМА";
            else if (level == Eco.LEVEL_BAD) word = "РАСХОД";
            else word = fresh ? "—" : "нет данных";
            bigValue(c, w / 2f, cy, u, word, hasK ? "" : "расход в литрах — после калибровки (Меню → Заправка)", lc);
        }

        // Δ к среднему (относительная экономичность)
        if (eco != null && fresh && !Float.isNaN(eco.ratio)) {
            int d = Math.round((eco.ratio - 1f) * 100);
            p.setTypeface(Typeface.DEFAULT);
            p.setColor(lc);
            p.setTextSize(3.2f * u);
            c.drawText(String.format(ru, "%+d %% к среднему за поездку", d), w / 2f, cy + 7 * u, p);
        }

        // ---- шкала ----
        float gx0 = pad + 4 * u, gx1 = w - pad - 4 * u, gy = h * 0.60f, gh = 4 * u;
        r.set(gx0, gy, gx1, gy + gh);
        // градиент красный → жёлтый → зелёный сегментами
        int seg = 60;
        for (int i = 0; i < seg; i++) {
            float t = i / (float) (seg - 1);
            p.setColor(grad(t));
            float xa = gx0 + (gx1 - gx0) * i / seg, xb = gx0 + (gx1 - gx0) * (i + 1) / seg;
            c.drawRect(xa, gy, xb + 1, gy + gh, p);
        }
        p.setColor(Theme.LABEL);
        p.setTextSize(2.6f * u);
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText("расход выше", gx0, gy + gh + 3.2f * u, p);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText("экономично", gx1, gy + gh + 3.2f * u, p);
        // маркер
        if (eco != null && fresh && !Float.isNaN(eco.ratio)) {
            float t = Math.max(0f, Math.min(1f, (1.3f - eco.ratio) / (1.3f - 0.8f)));
            float mx = gx0 + (gx1 - gx0) * t;
            p.setColor(Theme.VALUE);
            float tri = 1.6f * u;
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(mx, gy - 0.4f * u);
            path.lineTo(mx - tri, gy - 2.2f * u);
            path.lineTo(mx + tri, gy - 2.2f * u);
            path.close();
            c.drawPath(path, p);
            c.drawRect(mx - 0.25f * u, gy - 0.4f * u, mx + 0.25f * u, gy + gh + 0.4f * u, p);
        }

        // ---- подсказка ----
        if (eco != null && fresh) {
            p.setTypeface(bold);
            p.setColor(lc);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(4 * u);
            c.drawText(eco.hint, w / 2f, h * 0.80f, p);
        }

        // ---- нижняя строка: эко-счёт, средний расход, скорость ----
        float by = h - pad - 2.5f * u;
        float third = (w - 2 * pad) / 3f;
        float sc = eco == null ? Float.NaN : eco.score();
        statCell(c, pad, by, u, "Эко-счёт поездки", Float.isNaN(sc) ? "—" : Math.round(sc * 100) + " %");
        String avg = (stats != null && hasK) ? fmtL(stats.per100(stats.trip)) : "нужна калибровка";
        statCell(c, pad + third, by, u, "Средний на газу", avg);
        String spd = (stats != null && !Float.isNaN(stats.speedKmh)) ? Math.round(stats.speedKmh) + " км/ч" : "—";
        statCell(c, pad + 2 * third, by, u, "Скорость", spd);

        postInvalidateDelayed(400);
    }

    private void bigValue(Canvas c, float cx, float cy, float u, String val, String unit, int color) {
        p.setTypeface(bold);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(color);
        float size = 16 * u;
        p.setTextSize(size);
        float vw = p.measureText(val), maxW = getWidth() * 0.9f;
        if (vw > maxW) p.setTextSize(size * maxW / vw);
        c.drawText(val, cx, cy, p);
        if (!unit.isEmpty()) {
            p.setTypeface(Typeface.DEFAULT);
            p.setColor(Theme.LABEL);
            p.setTextSize(3.2f * u);
            c.drawText(unit, cx, cy + 4.5f * u, p);
        }
    }

    private void statCell(Canvas c, float x, float y, float u, String label, String value) {
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(Typeface.DEFAULT);
        p.setColor(Theme.LABEL);
        p.setTextSize(2.6f * u);
        c.drawText(label, x, y, p);
        p.setTypeface(bold);
        p.setColor(Theme.VALUE);
        p.setTextSize(4 * u);
        c.drawText(value, x, y + 4.3f * u, p);
    }

    private static String fmtL(double x) {
        return Double.isNaN(x) ? "—" : String.format(new Locale("ru"), "%.1f л/100", x);
    }

    private static int levelColor(int level) {
        switch (level) {
            case Eco.LEVEL_GOOD: return Theme.GAS;
            case Eco.LEVEL_OK: return Theme.WAIT;
            case Eco.LEVEL_BAD: return Theme.ALERT;
            default: return Theme.DIM;
        }
    }

    /** 0 = красный (плохо), 1 = зелёный (хорошо). */
    private static int grad(float t) {
        int[] stops = {0xFFFF5252, 0xFFFFB300, 0xFF3DDC84};
        float s = t * (stops.length - 1);
        int i = Math.min(stops.length - 2, (int) s);
        float f = s - i;
        int a = stops[i], b = stops[i + 1];
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | (Math.round(ar + (br - ar) * f) << 16) | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
