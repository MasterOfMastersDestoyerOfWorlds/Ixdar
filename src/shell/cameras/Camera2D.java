package shell.cameras;

import java.awt.geom.Point2D;
import java.util.ArrayList;

import org.joml.Vector2f;

import shell.PointSet;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.point.PointND;
import shell.render.Clock;
import shell.render.shaders.ShaderProgram;
import shell.ui.Canvas3D;
import shell.ui.IxdarWindow;
import shell.ui.main.Main;
import shell.ui.tools.Tool;
import static org.lwjgl.opengl.GL11.glViewport;

public class Camera2D implements Camera {

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
    private float height;
    public float zIndex;
    public float farZIndex;
    float width;
    private float SHIFT_MOD = 1.0f;
    public float ScreenOffsetY;
    public float ScreenOffsetX;
    public Bounds viewBounds;
    public double screenSpaceDistanceOverPointSpaceDistanceRatio = -1;

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
        this.viewBounds = new Bounds(ScreenOffsetX, ScreenOffsetY, Width, Height);
        this.ps = ps;
        zIndex = 0;

    }

    @Override
    public float getWidth() {
        return ScreenWidth;
    }

    @Override
    public float getHeight() {
        return ScreenHeight;
    }

    public void updateSize(float newWidth, float newHeight) {
        ScreenWidth = newWidth;
        ScreenHeight = newHeight;
    }

    @Override
    public void calculateCameraTransform() {
        PointSet ps = Main.retTup.ps;
        minX = java.lang.Float.MAX_VALUE;
        minY = java.lang.Float.MAX_VALUE;
        maxX = 0;
        maxY = 0;
        if (ps.size() == 0) {
            minX = -10;
            minY = -10;
            maxX = 10;
            maxY = 10;
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

    public void initCamera() {
        minX = java.lang.Float.MAX_VALUE;
        minY = java.lang.Float.MAX_VALUE;
        maxX = 0;
        maxY = 0;
        boolean empty = ps.size() == 0;
        if (empty) {
            minX = -10;
            minY = -10;
            maxX = 10;
            maxY = 10;
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

    @Override
    public void reset() {
        if (Main.tool != null) {
            if (Main.showHoverKnot) {
                zoomToKnot(Main.hoverKnot);
                return;
            }
            Tool tool = Main.tool;
            Knot selectedKnot = tool.selectedKnot();
            if (selectedKnot != null) {
                zoomToKnot(selectedKnot);
                return;
            }
        }
        offsetX = 0;
        offsetY = 0;

        float ScaleFactorX = InitialScale + InitialScale * (Main.MAIN_VIEW_WIDTH - Width) / Width;
        float ScaleFactorY = InitialScale + InitialScale * (Main.MAIN_VIEW_HEIGHT - Height) / Height;
        float aspectRatio = (maxX - minX) / (maxY - minY);
        if (aspectRatio >= 1) {
            ScaleFactor = ScaleFactorX;
            width = Width * ScaleFactor;
            height = Height * ScaleFactor;
            float rangeX = (Math.abs(pointTransformX(maxX) - pointTransformX(minX)));
            float rangeY = (Math.abs(pointTransformY(maxY) - pointTransformY(minY)));
            if (rangeY > Main.MAIN_VIEW_HEIGHT) {

                ScaleFactor = ScaleFactorY * aspectRatio;
                width = Width * ScaleFactor;
                height = Height * ScaleFactor;
                rangeX = (Math.abs(pointTransformX(maxX) - pointTransformX(minX)));
                rangeY = (Math.abs(pointTransformY(maxY) - pointTransformY(minY)));
            }
            offsetX += (Main.MAIN_VIEW_WIDTH - rangeX) / 2;
            offsetY += (Main.MAIN_VIEW_HEIGHT - rangeY) / 2;
        } else {
            ScaleFactor = ScaleFactorY;
            width = Width * ScaleFactor;
            height = Height * ScaleFactor;
            float rangeX = (Math.abs(pointTransformX(maxX) - pointTransformX(minX)));
            float rangeY = (Math.abs(pointTransformY(maxY) - pointTransformY(minY)));
            if (rangeX > Main.MAIN_VIEW_WIDTH) {
                ScaleFactor = ScaleFactorX * (maxY - minY) / (maxX - minX);
                width = Width * ScaleFactor;
                height = Height * ScaleFactor;
                rangeX = (Math.abs(pointTransformX(maxX) - pointTransformX(minX)));
                rangeY = (Math.abs(pointTransformY(maxY) - pointTransformY(minY)));
            }
            offsetX += (Main.MAIN_VIEW_WIDTH - rangeX) / 2;
            offsetY += (Main.MAIN_VIEW_HEIGHT - rangeY) / 2;
        }
        PanX = offsetX;
        PanY = offsetY;
        Point2D origin = new Point2D.Double(pointTransformX(0.0), pointTransformY(0.0));
        Point2D p2 = new Point2D.Double(pointTransformX(1.0), pointTransformY(1.0));
        screenSpaceDistanceOverPointSpaceDistanceRatio = origin.distance(p2) / Math.sqrt(2);

    }

    public void zoomToKnot(Knot containingKnot) {
        zoomToPoints(containingKnot.knotPointsFlattened);
    }

    public void zoomToSegment(Segment s) {
        ArrayList<Knot> points = new ArrayList<>();
        points.add(s.first);
        points.add(s.last);
        zoomToPoints(points);
    }

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
                + InitialScale * widthRatio * (Main.MAIN_VIEW_WIDTH - Width) / Width;
        float heightRatio = Math.abs(pointTransformY(maxY) - pointTransformY(minY))
                / Math.abs(pointTransformY(knotMaxY) - pointTransformY(knotMinY));
        float ScaleFactorY = InitialScale * heightRatio
                + InitialScale * heightRatio * (Main.MAIN_VIEW_HEIGHT - Height) / Height;
        float aspectRatio = (knotMaxX - knotMinX) / (knotMaxY - knotMinY);
        if (aspectRatio >= 1) {
            ScaleFactor = ScaleFactorX;
            width = Width * ScaleFactor;
            height = Height * ScaleFactor;
            float rangeX = (Math.abs(pointTransformX(knotMaxX) - pointTransformX(knotMinX)));
            float rangeY = (Math.abs(pointTransformY(knotMaxY) - pointTransformY(knotMinY)));
            if (rangeY > Main.MAIN_VIEW_HEIGHT) {

                ScaleFactor = ScaleFactorY;
                width = Width * ScaleFactor;
                height = Height * ScaleFactor;
                rangeX = (Math.abs(pointTransformX(knotMaxX) - pointTransformX(knotMinX)));
                rangeY = (Math.abs(pointTransformY(knotMaxY) - pointTransformY(knotMinY)));
            }
            offsetX += (Main.MAIN_VIEW_WIDTH - rangeX) / 2;
            offsetY += (Main.MAIN_VIEW_HEIGHT - rangeY) / 2;
        } else {
            ScaleFactor = ScaleFactorY;
            width = Width * ScaleFactor;
            height = Height * ScaleFactor;
            float rangeX = (Math.abs(pointTransformX(knotMaxX) - pointTransformX(knotMinX)));
            float rangeY = (Math.abs(pointTransformY(knotMaxY) - pointTransformY(knotMinY)));
            if (rangeX > Main.MAIN_VIEW_WIDTH) {
                ScaleFactor = ScaleFactorX;
                width = Width * ScaleFactor;
                height = Height * ScaleFactor;
                rangeX = (Math.abs(pointTransformX(knotMaxX) - pointTransformX(knotMinX)));
                rangeY = (Math.abs(pointTransformY(knotMaxY) - pointTransformY(knotMinY)));
            }
            offsetX += (Main.MAIN_VIEW_WIDTH - rangeX) / 2;
            offsetY += (Main.MAIN_VIEW_HEIGHT - rangeY) / 2;
        }
        PanX = offsetX - Math.abs(pointTransformX(knotMinX) - pointTransformX(minX));
        PanY = offsetY - Math.abs(pointTransformY(knotMinY) - pointTransformY(minY));
    }

    public void centerOnPoint(PointND pn) {
        Point2D p = pn.toPoint2D();
        PanX += Main.MAIN_VIEW_WIDTH / 2 - pointTransformX(p.getX());
        PanY += Main.MAIN_VIEW_HEIGHT / 2 - pointTransformY(p.getY());
    }

    public double pointSpaceLengthToScreenSpace(double smallestLength) {
        return smallestLength * screenSpaceDistanceOverPointSpaceDistanceRatio;
    }

    public float pointTransformX(double x) {
        return pointTransformX((float) x);
    }

    // transform from point space to screen space
    @Override
    public float pointTransformX(float x) {
        return ((((x - minX) * width) / rangeX) + offsetX);
    }

    // transform from point space to screen space
    public float pointTransformX(float x, float scale) {
        return ((((x - minX) * (Width * scale)) / rangeX) + offsetX);
    }

    // transform from screen space to point space
    @Override
    public float screenTransformX(float x) {
        return ((((x) - offsetX) * rangeX) / width) + minX;
    }

    public float pointTransformY(double y) {
        return pointTransformY((float) y);
    }

    // transform from point space to screen space
    @Override
    public float pointTransformY(float y) {
        return ((((y - minY) * height) / rangeY) + offsetY);
    }

    // transform from point space to screen space
    public float pointTransformY(float y, float scale) {
        return ((((y - minY) * (Height * scale)) / rangeY) + offsetY);
    }

    // transform from screen space to point space
    @Override
    public float screenTransformY(float y) {
        return ((((y) - offsetY) * rangeY) / height) + minY;
    }

    public void scale(float delta) {

        if (ScaleFactor + delta < 0.1) {
            return;
        }
        float newScaleY = ScaleFactor + delta;
        float midXPointSpace = screenTransformX(((float) ScreenWidth) / 2f);
        float midYPointSpace = screenTransformY(((float) ScreenHeight) / 2f);
        float midXNewScale = pointTransformX(midXPointSpace, newScaleY);
        float midYNewScale = pointTransformY(midYPointSpace, newScaleY);
        PanX += (((float) ScreenWidth) / 2f) - midXNewScale;
        PanY += (((float) ScreenHeight) / 2f) - midYNewScale;
        ScaleFactor += delta;
    }

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

    @Override
    public void setShiftMod(float SHIFT_MOD) {
        this.SHIFT_MOD = SHIFT_MOD;
    }

    @Override
    public void zoom(boolean b) {
        float d = (float) Clock.deltaTime() * ScaleFactor;
        if (b) {
            scale(ZOOM_SPEED * SHIFT_MOD * d);
        } else {
            scale(-1 * ZOOM_SPEED * SHIFT_MOD * d);
        }
    }

    @Override
    public void drag(float d, float e) {
        PanX += d;
        PanY += e;
    }

    @Override
    public float getScaleFactor() {
        return ScaleFactor;
    }

    @Override
    public void mouseMove(float lastX, float lastY, float x, float y) {
    }

    @Override
    public void incZIndex() {
        zIndex += 0.01;
    }

    @Override
    public void addZIndex(float diff) {
        zIndex += diff;
    }

    @Override
    public float getZIndex() {
        return zIndex;
    }

    @Override
    public void setZIndex(Camera camera) {
        zIndex = camera.getZIndex() + 1;
    }

    @Override
    public void resetZIndex() {
        zIndex = 0;
        farZIndex = ShaderProgram.ORTHO_FAR - 0.01f;
    }

    @Override
    public void decFarZIndex() {
        farZIndex -= 0.01;
    }

    @Override
    public float getFarZIndex() {
        return farZIndex;
    }

    @Override
    public float getScreenOffsetX() {
        return ScreenOffsetX;
    }

    @Override
    public float getScreenOffsetY() {
        return ScreenOffsetY;
    }

    @Override
    public float getScreenWidthRatio() {
        return Canvas3D.frameBufferWidth / ScreenWidth;
    }

    @Override
    public float getScreenHeightRatio() {
        return Canvas3D.frameBufferHeight / ScreenHeight;
    }

    @Override
    public float getNormalizePosX(float xPos) {
        return xPos;
    }

    @Override
    public float getNormalizePosY(float yPos) {
        return IxdarWindow.getHeight() - (yPos);
    }

    @Override
    public void updateView(int x, int y, int width, int height) {
        this.updateViewBounds(x, y, width, height);
        glViewport(x, y, width, height);
        for (ShaderProgram s : Canvas3D.shaders) {
            s.updateProjectionMatrix(width, height, 1f);
        }
    }

    @Override
    public void resetView() {
        this.updateView(0, 0, Canvas3D.frameBufferWidth, Canvas3D.frameBufferHeight);
    }

    private void updateViewBounds(int x, int y, int width, int height) {
        viewBounds.update(x, y, width, height);
        updateSize(width, height);
        ScreenOffsetX = x;
        ScreenOffsetY = y;
    }

    @Override
    public Bounds getBounds() {
        return viewBounds;
    }

    @Override
    public boolean contains(Vector2f pB) {
        if ((pB.x <= ScreenWidth && pB.x >= 0) &&
                (pB.y <= ScreenHeight && pB.y >= 0)) {
            return true;
        }
        return false;
    }

}