package ixdar.gui.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;

import org.apache.commons.math3.util.Pair;
import org.joml.Vector2d;
import org.joml.Vector2f;

import ixdar.geometry.tsp.SegmentBalanceException;
import ixdar.geometry.tsp.cuts.CutMatch;
import ixdar.geometry.tsp.cuts.CutMatchList;
import ixdar.geometry.tsp.knot.Knot;
import ixdar.geometry.tsp.knot.Segment;
import ixdar.geometry.tsp.points.PointND;
import ixdar.geometry.tsp.points.PointSet;
import ixdar.geometry.tsp.shell.DistanceMatrix;
import ixdar.geometry.tsp.shell.Shell;
import ixdar.graphics.cameras.Camera;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorFixedLerp;
import ixdar.graphics.render.color.Palette;
import ixdar.graphics.render.sdf.SDFCircle;
import ixdar.graphics.render.sdf.SDFLine;
import ixdar.graphics.render.text.Font;
import ixdar.gui.ui.text.HyperString;
import ixdar.platform.Platforms;
import ixdar.scenes.Scene;
import ixdar.scenes.main.Toggle;

/**
 * Static helper for low-level scene-overlay draw calls (segments, circles,
 * paths, knots, cut-match diagrams). Owns a per-platform set of cached
 * {@link SDFLine} / {@link SDFCircle} / {@link Font} instances and computes
 * camera-relative thickness/font sizes from the current shell extent.
 */
public class Drawing {
    public static final int DASH_LENGTH_SEGMENT_PX = 20;
    public static final int DEFAULT_PATH_SCALE = 3;
    public static final float DASH_LENGTH_PATH_PX = 60f;
    public static final float LINE_QUAD_HEIGHT_PER_WIDTH = 4f;

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
     * Build a Drawing for the current GL platform: instantiate the SDF line, SDF
     * circle, and font primitives and register this instance in the per-platform
     * map keyed by platform id.
     */
    public Drawing() {
        sdfLine = new SDFLine();
        circle = new SDFCircle();
        font = new Font();
        platformId = Platforms.gl().getPlatformID();
        drawing.put(platformId, this);
    }

    /**
     * Get (or lazily create) the {@link Drawing} bound to the current GL platform.
     * Each platform gets its own instance because GL primitives are not shareable
     * across contexts.
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
     * Recompute the static thickness, font, and circle-radius constants based on
     * the smallest segment in {@code d}: when that segment is shorter on screen
     * than the default thickness, all sizes scale down proportionally so they fit;
     * otherwise the defaults are restored.
     *
     * @param shell  the shell being rendered (unused; kept for symmetry)
     * @param camera the active scene camera, used to convert lengths
     * @param d      distance matrix providing the smallest segment length
     * @throws IllegalStateException if the computed minimum thickness collapses to
     *                               zero
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
        Palette palette = Scene.activePalette();
        float[] firstCoords = new float[2];
        float[] lastCoords = new float[2];
        float[] midCoords = new float[2];

        Vector2d first = (cut1.first).p.toVector2d();
        Vector2d last = (cut1.last).p.toVector2d();

        firstCoords[0] = camera.pointTransformX((float) first.x);
        firstCoords[1] = camera.pointTransformY((float) first.y);

        lastCoords[0] = camera.pointTransformX((float) last.x);
        lastCoords[1] = camera.pointTransformY((float) last.y);
        midCoords[0] = (firstCoords[0] + lastCoords[0]) / 2.0f;
        midCoords[1] = (firstCoords[1] + lastCoords[1]) / 2.0f;
        sbe.initDraw(palette);
        d.font.drawHyperString(sbe.x1, midCoords[0], midCoords[1], FONT_HEIGHT_PIXELS, camera);
        // Draw x 2
        first = (cut2.first).p.toVector2d();
        last = (cut2.last).p.toVector2d();

        firstCoords[0] = camera.pointTransformX((float) first.x);
        firstCoords[1] = camera.pointTransformY((float) first.y);

        lastCoords[0] = camera.pointTransformX((float) last.x);
        lastCoords[1] = camera.pointTransformY((float) last.y);
        midCoords[0] = (firstCoords[0] + lastCoords[0]) / 2.0f;
        midCoords[1] = (firstCoords[1] + lastCoords[1]) / 2.0f;

        d.font.drawHyperString(sbe.x2, midCoords[0], midCoords[1], FONT_HEIGHT_PIXELS, camera);
        // Draw external segment 1

        PointND pND = (ex1.getKnotPoint(topKnot.knotPointsFlattened)).p;
        Vector2d knotPoint1 = pND.toVector2d();

        firstCoords[0] = camera.pointTransformX(knotPoint1.x);
        firstCoords[1] = camera.pointTransformY(knotPoint1.y);

        d.circle.draw(new Vector2f(firstCoords[0], firstCoords[1]), CIRCLE_RADIUS * camera.ScaleFactor,
                palette.accent, camera);

        strokeSegment(ex1, lineThickness * camera.ScaleFactor, false, 1f, 0f, camera);
        drawSegment(ex1, palette.success, camera);

        // Draw external segment 2

        PointND pND2 = (ex2.getKnotPoint(topKnot.knotPointsFlattened)).p;

        Vector2d knotPoint2 = pND2.toVector2d();

        firstCoords[0] = camera.pointTransformX(knotPoint2.x);
        firstCoords[1] = camera.pointTransformY(knotPoint2.y);

        d.circle.draw(new Vector2f(firstCoords[0], firstCoords[1]), CIRCLE_RADIUS * camera.ScaleFactor,
                palette.accent, camera);
        strokeSegment(ex2, lineThickness * camera.ScaleFactor, false, 1f, 0f, camera);
        drawSegment(ex2, palette.success, camera);

        // Draw Cuts and Matches
        if (cml != null) {
            for (CutMatch cutMatch : cml.cutMatches) {

                if (cutMatch.c != null) {
                    for (Segment s : cutMatch.matchSegments) {
                        strokeSegment(s, lineThickness * camera.ScaleFactor, false, 1f, 0f, camera);
                        if (s.id == cutMatch.c.lowerMatchSegment.id || s.id == cutMatch.c.upperMatchSegment.id) {
                            drawSegment(s, palette.success, camera);
                        } else {
                            drawSegment(s, palette.selected, camera);
                        }
                    }

                    // Draw Cuts
                    for (Segment s : cutMatch.cutSegments) {
                        strokeSegment(s, 2 * lineThickness * camera.ScaleFactor, false, 1f, 0f, camera);
                        if (s.id == cutMatch.c.lowerCutSegment.id || s.id == cutMatch.c.upperCutSegment.id) {
                            drawSegment(s, palette.hover, camera);
                        } else {
                            drawSegment(s, palette.highlight, camera);
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
        strokeSegment(segment, thickness, false, 1f, 0f, camera);
        drawSegment(segment, c, camera);
    }

    /**
     * Stroke {@code segment} at {@code thickness * camera.ScaleFactor} pixels (so
     * the stroke widens with zoom) and draw it in color {@code c}.
     *
     * @param segment   segment to stroke
     * @param c         stroke color
     * @param thickness base pixel thickness before camera scaling
     * @param camera    the active scene camera
     */
    public static void drawScaledSegment(Segment segment, Color c, float thickness, Camera2D camera) {
        strokeSegment(segment, thickness * camera.ScaleFactor, false, 1f, 0f, camera);
        drawSegment(segment, c, camera);
    }

    /**
     * Like {@link #drawScaledSegment(Segment, Color, float, Camera2D)} but draws
     * between explicit screen-space endpoints {@code a} and {@code b} rather than
     * the segment's own endpoints.
     *
     * @param s         segment whose stroke params are used
     * @param a         first endpoint in screen pixels
     * @param b         second endpoint in screen pixels
     * @param c         stroke color
     * @param thickness base pixel thickness before camera scaling
     * @param camera    the active scene camera
     */
    public static void drawScaledSegment(Segment s, Vector2f a, Vector2f b, Color c, float thickness, Camera2D camera) {
        strokeSegment(s, thickness * camera.ScaleFactor, false, 1f, 0f, camera);
        getDrawing().sdfLine.draw(a, b, c, camera);
    }

    /**
     * Set the shared line's stroke for {@code s} with round caps and no arrow, as
     * {@link #strokeSegment(SDFLine, Segment, float, boolean, float, float, boolean, boolean, boolean, Camera2D)}
     * does.
     *
     * @param s          segment about to be drawn
     * @param lineWidth  stroke width in screen pixels
     * @param dashed     whether the line is dashed
     * @param dashLength one dash period in point space
     * @param dashRate   how fast the dashes march
     * @param camera     camera projecting the segment
     */
    private static void strokeSegment(Segment s, float lineWidth, boolean dashed, float dashLength, float dashRate,
            Camera2D camera) {
        strokeSegment(getDrawing().sdfLine, s, lineWidth, dashed, dashLength, dashRate, true, false, false, camera);
    }

    /**
     * Set {@code line}'s stroke for drawing {@code s}, the dash period given in point space and
     * converted to the line quad's texture units, so dashes keep their size on screen at any zoom.
     *
     * @param line       the line renderer about to draw the segment
     * @param s          segment about to be drawn
     * @param lineWidth  stroke width in screen pixels
     * @param dashed     whether the line is dashed
     * @param dashLength one dash period in point space
     * @param dashRate   how fast the dashes march
     * @param roundCaps  whether dashes have round caps
     * @param endCaps    whether the line ends have caps
     * @param arrow      whether the line ends in an arrowhead
     * @param camera     camera projecting the segment
     */
    public static void strokeSegment(SDFLine line, Segment s, float lineWidth, boolean dashed, float dashLength,
            float dashRate, boolean roundCaps, boolean endCaps, boolean arrow, Camera2D camera) {
        Vector2f base = s.getPointSpaceVector(s.first);
        Vector2f dashEnd = s.getPointSpaceVector(s.last).sub(base).normalize().mul(dashLength).add(base);
        float dashScreenLength = (float) Math.hypot(
                camera.pointTransformX(dashEnd.x) - camera.pointTransformX(base.x),
                camera.pointTransformY(dashEnd.y) - camera.pointTransformY(base.y));
        float quadHeight = LINE_QUAD_HEIGHT_PER_WIDTH * Math.max(lineWidth, MIN_THICKNESS);
        line.setStroke(lineWidth, dashed, dashScreenLength / quadHeight, dashRate, roundCaps, endCaps, arrow);
    }

    private static void drawSegment(Segment ex1, Color c, Camera2D camera) {
        Vector2d first;
        Vector2d last;
        if (!ex1.first.isSingleton()) {
            first = (((Knot) ex1.first).knotPoints.get(0)).p.toVector2d();
        } else {
            first = (ex1.first).p.toVector2d();
        }
        if (!ex1.last.isSingleton()) {
            last = (((Knot) ex1.last).knotPoints.get(0)).p.toVector2d();
        } else {
            last = (ex1.last).p.toVector2d();
        }
        Vector2f firstVec = new Vector2f(camera.pointTransformX(first.x), camera.pointTransformY(first.y));
        Vector2f lastVec = new Vector2f(camera.pointTransformX(last.x), camera.pointTransformY(last.y));

        getDrawing().sdfLine.draw(firstVec, lastVec, c, camera);
    }

    /**
     * Draw {@code ex1} as a dashed line in color {@code c}. Endpoints are resolved
     * to the first knot point of any non-singleton boundary.
     *
     * @param ex1    segment to draw
     * @param c      stroke color
     * @param camera the active scene camera
     */
    public static void drawDashedSegment(Segment ex1, Color c, Camera2D camera) {
        Vector2d first;
        Vector2d last;
        if (!ex1.first.isSingleton()) {
            first = (((Knot) ex1.first).knotPoints.get(0)).p.toVector2d();
        } else {
            first = (ex1.first).p.toVector2d();
        }
        if (!ex1.last.isSingleton()) {
            last = (((Knot) ex1.last).knotPoints.get(0)).p.toVector2d();
        } else {
            last = (ex1.last).p.toVector2d();
        }

        Vector2f firstVec = new Vector2f(camera.pointTransformX(first.x), camera.pointTransformY(first.y));
        Vector2f lastVec = new Vector2f(camera.pointTransformX(last.x), camera.pointTransformY(last.y));
        strokeSegment(ex1, Drawing.MIN_THICKNESS * camera.ScaleFactor, true, DASH_LENGTH_SEGMENT_PX * camera.ScaleFactor, 1f,
                camera);
        getDrawing().sdfLine.draw(firstVec, lastVec, c, camera);
    }

    /**
     * Draw {@code s} through {@code line}, stroked as last set, with a linear color gradient from
     * {@code color1} at the first endpoint to {@code color2} at the last endpoint.
     *
     * @param line   the line renderer, its stroke already set
     * @param s      segment to draw
     * @param color1 color at {@code s.first}
     * @param color2 color at {@code s.last}
     * @param camera the active scene camera
     */
    public static void drawGradientSegment(SDFLine line, Segment s, Color color1, Color color2, Camera2D camera) {
        Vector2d first;
        Vector2d last;
        if (!s.first.isSingleton()) {
            first = (((Knot) s.first).knotPoints.get(0)).p.toVector2d();
        } else {
            first = (s.first).p.toVector2d();
        }
        if (!s.last.isSingleton()) {
            last = (((Knot) s.last).knotPoints.get(0)).p.toVector2d();
        } else {
            last = (s.last).p.toVector2d();
        }
        float[] firstCoords = new float[2];
        float[] lastCoords = new float[2];
        firstCoords[0] = camera.pointTransformX(first.x);
        firstCoords[1] = camera.pointTransformY(first.y);

        lastCoords[0] = camera.pointTransformX(last.x);
        lastCoords[1] = camera.pointTransformY(last.y);
        line.draw(new Vector2f(firstCoords), new Vector2f(lastCoords), color1, color2, camera);
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
        Vector2d first;
        Vector2d last;
        if (!s.first.isSingleton()) {
            first = (((Knot) s.first).knotPoints.get(0)).p.toVector2d();
        } else {
            first = (s.first).p.toVector2d();
        }
        if (!s.last.isSingleton()) {
            last = (((Knot) s.last).knotPoints.get(0)).p.toVector2d();
        } else {
            last = (s.last).p.toVector2d();
        }

        Vector2f firstCoords = new Vector2f(camera.pointTransformX(first.x), camera.pointTransformY(first.y));
        Vector2f lastCoords = new Vector2f(camera.pointTransformX(last.x), camera.pointTransformY(last.y));

        Vector2f newLast = new Vector2f(lastCoords).sub(firstCoords).mul(length).add(firstCoords);

        getDrawing().sdfLine.draw(firstCoords, newLast, color1, new ColorFixedLerp(color1, color2, length), camera);
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
                    Color.random(colorSeed), ps,
                    true, false, false, false, camera);
        } else {
            Drawing.drawPath(shell, lineThickness, c, ps, true, false, false, false, camera);
        }
    }

    /**
     * Draws the tsp path of the pointset ps.
     *
     * @param shell         the path to render (each consecutive pair becomes a line
     *                      segment)
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
            scale = DEFAULT_PATH_SCALE;
        }
        if (shell.size() == 0) {
            return;
        }
        if (dashed) {
            d.sdfLine.setStroke(lineThickness * scale, true, DASH_LENGTH_PATH_PX, 1f, true, true, false);
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
                d.circle.draw(new Vector2f(x, y), CIRCLE_RADIUS * scale, color, camera);
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
     * Draw each manifold segment of {@code k} as a gradient between two palette
     * colors, indexed via {@code colorLookup}. When {@code lookUpPairs} is non-null
     * its (firstId,lastId) entries select the lookup keys; otherwise the segment's
     * own endpoint ids are used.
     *
     * @param k                knot whose manifold segments to draw
     * @param lookUpPairs      per-segment (first,last) key pairs, or null to fall
     *                         back to segment endpoint ids
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
            strokeSegment(s, minLineThickness * camera.ScaleFactor, false, 1f, 0f, camera);
            if (lookUpPairs != null) {
                Pair<Long, Long> lookUpPair = lookUpPairs.get(i);

                if (colorLookup.containsKey(lookUpPair.getFirst())) {
                    Drawing.drawGradientSegment(getDrawing().sdfLine, s,colors.get(colorLookup.get(lookUpPair.getFirst())),
                            colors.get(colorLookup.get(lookUpPair.getSecond())),
                            camera);
                }
            } else {
                if (colorLookup.containsKey((long) s.first.id)) {
                    Drawing.drawGradientSegment(getDrawing().sdfLine, s,colors.get(colorLookup.get((long) s.first.id)),
                            colors.get(colorLookup.get((long) s.last.id)),
                            camera);
                }
            }
        }

    }

    /**
     * Variant of
     * {@link #drawGradientPath(Knot, ArrayList, HashMap, ArrayList, Camera2D, float)}
     * where {@code colorLookup} maps directly from id to {@link Color}, skipping
     * the palette indirection.
     *
     * @param k                knot whose manifold segments to draw
     * @param lookupPairs      per-segment (first,last) key pairs, or null to fall
     *                         back to segment endpoint ids
     * @param colorLookup      maps an id to its endpoint color
     * @param camera           the active scene camera
     * @param minLineThickness base pixel thickness before camera scaling
     */
    public static void drawGradientPath(Knot k, ArrayList<Pair<Long, Long>> lookupPairs,
            HashMap<Long, Color> colorLookup, Camera2D camera, float minLineThickness) {

        for (int i = 0; i < k.manifoldSegments.size(); i++) {
            Segment s = k.manifoldSegments.get(i);
            strokeSegment(s, minLineThickness * camera.ScaleFactor, false, 1f, 0f, camera);
            if (lookupPairs != null) {
                Pair<Long, Long> lookUpPair = lookupPairs.get(i);

                if (colorLookup.containsKey(lookUpPair.getFirst())) {
                    Drawing.drawGradientSegment(getDrawing().sdfLine, s,colorLookup.get(lookUpPair.getFirst()),
                            colorLookup.get(lookUpPair.getSecond()),
                            camera);
                }
            } else {
                if (colorLookup.containsKey((long) s.first.id)) {
                    Drawing.drawGradientSegment(getDrawing().sdfLine, s,colorLookup.get((long) s.first.id),
                            colorLookup.get((long) s.last.id),
                            camera);
                }
            }
        }
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
     * Draw a circle at a screen-space point, scaled by the camera so it grows with
     * zoom.
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
            strokeSegment(s, lineThickness * camera.ScaleFactor, false, 1f, 0f, camera);
            Drawing.drawSegment(s, c, camera);
        }
    }

    /**
     * Reset the cached SDF line stroke to {@link #MIN_THICKNESS} times the camera
     * scale, so subsequent draws share a consistent base width.
     *
     * @param camera the active scene camera
     */
    public static void setScaledStroke(Camera2D camera) {
        Drawing d = getDrawing();
        d.sdfLine.setStroke(MIN_THICKNESS * camera.ScaleFactor, false, 1f, 0f, true, false, false);
    }

}
