package android.graphics;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/** Полотно поверх java.awt.image.BufferedImage: рисует те же примитивы, что Canvas Android. */
public class Canvas {
    public final BufferedImage image;
    private final Graphics2D g;

    public Canvas(int width, int height) {
        image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    private void apply(Paint p) {
        g.setColor(new Color(p.getColor(), true));
        if (p.getStyle() == Paint.Style.STROKE) {
            g.setStroke(new BasicStroke(Math.max(0.5f, p.getStrokeWidth())));
        } else {
            g.setStroke(new BasicStroke(0f));
        }
    }

    /** Залить всё полотно цветом (фон View). */
    public void fill(int argb) {
        g.setColor(new Color(argb, true));
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
    }

    public void drawRect(float left, float top, float right, float bottom, Paint p) {
        apply(p);
        Rectangle2D.Float r = new Rectangle2D.Float(left, top, right - left, bottom - top);
        if (p.getStyle() == Paint.Style.STROKE) g.draw(r);
        else g.fill(r);
    }

    public void drawRect(RectF r, Paint p) {
        drawRect(r.left, r.top, r.right, r.bottom, p);
    }

    public void drawRoundRect(RectF r, float rx, float ry, Paint p) {
        apply(p);
        RoundRectangle2D.Float s = new RoundRectangle2D.Float(
                r.left, r.top, r.width(), r.height(), rx * 2f, ry * 2f);
        if (p.getStyle() == Paint.Style.STROKE) g.draw(s);
        else g.fill(s);
    }

    public void drawCircle(float cx, float cy, float radius, Paint p) {
        apply(p);
        Ellipse2D.Float e = new Ellipse2D.Float(cx - radius, cy - radius, radius * 2f, radius * 2f);
        if (p.getStyle() == Paint.Style.STROKE) g.draw(e);
        else g.fill(e);
    }

    public void drawLine(float x0, float y0, float x1, float y1, Paint p) {
        apply(p);
        g.draw(new Line2D.Float(x0, y0, x1, y1));
    }

    public void drawText(String text, float x, float y, Paint p) {
        if (text == null || text.isEmpty()) return;
        g.setFont(p.awtFont());
        apply(p);
        float dx = 0;
        if (p.getTextAlign() == Paint.Align.CENTER) dx = -p.measureText(text) / 2f;
        else if (p.getTextAlign() == Paint.Align.RIGHT) dx = -p.measureText(text);
        g.drawString(text, x + dx, y);
    }

    public void drawPath(Path path, Paint p) {
        apply(p);
        if (p.getStyle() == Paint.Style.STROKE) g.draw(path.impl);
        else g.fill(path.impl);
    }
}
