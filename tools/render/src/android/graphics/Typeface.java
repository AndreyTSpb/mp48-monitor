package android.graphics;

/** Заглушка шрифта: семейство и начертание переносятся в java.awt.Font. */
public class Typeface {
    public static final int NORMAL = 0;
    public static final int BOLD = 1;

    public static final Typeface DEFAULT = new Typeface("sans-serif", NORMAL);
    public static final Typeface MONOSPACE = new Typeface("monospace", NORMAL);

    final String family;
    final int style;

    Typeface(String family, int style) {
        this.family = family;
        this.style = style;
    }

    public static Typeface create(String family, int style) {
        if (family != null && family.toLowerCase().contains("mono")) return MONOSPACE;
        return new Typeface("sans-serif", style);
    }
}
