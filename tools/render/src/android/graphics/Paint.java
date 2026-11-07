package android.graphics;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.geom.AffineTransform;

/** Кисть: цвет, шрифт, обводка. Размеры и координаты — в пикселях, как в Android. */
public class Paint {
    public static final int ANTI_ALIAS_FLAG = 1;

    public enum Style { FILL, STROKE }
    public enum Align { LEFT, CENTER, RIGHT }
    public enum Join { ROUND }

    /** Метрики шрифта в системе Android: ascent отрицательный, descent положительный. */
    public static class FontMetrics {
        public float ascent, descent;
    }

    private static final FontRenderContext FRC =
            new FontRenderContext(new AffineTransform(), true, true);

    private int color = 0xFF000000;
    private float textSize = 12f;
    private Align align = Align.LEFT;
    private Style style = Style.FILL;
    private float strokeWidth = 1f;
    private Typeface typeface = Typeface.DEFAULT;

    public Paint() {}

    public Paint(int flags) {}

    public void setColor(int color) { this.color = color; }
    public int getColor() { return color; }

    public void setTextSize(float size) { this.textSize = size; }
    public float getTextSize() { return textSize; }

    public void setTextAlign(Align align) { this.align = align; }
    public Align getTextAlign() { return align; }

    public void setStyle(Style style) { this.style = style; }
    public Style getStyle() { return style; }

    public void setStrokeWidth(float width) { this.strokeWidth = width; }
    public float getStrokeWidth() { return strokeWidth; }

    public void setStrokeJoin(Join join) { }

    public void setTypeface(Typeface typeface) { this.typeface = typeface; }

    public void setAntiAlias(boolean on) { }

    Font awtFont() {
        String family = "monospace".equals(typeface.family) ? "Monospaced" : "SansSerif";
        int style = typeface.style == Typeface.BOLD ? Font.BOLD : Font.PLAIN;
        return new Font(family, style, Math.max(1, Math.round(textSize)));
    }

    public float measureText(String text) {
        if (text == null || text.isEmpty()) return 0f;
        return (float) awtFont().getStringBounds(text, FRC).getWidth();
    }

    public FontMetrics getFontMetrics() {
        LineMetrics lm = awtFont().getLineMetrics("Ag", FRC);
        FontMetrics m = new FontMetrics();
        m.ascent = -lm.getAscent();
        m.descent = lm.getDescent();
        return m;
    }
}
