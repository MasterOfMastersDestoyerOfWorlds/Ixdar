
package ixdar.graphics.render.text;

public class Glyph {

    public final int width;
    public final int height;
    public final float widthHeightRatio;
    public final int x;
    public final int y;
    public final float advance;

    // Plane-space bounds (EM units) from the font atlas JSON.
    // These define the glyph's quad relative to the baseline.
    public final float planeLeft;
    public final float planeBottom;
    public final float planeRight;
    public final float planeTop;

    /**
     * Creates a font Glyph.
     *
     * @param width   Width of the Glyph
     * @param height  Height of the Glyph
     * @param x       X coordinate on the font texture
     * @param y       Y coordinate on the font texture
     * @param advance Advance width
     */
    public Glyph(int width, int height, int x, int y, float advance,
            float planeLeft, float planeBottom, float planeRight, float planeTop) {
        this.width = width;
        this.height = height;
        this.widthHeightRatio = ((float) width) / ((float) height);
        this.x = x;
        this.y = y;
        this.advance = advance;
        this.planeLeft = planeLeft;
        this.planeBottom = planeBottom;
        this.planeRight = planeRight;
        this.planeTop = planeTop;
    }

}
