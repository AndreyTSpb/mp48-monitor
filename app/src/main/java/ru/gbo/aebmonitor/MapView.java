package ru.gbo.aebmonitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * Карта K из блока с живым курсором: строки — время бензина (мс), столбцы — обороты.
 * Курсор ставится по билинейной интерполяции, как считает блок; за ним — след за 5 с.
 * Нажатие переключает раскраску ячеек: K карты / средний множитель за поездку / время в ячейке.
 */
public final class MapView extends View {
    private static final long TRAIL_MS = 5000;

    public static final int MODE_K = 0, MODE_MULT = 1, MODE_TIME = 2;
    private static final String[] MODE_NAME = {"K карты", "Множитель (за поездку)", "Время в ячейке"};

    public interface ModeListener { void onModeChanged(int mode); }

    private KMap map;
    private String error;
    private Protocol.Frame frame;
    private Trend trend;
    private CellStats cells;
    private Protocol.Frame cursorFrame;
    private int mode = MODE_K;
    private ModeListener modeListener;

    private final long[] tt = new long[Trend.capacity()];
    private final float[][] tv = new float[Trend.CHANNELS][Trend.capacity()];
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Typeface bold = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private final Locale ru = new Locale("ru");

    public MapView(Context c) {
        super(c);
        setBackgroundColor(Theme.BG);
    }

    public void setMap(KMap m, String err) { map = m; error = err; invalidate(); }
    public void setFrame(Protocol.Frame f) { frame = f; invalidate(); }
    public void setTrend(Trend t) { trend = t; }
    public void setCells(CellStats s) { cells = s; }
    public void setMode(int m) { mode = m; invalidate(); }
    public void setModeListener(ModeListener l) { modeListener = l; }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) {
            mode = (mode + 1) % 3;
            if (modeListener != null) modeListener.onModeChanged(mode);
            invalidate();
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float u = Math.min(w, h) / 100f, pad = 1.6f * u;
        boolean fresh = frame != null && System.currentTimeMillis() - frame.timeMs < 1500;

        // ---- строка сведений ----
        float headH = 7 * u;
        if (fresh && !frame.isTransient()) cursorFrame = frame;     // на отсечке и коротких импульсах курсор не прыгает
        Protocol.Frame cf = cursorFrame != null && System.currentTimeMillis() - cursorFrame.timeMs < 3000 ? cursorFrame : null;
        KMap.Point pt = (map != null && cf != null) ? map.locate(cf.benzMs, cf.rpm) : null;
        p.setTypeface(Typeface.DEFAULT);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(headH * 0.55f);
        if (map == null) {
            p.setColor(error != null ? Theme.ALERT : Theme.LABEL);
            c.drawText(error != null ? error + " — Меню → Перечитать карту K" : "Карта K: чтение из блока…",
                    pad, pad + headH * 0.65f, p);
            postInvalidateDelayed(1000);
            return;
        }
        float x = pad;
        x = info(c, x, pad + headH * 0.65f, MODE_NAME[mode], Theme.VALUE, true);
        x = info(c, x + 2 * u, pad + headH * 0.65f, "· нажмите, чтобы сменить", Theme.DIM, false);
        if (pt != null) {
            x = info(c, x + 3 * u, pad + headH * 0.65f, String.format(ru, "Tб %.2f · %d", cf.benzMs, cf.rpm), Theme.LABEL, false);
            x = info(c, x + 2 * u, pad + headH * 0.65f, String.format(ru, "K %.2f", pt.k), Theme.WAIT, true);
            float mult = cf.multiplier();
            if (!Float.isNaN(mult))
                info(c, x + 2 * u, pad + headH * 0.65f, String.format(ru, "м %.2f", mult), Theme.GAS, true);
        }

        // ---- таблица ----
        int rows = KMap.VISIBLE_ROWS, cols = Protocol.MAP_COLS;
        float labW = 9 * u, labH = 5 * u;
        float gx0 = pad + labW, gy0 = pad + headH + labH, gx1 = w - pad, gy1 = h - pad;
        float cw = (gx1 - gx0) / cols, ch = (gy1 - gy0) / rows;

        // диапазон раскраски по режиму
        float lo = 0, hi = 1;
        if (mode == MODE_K) {
            lo = map.min(); hi = Math.max(map.max(), lo + 1);
        } else if (mode == MODE_MULT && cells != null) {
            float mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE;
            for (int rI = 0; rI < rows; rI++) for (int cI = 0; cI < cols; cI++) {
                float v = cells.avgMult(rI, cI);
                if (!Float.isNaN(v)) { mn = Math.min(mn, v); mx = Math.max(mx, v); }
            }
            lo = mn == Float.MAX_VALUE ? 1.0f : mn; hi = mx == -Float.MAX_VALUE ? lo + 0.3f : Math.max(mx, lo + 0.05f);
        } else if (mode == MODE_TIME && cells != null) {
            lo = 0; hi = (float) Math.max(cells.maxSeconds(), 1);
        }

        // подписи осей
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(Math.min(labH * 0.6f, cw * 0.32f));
        p.setColor(Theme.LABEL);
        p.setTextAlign(Paint.Align.CENTER);
        for (int cI = 0; cI < cols; cI++)
            c.drawText(String.valueOf(map.rpm[cI]), gx0 + cw * (cI + 0.5f), gy0 - labH * 0.3f, p);
        p.setTextAlign(Paint.Align.RIGHT);
        p.setTextSize(Math.min(ch * 0.45f, labW * 0.32f));
        for (int rI = 0; rI < rows; rI++)
            c.drawText(String.format(ru, map.tbMs[rI] < 10 ? "%.1f" : "%.0f", map.tbMs[rI]), gx0 - u, gy0 + ch * (rI + 0.5f) + p.getTextSize() * 0.35f, p);
        p.setTextAlign(Paint.Align.RIGHT);
        p.setTextSize(Math.min(labH * 0.5f, labW * 0.3f));
        c.drawText("Tб, мс", gx0 - u, gy0 - labH * 0.3f, p);

        // ячейки
        float gap = Math.max(1f, u * 0.15f);
        p.setTextAlign(Paint.Align.CENTER);
        float ts = Math.min(ch * 0.5f, cw * 0.36f);
        for (int rI = 0; rI < rows; rI++) {
            for (int cI = 0; cI < cols; cI++) {
                float x0 = gx0 + cI * cw, y0 = gy0 + rI * ch;
                r.set(x0 + gap, y0 + gap, x0 + cw - gap, y0 + ch - gap);
                float val = cellValue(rI, cI);
                boolean has = !Float.isNaN(val);
                p.setColor(has ? heat((val - lo) / (hi - lo)) : Theme.TILE);
                c.drawRect(r, p);
                boolean active = pt != null && (rI == pt.r0 || rI == pt.r0 + 1) && (cI == pt.c0 || cI == pt.c0 + 1);
                if (active) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(Math.max(2f, u * 0.35f));
                    p.setColor(Theme.VALUE);
                    c.drawRect(r, p);
                    p.setStyle(Paint.Style.FILL);
                }
                String txt = cellText(rI, cI);
                if (txt != null) {
                    p.setTypeface(bold);
                    p.setColor(has ? 0xFF0E1116 : Theme.DIM);
                    p.setTextSize(ts);
                    c.drawText(txt, r.centerX(), r.centerY() + ts * 0.36f, p);
                }
            }
        }

        // след за последние секунды
        if (trend != null && fresh) {
            int n = trend.snapshot(System.currentTimeMillis(), TRAIL_MS, tt, tv);
            for (int i = 0; i < n; i++) {
                if (tv[Trend.STEADY][i] < 0.5f) continue;
                float tb = tv[Trend.BENZ][i];
                int rpmI = (int) tv[Trend.RPM][i];
                if (Float.isNaN(tb)) continue;
                KMap.Point q = map.locate(tb, rpmI);
                float age = (System.currentTimeMillis() - tt[i]) / (float) TRAIL_MS;
                int alpha = (int) (200 * (1 - age));
                if (alpha <= 0) continue;
                p.setColor((alpha << 24) | 0x00FFFFFF);
                c.drawCircle(gx0 + cw * (q.col + 0.5f), gy0 + ch * (q.row + 0.5f), Math.max(2f, u * 0.5f), p);
            }
        }

        // курсор
        if (pt != null) {
            float cx = gx0 + cw * (pt.col + 0.5f), cy = gy0 + ch * (pt.row + 0.5f);
            float rad = Math.max(6f, Math.min(cw, ch) * 0.32f);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(3f, u * 0.6f));
            p.setColor(0xFF0E1116);
            c.drawCircle(cx, cy, rad, p);
            p.setStrokeWidth(Math.max(2f, u * 0.35f));
            p.setColor(cf.fuel == Protocol.FUEL_GAS ? Theme.GAS : Theme.PETROL);
            c.drawCircle(cx, cy, rad, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, Math.max(2.5f, u * 0.5f), p);
        }
        postInvalidateDelayed(500);
    }

    /** Числовое значение ячейки для раскраски в текущем режиме, NaN — нет данных. */
    private float cellValue(int rI, int cI) {
        if (mode == MODE_K) return map.k[rI][cI];
        if (cells == null) return Float.NaN;
        if (mode == MODE_MULT) return cells.avgMult(rI, cI);
        double s = cells.seconds(rI, cI);
        return s > 0 ? (float) s : Float.NaN;
    }

    /** Подпись в ячейке или null. */
    private String cellText(int rI, int cI) {
        if (mode == MODE_K) return String.format(ru, "%.2f", map.k[rI][cI] / 100f);
        if (cells == null) return null;
        if (mode == MODE_MULT) {
            float m = cells.avgMult(rI, cI);
            return Float.isNaN(m) ? null : String.format(ru, "%.2f", m);
        }
        double s = cells.seconds(rI, cI);
        if (s < 1) return null;
        return s >= 60 ? String.format(ru, "%d:%02d", (int) (s / 60), (int) (s % 60)) : String.format(ru, "%.0fс", s);
    }

    private float info(Canvas c, float x, float y, String s, int color, boolean b) {
        p.setTypeface(b ? bold : Typeface.DEFAULT);
        p.setColor(color);
        c.drawText(s, x, y, p);
        return x + p.measureText(s);
    }

    /** Цвет ячейки: синий (мин) → зелёный → жёлтый → оранжевый (макс), приглушённые тона. */
    private static int heat(float t) {
        t = Math.max(0, Math.min(1, t));
        int[] stops = {0xFF5B8FD9, 0xFF5CC48E, 0xFFE0C35A, 0xFFE8955A};
        float s = t * (stops.length - 1);
        int i = Math.min(stops.length - 2, (int) s);
        float f = s - i;
        return mix(stops[i], stops[i + 1], f);
    }

    private static int mix(int a, int b, float f) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | (Math.round(ar + (br - ar) * f) << 16) | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
