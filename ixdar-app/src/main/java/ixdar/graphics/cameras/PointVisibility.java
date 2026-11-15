package ixdar.graphics.cameras;

import org.joml.Vector3f;

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
     * @param point     world point
     * @param depthLift world distance the point is pulled toward the eye before the hiding test
     * @return framebuffer x from the left, y from the top and the window depth, or null when the
     *         point is off screen or hidden
     */
    Vector3f visiblePixel(Camera3D camera, Vector3f point, float depthLift);
}
