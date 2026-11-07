package android.graphics;

/** Прямоугольник с float-координатами. */
public class RectF {
    public float left, top, right, bottom;

    public RectF() {}

    public RectF(float left, float top, float right, float bottom) {
        set(left, top, right, bottom);
    }

    public void set(float left, float top, float right, float bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public void set(RectF r) {
        set(r.left, r.top, r.right, r.bottom);
    }

    public float width() { return right - left; }
    public float height() { return bottom - top; }
    public float centerX() { return (left + right) / 2f; }
    public float centerY() { return (top + bottom) / 2f; }
}
