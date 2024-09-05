package shell.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;

import javax.swing.JComponent;

import shell.BalanceMap;
import shell.Main;
import shell.PointND;
import shell.PointSet;
import shell.cuts.CutMatch;
import shell.cuts.CutMatchList;
import shell.exceptions.SegmentBalanceException;
import shell.knot.Knot;
import shell.knot.Point;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.shell.Shell;

public class Drawing {

    public static void drawCutMatch(JComponent frame, Graphics2D g2, SegmentBalanceException sbe, int lineThickness,
            PointSet ps, Camera camera) {
        drawCutMatch(frame, g2, sbe.cutMatchList, sbe.c.balanceMap, sbe.cut1, sbe.cut2, sbe.ex1, sbe.ex2, sbe.topKnot,
                lineThickness, ps, camera);
    }

    public static void drawCutMatch(JComponent frame, Graphics2D g2, CutMatchList cml, BalanceMap balanceMap,
            Segment cut1, Segment cut2, Segment ex1, Segment ex2, Knot topKnot, int lineThickness,
            PointSet ps, Camera camera) {

        BasicStroke stroke = new BasicStroke(lineThickness);

        BasicStroke doubleStroke = new BasicStroke(lineThickness * 2);
        g2.setStroke(stroke);

        // Draw x 1

        Font font = new Font("San-Serif", Font.PLAIN, 20);
        g2.setFont(font);

        g2.setColor(Color.RED);

        double[] firstCoords = new double[2];
        double[] lastCoords = new double[2];
        double[] midCoords = new double[2];

        Point2D first = ((Point) cut1.first).p.toPoint2D();
        Point2D last = ((Point) cut1.last).p.toPoint2D();

        firstCoords[0] = camera.transformX(first.getX());
        firstCoords[1] = camera.transformY(first.getY());

        lastCoords[0] = camera.transformX(last.getX());
        lastCoords[1] = camera.transformY(last.getY());
        midCoords[0] = (firstCoords[0] + lastCoords[0]) / 2.0 - 8;
        midCoords[1] = (firstCoords[1] + lastCoords[1]) / 2.0 + 8;
        g2.drawString("X", (int) midCoords[0], (int) midCoords[1]);

        g2.setColor(new Color(210, 105, 30));
        // Draw x 2
        first = ((Point) cut2.first).p.toPoint2D();
        last = ((Point) cut2.last).p.toPoint2D();

        firstCoords[0] = camera.transformX(first.getX());
        firstCoords[1] = camera.transformY(first.getY());

        lastCoords[0] = camera.transformX(last.getX());
        lastCoords[1] = camera.transformY(last.getY());
        midCoords[0] = (firstCoords[0] + lastCoords[0]) / 2.0 - 8;
        midCoords[1] = (firstCoords[1] + lastCoords[1]) / 2.0 + 8;

        g2.drawString("X", (int) midCoords[0], (int) midCoords[1]);

        // Draw external segment 1

        Point2D knotPoint1 = ((Point) ex1.getKnotPoint(topKnot.knotPointsFlattened)).p.toPoint2D();

        firstCoords[0] = camera.transformX(knotPoint1.getX());
        firstCoords[1] = camera.transformY(knotPoint1.getY());

        g2.setColor(Color.GREEN);

        g2.draw(new Ellipse2D.Double(firstCoords[0] - 5, firstCoords[1] - 5, 10, 10));
        drawSegment(g2, camera, ex1);

        // Draw external segment 2

        Point2D knotPoint2 = ((Point) ex2.getKnotPoint(topKnot.knotPointsFlattened)).p.toPoint2D();

        firstCoords[0] = camera.transformX(knotPoint2.getX());
        firstCoords[1] = camera.transformY(knotPoint2.getY());

        g2.setColor(Color.GREEN);

        g2.draw(new Ellipse2D.Double(firstCoords[0] - 5, firstCoords[1] - 5, 10, 10));
        drawSegment(g2, camera, ex2);

        // g2.setColor(new Color(238, 130, 238));
        // for (Segment externalMatch : balanceMap.externalMatches) {
        // if (externalMatch.equals(ex1) || externalMatch.equals(ex2)) {
        // continue;
        // }
        // VirtualPoint kp = externalMatch.last;
        // if (balanceMap.knot.contains(externalMatch.first)) {
        // kp = externalMatch.first;
        // }
        // Point2D kp2d = ((Point) kp).p.toPoint2D();
        // firstCoords[0] = ((kp2d.getX() - minX) * (width) / rangeX + offsetx) / 1.5;
        // firstCoords[1] = ((kp2d.getY() - minY) * (height) / rangeY + offsety) / 1.5;
        // g2.draw(new Ellipse2D.Double(firstCoords[0] - 5, firstCoords[1] - 5, 10,
        // 10));
        // drawSegment(g2, minX, minY, rangeX, rangeY, height, width, offsetx, offsety,
        // firstCoords, lastCoords,
        // externalMatch);
        // }

        // Draw Cuts and Matches
        for (CutMatch cutMatch : cml.cutMatches) {

            // Draw Matches
            g2.setColor(Color.CYAN);
            g2.setStroke(stroke);
            for (Segment s : cutMatch.matchSegments) {
                drawSegment(g2, camera, s);
            }

            // Draw Cuts
            g2.setColor(Color.ORANGE);
            g2.setStroke(doubleStroke);
            for (Segment s : cutMatch.cutSegments) {
                drawSegment(g2, camera, s);
            }
            // Draw SubKnot
            Shell result = new Shell();
            for (VirtualPoint p : cutMatch.knot.knotPoints) {
                result.add(((Point) p).p);
            }
            // Drawing.drawPath(frame, g2, Shell.toPath(result), lineThickness,
            // Color.lightGray, ps, true, false, false,
            // true, camera);

        }

    }

    public static void drawManifoldCut(Graphics2D g2, VirtualPoint hoverKP, VirtualPoint hoverCP, Camera camera,
            int lineThickness) {

        Font font = new Font("San-Serif", Font.PLAIN, 20);
        g2.setFont(font);

        BasicStroke stroke = new BasicStroke(lineThickness);
        g2.setStroke(stroke);

        double[] kpCoords = new double[2];
        double[] cpCoords = new double[2];
        double[] midCoords = new double[2];

        Point2D kp = ((Point) hoverKP).p.toPoint2D();
        Point2D cp = ((Point) hoverCP).p.toPoint2D();

        kpCoords[0] = camera.transformX(kp.getX());
        kpCoords[1] = camera.transformY(kp.getY());

        cpCoords[0] = camera.transformX(cp.getX());
        cpCoords[1] = camera.transformY(cp.getY());
        midCoords[0] = (kpCoords[0] + cpCoords[0]) / 2.0 - 8;
        midCoords[1] = (kpCoords[1] + cpCoords[1]) / 2.0 + 8;
        g2.setColor(Color.RED);
        g2.drawString("X", (int) midCoords[0], (int) midCoords[1]);
        g2.setColor(Color.GREEN);
        g2.draw(new Ellipse2D.Double(kpCoords[0] - 5, kpCoords[1] - 5, 10, 10));
    }

    public static void drawSegment(Graphics2D g2, Camera camera, Segment s) {
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

        double[] firstCoords = new double[2];
        double[] lastCoords = new double[2];
        firstCoords[0] = camera.transformX(first.getX());
        firstCoords[1] = camera.transformY(first.getY());

        lastCoords[0] = camera.transformX(last.getX());
        lastCoords[1] = camera.transformY(last.getY());
        g2.drawLine((int) firstCoords[0], (int) firstCoords[1], (int) lastCoords[0], (int) lastCoords[1]);
    }

    public static void drawGradientSegment(Graphics2D g2, Camera camera, Segment s, Color color1, Color color2) {
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
        double[] firstCoords = new double[2];
        double[] lastCoords = new double[2];
        firstCoords[0] = camera.transformX(first.getX());
        firstCoords[1] = camera.transformY(first.getY());

        lastCoords[0] = camera.transformX(last.getX());
        lastCoords[1] = camera.transformY(last.getY());
        int[] xCoords = new int[] { (int) firstCoords[0], (int) lastCoords[0] };
        int[] yCoords = new int[] { (int) firstCoords[1], (int) lastCoords[1] };
        GradientPaint gp = new GradientPaint(new Point2D.Double(firstCoords[0], firstCoords[1]), color1,
                new Point2D.Double(lastCoords[0], lastCoords[1]), color2);
        g2.setPaint(gp);
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
    public static void drawPath(JComponent frame, Graphics2D g2, Path2D path, float lineThickness, Color color,
            PointSet ps,
            boolean drawLines, boolean drawCircles, boolean drawNumbers, boolean dashed, Camera camera) {
        g2.setPaint(color);

        BasicStroke stroke = new BasicStroke(lineThickness);
        if (dashed) {
            stroke = new BasicStroke(lineThickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[] { 9 },
                    0);
        }
        g2.setStroke(stroke);

        GeneralPath scaledpath = new GeneralPath();
        boolean first = true;

        PathIterator pi = path.getPathIterator(null);
        Point2D start = null;
        int count = 0;
        while (!pi.isDone()) {
            double[] coords = new double[2];
            pi.currentSegment(coords);
            pi.next();

            coords[0] = camera.transformX(coords[0]);
            coords[1] = camera.transformY(coords[1]);
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

    public static void drawGradientPath(Graphics2D g2, Knot k, Shell shell, Camera camera, int minLineThickness) {
        for (Segment s : k.manifoldSegments) {
            VirtualPoint vp1 = s.first;
            VirtualPoint vp2 = s.last;

            Knot smallestKnot1 = shell.cutEngine.flatKnots.get(shell.smallestKnotLookup[vp1.id]);

            Knot smallestKnot2 = shell.cutEngine.flatKnots.get(shell.smallestKnotLookup[vp2.id]);

            camera.calculateCameraTransform();
            BasicStroke doubleStroke = new BasicStroke(minLineThickness * 2);
            g2.setStroke(doubleStroke);
            Drawing.drawGradientSegment(g2, camera, s,
                    Main.knotGradientColors.get(Main.colorLookup.get(smallestKnot1.id)),
                    Main.knotGradientColors.get(Main.colorLookup.get(smallestKnot2.id)));
        }
    }

}
