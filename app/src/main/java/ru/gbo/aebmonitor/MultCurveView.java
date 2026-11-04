package ru.gbo.aebmonitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.Locale;

/**
 * Кривая среднего фактического множителя газ/бензин по времени бензина (нагрузке),
 * накопленная за поездку из статистики по ячейкам карты. Точка — узел оси Tб,
 * радиус точки растёт со временем, проведённым в этой зоне. Живой курсор — текущий режим.
 */
public final class MultCurveView extends View {
    private KMap map;
    private CellStats cells;
    private Protocol.Frame frame;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private final Typeface bold = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private final Locale ru = new Locale("ru");

    private final float[] rowMult = new float[KMap.VISIBLE_ROWS];   // средний множитель строки Tб
    private final double[] rowSec = new double[KMap.VISIBLE_ROWS];  // время в строке

    public MultCurveView(Context c) {
        super(c);
        setBackgroundColor(Theme.BG);
    }

    public void setMap(KMap m) { map = m; invalidate(); }
    public void setCells(CellStats s) { cells = s; invalidate(); }
    public void setFrame(Protocol.Frame f) { frame = f; invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float u = Math.min(w, h) / 100f, pad = 1.6f * u;
        boolean fresh = frame != null && System.currentTimeMillis() - frame.timeMs < 1500;

        float headH = 7 * u;
        p.setTypeface(Typeface.DEFAULT);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(headH * 0.55f);
        if (map == null) {
            p.setColor(Theme.LABEL);
            c.drawText("Множитель по Tб: карта K ещё не прочитана", pad, pad + headH * 0.65f, p);
            postInvalidateDelayed(1000);
            return;
        }
        p.setColor(Theme.VALUE);
        p.setTypeface(bold);
        c.drawText("Множитель газ/бензин по времени бензина (за поездку)", pad, pad + headH * 0.7f, p);

        // агрегируем по строкам Tб: среднее по столбцам, взвешенное временем
        int rows = KMap.VISIBLE_ROWS, cols = Protocol.MAP_COLS;
        int nPts = 0;
        double totalSec = 0;
        float mMin = Float.MAX_VALUE, mMax = -Float.MAX_VALUE, maxSec = 0;
        for (int rI = 0; rI < rows; rI++) {
            double sw = 0, swm = 0;
            for (int cI = 0; cI < cols; cI++) {
                float mv = cells == null ? Float.NaN : cells.avgMult(rI, cI);
                double sec = cells == null ? 0 : cells.seconds(rI, cI);
                if (!Float.isNaN(mv) && sec > 0) { sw += sec; swm += mv * sec; }
            }
            rowSec[rI] = sw;
            if (sw > 0) {
                rowMult[rI] = (float) (swm / sw);
                nPts++;
                totalSec += sw;
                mMin = Math.min(mMin, rowMult[rI]);
                mMax = Math.max(mMax, rowMult[rI]);
                maxSec = (float) Math.max(maxSec, sw);
            } else {
                rowMult[rI] = Float.NaN;
            }
        }

        if (nPts == 0) {
            p.setTypeface(Typeface.DEFAULT);
            p.setColor(Theme.DIM);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(4 * u);
            c.drawText("Нет данных — проедьте на газу, кривая построится сама", w / 2f, h / 2f, p);
            postInvalidateDelayed(1000);
            return;
        }

        // диапазон оси Y с запасом; учитываем и текущий множитель
        float curM = fresh ? frame.multiplier() : Float.NaN;
        if (!Float.isNaN(curM)) { mMin = Math.min(mMin, curM); mMax = Math.max(mMax, curM); }
        float span = Math.max(mMax - mMin, 0.1f);
        float ylo = mMin - span * 0.25f, yhi = mMax + span * 0.25f;

        // ось X — по времени бензина, как на карте (tbMs[0]..tbMs[11])
        float xlo = map.tbMs[0], xhi = map.tbMs[cols - 1];
        float gx0 = pad + 7 * u, gy0 = pad + headH + 2 * u, gx1 = w - pad, gy1 = h - pad - 4 * u;

        // сетка и подписи Y
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, u * 0.15f));
        p.setColor(Theme.GRID);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(2.6f * u);
        for (int i = 0; i <= 4; i++) {
            float yv = ylo + (yhi - ylo) * i / 4f;
            float y = gy1 - (yv - ylo) / (yhi - ylo) * (gy1 - gy0);
            p.setColor(Theme.GRID);
            p.setStyle(Paint.Style.STROKE);
            c.drawLine(gx0, y, gx1, y, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Theme.LABEL);
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText(String.format(ru, "%.2f", yv), gx0 - u, y + u * 0.9f, p);
        }
        // подписи X (Tб)
        p.setTextAlign(Paint.Align.CENTER);
        for (int rI = 0; rI < rows; rI += 2) {
            float x = gx0 + (map.tbMs[rI] - xlo) / (xhi - xlo) * (gx1 - gx0);
            c.drawText(String.format(ru, map.tbMs[rI] < 10 ? "%.1f" : "%.0f", map.tbMs[rI]), x, gy1 + 3 * u, p);
        }
        c.drawText("Tб, мс", (gx0 + gx1) / 2, h - pad * 0.3f, p);

        // линия 1,0 (ориентир) если в диапазоне
        if (1.0f > ylo && 1.0f < yhi) {
            float y = gy1 - (1.0f - ylo) / (yhi - ylo) * (gy1 - gy0);
            p.setColor(Theme.DIM);
            p.setStyle(Paint.Style.STROKE);
            c.drawLine(gx0, y, gx1, y, p);
            p.setStyle(Paint.Style.FILL);
        }

        // кривая
        path.reset();
        boolean pen = false;
        for (int rI = 0; rI < rows; rI++) {
            if (Float.isNaN(rowMult[rI])) { pen = false; continue; }
            float x = gx0 + (map.tbMs[rI] - xlo) / (xhi - xlo) * (gx1 - gx0);
            float y = gy1 - (rowMult[rI] - ylo) / (yhi - ylo) * (gy1 - gy0);
            if (!pen) { path.moveTo(x, y); pen = true; } else path.lineTo(x, y);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, u * 0.4f));
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(Theme.GAS);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);

        // точки с подписью значения; радиус по времени
        p.setTextAlign(Paint.Align.CENTER);
        for (int rI = 0; rI < rows; rI++) {
            if (Float.isNaN(rowMult[rI])) continue;
            float x = gx0 + (map.tbMs[rI] - xlo) / (xhi - xlo) * (gx1 - gx0);
            float y = gy1 - (rowMult[rI] - ylo) / (yhi - ylo) * (gy1 - gy0);
            float rad = (float) (u * (0.6 + 1.6 * Math.sqrt(rowSec[rI] / Math.max(1, maxSec))));
            p.setColor(Theme.GAS);
            c.drawCircle(x, y, rad, p);
            p.setColor(Theme.VALUE);
            p.setTypeface(bold);
            p.setTextSize(2.6f * u);
            c.drawText(String.format(ru, "%.2f", rowMult[rI]), x, y - rad - u, p);
        }

        // живой курсор
        if (!Float.isNaN(curM)) {
            float tb = Math.max(xlo, Math.min(xhi, frame.benzMs));
            float x = gx0 + (tb - xlo) / (xhi - xlo) * (gx1 - gx0);
            float y = gy1 - (Math.max(ylo, Math.min(yhi, curM)) - ylo) / (yhi - ylo) * (gy1 - gy0);
            p.setColor(Theme.WAIT);
            c.drawCircle(x, y, Math.max(3f, u * 0.7f), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, u * 0.2f));
            p.setColor(0xFF0E1116);
            c.drawCircle(x, y, Math.max(3f, u * 0.7f), p);
            p.setStyle(Paint.Style.FILL);
        }

        // подпись снизу: сколько накоплено
        p.setTypeface(Typeface.DEFAULT);
        p.setColor(Theme.DIM);
        p.setTextAlign(Paint.Align.RIGHT);
        p.setTextSize(2.6f * u);
        c.drawText(String.format(ru, "газ %d:%02d  ·  размер точки — время в зоне",
                (int) (totalSec / 60), (int) (totalSec % 60)), gx1, pad + headH * 0.7f, p);

        postInvalidateDelayed(500);
    }
}
