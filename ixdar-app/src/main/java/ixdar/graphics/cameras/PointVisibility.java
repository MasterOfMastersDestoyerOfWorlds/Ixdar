package ixdar.graphics.cameras;

/**
 * Answers where a world point lands on the framebuffer and whether anything drawn hides it, which
 * text anchored in 3D asks before it is laid out; the drawn surface knows what hides a point, the
 * camera alone only where it projects.
 */
public interface PointVisibility {

    /**
     * Whether a world point on the surface, pulled toward the eye, lands on the framebuffer where
     * the frame shows it, neither hidden nor past a silhouette.
     *
     * @param camera    camera the frame was drawn through
     * @param x         world x
     * @param y         world y
     * @param z         world z
     * @param depthLift world distance the point is pulled toward the eye before the hiding test
     * @param pixelDest at least three floats; receives framebuffer x from the left, y from the top
     *                  and the window depth
     * @return true when the point is on screen and not hidden
     */
    boolean visiblePixel(Camera3D camera, float x, float y, float z, float depthLift, float[] pixelDest);
}
