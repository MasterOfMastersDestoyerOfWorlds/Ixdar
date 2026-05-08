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

/**
 * Static helper for low-level scene-overlay draw calls (segments, circles,
 * paths, knots, cut-match diagrams). Owns a per-platform set of cached
 * {@link SDFLine} / {@link SDFCircle} / {@link Font} instances and computes
 * camera-relative thickness/font sizes from the current shell extent.
 */
public class Drawing {
    public static final float NUM_2_0 = 2.0f;
    public static final float NUM_1 = 1f;
    public static final float NUM_0 = 0f;
    public static final int NUM_20 = 20;
    public static final int NUM_3 = 3;
    public static final float NUM_60 = 60f;
    public static final int NUM_4 = 4;

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

    /**
     * Build a Drawing for the current GL platform: instantiate the SDF line,
     * SDF circle, and font primitives and register this instance in the
     * per-platform map keyed by platform id.
     */
    public Drawing() {
        sdfLine = new SDFLine();
        circle = new SDFCircle();
        font = new Font();
        platformId = Platforms.gl().getPlatformID();
        drawing.put(platformId, this);
    }

    /**
     * Get (or lazily create) the {@link Drawing} bound to the current GL
     * platform. Each platform gets its own instance because GL primitives are
     * not shareable across contexts.
     *
     * @return the drawing helper for the current platform
     */
    public static Drawing getDrawing() {
        int id = Platforms.gl().getPlatformID();
        if (!drawing.containsKey(id)) {
            drawing.put(id, new Drawing());
        }
        return drawing.get(id);
    }

    /**
     * Recompute the static thickness, font, and circle-radius constants based
     * on the smallest segment in {@code d}: when that segment is shorter on
     * screen than the default thickness, all sizes scale down proportionally
     * so they fit; otherwise the defaults are restored.
     *
     * @param shell  the shell being rendered (unused; kept for symmetry)
     * @param camera the active scene camera, used to convert lengths
     * @param d      distance matrix providing the smallest segment length
     * @throws IllegalStateException if the computed minimum thickness collapses to zero
     */
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

    /**
     * Draw the cut-match diagram associated with a {@link SegmentBalanceException},
     * unpacking its cuts, externals, and top knot from the exception itself.
     *
     * @param sbe           the exception describing the cut-match configuration
     * @param lineThickness base line thickness in pixels
     * @param ps            point set being rendered
     * @param camera        the active scene camera
     */
    public static void drawCutMatch(SegmentBalanceException sbe, float lineThickness,
            PointSet ps, Camera2D camera) {
        drawCutMatch(sbe, sbe.cutMatchList, sbe.cut1, sbe.cut2, sbe.ex1, sbe.ex2, sbe.topKnot,
                lineThickness, ps, camera);
    }

    /**
     * Draw a cut-match diagram: x1/x2 labels at the midpoints of the two cut
     * segments, the green external segments and their endpoints, and the
     * cyan/magenta/orange match and cut segments listed in {@code cml}.
     *
     * @param sbe           exception holding x1/x2 labels and draw state
     * @param cml           cut-match list to render (may be null)
     * @param cut1          first cut segment
     * @param cut2          second cut segment
     * @param ex1           first external segment
     * @param ex2           second external segment
     * @param topKnot       knot whose flattened points host {@code ex1}/{@code ex2}
     * @param lineThickness base line thickness in pixels
     * @param ps            point set being rendered (unused; kept for symmetry)
     * @param camera        the active scene camera
     */
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
        midCoords[0] = (firstCoords[0] + lastCoords[0]) / NUM_2_0;
        midCoords[1] = (firstCoords[1] + lastCoords[1]) / NUM_2_0;
        sbe.initDraw();
        d.font.drawHyperString(sbe.x1, midCoords[0], midCoords[1], FONT_HEIGHT_PIXELS, camera);
        // Draw x 2
        first = (cut2.first).p.toPoint2D();
        last = (cut2.last).p.toPoint2D();

        firstCoords[0] = camera.pointTransformX((float) first.getX());
        firstCoords[1] = camera.pointTransformY((float) first.getY());

        lastCoords[0] = camera.pointTransformX((float) last.getX());
        lastCoords[1] = camera.pointTransformY((float) last.getY());
        midCoords[0] = (firstCoords[0] + lastCoords[0]) / NUM_2_0;
        midCoords[1] = (firstCoords[1] + lastCoords[1]) / NUM_2_0;

        d.font.drawHyperString(sbe.x2, midCoords[0], midCoords[1], FONT_HEIGHT_PIXELS, camera);
        // Draw external segment 1

        PointND pND = (ex1.getKnotPoint(topKnot.knotPointsFlattened)).p;
        Point2D knotPoint1 = pND.toPoint2D();

        firstCoords[0] = camera.pointTransformX(knotPoint1.getX());
        firstCoords[1] = camera.pointTransformY(knotPoint1.getY());

        pND.draw(new Vector2f(firstCoords[0], firstCoords[1]), CIRCLE_RADIUS * camera.ScaleFactor, Color.GREEN, camera);

        ex1.setStroke(lineThickness * camera.ScaleFactor, false, NUM_1, NUM_0, true, false, false, camera);
        drawSegment(ex1, Color.GREEN, camera);

        // Draw external segment 2

        PointND pND2 = (ex2.getKnotPoint(topKnot.knotPointsFlattened)).p;

        Point2D knotPoint2 = pND2.toPoint2D();

        firstCoords[0] = camera.pointTransformX(knotPoint2.getX());
        firstCoords[1] = camera.pointTransformY(knotPoint2.getY());

        pND2.draw(new Vector2f(firstCoords[0], firstCoords[1]), CIRCLE_RADIUS * camera.ScaleFactor, Color.GREEN,
                camera);
        ex2.setStroke(lineThickness * camera.ScaleFactor, false, NUM_1, NUM_0, true, false, false, camera);
        drawSegment(ex2, Color.GREEN, camera);

        // Draw Cuts and Matches
        if (cml != null) {
            for (CutMatch cutMatch : cml.cutMatches) {

                if (cutMatch.c != null) {
                    for (Segment s : cutMatch.matchSegments) {
                        s.setStroke(lineThickness * camera.ScaleFactor, false, NUM_1, NUM_0, true, false, false, camera);
                        if (s.id == cutMatch.c.lowerMatchSegment.id || s.id == cutMatch.c.upperMatchSegment.id) {
                            drawSegment(s, Color.GREEN, camera);
                        } else {
                            drawSegment(s, Color.CYAN, camera);
                        }
                    }

                    // Draw Cuts
                    for (Segment s : cutMatch.cutSegments) {
                        s.setStroke(2 * lineThickness * camera.ScaleFactor, false, NUM_1, NUM_0, true, false, false, camera);
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

    /**
     * Stroke {@code segment} at exactly {@code thickness} pixels (no camera
     * scaling) and draw it in color {@code c}.
     *
     * @param segment   segment to stroke
     * @param c         stroke color
     * @param thickness pixel thickness, applied directly
     * @param camera    the active scene camera
     */
    public static void drawSegment(Segment segment, Color c, float thickness, Camera2D camera) {
        segment.setStroke(thickness, false, NUM_1, NUM_0, true, false, false, camera);
        drawSegment(segment, c, camera);
    }

    /**
     * Stroke {@code segment} at {@code thickness * camera.ScaleFactor} pixels
     * (so the stroke widens with zoom) and draw it in color {@code c}.
     *
     * @param segment   segment to stroke
     * @param c         stroke color
     * @param thickness base pixel thickness before camera scaling
     * @param camera    the active scene camera
     */
    public static void drawScaledSegment(Segment segment, Color c, float thickness, Camera2D camera) {
        segment.setStroke(thickness * camera.ScaleFactor, false, NUM_1, NUM_0, true, false, false, camera);
        drawSegment(segment, c, camera);
    }

    /**
     * Like {@link #drawScaledSegment(Segment, Color, float, Camera2D)} but
     * draws between explicit screen-space endpoints {@code a} and {@code b}
     * rather than the segment's own endpoints.
     *
     * @param s         segment whose stroke params and draw method are used
     * @param a         first endpoint in screen pixels
     * @param b         second endpoint in screen pixels
     * @param c         stroke color
     * @param thickness base pixel thickness before camera scaling
     * @param camera    the active scene camera
     */
    public static void drawScaledSegment(Segment s, Vector2f a, Vector2f b, Color c, float thickness, Camera2D camera) {
        s.setStroke(thickness * camera.ScaleFactor, false, NUM_1, NUM_0, true, false, false, camera);
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

    /**
     * Draw {@code ex1} as a dashed line in color {@code c}. Endpoints are
     * resolved to the first knot point of any non-singleton boundary.
     *
     * @param ex1    segment to draw
     * @param c      stroke color
     * @param camera the active scene camera
     */
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
        ex1.setStroke(Drawing.MIN_THICKNESS * camera.ScaleFactor, true, NUM_20 * camera.ScaleFactor, NUM_1, true,
                false, false, camera);
        ex1.draw(firstVec, lastVec, c, camera);
    }

    /**
     * Draw {@code s} with a linear color gradient from {@code color1} at the
     * first endpoint to {@code color2} at the last endpoint.
     *
     * @param s      segment to draw
     * @param color1 color at {@code s.first}
     * @param color2 color at {@code s.last}
     * @param camera the active scene camera
     */
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
     * @param s      segment to draw
     * @param color1 color at {@code s.first}
     * @param color2 target color (lerped to at the truncated endpoint)
     * @param length fraction of the segment to draw, in {@code [0, 1]}
     * @param camera the active scene camera
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
     * Draws the Shell and its children if drawChildren is true.
     *
     * @param drawChildren  whether or not to draw child shells
     * @param c             the color to draw the shell (set to null to get a random
     *                      color)
     * @param shell         the shell to render
     * @param lineThickness base line thickness in pixels
     * @param ps            point set being rendered
     * @param camera        the active scene camera
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
     * Draws the tsp path of the pointset ps.
     *
     * @param shell         the path to render (each consecutive pair becomes a line segment)
     * @param lineThickness base line thickness in pixels
     * @param color         stroke and label color
     * @param ps            point set being rendered
     * @param drawLines     true to draw the connecting line segments
     * @param drawCircles   true to draw a circle at each point
     * @param drawNumbers   true to draw index labels at each point
     * @param dashed        true to render the line segments dashed
     * @param camera        the active scene camera
     */
    public static void drawPath(Shell shell, float lineThickness, Color color,
            PointSet ps,
            boolean drawLines, boolean drawCircles, boolean drawNumbers, boolean dashed, Camera2D camera) {

        Drawing d = getDrawing();
        float scale = camera.ScaleFactor;
        if (!Toggle.ScalePath.value) {
            scale = NUM_3;
        }
        if (shell.size() == 0) {
            return;
        }
        if (dashed) {
            d.sdfLine.setStroke(lineThickness * scale, true, NUM_60, NUM_1, true, true, false);
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
                float numberPixelDistance = scale * FONT_HEIGHT_LABELS_PIXELS / NUM_4;
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

    /**
     * Draw each manifold segment of {@code k} as a gradient between two
     * palette colors, indexed via {@code colorLookup}. When {@code lookUpPairs}
     * is non-null its (firstId,lastId) entries select the lookup keys;
     * otherwise the segment's own endpoint ids are used.
     *
     * @param k                knot whose manifold segments to draw
     * @param lookUpPairs      per-segment (first,last) key pairs, or null to fall back to segment endpoint ids
     * @param colorLookup      maps an id to an index into {@code colors}
     * @param colors           palette indexed by {@code colorLookup} values
     * @param camera           the active scene camera
     * @param minLineThickness base pixel thickness before camera scaling
     */
    public static void drawGradientPath(Knot k,
            ArrayList<Pair<Long, Long>> lookUpPairs, HashMap<Long, Integer> colorLookup,
            ArrayList<Color> colors, Camera2D camera, float minLineThickness) {
        for (int i = 0; i < k.manifoldSegments.size(); i++) {
            Segment s = k.manifoldSegments.get(i);
            s.setStroke(minLineThickness * camera.ScaleFactor, false, NUM_1, NUM_0, true, false, false, camera);
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

    /**
     * Variant of
     * {@link #drawGradientPath(Knot, ArrayList, HashMap, ArrayList, Camera2D, float)}
     * where {@code colorLookup} maps directly from id to {@link Color},
     * skipping the palette indirection.
     *
     * @param k                knot whose manifold segments to draw
     * @param lookupPairs      per-segment (first,last) key pairs, or null to fall back to segment endpoint ids
     * @param colorLookup      maps an id to its endpoint color
     * @param camera           the active scene camera
     * @param minLineThickness base pixel thickness before camera scaling
     */
    public static void drawGradientPath(Knot k, ArrayList<Pair<Long, Long>> lookupPairs,
            HashMap<Long, Color> colorLookup, Camera2D camera, float minLineThickness) {

        for (int i = 0; i < k.manifoldSegments.size(); i++) {
            Segment s = k.manifoldSegments.get(i);
            s.setStroke(minLineThickness * camera.ScaleFactor, false, NUM_1, NUM_0, true, false, false, camera);
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

    /**
     * Draw a single match/cut pair: cyan match segment at base thickness, and
     * orange cut segment at twice the base thickness for emphasis.
     *
     * @param main          the main scene (unused; kept for symmetry)
     * @param matchSegment  match segment to draw cyan
     * @param cutSegment    cut segment to draw orange
     * @param lineThickness base pixel thickness before camera scaling
     * @param ps            point set being rendered (unused; kept for symmetry)
     * @param camera        the active scene camera
     */
    public static void drawSingleCutMatch(MainScene main, Segment matchSegment,
            Segment cutSegment, float lineThickness,
            PointSet ps, Camera2D camera) {

        matchSegment.setStroke(lineThickness * camera.ScaleFactor, false, NUM_1, NUM_0, true, false, false, camera);
        Drawing.drawSegment(matchSegment, Color.CYAN, camera);

        cutSegment.setStroke(2 * lineThickness * camera.ScaleFactor, false, NUM_1, NUM_0, true, false, false, camera);
        Drawing.drawSegment(cutSegment, Color.ORANGE, camera);
    }

    /**
     * Draw a circle at the screen-space position of a knot's point, sized by
     * {@link #CIRCLE_RADIUS} times the camera scale.
     *
     * @param displayPoint  knot whose {@code p} provides the world-space position
     * @param color         fill color
     * @param camera        the active scene camera
     * @param lineThickness pixel stroke width applied to the SDF line helper
     */
    public static void drawCircle(Knot displayPoint, Color color, Camera2D camera,
            float lineThickness) {
        Drawing d = getDrawing();
        d.sdfLine.setStroke(lineThickness, false);
        Knot p = displayPoint;
        double xCoord = camera.pointTransformX(p.p.getScreenX());
        double yCoord = camera.pointTransformY(p.p.getScreenY());
        d.circle.draw(new Vector2f((float) xCoord, (float) yCoord), CIRCLE_RADIUS * camera.ScaleFactor, color, camera);
    }

    /**
     * Draw a circle at a screen-space point, scaled by the camera so it
     * grows with zoom.
     *
     * @param cameraPoint   center in screen pixels
     * @param color         fill color
     * @param camera        the active 2D scene camera
     * @param lineThickness pixel stroke width applied to the SDF line helper
     */
    public static void drawCircle(Vector2f cameraPoint, Color color, Camera2D camera,
            float lineThickness) {
        Drawing d = getDrawing();
        d.sdfLine.setStroke(lineThickness, false);
        d.circle.draw(new Vector2f(cameraPoint.x, cameraPoint.y), CIRCLE_RADIUS * camera.ScaleFactor, color, camera);
    }

    /**
     * Draw a circle at a screen-space point at the unscaled {@link #CIRCLE_RADIUS}.
     * Used for HUD-space circles that should not zoom.
     *
     * @param cameraPoint   center in screen pixels
     * @param color         fill color
     * @param camera        the active scene camera (any subclass)
     * @param lineThickness pixel stroke width applied to the SDF line helper
     */
    public static void drawCircle(Vector2f cameraPoint, Color color, Camera camera,
            float lineThickness) {
        Drawing d = getDrawing();
        d.sdfLine.setStroke(lineThickness, false);
        d.circle.draw(new Vector2f(cameraPoint.x, cameraPoint.y), CIRCLE_RADIUS, color, camera);
    }

    /**
     * Stroke each manifold segment of {@code k} at camera-scaled
     * {@code lineThickness} and draw it in color {@code c}.
     *
     * @param k             knot whose manifold segments to draw
     * @param c             stroke color
     * @param lineThickness base pixel thickness before camera scaling
     * @param camera        the active scene camera
     */
    public static void drawKnot(Knot k, Color c, float lineThickness, Camera2D camera) {
        for (int i = 0; i < k.manifoldSegments.size(); i++) {
            Segment s = k.manifoldSegments.get(i);
            s.setStroke(lineThickness * camera.ScaleFactor, false, NUM_1, NUM_0, true, false, false, camera);
            Drawing.drawSegment(s, c, camera);
        }
    }

    /**
     * Reset the cached SDF line stroke to {@link #MIN_THICKNESS} times the
     * camera scale, so subsequent draws share a consistent base width.
     *
     * @param camera the active scene camera
     */
    public static void setScaledStroke(Camera2D camera) {
        Drawing d = getDrawing();
        d.sdfLine.setStroke(MIN_THICKNESS * camera.ScaleFactor, false, NUM_1, NUM_0, true, false, false);
    }

}
