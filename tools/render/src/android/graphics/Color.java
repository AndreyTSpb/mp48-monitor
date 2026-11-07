package android.graphics;

/** Заглушка: экраны импортируют Color, но пользуются готовыми ARGB-константами. */
public class Color {
    public static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
