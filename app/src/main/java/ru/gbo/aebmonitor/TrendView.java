package ru.gbo.aebmonitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/** Графики за последние 1 или 5 минут. Нажатие переключает окно. */
public final class TrendView extends View {

    public interface WindowListener { void onWindowChanged(int sec); }

    private Trend trend;
    private int windowSec = 60;
    private float lowPress = 0.90f;
    private WindowListener wl;

    private final long[] t = new long[Trend.capacity()];
    private final float[][] v = new float[Trend.CHANNELS][Trend.capacity()];
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private final Typeface bold = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private final Locale ru = new Locale("ru");

    public TrendView(Context c) {
        super(c);
        setBackgroundColor(Theme.BG);
    }

    public void setTrend(Trend tr) { trend = tr; invalidate(); }
    public void setWindowSec(int s) { windowSec = s; invalidate(); }
    public void setLowPress(float lp) { lowPress = lp; }
    public void setWindowListener(WindowListener l) { wl = l; }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) {
            windowSec = windowSec == 60 ? 300 : 60;
            if (wl != null) wl.onWindowChanged(windowSec);
            invalidate();
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float u = Math.min(w, h) / 100f, pad = 1.6f * u;
        long now = System.currentTimeMillis();
        long win = windowSec * 1000L;
        int n = trend == null ? 0 : trend.snapshot(now, win, t, v);

        // заголовок
        float headH = 6 * u;
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(headH * 0.6f);
        p.setColor(Theme.LABEL);
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText("Графики за " + (windowSec == 60 ? "1 минуту" : "5 минут") + "  ·  нажмите, чтобы сменить",
                pad, pad + headH * 0.7f, p);

        boolean land = w > h * 1.15f;
        int cols = land ? 2 : 1, rows = land ? 2 : 4;
        float gy0 = pad + headH + pad * 0.5f;
        float cw = (w - pad * (cols + 1)) / cols, ch = (h - gy0 - pad * rows) / rows;
        for (int i = 0; i < 4; i++) {
            int col = i % cols, row = i / cols;
            float x0 = pad + col * (cw + pad), y0 = gy0 + row * (ch + pad);
            drawChart(c, i, x0, y0, x0 + cw, y0 + ch, u, n, now, win);
        }
        postInvalidateDelayed(500);
    }

    private void drawChart(Canvas c, int which, float x0, float y0, float x1, float y1, float u,
                           int n, long now, long win) {
        r.set(x0, y0, x1, y1);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Theme.TILE);
        c.drawRoundRect(r, 2 * u, 2 * u, p);

        String title, unit;
        int[] ch;
        int[] colors;
        float lo, hi;
        float ref = Float.NaN;
        boolean fixed = false;
        switch (which) {
            case 0: title = "Множитель газ/бензин"; unit = ""; ch = new int[]{Trend.MULT}; colors = new int[]{Theme.GAS};
                lo = 1.0f; hi = 2.0f; break;
            case 1: title = "Давление газа"; unit = " бар"; ch = new int[]{Trend.PRESS}; colors = new int[]{Theme.VALUE};
                lo = 0.8f; hi = 1.4f; ref = lowPress; break;
            case 2: title = "Лямбда"; unit = " В"; ch = new int[]{Trend.LAMBDA}; colors = new int[]{Theme.WAIT};
                lo = 0f; hi = 1f; ref = 0.45f; fixed = true; break;
            default: title = "Время бензина / газа"; unit = " мс"; ch = new int[]{Trend.BENZ, Trend.GAS};
                colors = new int[]{Theme.PETROL, Theme.GAS}; lo = 0f; hi = 8f; break;
        }
        // автомасштаб
        if (!fixed && n > 0) {
            float mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE;
            for (int k : ch) for (int i = 0; i < n; i++) {
                float val = v[k][i];
                if (Float.isNaN(val)) continue;
                if (val < mn) mn = val;
                if (val > mx) mx = val;
            }
            if (mn <= mx) {
                if (which == 3) mn = 0;
                float span = Math.max(mx - mn, which == 1 ? 0.2f : which == 0 ? 0.3f : 2f);
                float mid = (mx + mn) / 2;
                lo = which == 3 ? 0 : mid - span * 0.6f;
                hi = which == 3 ? mx + span * 0.15f : mid + span * 0.6f;
            }
        }

        float ip = 1.4f * u;
        float th = Math.min((y1 - y0) * 0.14f, 4.5f * u);
        // подпись и текущее значение
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(th * 0.8f);
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(Theme.LABEL);
        c.drawText(title, x0 + ip, y0 + ip + th * 0.75f, p);
        p.setTypeface(bold);
        p.setTextAlign(Paint.Align.RIGHT);
        float vx = x1 - ip;
        for (int j = ch.length - 1; j >= 0; j--) {
            float cur = n > 0 ? v[ch[j]][n - 1] : Float.NaN;
            String s = Float.isNaN(cur) ? "—" : String.format(ru, "%.2f", cur) + (j == ch.length - 1 ? unit : "");
            p.setColor(colors[j]);
            p.setTextSize(th);
            c.drawText(s, vx, y0 + ip + th * 0.8f, p);
            vx -= p.measureText(s) + 2 * u;
        }

        // область графика
        float gx0 = x0 + ip + 5 * u, gx1 = x1 - ip, gy0 = y0 + ip + th * 1.4f, gy1 = y1 - ip - 2.5f * u;
        if (gy1 - gy0 < 10) return;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, u * 0.15f));
        p.setColor(Theme.GRID);
        for (int i = 0; i <= 2; i++) {
            float y = gy0 + (gy1 - gy0) * i / 2f;
            c.drawLine(gx0, y, gx1, y, p);
        }
        // подписи оси Y
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(2.4f * u);
        p.setColor(Theme.LABEL);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(fmt(hi), gx0 - u, gy0 + u, p);
        c.drawText(fmt(lo), gx0 - u, gy1, p);
        // ось времени
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText("−" + (win / 1000 >= 120 ? (win / 60000) + " мин" : (win / 1000) + " с"), gx0, y1 - ip * 0.4f, p);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText("сейчас", gx1, y1 - ip * 0.4f, p);

        // опорная линия
        if (!Float.isNaN(ref) && ref > lo && ref < hi) {
            float y = gy1 - (ref - lo) / (hi - lo) * (gy1 - gy0);
            p.setColor(which == 1 ? Theme.ALERT : Theme.LABEL);
            p.setStrokeWidth(Math.max(1, u * 0.2f));
            p.setStyle(Paint.Style.STROKE);
            c.drawLine(gx0, y, gx1, y, p);
        }

        // линии
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.5f, u * 0.35f));
        p.setStrokeJoin(Paint.Join.ROUND);
        for (int j = 0; j < ch.length; j++) {
            path.reset();
            boolean pen = false;
            for (int i = 0; i < n; i++) {
                float val = v[ch[j]][i];
                if (Float.isNaN(val)) { pen = false; continue; }
                float x = gx1 - (now - t[i]) / (float) win * (gx1 - gx0);
                float y = gy1 - (Math.max(lo, Math.min(hi, val)) - lo) / (hi - lo) * (gy1 - gy0);
                if (!pen) { path.moveTo(x, y); pen = true; } else path.lineTo(x, y);
            }
            p.setColor(colors[j]);
            c.drawPath(path, p);
        }
        p.setStyle(Paint.Style.FILL);
        if (n == 0) {
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(Theme.DIM);
            p.setTextSize(3.5f * u);
            c.drawText("нет данных", (gx0 + gx1) / 2, (gy0 + gy1) / 2, p);
        }
    }

    private String fmt(float x) {
        return String.format(ru, Math.abs(x) >= 10 ? "%.0f" : "%.2f", x);
    }
}
