package shell.cameras;

import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;

import shell.Main;
import shell.PointND;
import shell.PointSet;
import shell.render.shaders.ShaderProgram;

public class Camera2D implements Camera {

    public float ZOOM_SPEED = 0.01f;
    public float PAN_SPEED = 10f;
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
    private float minX;
    private float minY;
    private float maxX;
    private float maxY;
    private float height;
    public float zIndex;
    float width;
    private float SHIFT_MOD = 1.0f;

    public Camera2D(int Height, int Width, float ScaleFactor, float PanX, float PanY, PointSet ps) {
        this.Height = Height;
        this.Width = Width;
        this.height = Height * ScaleFactor;
        this.width = Width * ScaleFactor;
        this.InitialScale = ScaleFactor;
        this.ScaleFactor = ScaleFactor;
        this.PanX = PanX;
        this.PanY = PanY;
        this.ps = ps;
        zIndex = 0;

    }

    public void updateSize(float newWidth, float newHeight) {
        ScreenWidth = newWidth;
        ScreenHeight = newHeight;
    }

    public void calculateCameraTransform() {
        PointSet ps = Main.retTup.ps;
        minX = java.lang.Float.MAX_VALUE;
        minY = java.lang.Float.MAX_VALUE;
        maxX = 0;
        maxY = 0;
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
    }

    public float pointTransformX(double x) {
        return pointTransformX((float) x);
    }

    // transform from point space to screen space
    public float pointTransformX(float x) {
        return ((((x - minX) * width) / rangeX) + offsetX);
    }

    // transform from point space to screen space
    public float pointTransformX(float x, float scale) {
        return ((((x - minX) * (Width * scale)) / rangeX) + offsetX);
    }

    // transform from screen space to point space
    public float screenTransformX(float x) {
        return ((((x) - offsetX) * rangeX) / width) + minX;
    }

    public float pointTransformY(double y) {
        return pointTransformY((float) y);
    }

    // transform from point space to screen space
    public float pointTransformY(float y) {
        return ((((y - minY) * height) / rangeY) + offsetY);
    }

    // transform from point space to screen space
    public float pointTransformY(float y, float scale) {
        return ((((y - minY) * (Height * scale)) / rangeY) + offsetY);
    }

    // transform from screen space to point space
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
        if (Math.abs(pointTransformX(midXPointSpace) - (((float) ScreenWidth) / 2f)) > 0.01) {
            float z = 1;

        }
    }

    @Override
    public void reset() {
        ScaleFactor = InitialScale;
        PanX = defaultPanX;
        PanY = defaultPanY;
    }

    @Override
    public void move(Direction direction) {
        switch (direction) {
            case FORWARD:
                PanY += PAN_SPEED * SHIFT_MOD;
                break;
            case LEFT:
                PanX -= PAN_SPEED * SHIFT_MOD;
                break;
            case BACKWARD:
                PanY -= PAN_SPEED * SHIFT_MOD;
                break;
            case RIGHT:
                PanX += PAN_SPEED * SHIFT_MOD;
                break;

        }
    }

    @Override
    public void setShiftMod(float SHIFT_MOD) {
        this.SHIFT_MOD = SHIFT_MOD;
    }

    @Override
    public void zoom(boolean b) {
        if (b) {
            scale(ZOOM_SPEED * SHIFT_MOD);
        } else {
            scale(-1 * ZOOM_SPEED * SHIFT_MOD);
        }
    }

    @Override
    public void drag(float d, float e) {
        PanX += d;
        PanY += e;
    }

    @Override
    public void mouseMove(float lastX, float lastY, MouseEvent e) {
    }

    @Override
    public void incZIndex() {
        zIndex++;
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
    }
}