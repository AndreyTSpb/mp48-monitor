package ru.gbo.aebmonitor;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.Locale;

/**
 * Мини-панель поверх других приложений (навигатора): режим, давление, множитель, T редуктора.
 * Перетаскивается пальцем, нажатие открывает приложение.
 */
public final class OverlayPanel {
    private final Context ctx;
    private final Prefs prefs;
    private final WindowManager wm;
    private MiniView view;
    private WindowManager.LayoutParams lp;

    public OverlayPanel(Context ctx, Prefs prefs) {
        this.ctx = ctx;
        this.prefs = prefs;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean isShown() {
        return view != null;
    }

    public void show() {
        if (view != null) return;
        float d = ctx.getResources().getDisplayMetrics().density;
        view = new MiniView(ctx);
        int type = Build.VERSION.SDK_INT >= 26
                ? 2038 /* TYPE_APPLICATION_OVERLAY */
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(
                Math.round(230 * d), Math.round(78 * d), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = prefs.overlayX();
        lp.y = prefs.overlayY();
        try {
            wm.addView(view, lp);
        } catch (RuntimeException e) {
            view = null;          // нет разрешения «поверх других окон»
        }
    }

    public void hide() {
        if (view == null) return;
        try { wm.removeView(view); } catch (RuntimeException ignored) { }
        view = null;
    }

    public void update(Protocol.Frame f, int linkState, Alerts.Alert alert, boolean demo) {
        if (view == null) return;
        view.f = f;
        view.online = linkState == AebLink.ST_ONLINE;
        view.alert = alert;
        view.demo = demo;
        view.invalidate();
    }

    private final class MiniView extends View {
        Protocol.Frame f;
        boolean online, demo;
        Alerts.Alert alert;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Typeface bold = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        private final Locale ru = new Locale("ru");
        private float downX, downY;
        private int startX, startY;
        private boolean moved;

        MiniView(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            boolean fresh = f != null && System.currentTimeMillis() - f.timeMs < 1500;
            r.set(0, 0, w, h);
            p.setColor(alert != null ? 0xE6B71C1C : 0xE60E1116);
            c.drawRoundRect(r, h * 0.18f, h * 0.18f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1, h * 0.02f));
            p.setColor(0xFF2A303A);
            c.drawRoundRect(r, h * 0.18f, h * 0.18f, p);
            p.setStyle(Paint.Style.FILL);

            // Бейдж топлива слева
            String fuel;
            int col;
            if (!fresh) { fuel = online ? "…" : "НЕТ"; col = Theme.DIM; }
            else if (f.fuel == Protocol.FUEL_GAS) { fuel = "ГАЗ"; col = Theme.GAS; }
            else if (f.fuel == Protocol.FUEL_GAS_WAIT) { fuel = "ЖДЁТ"; col = Theme.WAIT; }
            else { fuel = "БЕНЗ"; col = Theme.PETROL; }
            float pad = h * 0.12f;
            float bw = w * 0.30f;
            r.set(pad, pad, pad + bw, h - pad);
            p.setColor(col);
            c.drawRoundRect(r, h * 0.12f, h * 0.12f, p);
            p.setTypeface(bold);
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(fresh ? 0xFF0E1116 : Theme.VALUE);
            fit(fuel, bw * 0.86f, h * 0.36f);
            c.drawText(fuel, r.centerX(), base(r.centerY()), p);

            float x0 = pad + bw + pad, x1 = w - pad;
            p.setTextAlign(Paint.Align.LEFT);
            if (alert != null) {
                p.setColor(Theme.VALUE);
                fit(alert.shortText, x1 - x0, h * 0.40f);
                c.drawText(alert.shortText, x0, base(h * 0.36f), p);
            } else {
                // давление крупно
                String pr = fresh ? String.format(ru, "%.2f", f.pressBar) : "—";
                p.setColor(Theme.VALUE);
                p.setTextSize(h * 0.42f);
                c.drawText(pr, x0, base(h * 0.36f), p);
                float pw = p.measureText(pr);
                p.setTypeface(Typeface.DEFAULT);
                p.setTextSize(h * 0.20f);
                p.setColor(Theme.LABEL);
                c.drawText(" бар", x0 + pw, base(h * 0.40f), p);
            }
            // нижняя строка: множитель и T редуктора
            p.setTypeface(Typeface.DEFAULT);
            p.setColor(Theme.LABEL);
            String line;
            if (fresh) {
                float k = f.multiplier();
                line = (Float.isNaN(k) ? "K —" : String.format(ru, "K %.2f", k))
                        + String.format(ru, "   Тр %d°", Math.round(f.tRid));
            } else {
                line = demo ? "демо" : (online ? "нет данных" : "нет связи");
            }
            fit(line, x1 - x0, h * 0.24f);
            c.drawText(line, x0, base(h * 0.76f), p);
            postInvalidateDelayed(1000);
        }

        private void fit(String s, float maxW, float size) {
            p.setTextSize(size);
            float tw = p.measureText(s);
            if (tw > maxW && tw > 0) p.setTextSize(size * maxW / tw);
        }

        private float base(float cy) {
            Paint.FontMetrics fm = p.getFontMetrics();
            return cy - (fm.ascent + fm.descent) / 2f;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (Math.abs(dx) > 12 || Math.abs(dy) > 12) moved = true;
                    if (moved) {
                        lp.x = startX + Math.round(dx);
                        lp.y = startY + Math.round(dy);
                        try { wm.updateViewLayout(this, lp); } catch (RuntimeException ignored) { }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (moved) {
                        prefs.setOverlayPos(lp.x, lp.y);
                    } else {
                        Intent i = new Intent(ctx, MainActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        ctx.startActivity(i);
                    }
                    return true;
            }
            return super.onTouchEvent(e);
        }
    }
}
