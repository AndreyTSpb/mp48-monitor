package android.view;

import android.content.Context;
import android.graphics.Canvas;

/** Мини-View: размеры, фон и вызов onDraw — всё, что нужно для рендера в PNG. */
public class View {
    public static final int VISIBLE = 0;
    public static final int INVISIBLE = 4;
    public static final int GONE = 8;

    private int width, height;
    private int background;
    private int visibility = VISIBLE;
    protected final Context context;

    public View(Context context) {
        this.context = context;
    }

    /** Размер задаётся рендерером вместо разметки. */
    public void setSize(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    public void setBackgroundColor(int color) { this.background = color; }
    public void setVisibility(int visibility) { this.visibility = visibility; }
    public int getVisibility() { return visibility; }

    public void invalidate() { }
    public void postInvalidateDelayed(long ms) { }

    protected void onDraw(Canvas canvas) { }

    public boolean onTouchEvent(MotionEvent e) { return false; }

    /** Отрисовать вид в полотно: фон, затем onDraw конкретного экрана. */
    public final void render(Canvas canvas) {
        if (background != 0) canvas.fill(background);
        onDraw(canvas);
    }
}
