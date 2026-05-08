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
     * TODO: document {@code Camera2D}.
     *
     * @param Width TODO: describe
     * @param Height TODO: describe
     * @param ScaleFactor TODO: describe
     * @param ScreenOffsetX TODO: describe
     * @param ScreenOffsetY TODO: describe
     * @param ps TODO: describe
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
     * TODO: document {@code getWidth}.
     *
     * @return TODO: describe
     */
    @Override
    public float getWidth() {
        return ScreenWidth;
    }

    /**
     * TODO: document {@code getHeight}.
     *
     * @return TODO: describe
     */
    @Override
    public float getHeight() {
        return ScreenHeight;
    }

    /**
     * TODO: document {@code updateSize}.
     *
     * @param newWidth TODO: describe
     * @param newHeight TODO: describe
     */
    public void updateSize(float newWidth, float newHeight) {
        ScreenWidth = newWidth;
        ScreenHeight = newHeight;
    }

    /**
     * TODO: document {@code calculateCameraTransform}.
     *
     * @param ps TODO: describe
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
     * TODO: document {@code initCamera}.
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
     * TODO: document {@code reset}.
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
     * TODO: document {@code zoomToKnot}.
     *
     * @param containingKnot TODO: describe
     */
    public void zoomToKnot(Knot containingKnot) {
        zoomToPoints(containingKnot.knotPointsFlattened);
    }

    /**
     * TODO: document {@code zoomToSegment}.
     *
     * @param s TODO: describe
     */
    public void zoomToSegment(Segment s) {
        ArrayList<Knot> points = new ArrayList<>();
        points.add(s.first);
        points.add(s.last);
        zoomToPoints(points);
    }

    /**
     * TODO: document {@code zoomToPoints}.
     *
     * @param list TODO: describe
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
     * TODO: document {@code centerOnPoint}.
     *
     * @param pn TODO: describe
     */
    public void centerOnPoint(PointND pn) {
        float sx = (float) pn.getScreenX();
        float sy = (float) pn.getScreenY();
        PanX += mainViewBounds.viewWidth / 2 - pointTransformX(sx);
        PanY += mainViewBounds.viewHeight / 2 - pointTransformY(sy);
    }

    /**
     * TODO: document {@code pointSpaceLengthToScreenSpace}.
     *
     * @param smallestLength TODO: describe
     * @return TODO: describe
     */
    public double pointSpaceLengthToScreenSpace(double smallestLength) {
        return smallestLength * screenSpaceDistanceOverPointSpaceDistanceRatio;
    }

    /**
     * TODO: document {@code pointTransformX}.
     *
     * @param x TODO: describe
     * @return TODO: describe
     */
    public float pointTransformX(double x) {
        return pointTransformX((float) x);
    }

    // transform from point space to screen space
    /**
     * TODO: document {@code pointTransformX}.
     *
     * @param x TODO: describe
     * @return TODO: describe
     */
    @Override
    public float pointTransformX(float x) {
        return ((((x - minX) * width) / rangeX) + offsetX);
    }

    // transform from point space to screen space
    /**
     * TODO: document {@code pointTransformX}.
     *
     * @param x TODO: describe
     * @param scale TODO: describe
     * @return TODO: describe
     */
    public float pointTransformX(float x, float scale) {
        return ((((x - minX) * (Width * scale)) / rangeX) + offsetX);
    }

    // transform from screen space to point space
    /**
     * TODO: document {@code screenTransformX}.
     *
     * @param x TODO: describe
     * @return TODO: describe
     */
    @Override
    public float screenTransformX(float x) {
        return ((((x) - offsetX) * rangeX) / width) + minX;
    }

    /**
     * TODO: document {@code pointTransformY}.
     *
     * @param y TODO: describe
     * @return TODO: describe
     */
    public float pointTransformY(double y) {
        return pointTransformY((float) y);
    }

    // transform from point space to screen space
    /**
     * TODO: document {@code pointTransformY}.
     *
     * @param y TODO: describe
     * @return TODO: describe
     */
    @Override
    public float pointTransformY(float y) {
        return ((((y - minY) * height) / rangeY) + offsetY);
    }

    // transform from point space to screen space
    /**
     * TODO: document {@code pointTransformY}.
     *
     * @param y TODO: describe
     * @param scale TODO: describe
     * @return TODO: describe
     */
    public float pointTransformY(float y, float scale) {
        return ((((y - minY) * (Height * scale)) / rangeY) + offsetY);
    }

    // transform from screen space to point space
    /**
     * TODO: document {@code screenTransformY}.
     *
     * @param y TODO: describe
     * @return TODO: describe
     */
    @Override
    public float screenTransformY(float y) {
        return ((((y) - offsetY) * rangeY) / height) + minY;
    }

    /**
     * TODO: document {@code scale}.
     *
     * @param delta TODO: describe
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
     * TODO: document {@code move}.
     *
     * @param direction TODO: describe
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
     * TODO: document {@code setShiftMod}.
     *
     * @param SHIFT_MOD TODO: describe
     */
    @Override
    public void setShiftMod(float SHIFT_MOD) {
        this.SHIFT_MOD = SHIFT_MOD;
    }

    /**
     * TODO: document {@code onScroll}.
     *
     * @param b TODO: describe
     * @param delta TODO: describe
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
     * TODO: document {@code drag}.
     *
     * @param d TODO: describe
     * @param e TODO: describe
     */
    @Override
    public void drag(float d, float e) {
        PanX += d;
        PanY += e;
    }

    /**
     * TODO: document {@code getScaleFactor}.
     *
     * @return TODO: describe
     */
    @Override
    public float getScaleFactor() {
        return ScaleFactor;
    }

    /**
     * TODO: document {@code mouseMove}.
     *
     * @param lastX TODO: describe
     * @param lastY TODO: describe
     * @param x TODO: describe
     * @param y TODO: describe
     */
    @Override
    public void mouseMove(float lastX, float lastY, float x, float y) {
    }

    /**
     * TODO: document {@code incZIndex}.
     */
    @Override
    public void incZIndex() {
        zIndex += ShaderProgram.ORTHO_Z_INCREMENT;
    }

    /**
     * TODO: document {@code addZIndex}.
     *
     * @param diff TODO: describe
     */
    @Override
    public void addZIndex(float diff) {
        zIndex += diff;
    }

    /**
     * TODO: document {@code getZIndex}.
     *
     * @return TODO: describe
     */
    @Override
    public float getZIndex() {
        return zIndex;
    }

    /**
     * TODO: document {@code setZIndex}.
     *
     * @param camera TODO: describe
     */
    @Override
    public void setZIndex(Camera camera) {
        zIndex = camera.getZIndex() + 1;
    }

    /**
     * TODO: document {@code resetZIndex}.
     */
    @Override
    public void resetZIndex() {
        zIndex = 0;
        farZIndex = ShaderProgram.ORTHO_FAR - ShaderProgram.ORTHO_Z_INCREMENT;
    }

    /**
     * TODO: document {@code decFarZIndex}.
     */
    @Override
    public void decFarZIndex() {
        farZIndex -= ShaderProgram.ORTHO_Z_INCREMENT;
    }

    /**
     * TODO: document {@code getFarZIndex}.
     *
     * @return TODO: describe
     */
    @Override
    public float getFarZIndex() {
        return farZIndex;
    }

    /**
     * TODO: document {@code getScreenOffsetX}.
     *
     * @return TODO: describe
     */
    @Override
    public float getScreenOffsetX() {
        return ScreenOffsetX;
    }

    /**
     * TODO: document {@code getScreenOffsetY}.
     *
     * @return TODO: describe
     */
    @Override
    public float getScreenOffsetY() {
        return ScreenOffsetY;
    }

    /**
     * TODO: document {@code getScreenWidthRatio}.
     *
     * @return TODO: describe
     */
    @Override
    public float getScreenWidthRatio() {
        return Platforms.get().getFrameBufferWidth() / ScreenWidth;
    }

    /**
     * TODO: document {@code getScreenHeightRatio}.
     *
     * @return TODO: describe
     */
    @Override
    public float getScreenHeightRatio() {
        return Platforms.get().getFrameBufferHeight() / ScreenHeight;
    }

    /**
     * TODO: document {@code getNormalizePosX}.
     *
     * @param xPos TODO: describe
     * @return TODO: describe
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
     * TODO: document {@code getNormalizePosY}.
     *
     * @param yPos TODO: describe
     * @return TODO: describe
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
     * TODO: document {@code updateView}.
     *
     * @param x TODO: describe
     * @param y TODO: describe
     * @param width TODO: describe
     * @param height TODO: describe
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
     * TODO: document {@code initCamera}.
     *
     * @param boundsMap TODO: describe
     * @param active TODO: describe
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
     * TODO: document {@code updateView}.
     *
     * @param key TODO: describe
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
     * TODO: document {@code resetView}.
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
     * TODO: document {@code getBounds}.
     *
     * @return TODO: describe
     */
    @Override
    public Bounds getBounds() {
        return viewBounds;
    }

    /**
     * TODO: document {@code contains}.
     *
     * @param pB TODO: describe
     * @return TODO: describe
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
     * TODO: document {@code pointsToScreenSpace}.
     *
     * @param points TODO: describe
     * @return TODO: describe
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