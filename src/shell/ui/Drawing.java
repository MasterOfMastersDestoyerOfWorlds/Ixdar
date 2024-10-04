package shell.ui;

import shell.render.color.Color;
import shell.render.sdf.SDFCircle;
import shell.render.sdf.SDFLine;
import shell.render.text.Font;

import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.HashMap;

import org.apache.commons.math3.util.Pair;
import org.joml.Vector2f;

import shell.Main;
import shell.PointSet;
import shell.cameras.Camera2D;
import shell.cuts.CutMatch;
import shell.cuts.CutMatchList;
import shell.exceptions.SegmentBalanceException;
import shell.knot.Knot;
import shell.knot.Point;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.shell.Shell;

public class Drawing {

    public static final int MIN_THICKNESS = 1;
    public static SDFLine sdfLine = new SDFLine();
    public static SDFCircle circle = new SDFCircle();
    public static Font font = new Font();

    public static void drawCutMatch(SegmentBalanceException sbe, int lineThickness,
            PointSet ps, Camera2D camera) {
        drawCutMatch(sbe.cutMatchList, sbe.cut1, sbe.cut2, sbe.ex1, sbe.ex2, sbe.topKnot,
                lineThickness, ps, camera);
    }

    public static void drawCutMatch(CutMatchList cml,
            Segment cut1, Segment cut2, Segment ex1, Segment ex2, Knot topKnot, int lineThickness,
            PointSet ps, Camera2D camera) {

        float[] firstCoords = new float[2];
        float[] lastCoords = new float[2];
        float[] midCoords = new float[2];

        Point2D first = ((Point) cut1.first).p.toPoint2D();
        Point2D last = ((Point) cut1.last).p.toPoint2D();

        firstCoords[0] = camera.pointTransformX((float) first.getX());
        firstCoords[1] = camera.pointTransformY((float) first.getY());

        lastCoords[0] = camera.pointTransformX((float) last.getX());
        lastCoords[1] = camera.pointTransformY((float) last.getY());
        midCoords[0] = (firstCoords[0] + lastCoords[0]) / 2.0f;
        midCoords[1] = (firstCoords[1] + lastCoords[1]) / 2.0f;
        font.drawTextCentered("X", midCoords[0], midCoords[1], 20, Color.RED, camera);
        // Draw x 2
        first = ((Point) cut2.first).p.toPoint2D();
        last = ((Point) cut2.last).p.toPoint2D();

        firstCoords[0] = camera.pointTransformX((float) first.getX());
        firstCoords[1] = camera.pointTransformY((float) first.getY());

        lastCoords[0] = camera.pointTransformX((float) last.getX());
        lastCoords[1] = camera.pointTransformY((float) last.getY());
        midCoords[0] = (firstCoords[0] + lastCoords[0]) / 2.0f;
        midCoords[1] = (firstCoords[1] + lastCoords[1]) / 2.0f;

        font.drawTextCentered("X", midCoords[0], midCoords[1], 20, Color.ORANGE, camera);
        // Draw external segment 1

        Point2D knotPoint1 = ((Point) ex1.getKnotPoint(topKnot.knotPointsFlattened)).p.toPoint2D();

        firstCoords[0] = camera.pointTransformX(knotPoint1.getX());
        firstCoords[1] = camera.pointTransformY(knotPoint1.getY());

        circle.draw(new Vector2f(firstCoords[0] - 5, firstCoords[1] - 5), Color.GREEN, camera);

        sdfLine.setStroke(lineThickness, false, 0f, false);
        drawSegment(ex1, Color.GREEN, camera);

        // Draw external segment 2

        Point2D knotPoint2 = ((Point) ex2.getKnotPoint(topKnot.knotPointsFlattened)).p.toPoint2D();

        firstCoords[0] = camera.pointTransformX(knotPoint2.getX());
        firstCoords[1] = camera.pointTransformY(knotPoint2.getY());

        circle.draw(new Vector2f(firstCoords[0] - 5, firstCoords[1] - 5), Color.GREEN, camera);
        drawSegment(ex2, Color.GREEN, camera);

        // Draw Cuts and Matches
        for (CutMatch cutMatch : cml.cutMatches) {

            for (Segment s : cutMatch.matchSegments) {
                drawSegment(s, Color.CYAN, camera);
            }

            // Draw Cuts
            sdfLine.setStroke(2 * lineThickness, false, 0f, false);
            for (Segment s : cutMatch.cutSegments) {
                drawSegment(s, Color.ORANGE, camera);
            }
            // Draw SubKnot
            Shell result = new Shell();
            for (VirtualPoint p : cutMatch.knot.knotPoints) {
                result.add(((Point) p).p);
            }

        }

    }

    public static void drawManifoldCut(VirtualPoint hoverKP, VirtualPoint hoverCP, Camera2D camera,
            int lineThickness) {

        float[] kpCoords = new float[2];
        float[] cpCoords = new float[2];
        float[] midCoords = new float[2];

        Point2D kp = ((Point) hoverKP).p.toPoint2D();
        Point2D cp = ((Point) hoverCP).p.toPoint2D();

        kpCoords[0] = camera.pointTransformX(kp.getX());
        kpCoords[1] = camera.pointTransformY(kp.getY());

        cpCoords[0] = camera.pointTransformX(cp.getX());
        cpCoords[1] = camera.pointTransformY(cp.getY());
        midCoords[0] = (kpCoords[0] + cpCoords[0]) / 2.0f;
        midCoords[1] = (kpCoords[1] + cpCoords[1]) / 2.0f;
        font.drawTextCentered("X", midCoords[0], midCoords[1], 20, Color.ORANGE, camera);
        circle.draw(new Vector2f(kpCoords[0] - 5, kpCoords[1] - 5), Color.GREEN, camera);
    }

    public static void drawSegment(Segment ex1, Color c, Camera2D camera) {
        Point2D first;
        Point2D last;
        if (ex1.first.isKnot) {
            first = ((Point) ((Knot) ex1.first).knotPoints.get(0)).p.toPoint2D();
        } else {
            first = ((Point) ex1.first).p.toPoint2D();
        }
        if (ex1.last.isKnot) {
            last = ((Point) ((Knot) ex1.last).knotPoints.get(0)).p.toPoint2D();
        } else {
            last = ((Point) ex1.last).p.toPoint2D();
        }

        Vector2f firstVec = new Vector2f(camera.pointTransformX(first.getX()), camera.pointTransformY(first.getY()));
        Vector2f lastVec = new Vector2f(camera.pointTransformX(last.getX()), camera.pointTransformY(last.getY()));

        sdfLine.draw(firstVec, lastVec, c,camera);
    }

    public static void drawGradientSegment(Segment s, Color color1, Color color2, Camera2D camera) {
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
        float[] firstCoords = new float[2];
        float[] lastCoords = new float[2];
        firstCoords[0] = camera.pointTransformX(first.getX());
        firstCoords[1] = camera.pointTransformY(first.getY());

        lastCoords[0] = camera.pointTransformX(last.getX());
        lastCoords[1] = camera.pointTransformY(last.getY());
        sdfLine.draw(new Vector2f(firstCoords), new Vector2f(lastCoords), color1, color2, camera);
    }

    /**
     * Draws the tsp path of the pointset ps
     * 
     * @param frame
     * @param path
     * @param color
     * @param ps
     * @param drawLines
     * @param drawCircles
     * @param drawNumbers
     */
    public static void drawPath(Path2D path, float lineThickness, Color color,
            PointSet ps,
            boolean drawLines, boolean drawCircles, boolean drawNumbers, boolean dashed, Camera2D camera) {
        if (dashed) {
            sdfLine.setStroke(lineThickness, true, 60f, true);
        } else {
            sdfLine.setStroke(lineThickness, false, 0f, false);
        }
        boolean first = true;

        PathIterator pi = path.getPathIterator(null);
        float[] start = null;
        int count = 0;
        float[] last = new float[2];
        float[] curr = new float[2];
        while (!pi.isDone()) {
            pi.currentSegment(curr);
            pi.next();

            curr[0] = camera.pointTransformX(curr[0]);
            curr[1] = camera.pointTransformY(curr[1]);
            if (drawCircles) {
                circle.draw(new Vector2f(curr[0] - 5, curr[1] - 5), color, camera);
            }
            if (drawNumbers) {
                font.drawTextCentered("" + count, (int) curr[0] - 5, (int) curr[1] - 5, 20, color, camera);
            }
            if (first) {
                first = false;
                start = new float[] { curr[0], curr[1] };
            } else {
                if (drawLines) {
                    sdfLine.draw(new Vector2f(last), new Vector2f(curr), color, camera);
                }
            }
            last = curr;
            count++;
        }
        if (drawLines) {
            sdfLine.draw(new Vector2f(last), new Vector2f(start), color, camera);
        }

    }

    public static void drawGradientPath(Knot k,
            ArrayList<Pair<Long, Long>> lookUpPairs, HashMap<Long, Integer> colorLookup,
            ArrayList<Color> colors, Camera2D camera, int minLineThickness) {

        sdfLine.setStroke(2 * minLineThickness, false, 0f, false);

        for (int i = 0; i < k.manifoldSegments.size(); i++) {
            Segment s = k.manifoldSegments.get(i);
            if (lookUpPairs != null) {
                Pair<Long, Long> lookUpPair = lookUpPairs.get(i);

                if (colorLookup.containsKey(lookUpPair.getFirst())) {
                    Drawing.drawGradientSegment(s, colors.get(colorLookup.get(lookUpPair.getFirst())),
                            colors.get(colorLookup.get(lookUpPair.getSecond())),
                            camera);
                }
            } else {
                if (colorLookup.containsKey((long) s.first.id)) {
                    Drawing.drawGradientSegment(s, colors.get(colorLookup.get((long) s.first.id)),
                            colors.get(colorLookup.get((long) s.last.id)),
                            camera);
                }
            }
        }

    }

    public static void drawSingleCutMatch(Main main, Segment matchSegment,
            Segment cutSegment, int lineThickness,
            PointSet ps, Camera2D camera) {

        sdfLine.setStroke(lineThickness, false, 0f, false);
        Drawing.drawSegment(matchSegment, Color.CYAN, camera);

        sdfLine.setStroke(2 * lineThickness, false, 0f, false);
        Drawing.drawSegment(cutSegment, Color.ORANGE, camera);
    }

    public static void drawCircle(VirtualPoint displayPoint, Color color, Camera2D camera,
            int lineThickness) {
        sdfLine.setStroke(lineThickness, false, 0f, false);
        Point p = (Point) displayPoint;
        double xCoord = camera.pointTransformX(p.p.getCoord(0));
        double yCoord = camera.pointTransformY(p.p.getCoord(1));
        circle.draw(new Vector2f((float) xCoord - 5, (float) yCoord - 5), color, camera);
    }

}
