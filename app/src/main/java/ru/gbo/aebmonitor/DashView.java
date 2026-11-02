package ru.gbo.aebmonitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.Locale;

/** Экран параметров: рисуется вручную, масштабируется под любой размер. */
public final class DashView extends View {

    // Цвета
    private static final int BG = 0xFF0E1116;
    private static final int TILE = 0xFF1A1F27;
    private static final int TILE_EDGE = 0xFF262C36;
    private static final int LABEL = 0xFF8B95A5;
    private static final int VALUE = 0xFFF2F4F7;
    private static final int DIM = 0xFF4A5260;
    private static final int GAS = 0xFF3DDC84;
    private static final int WAIT = 0xFFFFB300;
    private static final int PETROL = 0xFF4FA3FF;
    private static final int ALERT = 0xFFFF5252;

    private static final long STALE_MS = 1500;
    private static final float LOW_PRESS = 0.90f;   // порог предупреждения о низком давлении на газу, бар

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Typeface bold = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private final Typeface normal = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Locale ru = new Locale("ru");

    private Protocol.Frame frame;
    private long frameAt;
    private int linkState = AebLink.ST_CLOSED;
    private String linkText = "Кабель не подключён";
    private float fps;
    private boolean raw;
    private boolean logging;
    private boolean demo;
    private Alerts.Alert alert;

    public DashView(Context c) {
        super(c);
        setBackgroundColor(BG);
    }

    public void setFrame(Protocol.Frame f, float fps) {
        this.frame = f;
        this.frameAt = System.currentTimeMillis();
        this.fps = fps;
        invalidate();
    }

    public void setLink(int state, String text) {
        this.linkState = state;
        this.linkText = text;
        invalidate();
    }

    public void setRaw(boolean on) { raw = on; invalidate(); }
    public void setLogging(boolean on) { logging = on; invalidate(); }
    public void setDemo(boolean on) { demo = on; invalidate(); }
    public void setAlert(Alerts.Alert a) { alert = a; invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float u = Math.min(w, h) / 100f;          // условная единица
        float pad = 1.6f * u;
        boolean fresh = frame != null && System.currentTimeMillis() - frameAt < STALE_MS;
        Protocol.Frame f = fresh ? frame : null;

        // ---- Шапка ----
        float headH = Math.max(14 * u, h * 0.13f);
        drawHeader(c, pad, pad, w - pad, headH, u, f);

        // ---- Подвал: лямбда (+ сырые байты) ----
        float footH = Math.max(12 * u, h * 0.12f);
        float rawH = raw ? 7 * u : 0;
        float footTop = h - pad - footH - rawH;
        drawLambda(c, pad, footTop, w - pad, footTop + footH, u, f);
        if (raw) drawRaw(c, pad, footTop + footH + u, w - pad, h - pad, u);

        // ---- Плитки ----
        boolean landscape = w > h * 1.15f;
        int cols = landscape ? 4 : 2, rows = landscape ? 2 : 4;
        float gx0 = pad, gy0 = pad + headH + pad, gx1 = w - pad, gy1 = footTop - pad;
        float tw = (gx1 - gx0 - pad * (cols - 1)) / cols;
        float th = (gy1 - gy0 - pad * (rows - 1)) / rows;

        String[] labels = {"Обороты", "Время бензина", "Время газа", "Множитель",
                "Давление газа", "MAP", "T редуктора", "T газа"};
        String[] units = {"об/мин", "мс", "мс", "газ/бенз", "бар", "бар", "°C", "°C"};
        String[] vals = new String[8];
        int[] colors = new int[8];
        for (int i = 0; i < 8; i++) { vals[i] = "—"; colors[i] = DIM; }
        if (f != null) {
            boolean onGas = f.fuel == Protocol.FUEL_GAS;
            vals[0] = String.valueOf(f.rpm);
            vals[1] = fmt2(f.benzMs);
            vals[2] = onGas || f.gasMs > 0 ? fmt2(f.gasMs) : "—";
            float k = f.multiplier();
            vals[3] = Float.isNaN(k) ? "—" : fmt2(k);
            vals[4] = fmt2(f.pressBar);
            vals[5] = fmt2(f.mapBar);
            vals[6] = String.valueOf(Math.round(f.tRid));
            vals[7] = String.valueOf(Math.round(f.tGas));
            for (int i = 0; i < 8; i++) colors[i] = VALUE;
            if (vals[2].equals("—")) colors[2] = DIM;
            if (vals[3].equals("—")) colors[3] = DIM;
            if (onGas && f.pressBar < LOW_PRESS) colors[4] = ALERT;
        }
        for (int i = 0; i < 8; i++) {
            int col = i % cols, row = i / cols;
            float x = gx0 + col * (tw + pad), y = gy0 + row * (th + pad);
            drawTile(c, x, y, x + tw, y + th, u, labels[i], vals[i], units[i], colors[i]);
        }

        // перерисовка для отслеживания «устаревших» данных
        postInvalidateDelayed(500);
    }

    private void drawHeader(Canvas c, float x0, float y0, float x1, float y1, float u, Protocol.Frame f) {
        float h = y1 - y0;
        // Бейдж топлива
        String fuel;
        int color;
        if (f == null) { fuel = "НЕТ ДАННЫХ"; color = DIM; }
        else if (f.fuel == Protocol.FUEL_GAS) { fuel = "ГАЗ"; color = GAS; }
        else if (f.fuel == Protocol.FUEL_GAS_WAIT) { fuel = "ГАЗ · ожидание"; color = WAIT; }
        else { fuel = "БЕНЗИН"; color = PETROL; }

        text.setTypeface(bold);
        text.setTextSize(h * 0.55f);
        float bw = text.measureText(fuel) + h * 0.8f;
        float maxBadge = (x1 - x0) * 0.45f;                 // на узком экране бейдж не больше 45 % ширины
        if (bw > maxBadge) {
            float k = (maxBadge - h * 0.8f) / (bw - h * 0.8f);
            text.setTextSize(h * 0.55f * k);
            bw = maxBadge;
        }
        r.set(x0, y0, x0 + bw, y1);
        fill.setColor(color);
        c.drawRoundRect(r, h * 0.22f, h * 0.22f, fill);
        text.setColor(f == null ? VALUE : 0xFF0E1116);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(fuel, r.centerX(), centerBase(r.centerY()), text);

        // Предупреждение — красная плашка вместо строки состояния
        if (alert != null) {
            r.set(x0 + bw + 2 * u, y0, x1, y1);
            fill.setColor(ALERT);
            c.drawRoundRect(r, h * 0.22f, h * 0.22f, fill);
            text.setTypeface(bold);
            text.setColor(0xFFFFFFFF);
            text.setTextAlign(Paint.Align.CENTER);
            float size = h * 0.40f;
            text.setTextSize(size);
            float aw = text.measureText(alert.text), maxA = r.width() - 2 * u;
            if (aw > maxA) text.setTextSize(Math.max(size * 0.55f, size * maxA / aw));
            c.drawText(ellipsize(alert.text, maxA), r.centerX(), centerBase(r.centerY()), text);
            return;
        }

        // Справа: состояние связи
        text.setTypeface(normal);
        text.setTextAlign(Paint.Align.RIGHT);
        float line1 = y0 + h * 0.42f, line2 = y0 + h * 0.85f;
        text.setTextSize(h * 0.30f);
        int lc;
        switch (linkState) {
            case AebLink.ST_ONLINE: lc = GAS; break;
            case AebLink.ST_OPENING: lc = WAIT; break;
            case AebLink.ST_NO_ECU: case AebLink.ST_ERROR: lc = ALERT; break;
            default: lc = LABEL;
        }
        String l1 = demo ? "ДЕМО-РЕЖИМ" : linkText;
        text.setColor(demo ? WAIT : lc);
        // обрезка длинного текста, чтобы не налезал на бейдж
        float maxW = x1 - (x0 + bw) - 2 * u;
        float l1w = text.measureText(l1);
        if (l1w > maxW && l1w > 0) text.setTextSize(text.getTextSize() * Math.max(0.6f, maxW / l1w));
        l1 = ellipsize(l1, maxW);
        c.drawText(l1, x1, line1, text);

        text.setTextSize(h * 0.26f);
        text.setColor(LABEL);
        StringBuilder sb = new StringBuilder();
        if (frame != null && (linkState == AebLink.ST_ONLINE || demo)) {
            sb.append(String.format(ru, "%.0f кадр/с", fps));
        }
        float l2w = text.measureText(sb.toString());
        c.drawText(sb.toString(), x1, line2, text);
        if (logging) {
            String rec = "● ЗАПИСЬ";
            text.setColor(ALERT);
            c.drawText(rec, x1 - l2w - (l2w > 0 ? 3 * u : 0), line2, text);
        }
    }

    private void drawTile(Canvas c, float x0, float y0, float x1, float y1, float u,
                          String label, String value, String unit, int valueColor) {
        r.set(x0, y0, x1, y1);
        fill.setColor(TILE);
        float rad = 2.2f * u;
        c.drawRoundRect(r, rad, rad, fill);
        fill.setColor(TILE_EDGE);
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(Math.max(1f, u * 0.25f));
        c.drawRoundRect(r, rad, rad, fill);
        fill.setStyle(Paint.Style.FILL);

        float tw = x1 - x0, th = y1 - y0;
        float ip = Math.min(tw, th) * 0.08f;

        // подпись
        text.setTypeface(normal);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(LABEL);
        text.setTextSize(Math.min(th * 0.15f, tw * 0.11f));
        c.drawText(ellipsize(label, tw - 2 * ip), x0 + ip, y0 + ip + text.getTextSize() * 0.85f, text);

        // единица — внизу справа
        text.setTextAlign(Paint.Align.RIGHT);
        text.setTextSize(Math.min(th * 0.13f, tw * 0.10f));
        c.drawText(unit, x1 - ip, y1 - ip, text);

        // значение — крупно по центру, подбор размера под ширину
        text.setTypeface(bold);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(valueColor);
        float size = th * 0.48f;
        text.setTextSize(size);
        float maxW = tw - 2 * ip;
        float vw = text.measureText(value);
        if (vw > maxW) text.setTextSize(size * maxW / vw);
        float cy = y0 + th * 0.55f;
        c.drawText(value, (x0 + x1) / 2f, centerBase(cy), text);
    }

    private void drawLambda(Canvas c, float x0, float y0, float x1, float y1, float u, Protocol.Frame f) {
        float h = y1 - y0;
        r.set(x0, y0, x1, y1);
        fill.setColor(TILE);
        c.drawRoundRect(r, 2.2f * u, 2.2f * u, fill);

        float ip = h * 0.18f;
        // подпись и значение слева
        text.setTypeface(normal);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(LABEL);
        text.setTextSize(h * 0.26f);
        c.drawText("Лямбда", x0 + ip, y0 + ip + text.getTextSize() * 0.8f, text);
        String v = f == null ? "—" : String.format(ru, "%.2f В", f.lambdaV);
        text.setTypeface(bold);
        text.setTextSize(h * 0.40f);
        text.setColor(f == null ? DIM : VALUE);
        float labelW = Math.max(text.measureText("0,00 В"), h * 1.6f);
        c.drawText(v, x0 + ip, y1 - ip, text);

        // шкала 0…1 В
        float bx0 = x0 + ip + labelW + 2 * u, bx1 = x1 - ip;
        float by = y0 + h * 0.5f, bh = h * 0.22f;
        r.set(bx0, by - bh / 2, bx1, by + bh / 2);
        fill.setColor(0xFF2A303A);
        c.drawRoundRect(r, bh / 2, bh / 2, fill);
        // середина 0,45 В
        float mid = bx0 + (bx1 - bx0) * 0.45f;
        fill.setColor(LABEL);
        c.drawRect(mid - u * 0.2f, by - bh, mid + u * 0.2f, by + bh, fill);
        // подписи краёв
        text.setTypeface(normal);
        text.setTextSize(h * 0.20f);
        text.setColor(LABEL);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText("бедно", bx0, y1 - ip * 0.4f, text);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText("богато", bx1, y1 - ip * 0.4f, text);
        if (f != null) {
            float lv = Math.max(0f, Math.min(1f, f.lambdaV));
            float px = bx0 + (bx1 - bx0) * lv;
            fill.setColor(lv > 0.45f ? WAIT : PETROL);
            c.drawCircle(px, by, bh * 0.95f, fill);
        }
    }

    private void drawRaw(Canvas c, float x0, float y0, float x1, float y1, float u) {
        if (frame == null) return;
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(LABEL);
        String s = Protocol.hex(frame.raw);
        float size = (y1 - y0) * 0.6f;
        text.setTextSize(size);
        float tw = text.measureText(s);
        if (tw > x1 - x0) text.setTextSize(size * (x1 - x0) / tw);
        c.drawText(s, x0, y1 - (y1 - y0) * 0.2f, text);
    }

    private float centerBase(float cy) {
        Paint.FontMetrics fm = text.getFontMetrics();
        return cy - (fm.ascent + fm.descent) / 2f;
    }

    private String ellipsize(String s, float maxW) {
        if (maxW <= 0) return "";
        if (text.measureText(s) <= maxW) return s;
        String e = "…";
        int n = s.length();
        while (n > 0 && text.measureText(s.substring(0, n) + e) > maxW) n--;
        return s.substring(0, n) + e;
    }

    private String fmt2(float v) {
        return String.format(ru, "%.2f", v);
    }
}
