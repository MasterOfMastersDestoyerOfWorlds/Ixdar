package shell.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;

import javax.swing.JComponent;

import shell.BalanceMap;
import shell.PointND;
import shell.PointSet;
import shell.Shell;
import shell.cuts.CutMatch;
import shell.exceptions.SegmentBalanceException;
import shell.knot.Knot;
import shell.knot.Point;
import shell.knot.Segment;
import shell.knot.VirtualPoint;

public class Drawing {

    public static void drawCutMatch(JComponent frame, Graphics2D g2, SegmentBalanceException sbe, int lineThickness,
            PointSet ps, Camera camera) {

        BasicStroke stroke = new BasicStroke(lineThickness);

        BasicStroke doubleStroke = new BasicStroke(lineThickness * 2);
        g2.setStroke(stroke);

        // Draw x 1

        Font font = new Font("San-Serif", Font.PLAIN, 20);
        g2.setFont(font);

        g2.setColor(Color.RED);

        double minX = java.lang.Double.MAX_VALUE, minY = java.lang.Double.MAX_VALUE, maxX = 0, maxY = 0;
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
        double rangeX = maxX - minX, rangeY = maxY - minY;
        double height = camera.Height * camera.ScaleFactor,
                width = camera.Width * camera.ScaleFactor;
        int offsetx = 100 + (int) camera.PanX, offsety = 100 + (int) camera.PanY;

        if (rangeX > rangeY) {
            offsety += (((double) rangeY) / ((double) rangeX) * height / 2);
            rangeY = rangeX;

        } else {
            offsetx += (((double) rangeX) / ((double) rangeY) * width / 2);
            rangeX = rangeY;
        }

        double[] firstCoords = new double[2];
        double[] lastCoords = new double[2];
        double[] midCoords = new double[2];

        Point2D first = ((Point) sbe.cut1.first).p.toPoint2D();
        Point2D last = ((Point) sbe.cut1.last).p.toPoint2D();

        firstCoords[0] = ((first.getX() - minX) * (width) / rangeX + offsetx) / 1.5;
        firstCoords[1] = ((first.getY() - minY) * (height) / rangeY + offsety) / 1.5;

        lastCoords[0] = ((last.getX() - minX) * (width) / rangeX + offsetx) / 1.5;
        lastCoords[1] = ((last.getY() - minY) * (height) / rangeY + offsety) / 1.5;
        midCoords[0] = (firstCoords[0] + lastCoords[0]) / 2.0 - 8;
        midCoords[1] = (firstCoords[1] + lastCoords[1]) / 2.0 + 8;
        g2.drawString("X", (int) midCoords[0], (int) midCoords[1]);

        g2.setColor(new Color(210, 105, 30));
        // Draw x 2
        first = ((Point) sbe.cut2.first).p.toPoint2D();
        last = ((Point) sbe.cut2.last).p.toPoint2D();

        firstCoords[0] = ((first.getX() - minX) * (width) / rangeX + offsetx) / 1.5;
        firstCoords[1] = ((first.getY() - minY) * (height) / rangeY + offsety) / 1.5;

        lastCoords[0] = ((last.getX() - minX) * (width) / rangeX + offsetx) / 1.5;
        lastCoords[1] = ((last.getY() - minY) * (height) / rangeY + offsety) / 1.5;
        midCoords[0] = (firstCoords[0] + lastCoords[0]) / 2.0 - 8;
        midCoords[1] = (firstCoords[1] + lastCoords[1]) / 2.0 + 8;

        g2.drawString("X", (int) midCoords[0], (int) midCoords[1]);

        // Draw external segment 1

        Point2D knotPoint1 = ((Point) sbe.ex1.getKnotPoint(sbe.topKnot.knotPointsFlattened)).p.toPoint2D();

        firstCoords[0] = ((knotPoint1.getX() - minX) * (width) / rangeX + offsetx) / 1.5;
        firstCoords[1] = ((knotPoint1.getY() - minY) * (height) / rangeY + offsety) / 1.5;

        g2.setColor(Color.GREEN);

        g2.draw(new Ellipse2D.Double(firstCoords[0] - 5, firstCoords[1] - 5, 10, 10));
        drawSegment(g2, minX, minY, rangeX, rangeY, height, width, offsetx, offsety, firstCoords, lastCoords, sbe.ex1);

        // Draw external segment 2

        Point2D knotPoint2 = ((Point) sbe.ex2.getKnotPoint(sbe.topKnot.knotPointsFlattened)).p.toPoint2D();

        firstCoords[0] = ((knotPoint2.getX() - minX) * (width) / rangeX + offsetx) / 1.5;
        firstCoords[1] = ((knotPoint2.getY() - minY) * (height) / rangeY + offsety) / 1.5;

        g2.setColor(Color.GREEN);

        g2.draw(new Ellipse2D.Double(firstCoords[0] - 5, firstCoords[1] - 5, 10, 10));
        drawSegment(g2, minX, minY, rangeX, rangeY, height, width, offsetx, offsety, firstCoords, lastCoords, sbe.ex2);

        g2.setColor(new Color(238, 130, 238));
        BalanceMap bm = sbe.c.balanceMap;
        for (Segment externalMatch : bm.externalMatches) {
            if (externalMatch.equals(sbe.ex1) || externalMatch.equals(sbe.ex2)) {
                continue;
            }
            VirtualPoint kp = externalMatch.last;
            if (bm.knot.contains(externalMatch.first)) {
                kp = externalMatch.first;
            }
            Point2D kp2d = ((Point) kp).p.toPoint2D();
            firstCoords[0] = ((kp2d.getX() - minX) * (width) / rangeX + offsetx) / 1.5;
            firstCoords[1] = ((kp2d.getY() - minY) * (height) / rangeY + offsety) / 1.5;
            g2.draw(new Ellipse2D.Double(firstCoords[0] - 5, firstCoords[1] - 5, 10, 10));
            drawSegment(g2, minX, minY, rangeX, rangeY, height, width, offsetx, offsety, firstCoords, lastCoords,
                    externalMatch);
        }

        // Draw Cuts and Matches
        for (CutMatch cutMatch : sbe.cutMatchList.cutMatches) {

            // Draw Matches
            g2.setColor(Color.CYAN);
            g2.setStroke(stroke);
            for (Segment s : cutMatch.matchSegments) {
                drawSegment(g2, minX, minY, rangeX, rangeY, height, width, offsetx, offsety, firstCoords, lastCoords,
                        s);
            }

            // Draw Cuts
            g2.setColor(Color.ORANGE);
            g2.setStroke(doubleStroke);
            for (Segment s : cutMatch.cutSegments) {
                drawSegment(g2, minX, minY, rangeX, rangeY, height, width, offsetx, offsety, firstCoords, lastCoords,
                        s);
            }
            // Draw SubKnot
            Shell result = new Shell();
            for (VirtualPoint p : cutMatch.knot.knotPoints) {
                result.add(((Point) p).p);
            }
            Drawing.drawPath(frame, g2, Shell.toPath(result), lineThickness, Color.lightGray, ps, true, false, false,
                    true, camera);

        }

    }

    private static void drawSegment(Graphics2D g2, double minX, double minY, double rangeX, double rangeY,
            double height,
            double width, int offsetx, int offsety, double[] firstCoords, double[] lastCoords, Segment s) {
        Point2D first;
        Point2D last;
        if (s.first.isKnot) {
            first = ((Point) ((Knot) s.first).knotPoints.get(0)).p.toPoint2D();
        } else {
            first = ((Point) s.first).p.toPoint2D();
        }
        if (s.last.isKnot) {
            last = ((Point) ((Knot) s.last).knotPoints.get(0)).p.toPoint2D();
        } else {
            last = ((Point) s.last).p.toPoint2D();
        }

        firstCoords[0] = ((first.getX() - minX) * (width) / rangeX + offsetx) / 1.5;
        firstCoords[1] = ((first.getY() - minY) * (height) / rangeY + offsety) / 1.5;

        lastCoords[0] = ((last.getX() - minX) * (width) / rangeX + offsetx) / 1.5;
        lastCoords[1] = ((last.getY() - minY) * (height) / rangeY + offsety) / 1.5;

        g2.drawLine((int) firstCoords[0], (int) firstCoords[1], (int) lastCoords[0], (int) lastCoords[1]);
    }

    /**
     * Draws the tsp path of the pointset ps
     * 
     * @param frame
     * @param g2
     * @param path
     * @param color
     * @param ps
     * @param drawLines
     * @param drawCircles
     * @param drawNumbers
     */
    public static void drawPath(JComponent frame, Graphics2D g2, Path2D path, int lineThickness, Color color,
            PointSet ps,
            boolean drawLines, boolean drawCircles, boolean drawNumbers, boolean dashed, Camera camera) {
        g2.setPaint(color);

        BasicStroke stroke = new BasicStroke(lineThickness);
        if (dashed) {
            stroke = new BasicStroke(lineThickness, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[] { 9 },
                    0);
        }
        g2.setStroke(stroke);

        GeneralPath scaledpath = new GeneralPath();
        double minX = java.lang.Double.MAX_VALUE, minY = java.lang.Double.MAX_VALUE, maxX = 0, maxY = 0;
        boolean first = true;
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
        PathIterator pi = path.getPathIterator(null);
        Point2D start = null;
        int count = 0, offsetx = 100 + (int) camera.PanX, offsety = 100 + (int) camera.PanY;

        double height = camera.Height * camera.ScaleFactor,
                width = camera.Width * camera.ScaleFactor;

        // g2.drawLine((int)((width+offsetx)/1.5), (int)0, (int)((width+offsetx)/1.5),
        // (int)((height+offsety)/1.5));
        // g2.drawLine(0, (int)((height+offsety)/1.5), (int)((width+offsetx)/1.5),
        // (int)((height+offsety)/1.5));
        double rangeX = maxX - minX, rangeY = maxY - minY;
        if (rangeX > rangeY) {
            offsety += (((double) rangeY) / ((double) rangeX) * height / 2);
            rangeY = rangeX;

        } else {
            offsetx += (((double) rangeX) / ((double) rangeY) * width / 2);
            rangeX = rangeY;
        }
        while (!pi.isDone()) {
            double[] coords = new double[2];
            pi.currentSegment(coords);
            pi.next();

            coords[0] = ((coords[0] - minX) * (width) / rangeX + offsetx) / 1.5;
            coords[1] = ((coords[1] - minY) * (height) / rangeY + offsety) / 1.5;
            if (drawCircles) {
                g2.draw(new Ellipse2D.Double(coords[0] - 5, coords[1] - 5, 10, 10));
            }
            if (drawNumbers) {
                Font font = new Font("Serif", Font.PLAIN, 12);
                g2.setFont(font);

                g2.drawString("" + count, (int) coords[0] - 5, (int) coords[1] - 5);
            }
            if (first) {
                scaledpath.moveTo(coords[0], coords[1]);
                first = false;
                start = new Point2D.Double(coords[0], coords[1]);
            } else {
                scaledpath.lineTo(coords[0], coords[1]);
            }

            count++;
        }
        scaledpath.lineTo(start.getX(), start.getY());
        if (drawLines) {
            g2.draw(scaledpath);
        }

    }
}
