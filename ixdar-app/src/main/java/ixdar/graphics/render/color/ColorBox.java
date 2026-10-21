package ixdar.graphics.render.color;

import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.sdf.SDFRoundedRect;

/**
 * A solid, hard-edged rectangle: an {@link SDFRoundedRect} with no corner radius, no border and
 * an edge too sharp to anti-alias, so it covers exactly the pixels whose centres it contains.
 */
public class ColorBox {
    public static final float HARD_EDGE_SHARPNESS = 1e-4f;

    public final SDFRoundedRect rect = new SDFRoundedRect();

    /**
     * Build the box's rectangle drawable with a hard edge.
     */
    public ColorBox() {
        rect.edgeSharpness = HARD_EDGE_SHARPNESS;
    }

    /**
     * Draw a solid-color rectangle anchored at its bottom-left corner with the given size, using
     * the camera's current Z index. Advances the camera's Z index after drawing.
     *
     * @param x bottom-left x in camera-space coordinates
     * @param y bottom-left y in camera-space coordinates
     * @param width rectangle width
     * @param height rectangle height
     * @param c fill color
     * @param camera camera supplying the Z index and projection
     */
    public void draw(float x, float y, float width, float height, Color c, Camera camera) {
        rect.draw(x, y, width, height, 0, 0, c, c, camera);
    }

    /**
     * Fill the camera's full viewport with {@code c}, useful for background
     * clears or overlays.
     *
     * @param c fill color
     * @param camera camera supplying the viewport extents
     */
    public void draw(Color c, Camera camera) {
        draw(0, 0, camera.getWidth(), camera.getHeight(), c, camera);
    }

}
