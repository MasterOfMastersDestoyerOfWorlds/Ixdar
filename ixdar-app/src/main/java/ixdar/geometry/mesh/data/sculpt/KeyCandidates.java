package ixdar.geometry.mesh.data.sculpt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.IntStream;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import ixdar.geometry.grid2d.PolygonScan;
import ixdar.geometry.mesh.data.analysis.curvature.ImplicitFit;
import ixdar.geometry.mesh.data.paths.query.NearestVertex;
import ixdar.geometry.mesh.data.paths.query.TriangleBvh;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.graphics.cameras.Camera3D;

/**
 * The strokes a stroke key may choose from, each held on the base in the template cell that sees it
 * most squarely: fills over the regions an offset field sinks or raises at several scales, and
 * chains along the field's ridge and valley lines.
 */
public final class KeyCandidates {

    public static final String SINK_LAYER = "sink_fills";

    public static final String RAISE_LAYER = "raise_fills";

    public static final String RIDGE_LAYER = "ridges";

    public static final String VALLEY_LAYER = "valleys";

    public static final String BOWL_PROFILE = " data-profile=\"bowl\"";

    public static final int CELL_SAMPLES = 24;

    public static final float VISIBILITY_SLACK = 1e-3f;

    public static final float ERODED_FRACTION = 0.6f;

    public static final float SHRINK_TOWARD_CENTRE = 0.5f;

    public static final float SHIFT_FRACTION = 0.5f;

    public static final float NARROWER = 0.7f;

    public static final float WIDER = 1.4f;

    public static final float FILL_RADIUS_SMALL = 0.35f;

    public static final float FILL_RADIUS_LARGE = 0.7f;

    public static final float CHAIN_LENGTH_RADII = 10f;

    public static final float HULL_COVERAGE_LATTICE_STEPS = 24f;

    public static final float MINIMUM_HULL_COVERAGE = 0.75f;

    public static final float REPEATED_CHAIN_RADII = 1f;

    /** Smoothing iterations of the offset field per scale, finest first, as in scale-space blob detection. */
    public int[] smoothingIterations = { 4, 16, 64, 256 };

    /** Smoothing iterations of the offset field each set of crest lines is found at. */
    public int[] crestSmoothingIterations = { 2, 16 };

    /** Quantiles of |field| at each scale whose level sets become fill regions. */
    public float[] regionQuantiles = { 0.75f, 0.9f };

    /** Smallest falloff radius a candidate gets, world units. */
    public float minimumRadius = 0.03f;

    /** Largest falloff radius a candidate gets, world units. */
    public float maximumRadius = 0.3f;

    /**
     * Falloff radius a chain along one of the goal's own lines is offered at, world units; refinement
     * tries it narrower and wider. Also how far a goal line point may lie from the nearest surface
     * vertex's landing on the goal before the line breaks there.
     */
    public float goalLineRadius = 0.06f;

    /** The goal lines the last {@link #chains} carried onto the surface, whole before they are cut. */
    public final List<PackedPoints> carriedLines = new ArrayList<>();

    /** Which goal line each of {@link #carriedLines} was carried from; a line broken in the carry gives several. */
    public final List<Integer> carriedFrom = new ArrayList<>();

    /** Strokes whose place is the goal's: their variants change width only, never position. */
    public final Set<AnchoredStroke> placed = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Crest lines whose strength, the integral of the offset's |second derivative| along them (OBS04,
     * eq. 5), falls below this are dropped.
     */
    public float minimumCrestStrength = 1.5f;

    /** Axis the key's pass mirrors across, -1 for none: candidates on its negative side are left to the mirror. */
    public int symmetryAxis = -1;

    /** The base the candidates are held on. */
    public final MeshTopology base;

    /** Anchors on the base. */
    public final FaceAnchors baseAnchors;

    /** Triangle tree over the base, for which cell sees a point. */
    public final TriangleBvh baseTriangles;

    /** The template whose cells the candidates are drawn in. */
    public final StrokeSketch template;

    /** Each template cell's camera. */
    public final Camera3D[] cameras;

    /**
     * Candidates over one base and template.
     *
     * @param base     the base mesh the sculpt pass reads
     * @param template the multiview SVG the key is written into, for its cell cameras
     */
    public KeyCandidates(MeshTopology base, StrokeSketch template) {
        this.base = base;
        this.template = template;
        baseAnchors = new FaceAnchors(base);
        baseTriangles = TriangleBvh.over(base, new int[base.triangleCount()]);
        cameras = new Camera3D[template.viewCameras.length];
        for (int cell = 0; cell < cameras.length; cell++) {
            cameras[cell] = template.cellCamera(cell);
        }
    }

    /**
     * Fill candidates: at each smoothing scale, every connected region beyond a quantile of the
     * field's magnitude becomes flat and bowl polygons, unless its hull spans ground it does not cover.
     *
     * @param surface the surface the field lives on
     * @param field   signed field by dense vertex index, + where the surface should rise
     * @param area    area per dense vertex
     * @return the candidates, each with depth 1
     */
    public List<AnchoredStroke> fills(MeshTopology surface, float[] field, float[] area) {
        int vertexCount = surface.vertexCount();
        PackedPoints vertices = surface.packedPositions(null);
        int[][] neighbours = surface.oneRings();
        List<AnchoredStroke> candidates = new ArrayList<>();
        float[] smoothed = field;
        int done = 0;
        int[] region = new int[vertexCount];
        int[] label = new int[vertexCount];
        int[] boundary = new int[vertexCount];
        EdgeDistances inset = new EdgeDistances();
        for (int iterations : smoothingIterations) {
            smoothed = OffsetCrests.smoothed(neighbours, smoothed, iterations - done);
            done = iterations;
            float[] magnitudes = new float[vertexCount];
            for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
                magnitudes[activeVertex] = Math.abs(smoothed[activeVertex]);
            }
            Arrays.sort(magnitudes);
            for (float quantile : regionQuantiles) {
                float level = magnitudes[Math.min(vertexCount - 1, (int) (quantile * vertexCount))];
                for (float sign = 1f; sign >= -1f && level > 0f; sign -= 2f) {
                    Arrays.fill(label, 0);
                    for (int seed = 0; seed < vertexCount; seed++) {
                        if (label[seed] != 0 || sign * smoothed[seed] <= level) {
                            continue;
                        }
                        int size = 0;
                        region[size++] = seed;
                        label[seed] = 1;
                        double regionArea = 0.0;
                        Vector3f centre = new Vector3f();
                        int boundaryCount = 0;
                        for (int head = 0; head < size; head++) {
                            int member = region[head];
                            regionArea += area[member];
                            centre.add(vertices.at(member).mul(area[member]));
                            boolean edge = false;
                            for (int neighbour : neighbours[member]) {
                                boolean inside = sign * smoothed[neighbour] > level;
                                edge |= !inside;
                                if (inside && label[neighbour] == 0) {
                                    label[neighbour] = 1;
                                    region[size++] = neighbour;
                                }
                            }
                            if (edge) {
                                boundary[boundaryCount++] = surface.vertexIdAt(member);
                            }
                        }
                        centre.div((float) regionArea);
                        float equivalentRadius = (float) Math.sqrt(regionArea / Math.PI);
                        if (equivalentRadius < minimumRadius || symmetryAxis >= 0
                                && centre.get(symmetryAxis) < -minimumRadius) {
                            continue;
                        }
                        inset.expand(surface, boundary, boundaryCount, Float.POSITIVE_INFINITY, -1, null);
                        for (float fraction : new float[] { FILL_RADIUS_SMALL, FILL_RADIUS_LARGE }) {
                            float radius = Math.max(minimumRadius, Math.min(maximumRadius, fraction * equivalentRadius));
                            PackedPoints interiorPoints = new PackedPoints(size);
                            int kept = 0;
                            for (int index = 0; index < size; index++) {
                                int member = region[index];
                                if (inset.distance(surface.vertexIdAt(member)) >= ERODED_FRACTION * radius) {
                                    interiorPoints.set(kept++, vertices.at(member));
                                }
                            }
                            if (kept < 3) {
                                kept = 0;
                                for (int index = 0; index < size; index++) {
                                    interiorPoints.set(kept++,
                                            vertices.at(region[index]).sub(centre).mul(SHRINK_TOWARD_CENTRE).add(centre));
                                }
                            }
                            int cell = squarestCell(interiorPoints, kept);
                            if (cell < 0) {
                                continue;
                            }
                            float[] pixels = new float[kept * 2];
                            float[] pixel = new float[2];
                            for (int index = 0; index < kept; index++) {
                                template.cellPixel(cameras[cell], interiorPoints.at(index), pixel);
                                pixels[index * 2] = pixel[0];
                                pixels[index * 2 + 1] = pixel[1];
                            }
                            int[] hull = PolygonScan.convexHull(pixels);
                            float[] hullPixels = new float[hull.length * 2];
                            for (int index = 0; index < hull.length; index++) {
                                hullPixels[index * 2] = pixels[hull[index] * 2];
                                hullPixels[index * 2 + 1] = pixels[hull[index] * 2 + 1];
                            }
                            float[] box = { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                                Float.NEGATIVE_INFINITY };
                            for (int index = 0; index < kept; index++) {
                                box[0] = Math.min(box[0], pixels[index * 2]);
                                box[1] = Math.min(box[1], pixels[index * 2 + 1]);
                                box[2] = Math.max(box[2], pixels[index * 2]);
                                box[3] = Math.max(box[3], pixels[index * 2 + 1]);
                            }
                            float step = Math.max(1f, Math.max(box[2] - box[0], box[3] - box[1]) / HULL_COVERAGE_LATTICE_STEPS);
                            int columns = (int) ((box[2] - box[0]) / step) + 1;
                            boolean[] occupied = new boolean[columns * ((int) ((box[3] - box[1]) / step) + 1)];
                            for (int index = 0; index < kept; index++) {
                                occupied[(int) ((pixels[index * 2 + 1] - box[1]) / step) * columns
                                        + (int) ((pixels[index * 2] - box[0]) / step)] = true;
                            }
                            float[] lattice = PolygonScan.coveredPoints(hullPixels, step);
                            int filled = 0;
                            for (int index = 0; index * 2 < lattice.length; index++) {
                                int column = (int) ((lattice[index * 2] - box[0]) / step);
                                int row = (int) ((lattice[index * 2 + 1] - box[1]) / step);
                                boolean near = false;
                                for (int other = 0; other < 9 && !near; other++) {
                                    int neighbourColumn = column + other % 3 - 1;
                                    int neighbourRow = row + other / 3 - 1;
                                    near = neighbourColumn >= 0 && neighbourColumn < columns && neighbourRow >= 0
                                            && neighbourRow * columns + neighbourColumn < occupied.length
                                            && occupied[neighbourRow * columns + neighbourColumn];
                                }
                                filled += near ? 1 : 0;
                            }
                            if (lattice.length > 0 && filled < MINIMUM_HULL_COVERAGE * (lattice.length / 2)) {
                                continue;
                            }
                            PackedPoints outline = interiorPoints.gather(hull);
                            for (int profile = 0; profile < 2 && outline.size() >= 3; profile++) {
                                candidates.add(stroke(true, outline, outline.size(), cell, radius, profile == 1));
                            }
                        }
                    }
                }
            }
        }
        return candidates;
    }

    /**
     * Chain candidates along ridge and valley lines, cut into pieces and offered once each; a piece
     * found again (the same crease at a coarser scale) is dropped.
     *
     * @param surface   the surface the offsets live on
     * @param offset    signed offset by dense vertex index
     * @param normalXyz unit direction of each offset by dense vertex index
     * @param goalLines the goal's own ridge and valley lines in the base's frame, each carried onto the
     *                  surface vertex whose offset lands nearest it; null for the offset field's lines
     * @return the candidates, each with depth 1
     */
    public List<AnchoredStroke> chains(MeshTopology surface, float[] offset, PackedPoints normalXyz,
            List<PackedPoints> goalLines) {
        List<PackedPoints> lines = new ArrayList<>();
        List<Float> widths = new ArrayList<>();
        if (goalLines != null) {
            PackedPoints surfacePoints = surface.packedPositions(null);
            PackedPoints landingXyz = new PackedPoints(surfacePoints.size());
            for (int activeVertex = 0; activeVertex < landingXyz.size(); activeVertex++) {
                landingXyz.set(activeVertex, normalXyz.at(activeVertex).mul(offset[activeVertex])
                        .add(surfacePoints.at(activeVertex)));
            }
            PackedPoints box = landingXyz.bounds();
            double low = Math.min(box.get(0, 0), Math.min(box.get(0, 1), box.get(0, 2)));
            double high = Math.max(box.get(1, 0), Math.max(box.get(1, 1), box.get(1, 2)));
            ImplicitFit.Buckets landings = new ImplicitFit.Buckets(landingXyz, goalLineRadius, low - goalLineRadius,
                    high - low + 2.0 * goalLineRadius);
            carriedLines.clear();
            carriedFrom.clear();
            for (int goalIndex = 0; goalIndex < goalLines.size(); goalIndex++) {
                PackedPoints goalLine = goalLines.get(goalIndex);
                PackedPoints carried = new PackedPoints();
                for (int point = 0; point <= goalLine.size(); point++) {
                    int nearest = point < goalLine.size() ? landings.nearest(goalLine.at(point), goalLineRadius, null)
                            : -1;
                    if (nearest >= 0) {
                        carried.append(surfacePoints.at(nearest));
                    } else if (carried.size() > 0) {
                        lines.add(carried);
                        widths.add(goalLineRadius);
                        carriedLines.add(carried);
                        carriedFrom.add(goalIndex);
                        carried = new PackedPoints();
                    }
                }
            }
            PackedPoints allXyz = PackedPoints.concat(lines.toArray(new PackedPoints[0]));
            int[] lineOfPoint = new int[allXyz.size()];
            int filled = 0;
            for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
                int count = lines.get(lineIndex).size();
                Arrays.fill(lineOfPoint, filled, filled + count, lineIndex);
                filled += count;
            }
            double reach = 2.0 * maximumRadius;
            ImplicitFit.Buckets linePoints = new ImplicitFit.Buckets(allXyz, reach, low - reach, high - low + 2.0 * reach);
            NearestVertex vertices = NearestVertex.over(surface);
            int[] activeVertexById = surface.activeIndexById();
            filled = 0;
            for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
                int count = lines.get(lineIndex).size();
                float[] gaps = new float[count];
                int own = lineIndex;
                for (int point = 0; point < count; point++, filled++) {
                    int other = linePoints.nearest(allXyz.at(filled), reach, index -> lineOfPoint[index] == own);
                    gaps[point] = other < 0 ? (float) reach
                            : (float) Math.sqrt(allXyz.distanceSquared(other, filled));
                }
                Arrays.sort(gaps);
                widths.set(lineIndex, OffsetCrests.fittedWidth(lines.get(lineIndex), gaps[count / 2] / 2f, surface,
                        vertices, activeVertexById, offset));
            }
        } else {
            int[][] rings = surface.oneRings();
            for (int iterations : crestSmoothingIterations) {
                OffsetCrests crests = new OffsetCrests();
                crests.minimumStrength = minimumCrestStrength;
                crests.detect(surface, OffsetCrests.smoothed(rings, offset, iterations), offset, normalXyz);
                lines.addAll(crests.lines);
                widths.addAll(crests.widthByLine);
            }
        }
        List<AnchoredStroke> candidates = new ArrayList<>();
        List<PackedPoints> offeredPieces = new ArrayList<>();
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            PackedPoints line = lines.get(lineIndex);
            int lineLength = line.size();
            double length = line.arcLength();
            float radius = Math.max(minimumRadius, Math.min(maximumRadius, widths.get(lineIndex)));
            float pieceRadius = goalLines != null ? Math.max(radius, goalLineRadius) : radius;
            float pieceLength = Math.max((float) length / Math.max(1, (int) Math.ceil(length
                    / (CHAIN_LENGTH_RADII * pieceRadius))), StrokeSketch.DEFAULT_SPACING);
            PackedPoints piece = new PackedPoints(lineLength);
            int count = 0;
            float along = 0f;
            for (int point = 0; point < lineLength; point++) {
                Vector3f position = line.at(point);
                float step = count == 0 ? 0f : (float) Math.sqrt(piece.distanceSquared(count - 1, position));
                if (count > 0 && step < StrokeSketch.DEFAULT_SPACING && point < lineLength - 1) {
                    continue;
                }
                along += step;
                piece.set(count++, position);
                if (along >= pieceLength || point == lineLength - 1) {
                    float meanSide = 0f;
                    for (int index = 0; index < count && symmetryAxis >= 0; index++) {
                        meanSide += piece.get(index, symmetryAxis) / count;
                    }
                    boolean repeated = false;
                    PackedPoints points = piece.gather(IntStream.range(0, count).toArray());
                    for (int other = 0; other < offeredPieces.size() && !repeated && count >= 2; other++) {
                        double total = 0.0;
                        for (float distance : SculptStroke.nearestDistances(points, offeredPieces.get(other))) {
                            total += distance;
                        }
                        repeated = total / count < REPEATED_CHAIN_RADII * radius;
                    }
                    int cell = count >= 2 && !repeated && (symmetryAxis < 0 || meanSide >= -minimumRadius)
                            ? squarestCell(piece, count) : -1;
                    if (cell >= 0) {
                        candidates.add(stroke(false, piece, count, cell, radius, false));
                        if (goalLines != null) {
                            placed.add(candidates.get(candidates.size() - 1));
                        }
                        offeredPieces.add(points);
                    }
                    piece.set(0, piece.at(count - 1));
                    count = 1;
                    along = 0f;
                }
            }
        }
        return candidates;
    }

    /**
     * Shape variants of a candidate for coordinate descent: narrower, wider, and shifted half its
     * radius each way along two tangents, in the same cell.
     *
     * @param stroke the candidate
     * @return the variants, each with depth 1
     */
    public List<AnchoredStroke> variants(AnchoredStroke stroke) {
        int count = stroke.pointCount();
        PackedPoints points = new PackedPoints(count);
        Vector3f position = new Vector3f();
        Vector3f centre = new Vector3f();
        for (int point = 0; point < count; point++) {
            FaceAnchors.position(base, stroke.anchors, point * FaceAnchors.FIELDS, position);
            points.set(point, position);
            centre.add(position.div(count));
        }
        float radius = radius(stroke);
        boolean bowl = StrokeSketch.BOWL_ATTRIBUTE.matcher(stroke.attributes).find();
        List<AnchoredStroke> variants = new ArrayList<>();
        for (float scale : new float[] { NARROWER, WIDER }) {
            float scaled = Math.max(minimumRadius, Math.min(maximumRadius, radius * scale));
            if (scaled != radius && !(scale > 1f && placed.contains(stroke))) {
                variants.add(stroke(stroke.polygon, points, count, stroke.cell, scaled, bowl));
            }
        }
        if (placed.contains(stroke)) {
            placed.addAll(variants);
            return variants;
        }
        Matrix3f frame = base.tangentFrame(baseAnchors.nearestVertex.nearest(centre, Float.POSITIVE_INFINITY));
        Vector3f first = frame.getColumn(0, new Vector3f());
        Vector3f second = frame.getColumn(1, new Vector3f());
        for (int shift = 0; shift < 4; shift++) {
            Vector3f step = new Vector3f(shift < 2 ? first : second).mul((shift % 2 == 0 ? 1f : -1f)
                    * SHIFT_FRACTION * radius);
            PackedPoints moved = points.copy();
            moved.transformPositions(new Matrix4f().translation(step));
            variants.add(stroke(stroke.polygon, moved, count, stroke.cell, radius, bowl));
        }
        return variants;
    }

    /**
     * A stroke's falloff radius, read from its attributes.
     *
     * @param stroke the stroke
     * @return its data-radius
     */
    public static float radius(AnchoredStroke stroke) {
        Matcher radius = StrokeSketch.RADIUS_ATTRIBUTE.matcher(stroke.attributes);
        return radius.find() ? Float.parseFloat(radius.group(1)) : Float.NaN;
    }

    /**
     * A candidate's attributes with its depth set: a chain labelled ridge or valley by the sign, a
     * fill keeping its profile.
     *
     * @param stroke the candidate
     * @param depth  signed depth, world units
     * @return the attributes text
     */
    public static String withDepth(AnchoredStroke stroke, float depth) {
        String kind = stroke.polygon ? "" : String.format(Locale.ROOT, "data-kind=\"%s\" ", depth >= 0f
                ? StrokeSketch.RIDGE : StrokeSketch.VALLEY);
        return String.format(Locale.ROOT, "%sdata-depth=\"%.5f\" data-radius=\"%.5f\"%s", kind, depth, radius(stroke),
                stroke.polygon ? StrokeSketch.BOWL_ATTRIBUTE.matcher(stroke.attributes).find()
                        ? BOWL_PROFILE : "" : " fill=\"none\"");
    }

    /**
     * The layer a key stroke is written in, by its kind and sign.
     *
     * @param stroke the stroke
     * @param depth  its signed depth
     * @return one of the layer names
     */
    public static String layer(AnchoredStroke stroke, float depth) {
        return stroke.polygon ? depth < 0f ? SINK_LAYER : RAISE_LAYER : depth < 0f ? VALLEY_LAYER : RIDGE_LAYER;
    }

    /**
     * The template cell that sees points most squarely: the largest sum over a sample of them of
     * the cosine between the base normal and the way to the eye, counting only points in the cell
     * and in front of the base.
     *
     * @param points the points
     * @param count  how many
     * @return the cell, or -1 when no cell sees any
     */
    public int squarestCell(PackedPoints points, int count) {
        int[] stack = new int[Math.max(1, baseTriangles.nodeCount)];
        float[] pixel = new float[2];
        Vector3f point = new Vector3f();
        Vector3f normal = new Vector3f();
        int best = -1;
        float bestScore = 0f;
        int stride = Math.max(1, count / CELL_SAMPLES);
        for (int cell = 0; cell < cameras.length; cell++) {
            Camera3D camera = cameras[cell];
            float score = 0f;
            for (int sample = 0; sample < count; sample += stride) {
                point.set(points.get(sample, 0), points.get(sample, 1), points.get(sample, 2));
                if (!template.cellPixel(camera, point, pixel) || pixel[0] < 0f || pixel[1] < 0f
                        || pixel[0] >= template.cellWidth || pixel[1] >= template.cellHeight) {
                    continue;
                }
                base.vertexNormal(baseAnchors.nearestVertex.nearest(point, Float.POSITIVE_INFINITY), normal).normalize();
                point.sub(camera.position);
                float facing = -normal.dot(point) / Math.max(SculptStroke.MINIMUM_NORMAL_LENGTH, point.length());
                if (facing > 0f && baseTriangles.nearestHit(camera.position, point, 1.0 - VISIBILITY_SLACK,
                        stack) < 0) {
                    score += facing;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = cell;
            }
        }
        return best;
    }

    /**
     * A candidate held on the base: its points anchored, its cell, unit depth.
     *
     * @param polygon whether it is a fill
     * @param points  its points (a fill's outline)
     * @param count   how many
     * @param cell    the cell it is drawn in
     * @param radius  its falloff radius
     * @param bowl    whether a fill's floor curves up to its rim
     * @return the stroke, its layer and id left for the key to set
     */
    public AnchoredStroke stroke(boolean polygon, PackedPoints points, int count, int cell, float radius, boolean bowl) {
        AnchoredStroke stroke = new AnchoredStroke();
        stroke.polygon = polygon;
        stroke.cell = cell;
        stroke.anchors = new float[count * FaceAnchors.FIELDS];
        for (int point = 0; point < count; point++) {
            baseAnchors.anchor(points.at(point), stroke.anchors, point * FaceAnchors.FIELDS);
        }
        stroke.attributes = String.format(Locale.ROOT, "data-radius=\"%.5f\"%s", radius, bowl ? BOWL_PROFILE : "");
        stroke.attributes = withDepth(stroke, 1f);
        return stroke;
    }
}
