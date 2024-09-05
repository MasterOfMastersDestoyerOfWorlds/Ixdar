package shell.ui;

import java.awt.geom.Point2D;

import shell.PointND;
import shell.PointSet;

public class Camera {

    public double ZOOM_SPEED = 0.005;
    public double PAN_SPEED = 0.65;
    public int Width, Height;
    public int ScreenWidth, ScreenHeight;
    public double ScaleFactor;
    public double InitialScale;
    public double PanX;
    public double PanY;
    public double defaultPanX;
    public double defaultPanY;
    public int offsetX;
    public int offsetY;
    public double rangeX;
    public double rangeY;
    public PointSet ps;
    private double minX;
    private double minY;
    private double maxX;
    private double maxY;
    private double height;
    double width;

    public Camera(int Height, int Width, double ScaleFactor, double PanX, double PanY, PointSet ps) {
        this.Height = Height;
        this.Width = Width;
        this.height = Height * ScaleFactor;
        this.width = Width * ScaleFactor;
        this.InitialScale = ScaleFactor;
        this.ScaleFactor = ScaleFactor;
        this.PanX = PanX;
        this.PanY = PanY;
        this.ps = ps;

    }

    public void updateSize(int newWidth, int newHeight) {
        ScreenWidth = newWidth;
        ScreenHeight = newHeight;
    }

    public void calculateCameraTransform() {
        minX = java.lang.Double.MAX_VALUE;
        minY = java.lang.Double.MAX_VALUE;
        maxX = 0;
        maxY = 0;
        for (PointND pn : ps) {
            if (!pn.isDummyNode()) {
                Point2D p = pn.toPoint2D();

                if (p.getX() < minX) {
                    minX = p.getX();
                }
                if (p.getY() < minY) {
                    minY = p.getY();
                }
                if (p.getX() > maxX) {
                    maxX = p.getX();
                }
                if (p.getY() > maxY) {
                    maxY = p.getY();
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
        minX = java.lang.Double.MAX_VALUE;
        minY = java.lang.Double.MAX_VALUE;
        maxX = 0;
        maxY = 0;
        for (PointND pn : ps) {
            if (!pn.isDummyNode()) {
                Point2D p = pn.toPoint2D();

                if (p.getX() < minX) {
                    minX = p.getX();
                }
                if (p.getY() < minY) {
                    minY = p.getY();
                }
                if (p.getX() > maxX) {
                    maxX = p.getX();
                }
                if (p.getY() > maxY) {
                    maxY = p.getY();
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

        offsetX += (Width - (Math.abs(transformX(maxX) - transformX(minX)))) / 2;
        offsetY += (Height - (Math.abs(transformY(maxY) - transformY(minY)))) / 2;
        PanX = offsetX;
        PanY = offsetY;
        defaultPanX = PanX;
        defaultPanY = PanY;
    }

    // transform from point space to screen space
    public double transformX(double x) {
        return ((((x - minX) * width) / rangeX) + offsetX);
    }

    // transform from point space to screen space
    public double transformX(double x, double scale) {
        return ((((x - minX) * (Width * scale)) / rangeX) + offsetX);
    }

    // transform from screen space to point space
    public double invertTransformX(double x) {
        return ((((x) - offsetX) * rangeX) / width) + minX;
    }

    // transform from point space to screen space
    public double transformY(double y) {
        return ((((y - minY) * height) / rangeY) + offsetY);
    }

    // transform from point space to screen space
    public double transformY(double x, double scale) {
        return ((((x - minY) * (Height * scale)) / rangeY) + offsetY);
    }

    // transform from screen space to point space
    public double invertTransformY(double y) {
        return ((((y) - offsetY) * rangeY) / height) + minY;
    }

    public void scale(double delta) {
        double newScaleY = ScaleFactor + delta;
        double midXPointSpace = invertTransformX(((double) ScreenWidth) / 2);
        double midYPointSpace = invertTransformY(((double) ScreenHeight) / 2);
        double midXNewScale = transformX(midXPointSpace, newScaleY);
        double midYNewScale = transformY(midYPointSpace, newScaleY);

        PanX += (((double) ScreenWidth) / 2) - midXNewScale;
        PanY += (((double) ScreenHeight) / 2) - midYNewScale;
        ScaleFactor += delta;
    }

}