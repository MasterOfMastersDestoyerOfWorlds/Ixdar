package ixdar.graphics.cameras;

import java.util.ArrayList;
import java.util.Map;

import org.joml.Vector2f;

import ixdar.geometry.knot.Knot;
import ixdar.geometry.knot.Segment;
import ixdar.geometry.point.Point2D;
import ixdar.geometry.point.PointND;
import ixdar.geometry.point.PointSet;
import ixdar.graphics.render.Clock;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.gui.ui.tools.Tool;
import ixdar.platform.Platforms;
import ixdar.scenes.main.MainScene;

/**
 * Orthographic 2D camera that frames a {@link PointSet} on a screen-space
 * viewport. Holds pan/scale state, point↔screen transforms, and zoom-to-fit
 * helpers for knots, segments, and arbitrary point selections. Maintains an
 * ortho z-index counter used by ordered 2D draw calls within one frame.
 */
public class Camera2D implements Camera {
    public static final int NUM__10 = -10;
    public static final int NUM_10 = 10;
    public static final double NUM_0_1 = 0.1;
    public static final float NUM_2 = 2f;
    public static final float NUM_100 = 100f;
    public static final float NUM_1 = 1f;

    public float ZOOM_SPEED = 1f;
    public float PAN_SPEED = 300f;
    public int Width, Height;
    public float ScreenWidth, ScreenHeight;
    public float ScaleFactor;
    public float InitialScale;
    public float PanX;
    public float PanY;
    public float defaultPanX;
    public float defaultPanY;
    public float offsetX;
    public float offsetY;
    public float rangeX;
    public float rangeY;
    public PointSet ps;
    public float minX;
    public float minY;
    public float maxX;
    public float maxY;
    public float height;
    public float zIndex;
    public float farZIndex;
    public float width;
    public float ScreenOffsetY;
    public float ScreenOffsetX;
    public Bounds viewBounds;
    public double screenSpaceDistanceOverPointSpaceDistanceRatio = -1;
    private float SHIFT_MOD = 1.0f;
    private Map<String, Bounds> namedBounds;
    private Bounds mainViewBounds;

    /**
     * Construct a camera over the square min(Width, Height) viewport at the
     * given screen offset, framing the provided point set.
     *
     * @param Width design-space width in points
     * @param Height design-space height in points
     * @param ScaleFactor initial zoom; preserved as {@link #InitialScale}
     * @param ScreenOffsetX viewport lower-left x in framebuffer space
     * @param ScreenOffsetY viewport lower-left y in framebuffer space
     * @param ps point set this camera will frame
     */
    public Camera2D(int Width, int Height, float ScaleFactor, float ScreenOffsetX, float ScreenOffsetY, PointSet ps) {
        if (Height < Width) {
            this.Height = Height;
            this.Width = Height;
            this.height = Height * ScaleFactor;
            this.width = Height * ScaleFactor;
        } else {
            this.Height = Width;
            this.Width = Width;
            this.height = Width * ScaleFactor;
            this.width = Width * ScaleFactor;

        }
        this.InitialScale = ScaleFactor;
        this.ScaleFactor = ScaleFactor;
        this.ScreenOffsetX = ScreenOffsetX;
        this.ScreenOffsetY = ScreenOffsetY;
        this.viewBounds = new Bounds(ScreenOffsetX, ScreenOffsetY, Width, Height, "CAMERA_2D");
        this.ps = ps;
        zIndex = 0;

    }

    /**
     * {@inheritDoc}.
     *
     * @return the camera viewport's current screen width
     */
    @Override
    public float getWidth() {
        return ScreenWidth;
    }

    /**
     * {@inheritDoc}.
     *
     * @return the camera viewport's current screen height
     */
    @Override
    public float getHeight() {
        return ScreenHeight;
    }

    /**
     * Update the cached screen-space viewport size used by transforms.
     *
     * @param newWidth new viewport screen width
     * @param newHeight new viewport screen height
     */
    public void updateSize(float newWidth, float newHeight) {
        ScreenWidth = newWidth;
        ScreenHeight = newHeight;
    }

    /**
     * Recompute min/max bounds, range, and pixel offsets of the point set so
     * subsequent {@link #pointTransformX(float)} / {@link #pointTransformY(float)}
     * calls reflect the latest geometry. Falls back to a [-10, 10] box when
     * the set is empty.
     *
     * @param ps point set to bound
     */
    @Override
    public void calculateCameraTransform(PointSet ps) {
        minX = java.lang.Float.MAX_VALUE;
        minY = java.lang.Float.MAX_VALUE;
        maxX = 0;
        maxY = 0;
        if (ps.size() == 0) {
            minX = NUM__10;
            minY = NUM__10;
            maxX = NUM_10;
            maxY = NUM_10;
        }
        for (PointND pn : ps) {
            if (!pn.isDummyNode()) {
                Point2D p = pn.toPoint2D();

                if (p.getX() < minX) {
                    minX = (float) p.getX();
                }
                if (p.getY() < minY) {
                    minY = (float) p.getY();
                }
                if (p.getX() > maxX) {
                    maxX = (float) p.getX();
                }
                if (p.getY() > maxY) {
                    maxY = (float) p.getY();
                }
            }
        }
        rangeX = Math.abs(maxX - minX);
        rangeY = Math.abs(maxY - minY);
        height = Height * ScaleFactor;
        width = Width * ScaleFactor;
        offsetX = 0 + (int) PanX;
        offsetY = 0 + (int) PanY;

        if (rangeX > rangeY) {
            rangeY = rangeX;
        } else {
            rangeX = rangeY;
        }
    }

    /**
     * Compute the bounding box of the point set, center it within the
     * viewport, seed pan/default-pan with that centering, and call
     * {@link #reset()} to apply the framing.
     */
    public void initCamera() {
        minX = java.lang.Float.MAX_VALUE;
        minY = java.lang.Float.MAX_VALUE;
        maxX = 0;
        maxY = 0;
        boolean empty = ps.size() == 0;
        if (empty) {
            minX = NUM__10;
            minY = NUM__10;
            maxX = NUM_10;
            maxY = NUM_10;
        }

        for (PointND pn : ps) {
            if (!pn.isDummyNode()) {
                Point2D p = pn.toPoint2D();

                if (p.getX() < minX) {
                    minX = (float) p.getX();
                }
                if (p.getY() < minY) {
                    minY = (float) p.getY();
                }
                if (p.getX() > maxX) {
                    maxX = (float) p.getX();
                }
                if (p.getY() > maxY) {
                    maxY = (float) p.getY();
                }
            }
        }
        offsetX = 0;
        offsetY = 0;

        rangeX = Math.abs(maxX - minX);
        rangeY = Math.abs(maxY - minY);
        if (rangeX > rangeY) {
            rangeY = rangeX;

        } else {
            rangeX = rangeY;
        }
        offsetX += (Width - (Math.abs(pointTransformX(maxX) - pointTransformX(minX)))) / 2;
        offsetY += (Height - (Math.abs(pointTransformY(maxY) - pointTransformY(minY)))) / 2;

        PanX = offsetX;
        PanY = offsetY;
        defaultPanX = PanX;
        defaultPanY = PanY;
        reset();
    }

    /**
     * Reframe the camera. If a hover/selected knot is active in
     * {@code MainScene}, zoom to it; otherwise refit the full point set into
     * the active view bounds, choosing a scale that preserves aspect ratio.
     * Also caches the screen-space-per-point-space ratio.
     */
    @Override
    public void reset() {
        if (MainScene.tool != null) {
            if (MainScene.showHoverKnot) {
                zoomToKnot(MainScene.hoverKnot);
                return;
            }
            Tool tool = MainScene.tool;
            Knot selectedKnot = tool.selectedKnot();
            if (selectedKnot != null) {
                zoomToKnot(selectedKnot);
                return;
            }
        }
        offsetX = 0;
        offsetY = 0;

        float ScaleFactorX = InitialScale + InitialScale * (mainViewBounds.viewWidth - Width) / Width;
        float ScaleFactorY = InitialScale + InitialScale * (mainViewBounds.viewHeight - Height) / Height;
        float aspectRatio = (maxX - minX) / (maxY - minY);
        if (aspectRatio >= 1) {
            ScaleFactor = ScaleFactorX;
            width = Width * ScaleFactor;
            height = Height * ScaleFactor;
            float rangeX = (Math.abs(pointTransformX(maxX) - pointTransformX(minX)));
            float rangeY = (Math.abs(pointTransformY(maxY) - pointTransformY(minY)));
            if (rangeY > mainViewBounds.viewHeight) {

                ScaleFactor = ScaleFactorY * aspectRatio;
                width = Width * ScaleFactor;
                height = Height * ScaleFactor;
                rangeX = (Math.abs(pointTransformX(maxX) - pointTransformX(minX)));
                rangeY = (Math.abs(pointTransformY(maxY) - pointTransformY(minY)));
            }
            offsetX += (mainViewBounds.viewWidth - rangeX) / 2;
            offsetY += (mainViewBounds.viewHeight - rangeY) / 2;
        } else {
            ScaleFactor = ScaleFactorY;
            width = Width * ScaleFactor;
            height = Height * ScaleFactor;
            float rangeX = (Math.abs(pointTransformX(maxX) - pointTransformX(minX)));
            float rangeY = (Math.abs(pointTransformY(maxY) - pointTransformY(minY)));
            if (rangeX > mainViewBounds.viewWidth) {
                ScaleFactor = ScaleFactorX * (maxY - minY) / (maxX - minX);
                width = Width * ScaleFactor;
                height = Height * ScaleFactor;
                rangeX = (Math.abs(pointTransformX(maxX) - pointTransformX(minX)));
                rangeY = (Math.abs(pointTransformY(maxY) - pointTransformY(minY)));
            }
            offsetX += (mainViewBounds.viewWidth - rangeX) / 2;
            offsetY += (mainViewBounds.viewHeight - rangeY) / 2;
        }
        PanX = offsetX;
        PanY = offsetY;
        Point2D origin = new Point2D.Double(pointTransformX(0.0), pointTransformY(0.0));
        Point2D p2 = new Point2D.Double(pointTransformX(1.0), pointTransformY(1.0));
        screenSpaceDistanceOverPointSpaceDistanceRatio = origin.distance(p2) / Math.sqrt(2);

    }

    /**
     * Frame the camera around the flattened points of {@code containingKnot}.
     *
     * @param containingKnot knot whose constituent points define the target frame
     */
    public void zoomToKnot(Knot containingKnot) {
        zoomToPoints(containingKnot.knotPointsFlattened);
    }

    /**
     * Frame the camera around a single segment (its two endpoint knots).
     *
     * @param s segment to bring fully into view
     */
    public void zoomToSegment(Segment s) {
        ArrayList<Knot> points = new ArrayList<>();
        points.add(s.first);
        points.add(s.last);
        zoomToPoints(points);
    }

    /**
     * Compute a scale and pan that fits the bounding box of {@code list} into
     * the active view bounds while preserving aspect ratio.
     *
     * @param list knots whose 2D positions define the target frame
     */
    public void zoomToPoints(ArrayList<Knot> list) {

        offsetX = 0;
        offsetY = 0;
        float knotMinX = Float.MAX_VALUE;
        float knotMinY = Float.MAX_VALUE;
        float knotMaxX = Float.MIN_VALUE;
        float knotMaxY = Float.MIN_VALUE;
        for (Knot vp : list) {
            PointND pn = (vp).p;
            if (!pn.isDummyNode()) {
                Point2D p = pn.toPoint2D();

                if (p.getX() < knotMinX) {
                    knotMinX = (float) p.getX();
                }
                if (p.getY() < knotMinY) {
                    knotMinY = (float) p.getY();
                }
                if (p.getX() > knotMaxX) {
                    knotMaxX = (float) p.getX();
                }
                if (p.getY() > knotMaxY) {
                    knotMaxY = (float) p.getY();
                }
            }
        }
        float widthRatio = Math.abs(pointTransformX(maxX) - pointTransformX(minX))
                / Math.abs(pointTransformX(knotMaxX) - pointTransformX(knotMinX));
        float ScaleFactorX = InitialScale * widthRatio
                + InitialScale * widthRatio * (mainViewBounds.viewWidth - Width) / Width;
        float heightRatio = Math.abs(pointTransformY(maxY) - pointTransformY(minY))
                / Math.abs(pointTransformY(knotMaxY) - pointTransformY(knotMinY));
        float ScaleFactorY = InitialScale * heightRatio
                + InitialScale * heightRatio * (mainViewBounds.viewHeight - Height) / Height;
        float aspectRatio = (knotMaxX - knotMinX) / (knotMaxY - knotMinY);
        if (aspectRatio >= 1) {
            ScaleFactor = ScaleFactorX;
            width = Width * ScaleFactor;
            height = Height * ScaleFactor;
            float rangeX = (Math.abs(pointTransformX(knotMaxX) - pointTransformX(knotMinX)));
            float rangeY = (Math.abs(pointTransformY(knotMaxY) - pointTransformY(knotMinY)));
            if (rangeY > mainViewBounds.viewHeight) {

                ScaleFactor = ScaleFactorY;
                width = Width * ScaleFactor;
                height = Height * ScaleFactor;
                rangeX = (Math.abs(pointTransformX(knotMaxX) - pointTransformX(knotMinX)));
                rangeY = (Math.abs(pointTransformY(knotMaxY) - pointTransformY(knotMinY)));
            }
            offsetX += (mainViewBounds.viewWidth - rangeX) / 2;
            offsetY += (mainViewBounds.viewHeight - rangeY) / 2;
        } else {
            ScaleFactor = ScaleFactorY;
            width = Width * ScaleFactor;
            height = Height * ScaleFactor;
            float rangeX = (Math.abs(pointTransformX(knotMaxX) - pointTransformX(knotMinX)));
            float rangeY = (Math.abs(pointTransformY(knotMaxY) - pointTransformY(knotMinY)));
            if (rangeX > mainViewBounds.viewWidth) {
                ScaleFactor = ScaleFactorX;
                width = Width * ScaleFactor;
                height = Height * ScaleFactor;
                rangeX = (Math.abs(pointTransformX(knotMaxX) - pointTransformX(knotMinX)));
                rangeY = (Math.abs(pointTransformY(knotMaxY) - pointTransformY(knotMinY)));
            }
            offsetX += (mainViewBounds.viewWidth - rangeX) / 2;
            offsetY += (mainViewBounds.viewHeight - rangeY) / 2;
        }
        PanX = offsetX - Math.abs(pointTransformX(knotMinX) - pointTransformX(minX));
        PanY = offsetY - Math.abs(pointTransformY(knotMinY) - pointTransformY(minY));
    }

    /**
     * Translate pan so {@code pn}'s screen-space position lands at the
     * center of the active view bounds (no scale change).
     *
     * @param pn point to recenter on
     */
    public void centerOnPoint(PointND pn) {
        float sx = (float) pn.getScreenX();
        float sy = (float) pn.getScreenY();
        PanX += mainViewBounds.viewWidth / 2 - pointTransformX(sx);
        PanY += mainViewBounds.viewHeight / 2 - pointTransformY(sy);
    }

    /**
     * Scale a point-space length by the cached screen-space-per-point-space
     * ratio, giving the equivalent length in pixels.
     *
     * @param smallestLength length in point space
     * @return equivalent length in screen space
     */
    public double pointSpaceLengthToScreenSpace(double smallestLength) {
        return smallestLength * screenSpaceDistanceOverPointSpaceDistanceRatio;
    }

    /**
     * Double-precision overload of {@link #pointTransformX(float)}.
     *
     * @param x point-space x coordinate
     * @return screen-space x
     */
    public float pointTransformX(double x) {
        return pointTransformX((float) x);
    }

    // transform from point space to screen space
    /**
     * Map a point-space x to screen-space using the current pan and scale.
     *
     * @param x point-space x coordinate
     * @return screen-space x
     */
    @Override
    public float pointTransformX(float x) {
        return ((((x - minX) * width) / rangeX) + offsetX);
    }

    // transform from point space to screen space
    /**
     * Variant of {@link #pointTransformX(float)} that uses the supplied scale
     * instead of the current zoom (used during scale animations).
     *
     * @param x point-space x coordinate
     * @param scale alternate scale factor to apply
     * @return screen-space x at the given scale
     */
    public float pointTransformX(float x, float scale) {
        return ((((x - minX) * (Width * scale)) / rangeX) + offsetX);
    }

    // transform from screen space to point space
    /**
     * Inverse of {@link #pointTransformX(float)}: map a screen-space x back
     * to point space.
     *
     * @param x screen-space x coordinate
     * @return point-space x
     */
    @Override
    public float screenTransformX(float x) {
        return ((((x) - offsetX) * rangeX) / width) + minX;
    }

    /**
     * Double-precision overload of {@link #pointTransformY(float)}.
     *
     * @param y point-space y coordinate
     * @return screen-space y
     */
    public float pointTransformY(double y) {
        return pointTransformY((float) y);
    }

    // transform from point space to screen space
    /**
     * Map a point-space y to screen-space using the current pan and scale.
     *
     * @param y point-space y coordinate
     * @return screen-space y
     */
    @Override
    public float pointTransformY(float y) {
        return ((((y - minY) * height) / rangeY) + offsetY);
    }

    // transform from point space to screen space
    /**
     * Variant of {@link #pointTransformY(float)} that uses the supplied scale
     * instead of the current zoom.
     *
     * @param y point-space y coordinate
     * @param scale alternate scale factor to apply
     * @return screen-space y at the given scale
     */
    public float pointTransformY(float y, float scale) {
        return ((((y - minY) * (Height * scale)) / rangeY) + offsetY);
    }

    // transform from screen space to point space
    /**
     * Inverse of {@link #pointTransformY(float)}: map a screen-space y back
     * to point space.
     *
     * @param y screen-space y coordinate
     * @return point-space y
     */
    @Override
    public float screenTransformY(float y) {
        return ((((y) - offsetY) * rangeY) / height) + minY;
    }

    /**
     * Apply a zoom delta while keeping the screen-space center anchored to
     * the same point-space location. Ignored when the resulting scale would
     * fall below 0.1.
     *
     * @param delta signed change to {@link #ScaleFactor}
     */
    public void scale(float delta) {

        if (ScaleFactor + delta < NUM_0_1) {
            return;
        }
        float newScaleY = ScaleFactor + delta;
        float midXPointSpace = screenTransformX(((float) ScreenWidth) / NUM_2);
        float midYPointSpace = screenTransformY(((float) ScreenHeight) / NUM_2);
        float midXNewScale = pointTransformX(midXPointSpace, newScaleY);
        float midYNewScale = pointTransformY(midYPointSpace, newScaleY);
        PanX += (((float) ScreenWidth) / NUM_2) - midXNewScale;
        PanY += (((float) ScreenHeight) / NUM_2) - midYNewScale;
        ScaleFactor += delta;
    }

    /**
     * Pan one frame in {@code direction} at {@code PAN_SPEED * SHIFT_MOD *
     * deltaTime} pixels.
     *
     * @param direction cardinal direction to pan
     */
    @Override
    public void move(Direction direction) {

        double d = Clock.deltaTime();
        switch (direction) {
        case FORWARD:
            PanY += PAN_SPEED * SHIFT_MOD * d;
            break;
        case LEFT:
            PanX -= PAN_SPEED * SHIFT_MOD * d;
            break;
        case BACKWARD:
            PanY -= PAN_SPEED * SHIFT_MOD * d;
            break;
        case RIGHT:
            PanX += PAN_SPEED * SHIFT_MOD * d;
            break;

        }
    }

    /**
     * Set the multiplier applied to pan and zoom rates while shift is held.
     *
     * @param SHIFT_MOD multiplier to install
     */
    @Override
    public void setShiftMod(float SHIFT_MOD) {
        this.SHIFT_MOD = SHIFT_MOD;
    }

    /**
     * Translate scroll wheel input into a {@link #scale(float)} call,
     * zooming in when {@code b} is true and out otherwise.
     *
     * @param b true for zoom-in, false for zoom-out
     * @param delta wheel notch magnitude (units of 100)
     */
    @Override
    public void onScroll(boolean b, double delta) {
        float deltaRee = (float) delta / NUM_100;
        if (b) {
            scale(ZOOM_SPEED * SHIFT_MOD * deltaRee * ScaleFactor);
        } else {
            scale(-1 * ZOOM_SPEED * SHIFT_MOD * deltaRee * ScaleFactor);
        }
    }

    /**
     * Pan the camera by the given screen-space delta.
     *
     * @param d horizontal pan delta
     * @param e vertical pan delta
     */
    @Override
    public void drag(float d, float e) {
        PanX += d;
        PanY += e;
    }

    /**
     * {@inheritDoc}.
     *
     * @return the current zoom factor
     */
    @Override
    public float getScaleFactor() {
        return ScaleFactor;
    }

    /**
     * No-op for the 2D camera; mouse-look only applies in 3D.
     *
     * @param lastX previous cursor x (ignored)
     * @param lastY previous cursor y (ignored)
     * @param x current cursor x (ignored)
     * @param y current cursor y (ignored)
     */
    @Override
    public void mouseMove(float lastX, float lastY, float x, float y) {
    }

    /**
     * Advance the ortho z-index by one {@link ShaderProgram#ORTHO_Z_INCREMENT}.
     */
    @Override
    public void incZIndex() {
        zIndex += ShaderProgram.ORTHO_Z_INCREMENT;
    }

    /**
     * Add an arbitrary delta to the ortho z-index.
     *
     * @param diff signed z-index increment
     */
    @Override
    public void addZIndex(float diff) {
        zIndex += diff;
    }

    /**
     * {@inheritDoc}.
     *
     * @return the current ortho z-index
     */
    @Override
    public float getZIndex() {
        return zIndex;
    }

    /**
     * Place this camera one step in front of {@code camera} on the ortho z axis.
     *
     * @param camera reference camera whose z-index defines the baseline
     */
    @Override
    public void setZIndex(Camera camera) {
        zIndex = camera.getZIndex() + 1;
    }

    /**
     * Reset z-index to zero and far-z cursor one increment in front of
     * {@link ShaderProgram#ORTHO_FAR} for the start of a frame.
     */
    @Override
    public void resetZIndex() {
        zIndex = 0;
        farZIndex = ShaderProgram.ORTHO_FAR - ShaderProgram.ORTHO_Z_INCREMENT;
    }

    /**
     * Step the descending far-z cursor one ortho-z increment toward the near plane.
     */
    @Override
    public void decFarZIndex() {
        farZIndex -= ShaderProgram.ORTHO_Z_INCREMENT;
    }

    /**
     * {@inheritDoc}.
     *
     * @return current depth used by the descending far-z cursor
     */
    @Override
    public float getFarZIndex() {
        return farZIndex;
    }

    /**
     * {@inheritDoc}.
     *
     * @return viewport lower-left x in framebuffer space
     */
    @Override
    public float getScreenOffsetX() {
        return ScreenOffsetX;
    }

    /**
     * {@inheritDoc}.
     *
     * @return viewport lower-left y in framebuffer space
     */
    @Override
    public float getScreenOffsetY() {
        return ScreenOffsetY;
    }

    /**
     * {@inheritDoc}.
     *
     * @return framebufferWidth / screenWidth DPI ratio
     */
    @Override
    public float getScreenWidthRatio() {
        return Platforms.get().getFrameBufferWidth() / ScreenWidth;
    }

    /**
     * {@inheritDoc}.
     *
     * @return framebufferHeight / screenHeight DPI ratio
     */
    @Override
    public float getScreenHeightRatio() {
        return Platforms.get().getFrameBufferHeight() / ScreenHeight;
    }

    /**
     * Normalize a window-space cursor x to framebuffer space, accounting
     * for HiDPI scaling.
     *
     * @param xPos window-space cursor x
     * @return framebuffer-space x
     */
    @Override
    public float getNormalizePosX(float xPos) {
        // Mouse coordinates are already in canvas space (0 to canvas.width/height)
        // Normalize to frame buffer coordinates (0 to
        // frameBufferWidth/frameBufferHeight)
        if (Platforms.get().getWindowWidth() > 0 && Platforms.get().getFrameBufferWidth() > 0) {
            return (xPos / Platforms.get().getWindowWidth()) * Platforms.get().getFrameBufferWidth();
        }
        return xPos;
    }

    /**
     * Flip a window-space cursor y (top-origin) to framebuffer space
     * (bottom-origin) and normalize for HiDPI scaling.
     *
     * @param yPos window-space cursor y
     * @return framebuffer-space y
     */
    @Override
    public float getNormalizePosY(float yPos) {
        // Mouse coordinates are already in canvas space (0 to canvas.width/height)
        // Flip Y coordinate and normalize to frame buffer coordinates
        if (Platforms.get().getWindowHeight() > 0 && Platforms.get().getFrameBufferHeight() > 0) {
            return (1.0f - (yPos / Platforms.get().getWindowHeight())) * Platforms.get().getFrameBufferHeight();
        }
        return Platforms.get().getWindowHeight() - yPos;
    }

    /**
     * Resize the GL viewport, refresh cached view bounds, and rebuild the
     * orthographic projection matrix on every shader.
     *
     * @param x viewport lower-left x
     * @param y viewport lower-left y
     * @param width viewport width
     * @param height viewport height
     */
    @Override
    public void updateView(int x, int y, int width, int height) {
        this.updateViewBounds(x, y, width, height);
        Platforms.gl().viewport(x, y, width, height);
        for (ShaderProgram s : Platforms.gl().getShaders()) {
            if (s.ID < 0) {
                continue;
            }
            s.updateProjectionMatrix(width, height, NUM_1);
        }
    }

    /**
     * Bind a named viewport map to this camera, switch to the {@code active}
     * region, and call {@link #initCamera()} so the point set is framed
     * inside it.
     *
     * @param boundsMap lookup of named viewport regions
     * @param active key in {@code boundsMap} to make the main view bounds
     */
    public void initCamera(Map<String, Bounds> boundsMap, String active) {
        this.namedBounds = boundsMap;
        Bounds b = boundsMap.get(active);
        if (b != null) {
            this.viewBounds.update(b);
            this.updateSize(b.viewWidth, b.viewHeight);
            this.ScreenOffsetX = b.offsetX;
            this.ScreenOffsetY = b.offsetY;
            this.mainViewBounds = b;
        }
        initCamera();
    }

    /**
     * Recalculate the named viewport region {@code key} and switch the GL
     * viewport to it. No-op if no named bounds map was registered.
     *
     * @param key region name in the registered bounds map
     */
    public void updateView(String key) {
        if (namedBounds == null) {
            return;
        }
        Bounds b = namedBounds.get(key);
        if (b == null) {
            return;
        }
        b.recalc();
        this.updateView((int) b.offsetX, (int) b.offsetY, (int) b.viewWidth, (int) b.viewHeight);
    }

    /**
     * Reset the GL viewport to the full framebuffer.
     */
    @Override
    public void resetView() {
        this.updateView(0, 0, Platforms.get().getFrameBufferWidth(), Platforms.get().getFrameBufferHeight());
    }

    private void updateViewBounds(int x, int y, int width, int height) {
        viewBounds.update(x, y, width, height);
        updateSize(width, height);
        ScreenOffsetX = x;
        ScreenOffsetY = y;
    }

    /**
     * {@inheritDoc}.
     *
     * @return the camera's screen-space viewport rectangle
     */
    @Override
    public Bounds getBounds() {
        return viewBounds;
    }

    /**
     * Test whether a screen-space point falls inside this camera's viewport.
     *
     * @param pB screen-space point (origin at lower-left of viewport)
     * @return {@code true} when the point lies in [0, ScreenWidth] × [0, ScreenHeight]
     */
    @Override
    public boolean contains(Vector2f pB) {
        if ((pB.x <= ScreenWidth && pB.x >= 0) &&
                (pB.y <= ScreenHeight && pB.y >= 0)) {
            return true;
        }
        return false;
    }

    /**
     * Project an array of N-D points onto the 2D screen using the current
     * pan and scale.
     *
     * @param points point-space inputs
     * @return parallel array of screen-space {@link Vector2f}s
     */
    public Vector2f[] pointsToScreenSpace(PointND... points) {
        Vector2f[] result = new Vector2f[points.length];
        for (int i = 0; i < points.length; i++) {
            Point2D p = points[i].toPoint2D();
            result[i] = new Vector2f(pointTransformX(p.getX()), pointTransformY(p.getY()));
        }
        return result;
    }

}