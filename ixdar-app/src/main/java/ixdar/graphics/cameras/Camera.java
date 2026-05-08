package ixdar.graphics.cameras;

import org.joml.Vector2f;

import ixdar.geometry.point.PointSet;
import ixdar.platform.input.MouseTrap;

/**
 * Common surface for the editor's 2D and 3D cameras: viewport sizing,
 * scroll/drag/keyboard input, point↔screen-space transforms, and a
 * z-index counter that orders successive ortho draws within a frame.
 */
public interface Camera extends MouseTrap.ScrollHandler {

    /**
     * Restore the camera to its default framing of the current scene.
     */
    void reset();

    /**
     * Translate the camera one frame's worth in {@code direction} using the
     * camera's pan/movement speed and the current shift modifier.
     *
     * @param direction cardinal direction to move
     */
    void move(Direction direction);

    /**
     * Set the multiplier applied to pan/zoom rates while the shift key is held.
     *
     * @param sHIFT_MOD multiplier (typically 1 when released, larger when held)
     */
    void setShiftMod(float sHIFT_MOD);

    /**
     * Apply a mouse-drag delta — pans the 2D camera or rotates the 3D camera
     * depending on the implementation.
     *
     * @param d horizontal delta
     * @param e vertical delta
     */
    void drag(float d, float e);

    /**
     * Apply a continuous mouse-look update from the previous to the current cursor position.
     *
     * @param lastX previous cursor x
     * @param lastY previous cursor y
     * @param x current cursor x
     * @param y current cursor y
     */
    void mouseMove(float lastX, float lastY, float x, float y);

    /**
     * Advance the camera's z-index by one ortho-z increment so the next ortho
     * draw lands above the previous one.
     */
    void incZIndex();

    /**
     * Read the camera's current ortho z-index.
     *
     * @return the current ortho z-index used by upcoming draws
     */
    float getZIndex();

    /**
     * Reset the z-index counter and far-z cursor for a new frame.
     */
    void resetZIndex();

    /**
     * Add an arbitrary delta to the z-index counter.
     *
     * @param diff signed z-index increment
     */
    void addZIndex(float diff);

    /**
     * Place this camera one z-step in front of {@code camera}.
     *
     * @param camera reference camera whose z-index defines the baseline
     */
    void setZIndex(Camera camera);

    /**
     * Recompute the point-space → screen-space transform from the bounding box
     * of {@code ps}, used to frame all input points.
     *
     * @param ps point set to fit into the view
     */
    void calculateCameraTransform(PointSet ps);

    /**
     * transform from point space to screen space.
     *
     * @param x point-space x coordinate
     * @return pointSpaceX
     */
    float pointTransformX(float x);

    /**
     * transform from point space to screen space.
     *
     * @param y point-space y coordinate
     * @return pointSpaceY
     */
    float pointTransformY(float y);

    /**
     * transform from screen space to point space.
     *
     * @param normalizedPosX framebuffer-space x coordinate
     * @return pointSpaceX
     */
    float screenTransformX(float normalizedPosX);

    /**
     * transform from screen space to point space.
     *
     * @param normalizedPosY framebuffer-space y coordinate
     * @return pointSpaceY
     */
    float screenTransformY(float normalizedPosY);

    /**
     * Camera viewport width in screen space.
     *
     * @return the camera's current screen-space width
     */
    float getWidth();

    /**
     * Camera viewport height in screen space.
     *
     * @return the camera's current screen-space height
     */
    float getHeight();

    /**
     * X offset of the camera viewport in framebuffer space.
     *
     * @return the camera viewport's lower-left x in framebuffer space
     */
    float getScreenOffsetX();

    /**
     * Y offset of the camera viewport in framebuffer space.
     *
     * @return the camera viewport's lower-left y in framebuffer space
     */
    float getScreenOffsetY();

    /**
     * DPI scaling ratio along x.
     *
     * @return framebuffer-width / window-width DPI ratio
     */
    float getScreenWidthRatio();

    /**
     * DPI scaling ratio along y.
     *
     * @return framebuffer-height / window-height DPI ratio
     */
    float getScreenHeightRatio();

    /**
     * Read the camera's zoom factor.
     *
     * @return current zoom / scale factor applied to point-space distances
     */
    float getScaleFactor();

    /**
     * Convert a window-space cursor x to framebuffer-space x for hit testing.
     *
     * @param xPos window-space cursor x
     * @return framebuffer-space x
     */
    float getNormalizePosX(float xPos);

    /**
     * Convert a window-space cursor y (top-origin) to framebuffer-space y (bottom-origin).
     *
     * @param yPos window-space cursor y
     * @return framebuffer-space y
     */
    float getNormalizePosY(float yPos);

    /**
     * Read the descending far-z cursor.
     *
     * @return current depth used by the descending far-z cursor
     */
    float getFarZIndex();

    /**
     * Step the descending far-z cursor one increment toward the near plane.
     */
    void decFarZIndex();

    /**
     * Camera viewport rectangle in screen space.
     *
     * @return the camera's screen-space viewport rectangle, or {@code null} if not assigned
     */
    Bounds getBounds();

    /**
     * Test whether {@code pB} lies inside this camera's screen-space viewport.
     *
     * @param pB screen-space point
     * @return {@code true} when the point falls within the viewport
     */
    boolean contains(Vector2f pB);

    /**
     * Resize the GL viewport and rebuild projection matrices on every shader.
     *
     * @param x viewport lower-left x
     * @param y viewport lower-left y
     * @param width viewport width
     * @param height viewport height
     */
    void updateView(int x, int y, int width, int height);

    /**
     * Reset the GL viewport to the full framebuffer.
     */
    void resetView();

    public enum Direction {
        FORWARD, BACKWARD, LEFT, RIGHT

    }
}
