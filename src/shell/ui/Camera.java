package shell.ui;

import java.awt.geom.Point2D;

import shell.PointND;
import shell.PointSet;

public class Camera {

    public int Width, Height;
    public double ScaleFactor;
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
    private double width;

    public Camera(int Height, int Width, double ScaleFactor, double PanX, double PanY, PointSet ps) {
        this.Height = Height;
        this.Width = Width;
        this.ScaleFactor = ScaleFactor;
        this.PanX = PanX;
        this.PanY = PanY;
        this.ps = ps;

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
        rangeX = maxX - minX;
        rangeY = maxY - minY;
        height = Height * ScaleFactor;
        width = Width * ScaleFactor;
        offsetX = 0 + (int) PanX;
        offsetY = 0 + (int) PanY;

        if (rangeX > rangeY) {
            offsetY += (((double) rangeY) / ((double) rangeX) * height / 2);
            rangeY = rangeX;

        } else {
            offsetX += (((double) rangeX) / ((double) rangeY) * width / 2);
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

        rangeX = maxX - minX;
        rangeY = maxY - minY;
        if (rangeX > rangeY) {
            offsetX += (((double) rangeY) / ((double) rangeX) * height / 2);
            rangeY = rangeX;

        } else {
            offsetY += (((double) rangeX) / ((double) rangeY) * width / 2);
            rangeX = rangeY;
        }
        PanX = 50 - offsetY;
        PanY = 50 - offsetX;
        defaultPanX = PanX;
        defaultPanY = PanY;
    }

    public double transformX(double x) {
        return ((((x - minX) * width) / rangeX) + offsetX) / 1.5;
    }

    public double invertTransformX(double x) {
        return ((((x * 1.5) - offsetX) * rangeX) / width) + minX;
    }

    public double transformY(double y) {
        return ((((y - minY) * height) / rangeY) + offsetY) / 1.5;
    }

    public double invertTransformY(double y) {
        return ((((y * 1.5) - offsetY) * rangeY) / height) + minY;
    }

}