package android.view;

/** Заглушка события касания — нужна только для компиляции обработчиков. */
public class MotionEvent {
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;

    public int getAction() { return ACTION_UP; }
    public float getRawX() { return 0f; }
    public float getRawY() { return 0f; }
}
