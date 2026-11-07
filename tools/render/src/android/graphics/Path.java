package android.graphics;

import java.awt.geom.Path2D;

/** Путь из отрезков — то, что нужно графикам (moveTo/lineTo/close). */
public class Path {
    final Path2D.Float impl = new Path2D.Float();

    public void reset() { impl.reset(); }
    public void moveTo(float x, float y) { impl.moveTo(x, y); }
    public void lineTo(float x, float y) { impl.lineTo(x, y); }
    public void close() { impl.closePath(); }
}
