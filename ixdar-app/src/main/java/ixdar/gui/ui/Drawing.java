package ixdar.gui.ui;

import java.util.ArrayList;
import java.util.HashMap;

import org.apache.commons.math3.util.Pair;
import org.joml.Random;
import org.joml.Vector2f;

import ixdar.common.exceptions.SegmentBalanceException;
import ixdar.geometry.cuts.CutMatch;
import ixdar.geometry.cuts.CutMatchList;
import ixdar.geometry.knot.Knot;
import ixdar.geometry.knot.Segment;
import ixdar.geometry.point.Point2D;
import ixdar.geometry.point.PointND;
import ixdar.geometry.point.PointSet;
import ixdar.geometry.shell.DistanceMatrix;
import ixdar.geometry.shell.Shell;
import ixdar.graphics.cameras.Camera;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorFixedLerp;
import ixdar.graphics.render.color.ColorRGB;
import ixdar.graphics.render.sdf.SDFCircle;
import ixdar.graphics.render.sdf.SDFLine;
import ixdar.graphics.render.text.Font;
import ixdar.graphics.render.text.HyperString;
import ixdar.platform.Platforms;
import ixdar.platform.Toggle;
import ixdar.scenes.main.MainScene;

public class Drawing {

    public static final float MIN_THICKNESS_START = 2;
    public static final float FONT_HEIGHT_PIXELS_START = 30;
    public static final float CIRCLE_RADIUS_START = 7.5f;
    public static float MIN_THICKNESS = 2;
    public static float FONT_HEIGHT_PIXELS = 30;
    public static float FONT_HEIGHT_LABELS_PIXELS = 30;
    public static float CIRCLE_RADIUS = 7.5f;

    public static HashMap<Integer, Drawing> drawing = new HashMap<>();
    public SDFLine sdfLine;
    public SDFCircle circle;
    public Font font;
    public int platformId;

    public Drawing() {
        sdfLine = new SDFLine();
        circle = new SDFCircle();
        font = new Font();
        platformId = Platforms.gl().getPlatformID();
        drawing.put(platformId, this);
    }

    public static Drawing getDrawing() {
        int id = Platforms.gl().getPlatformID();
        if (!drawing.containsKey(id)) {
            drawing.put(id, new Drawing());
        }
        return drawing.get(id);
    }

    public static void initDrawingSizes(Shell shell, Camera2D camera, DistanceMatrix d) {

        double smallestLength = d.getSmallestSegmentLength();
        double smallestLengthScreenSpace = camera.pointSpaceLengthToScreenSpace(smallestLength);
        if (MIN_THICKNESS_START > smallestLengthScreenSpace) {
            MIN_THICKNESS = (float) (smallestLengthScreenSpace);
            FONT_HEIGHT_PIXELS = FONT_HEIGHT_PIXELS_START;
            FONT_HEIGHT_LABELS_PIXELS = (FONT_HEIGHT_PIXELS_START / MIN_THICKNESS_START) * MIN_THICKNESS;
            CIRCLE_RADIUS = (CIRCLE_RADIUS_START / MIN_THICKNESS_START) * MIN_THICKNESS;
        } else {
            MIN_THICKNESS = MIN_THICKNESS_START;
            FONT_HEIGHT_PIXELS = FONT_HEIGHT_PIXELS_START;
            FONT_HEIGHT_LABELS_PIXELS = FONT_HEIGHT_PIXELS_START;
            CIRCLE_RADIUS = CIRCLE_RADIUS_START;
        }
        if (MIN_THICKNESS == 0.0) {
            throw new IllegalStateException("MIN_THICKNESS is zero");
        }
    }

    public static void drawCutMatch(SegmentBalanceException sbe, float lineThickness,
            PointSet ps, Camera2D camera) {
        drawCutMatch(sbe, sbe.cutMatchList, sbe.cut1, sbe.cut2, sbe.ex1, sbe.ex2, sbe.topKnot,
                lineThickness, ps, camera);
    }

    public static void drawCutMatch(SegmentBalanceException sbe, CutMatchList cml,
            Segment cut1, Segment cut2, Segment ex1, Segment ex2, Knot topKnot, float lineThickness,
            PointSet ps, Camera2D camera) {

        Drawing d = getDrawing();
        float[] firstCoords = new float[2];
        float[] lastCoords = new float[2];
        float[] midCoords = new float[2];

        Point2D first = (cut1.first).p.toPoint2D();
        Point2D last = (cut1.last).p.toPoint2D();

        firstCoords[0] = camera.pointTransformX((float) first.getX());
        firstCoords[1] = camera.pointTransformY((float) first.getY());

        lastCoords[0] = camera.pointTransformX((float) last.getX());
        lastCoords[1] = camera.pointTransformY((float) last.getY());
        midCoords[0] = (firstCoords[0] + lastCoords[0]) / 2.0f;
        midCoords[1] = (firstCoords[1] + lastCoords[1]) / 2.0f;
        sbe.initDraw();
        d.font.drawHyperString(sbe.x1, midCoords[0], midCoords[1], FONT_HEIGHT_PIXELS, camera);
        // Draw x 2
        first = (cut2.first).p.toPoint2D();
        last = (cut2.last).p.toPoint2D();

        firstCoords[0] = camera.pointTransformX((float) first.getX());
        firstCoords[1] = camera.pointTransformY((float) first.getY());

        lastCoords[0] = camera.pointTransformX((float) last.getX());
        lastCoords[1] = camera.pointTransformY((float) last.getY());
        midCoords[0] = (firstCoords[0] + lastCoords[0]) / 2.0f;
        midCoords[1] = (firstCoords[1] + lastCoords[1]) / 2.0f;

        d.font.drawHyperString(sbe.x2, midCoords[0], midCoords[1], FONT_HEIGHT_PIXELS, camera);
        // Draw external segment 1

        PointND pND = (ex1.getKnotPoint(topKnot.knotPointsFlattened)).p;
        Point2D knotPoint1 = pND.toPoint2D();

        firstCoords[0] = camera.pointTransformX(knotPoint1.getX());
        firstCoords[1] = camera.pointTransformY(knotPoint1.getY());

        pND.draw(new Vector2f(firstCoords[0], firstCoords[1]), CIRCLE_RADIUS * camera.ScaleFactor, Color.GREEN, camera);

        ex1.setStroke(lineThickness * camera.ScaleFactor, false, 1f, 0f, true, false, camera);
        drawSegment(ex1, Color.GREEN, camera);

        // Draw external segment 2

        PointND pND2 = (ex2.getKnotPoint(topKnot.knotPointsFlattened)).p;

        Point2D knotPoint2 = pND2.toPoint2D();

        firstCoords[0] = camera.pointTransformX(knotPoint2.getX());
        firstCoords[1] = camera.pointTransformY(knotPoint2.getY());

        pND2.draw(new Vector2f(firstCoords[0], firstCoords[1]), CIRCLE_RADIUS * camera.ScaleFactor, Color.GREEN,
                camera);
        ex2.setStroke(lineThickness * camera.ScaleFactor, false, 1f, 0f, true, false, camera);
        drawSegment(ex2, Color.GREEN, camera);

        // Draw Cuts and Matches
        if (cml != null) {
            for (CutMatch cutMatch : cml.cutMatches) {

                if (cutMatch.c != null) {
                    for (Segment s : cutMatch.matchSegments) {
                        s.setStroke(lineThickness * camera.ScaleFactor, false, 1f, 0f, true, false, camera);
                        if (s.id == cutMatch.c.lowerMatchSegment.id || s.id == cutMatch.c.upperMatchSegment.id) {
                            drawSegment(s, Color.GREEN, camera);
                        } else {
                            drawSegment(s, Color.CYAN, camera);
                        }
                    }

                    // Draw Cuts
                    for (Segment s : cutMatch.cutSegments) {
                        s.setStroke(2 * lineThickness * camera.ScaleFactor, false, 1f, 0f, true, false, camera);
                        if (s.id == cutMatch.c.lowerCutSegment.id || s.id == cutMatch.c.upperCutSegment.id) {
                            drawSegment(s, Color.MAGENTA, camera);
                        } else {
                            drawSegment(s, Color.ORANGE, camera);
                        }
                    }
                }
                // Draw SubKnot
                Shell result = new Shell();
                for (Knot p : cutMatch.knot.knotPoints) {
                    result.add((p).p);
                }

            }
        }

    }

    public static void drawSegment(Segment segment, Color c, float thickness, Camera2D camera) {
        segment.setStroke(thickness, false, 1f, 0f, true, false, camera);
        drawSegment(segment, c, camera);
    }

    public static void drawScaledSegment(Segment segment, Color c, float thickness, Camera2D camera) {
        segment.setStroke(thickness * camera.ScaleFactor, false, 1f, 0f, true, false, camera);
        drawSegment(segment, c, camera);
    }

    public static void drawScaledSegment(Segment s, Vector2f a, Vector2f b, Color c, float thickness, Camera2D camera) {
        s.setStroke(thickness * camera.ScaleFactor, false, 1f, 0f, true, false, camera);
        s.draw(a, b, c, camera);
    }

    private static void drawSegment(Segment ex1, Color c, Camera2D camera) {
        Point2D first;
        Point2D last;
        if (!ex1.first.isSingleton()) {
            first = (((Knot) ex1.first).knotPoints.get(0)).p.toPoint2D();
        } else {
            first = (ex1.first).p.toPoint2D();
        }
        if (!ex1.last.isSingleton()) {
            last = (((Knot) ex1.last).knotPoints.get(0)).p.toPoint2D();
        } else {
            last = (ex1.last).p.toPoint2D();
        }
        Vector2f firstVec = new Vector2f(camera.pointTransformX(first.getX()), camera.pointTransformY(first.getY()));
        Vector2f lastVec = new Vector2f(camera.pointTransformX(last.getX()), camera.pointTransformY(last.getY()));

        ex1.draw(firstVec, lastVec, c, camera);
    }

    public static void drawDashedSegment(Segment ex1, Color c, Camera2D camera) {
        Point2D first;
        Point2D last;
        if (!ex1.first.isSingleton()) {
            first = (((Knot) ex1.first).knotPoints.get(0)).p.toPoint2D();
        } else {
            first = (ex1.first).p.toPoint2D();
        }
        if (!ex1.last.isSingleton()) {
            last = (((Knot) ex1.last).knotPoints.get(0)).p.toPoint2D();
        } else {
            last = (ex1.last).p.toPoint2D();
        }

        Vector2f firstVec = new Vector2f(camera.pointTransformX(first.getX()), camera.pointTransformY(first.getY()));
        Vector2f lastVec = new Vector2f(camera.pointTransformX(last.getX()), camera.pointTransformY(last.getY()));
        ex1.setStroke(Drawing.MIN_THICKNESS * camera.ScaleFactor, true, 20 * camera.ScaleFactor, 1f, true,
                false, camera);
        ex1.draw(firstVec, lastVec, c, camera);
    }

    public static void drawGradientSegment(Segment s, Color color1, Color color2, Camera2D camera) {
        Point2D first;
        Point2D last;
        if (!s.first.isSingleton()) {
            first = (((Knot) s.first).knotPoints.get(0)).p.toPoint2D();
        } else {
            first = (s.first).p.toPoint2D();
        }
        if (!s.last.isSingleton()) {
            last = (((Knot) s.last).knotPoints.get(0)).p.toPoint2D();
        } else {
            last = (s.last).p.toPoint2D();
        }
        float[] firstCoords = new float[2];
        float[] lastCoords = new float[2];
        firstCoords[0] = camera.pointTransformX(first.getX());
        firstCoords[1] = camera.pointTransformY(first.getY());

        lastCoords[0] = camera.pointTransformX(last.getX());
        lastCoords[1] = camera.pointTransformY(last.getY());
        s.draw(new Vector2f(firstCoords), new Vector2f(lastCoords), color1, color2, camera);
    }

    /**
     * Draws gradient segment from the first in the segment to the last in the
     * segment with length being a value from zero to one indicating the distance
     * along that segment to draw. Calling this method with a length of 1 draws the
     * segment normally.
     * 
     * @param s
     * @param color1
     * @param color2
     * @param length
     * @param camera
     */
    public static void drawGradientSegmentPartial(Segment s, Color color1, Color color2, float length,
            Camera2D camera) {
        Point2D first;
        Point2D last;
        if (!s.first.isSingleton()) {
            first = (((Knot) s.first).knotPoints.get(0)).p.toPoint2D();
        } else {
            first = (s.first).p.toPoint2D();
        }
        if (!s.last.isSingleton()) {
            last = (((Knot) s.last).knotPoints.get(0)).p.toPoint2D();
        } else {
            last = (s.last).p.toPoint2D();
        }

        Vector2f firstCoords = new Vector2f(camera.pointTransformX(first.getX()), camera.pointTransformY(first.getY()));
        Vector2f lastCoords = new Vector2f(camera.pointTransformX(last.getX()), camera.pointTransformY(last.getY()));

        Vector2f newLast = new Vector2f(lastCoords).sub(firstCoords).mul(length).add(firstCoords);

        s.draw(firstCoords, newLast, color1, new ColorFixedLerp(color1, color2, length), camera);
    }

    /**
     * Draws the Shell and its children if drawChildren is true
     * 
     * @param frame        where to draw the shell
     * @param g2           graphics object for frame
     * @param drawChildren whether or not to draw child shells
     * @param c            the color to draw the shell (set to null to get a random
     *                     color)
     */
    public static void drawShell(Shell shell, boolean drawChildren, float lineThickness, Color c,
            PointSet ps, Camera2D camera) {
        if (c == null) {
            Random colorSeed = new Random();
            Drawing.drawPath(shell, lineThickness,
                    new ColorRGB(colorSeed.nextFloat(), colorSeed.nextFloat(), colorSeed.nextFloat()), ps,
                    true, false, false, false, camera);
        } else {
            Drawing.drawPath(shell, lineThickness, c, ps, true, false, false, false, camera);
        }
    }

    /**
     * Draws the tsp path of the pointset ps
     * 
     * @param frame
     * @param shell
     * @param color
     * @param ps
     * @param drawLines
     * @param drawCircles
     * @param drawNumbers
     */
    public static void drawPath(Shell shell, float lineThickness, Color color,
            PointSet ps,
            boolean drawLines, boolean drawCircles, boolean drawNumbers, boolean dashed, Camera2D camera) {

        Drawing d = getDrawing();
        float scale = camera.ScaleFactor;
        if (!Toggle.ScalePath.value) {
            scale = 3;
        }
        if (shell.size() == 0) {
            return;
        }
        if (dashed) {
            d.sdfLine.setStroke(lineThickness * scale, true, 60f, 1f, true, true);
        } else {
            d.sdfLine.setStroke(lineThickness * scale, false);
        }
        PointND last = shell.getLast();
        PointND next;
        int count = 0;
        if (drawNumbers) {
            ArrayList<HyperString> hyperStrings = shell.getHyperStrings(color);
            ArrayList<Vector2f> xLoc = new ArrayList<>();
            for (HyperString h : hyperStrings) {
                PointND p = (PointND) h.getData();
                float x = camera.pointTransformX(p.getScreenX());
                float y = camera.pointTransformY(p.getScreenY());
                next = shell.getNext(count);
                float numberPixelDistance = scale * FONT_HEIGHT_LABELS_PIXELS / 4;
                Vector2f point = new Vector2f(x, y);
                Vector2f lastVector = new Vector2f(camera.pointTransformX(last.getScreenX()),
                        camera.pointTransformY(last.getScreenY())).sub(point);
                Vector2f nextVector = new Vector2f(camera.pointTransformX(next.getScreenX()),
                        camera.pointTransformY(next.getScreenY())).sub(point);
                Vector2f bisector = new Vector2f(lastVector).normalize().add(new Vector2f(nextVector).normalize())
                        .normalize().mul(numberPixelDistance);
                Vector2f textCenter = point.sub(bisector);
                xLoc.add(textCenter);
            }
            d.font.drawHyperStrings(hyperStrings, xLoc, scale * FONT_HEIGHT_LABELS_PIXELS, camera);
        }
        for (PointND p : shell) {
            next = shell.getNext(count);
            float x = camera.pointTransformX(p.getScreenX());
            float y = camera.pointTransformY(p.getScreenY());
            if (drawCircles) {
                p.draw(new Vector2f(x, y), CIRCLE_RADIUS * scale, color, camera);
            }

            if (drawLines) {
                float lx = camera.pointTransformX(last.getScreenX());
                float ly = camera.pointTransformY(last.getScreenY());
                d.sdfLine.draw(new Vector2f(lx, ly), new Vector2f(x, y), color, camera);
            }
            last = p;
            count++;
        }
    }

    public static void drawGradientPath(Knot k,
            ArrayList<Pair<Long, Long>> lookUpPairs, HashMap<Long, Integer> colorLookup,
            ArrayList<Color> colors, Camera2D camera, float minLineThickness) {
        for (int i = 0; i < k.manifoldSegments.size(); i++) {
            Segment s = k.manifoldSegments.get(i);
            s.setStroke(minLineThickness * camera.ScaleFactor, false, 1f, 0f, true, false, camera);
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

    public static void drawGradientPath(Knot k, ArrayList<Pair<Long, Long>> lookupPairs,
            HashMap<Long, Color> colorLookup, Camera2D camera, float minLineThickness) {

        for (int i = 0; i < k.manifoldSegments.size(); i++) {
            Segment s = k.manifoldSegments.get(i);
            s.setStroke(minLineThickness * camera.ScaleFactor, false, 1f, 0f, true, false, camera);
            if (lookupPairs != null) {
                Pair<Long, Long> lookUpPair = lookupPairs.get(i);

                if (colorLookup.containsKey(lookUpPair.getFirst())) {
                    Drawing.drawGradientSegment(s, colorLookup.get(lookUpPair.getFirst()),
                            colorLookup.get(lookUpPair.getSecond()),
                            camera);
                }
            } else {
                if (colorLookup.containsKey((long) s.first.id)) {
                    Drawing.drawGradientSegment(s, colorLookup.get((long) s.first.id),
                            colorLookup.get((long) s.last.id),
                            camera);
                }
            }
        }
    }

    public static void drawSingleCutMatch(MainScene main, Segment matchSegment,
            Segment cutSegment, float lineThickness,
            PointSet ps, Camera2D camera) {

        matchSegment.setStroke(lineThickness * camera.ScaleFactor, false, 1f, 0f, true, false, camera);
        Drawing.drawSegment(matchSegment, Color.CYAN, camera);

        cutSegment.setStroke(2 * lineThickness * camera.ScaleFactor, false, 1f, 0f, true, false, camera);
        Drawing.drawSegment(cutSegment, Color.ORANGE, camera);
    }

    public static void drawCircle(Knot displayPoint, Color color, Camera2D camera,
            float lineThickness) {
        Drawing d = getDrawing();
        d.sdfLine.setStroke(lineThickness, false);
        Knot p = displayPoint;
        double xCoord = camera.pointTransformX(p.p.getScreenX());
        double yCoord = camera.pointTransformY(p.p.getScreenY());
        d.circle.draw(new Vector2f((float) xCoord, (float) yCoord), CIRCLE_RADIUS * camera.ScaleFactor, color, camera);
    }

    public static void drawCircle(Vector2f cameraPoint, Color color, Camera2D camera,
            float lineThickness) {
        Drawing d = getDrawing();
        d.sdfLine.setStroke(lineThickness, false);
        d.circle.draw(new Vector2f(cameraPoint.x, cameraPoint.y), CIRCLE_RADIUS * camera.ScaleFactor, color, camera);
    }

    public static void drawCircle(Vector2f cameraPoint, Color color, Camera camera,
            float lineThickness) {
        Drawing d = getDrawing();
        d.sdfLine.setStroke(lineThickness, false);
        d.circle.draw(new Vector2f(cameraPoint.x, cameraPoint.y), CIRCLE_RADIUS, color, camera);
    }

    public static void drawKnot(Knot k, Color c, float lineThickness, Camera2D camera) {
        for (int i = 0; i < k.manifoldSegments.size(); i++) {
            Segment s = k.manifoldSegments.get(i);
            s.setStroke(lineThickness * camera.ScaleFactor, false, 1f, 0f, true, false, camera);
            Drawing.drawSegment(s, c, camera);
        }
    }

    public static void setScaledStroke(Camera2D camera) {
        Drawing d = getDrawing();
        d.sdfLine.setStroke(MIN_THICKNESS * camera.ScaleFactor, false, 1f, 0f, true, false);
    }

}
