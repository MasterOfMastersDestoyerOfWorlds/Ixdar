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
    public int offsetx;
    public int offsety;
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
        double meanX = 0, meanY = 0, numPoints = 0;
        for (PointND pn : ps) {
            if (!pn.isDummyNode()) {
                Point2D p = pn.toPoint2D();

                if (p.getX() < minX) {
                    minX = p.getX();
                }
                if (p.getY() < minY) {
                    minY = p.getY();
                }
                meanX += p.getX();
                if (p.getX() > maxX) {
                    maxX = p.getX();
                }
                if (p.getY() > maxY) {
                    maxY = p.getY();
                }
                meanY += p.getY();
                numPoints++;
            }
        }
        meanX = meanX / numPoints;
        meanY = meanY / numPoints;
        rangeX = maxX - minX;
        rangeY = maxY - minY;
        height = Height * ScaleFactor;
        width = Width * ScaleFactor;
        offsetx = 0 + (int) PanX;
        offsety = 0 + (int) PanY;

        if (rangeX > rangeY) {
            offsety += (((double) rangeY) / ((double) rangeX) * height / 2);
            rangeY = rangeX;

        } else {
            offsetx += (((double) rangeX) / ((double) rangeY) * width / 2);
            rangeX = rangeY;
        }
    }

    public double transformX(double x) {
        return ((x - minX) * (width) / rangeX + offsetx) / 1.5;
    }

    public double transformY(double y) {
        return ((y - minY) * (height) / rangeY + offsety) / 1.5;
    }
}