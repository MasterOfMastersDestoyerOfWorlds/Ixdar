package ixdar.geometry.mesh.data.sculpt;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.ops.MeshVertexOffset;
import ixdar.geometry.mesh.data.paths.query.NearestVertex;
import ixdar.geometry.mesh.data.paths.query.TriangleBvh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.nodegraph.field.Vector3Field;

/**
 * Sculpts a surface with weighted strokes: chains and fills push nearby vertices along the
 * stroke's mean normal with a falloff measured across it, silhouettes drag the outline onto the
 * stroke, after the faces they reach are refined.
 *
 * <p>
 * Silhouette drag after SilSketch (Zimmermann, Nealen and Alexa 2007).
 */
public final class StrokeSculpt {

    public static final String CREASE_LABEL = "crease";

    public static final String STROKE_SEPARATOR = ";";

    public static final String HEADER_SEPARATOR = ":";

    public static final String POINT_SEPARATOR = ",";

    public static final String FIELD_SEPARATOR = "\\s+";

    public static final String RADIUS_PREFIX = "r=";

    public static final String FILL_FLAG = "fill";

    public static final String MIRROR_FLAG = "mirror";

    public static final String SMOOTH_FLAG = "smooth";

    public static final String SILHOUETTE_FLAG = "silhouette";

    public static final String EYE_PREFIX = "eye=";

    public static final String EYE_VALUE_SEPARATOR = "/";

    public static final int EYE_AND_FORWARD_VALUES = 6;

    public static final int WEIGHTED_POINT_FIELDS = 4;

    public static final int NORMAL_SMOOTHING_PASSES = 2;

    public static final int RELAX_ITERATIONS = 4;

    public static final int DRAG_SMOOTHING_PASSES = 6;

    public static final int MAXIMUM_REFINEMENT_ROUNDS = 4;

    public static final int QUAD_CORNERS = 4;

    public static final float MAXIMUM_SMOOTH_STEP = 0.6f;

    public static final float GATHER_REACH = 2f;

    public static final float FALLOFF_STEEPEST_SLOPE = 1.5396f;

    public static final float MINIMUM_NORMAL_LENGTH = 1e-6f;

    public static final float CONTOUR_DEPTH_SLACK_EDGES = 2f;

    public static final float OUTLINE_PROBE_FRACTION = 0.005f;

    public static final float INNER_CONTOUR_VISIBILITY = 0.5f;

    public static final float SHADOW_FEATHER = 0.5f;

    /** Whether strokes move vertices (mode B) or only crease their chains (mode A). */
    public boolean displace = true;

    /** Distance along the normal a strength-1 stroke moves its chain. */
    public float displacementScale = 0.2f;

    /** Falloff radius of a stroke whose header names none, as an edge-path distance. */
    public float falloffRadius = 0.4f;

    /** Crease weight a strength-1 stroke writes on its chain's edges. */
    public float creaseScale = 2f;

    /** Fraction of the tangential Laplacian each relax iteration applies to moved vertices. */
    public float tangentialRelax = 0.5f;

    /** Multiplier on every stroke of the pass: displacement, drag, smoothing and crease. */
    public float strength = 1f;

    /** Axis the pass is mirrored across and symmetrised about: 0, 1 or 2 for x, y or z, -1 for none. */
    public int symmetryAxis = -1;

    /** Edge length refinement stops at, however thin or close the strokes are. */
    public float minimumEdgeLength = 0.005f;

    /** Fraction of a stroke's width, or of its gap to the nearest other stroke, edges are split below. */
    public float edgesPerWidth = 1f / 3f;

    /**
     * Sculpt a bundle's mesh with strokes written {@code "s [r=R] [fill] [mirror] [smooth]
     * [silhouette] [eye=...]: x y z [w], ..."}; same-sign overlaps keep the larger, opposite signs
     * add. A stroke with an eye moves only what that eye sees. A set {@link #symmetryAxis} needs a
     * symmetric input.
     *
     * @param base    geometry to sculpt; its float crease marks are carried over
     * @param strokes strokes, each a header then its lifted points (silhouettes: view rays) with
     *                optional view weights
     * @throws IllegalArgumentException when a stroke's eye flag is malformed, or a silhouette has none
     * @return the sculpted geometry with the strokes' crease marks merged in
     */
    public GeometryBundle apply(GeometryBundle base, String strokes) {
        // This method must stay under HotSpot's 8000-byte HugeMethodLimit (javap -c shows its size):
        // past it the JIT never compiles it and a skull pass runs about ten times slower.
        MeshTopology mesh = base.mesh();
        if (mesh == null || mesh.vertexCount() == 0 || strokes == null || strokes.isBlank()) {
            return base;
        }

        // Parse every stroke, then expand it into its reflected copies: across the symmetry axis,
        // and across x for a stroke's own mirror flag.
        String[] strokeTexts = strokes.split(STROKE_SEPARATOR);
        int copyBound = strokeTexts.length * QUAD_CORNERS;
        float[] strengthByCopy = new float[copyBound];
        float[] radiusByCopy = new float[copyBound];
        boolean[] fillByCopy = new boolean[copyBound];
        boolean[] smoothByCopy = new boolean[copyBound];
        boolean[] silhouetteByCopy = new boolean[copyBound];
        int[] sourceByCopy = new int[copyBound];
        float[][] pointXyzByCopy = new float[copyBound][];
        float[][] pointWeightByCopy = new float[copyBound][];
        float[][] eyeForwardByCopy = new float[copyBound][];
        int copies = 0;
        Vector3f sample = new Vector3f();
        for (int stroke = 0; stroke < strokeTexts.length; stroke++) {
            String strokeText = strokeTexts[stroke];
            int colon = strokeText.indexOf(HEADER_SEPARATOR);
            if (colon < 0) {
                continue;
            }
            String[] header = strokeText.substring(0, colon).trim().split(FIELD_SEPARATOR);
            float strokeStrength = Math.max(-1f, Math.min(1f, Float.parseFloat(header[0])));
            float radius = falloffRadius;
            boolean fill = false;
            boolean mirror = false;
            boolean smooth = false;
            boolean silhouette = false;
            float[] eyeForward = null;
            for (String flag : header) {
                radius = flag.startsWith(RADIUS_PREFIX)
                        ? Float.parseFloat(flag.substring(RADIUS_PREFIX.length())) : radius;
                fill |= FILL_FLAG.equals(flag);
                mirror |= MIRROR_FLAG.equals(flag);
                smooth |= SMOOTH_FLAG.equals(flag);
                silhouette |= SILHOUETTE_FLAG.equals(flag);
                if (flag.startsWith(EYE_PREFIX)) {
                    String[] values = flag.substring(EYE_PREFIX.length()).split(EYE_VALUE_SEPARATOR);
                    if (values.length != EYE_AND_FORWARD_VALUES) {
                        throw new IllegalArgumentException("a stroke's eye is eye=ex/ey/ez/fx/fy/fz: " + flag);
                    }
                    eyeForward = new float[EYE_AND_FORWARD_VALUES];
                    for (int value = 0; value < EYE_AND_FORWARD_VALUES; value++) {
                        eyeForward[value] = Float.parseFloat(values[value]);
                    }
                }
            }
            if (silhouette && eyeForward == null) {
                throw new IllegalArgumentException("a silhouette stroke needs its view's eye=: " + strokeText);
            }
            String[] pointTexts = strokeText.substring(colon + 1).split(POINT_SEPARATOR);
            float[] pointXyz = new float[pointTexts.length * 3];
            float[] pointWeight = new float[pointTexts.length];
            int pointCount = 0;
            for (String pointText : pointTexts) {
                String[] fields = pointText.trim().split(FIELD_SEPARATOR);
                if (fields.length < 3) {
                    continue;
                }
                for (int axis = 0; axis < 3; axis++) {
                    pointXyz[pointCount * 3 + axis] = Float.parseFloat(fields[axis]);
                }
                pointWeight[pointCount++] = fields.length >= WEIGHTED_POINT_FIELDS
                        ? Float.parseFloat(fields[3]) : 1f;
            }
            int firstAxis = symmetryAxis;
            int secondAxis = mirror && symmetryAxis != 0 ? 0 : -1;
            for (int reflection = 0; reflection < QUAD_CORNERS; reflection++) {
                boolean acrossFirst = (reflection & 1) != 0;
                boolean acrossSecond = (reflection & 2) != 0;
                if (acrossFirst && firstAxis < 0 || acrossSecond && secondAxis < 0) {
                    continue;
                }
                float[] reflectedXyz = Arrays.copyOf(pointXyz, pointCount * 3);
                float[] reflectedEye = eyeForward == null ? null : eyeForward.clone();
                for (int axis = 0; axis < 3; axis++) {
                    if ((acrossFirst && axis == firstAxis) ^ (acrossSecond && axis == secondAxis)) {
                        for (int point = 0; point < pointCount; point++) {
                            reflectedXyz[point * 3 + axis] = -reflectedXyz[point * 3 + axis];
                        }
                        if (reflectedEye != null) {
                            reflectedEye[axis] = -reflectedEye[axis];
                            reflectedEye[axis + 3] = -reflectedEye[axis + 3];
                        }
                    }
                }
                // A fill reaching across the mirror plane into its own reflection joins it as one
                // region, so the two halves union instead of meeting in a groove on the midline.
                int joined = -1;
                for (int earlier = copies - 1; earlier >= 0 && sourceByCopy[earlier] == stroke && fill
                        && joined < 0; earlier--) {
                    float[] earlierXyz = pointXyzByCopy[earlier];
                    for (int point = 0; point < reflectedXyz.length && joined < 0; point += 3) {
                        sample.set(reflectedXyz[point], reflectedXyz[point + 1], reflectedXyz[point + 2]);
                        int nearest = nearestPoint(earlierXyz, sample) * 3;
                        joined = nearest >= 0 && sample.distanceSquared(earlierXyz[nearest], earlierXyz[nearest + 1],
                                earlierXyz[nearest + 2]) < radius * radius ? earlier : -1;
                    }
                }
                if (joined >= 0) {
                    float[] earlierXyz = pointXyzByCopy[joined];
                    float[] earlierWeight = pointWeightByCopy[joined];
                    pointXyzByCopy[joined] = Arrays.copyOf(earlierXyz, earlierXyz.length + reflectedXyz.length);
                    System.arraycopy(reflectedXyz, 0, pointXyzByCopy[joined], earlierXyz.length, reflectedXyz.length);
                    pointWeightByCopy[joined] = Arrays.copyOf(earlierWeight, earlierWeight.length + pointCount);
                    System.arraycopy(pointWeight, 0, pointWeightByCopy[joined], earlierWeight.length, pointCount);
                    continue;
                }
                strengthByCopy[copies] = strokeStrength;
                radiusByCopy[copies] = radius;
                fillByCopy[copies] = fill;
                smoothByCopy[copies] = smooth;
                silhouetteByCopy[copies] = silhouette;
                sourceByCopy[copies] = stroke;
                pointXyzByCopy[copies] = reflectedXyz;
                pointWeightByCopy[copies] = Arrays.copyOf(pointWeight, pointCount);
                eyeForwardByCopy[copies++] = reflectedEye;
            }
        }

        // Refine: split the faces within a stroke's reach whose edges are longer than a third of
        // its width, or of its gap to the nearest other stroke, until they are not. The rounds end
        // with the mirror twins of the final mesh's vertices, which the symmetrising reads.
        float[] targetByCopy = new float[copies];
        for (int copy = 0; copy < copies; copy++) {
            float gap = radiusByCopy[copy];
            float[] points = pointXyzByCopy[copy];
            int pointCount = points.length / 3;
            for (int other = 0; other < copies && pointCount > 0 && !silhouetteByCopy[copy]; other++) {
                float[] otherPoints = pointXyzByCopy[other];
                if (sourceByCopy[other] == sourceByCopy[copy] || silhouetteByCopy[other]
                        || otherPoints.length == 0) {
                    continue;
                }
                double meanNearest = 0.0;
                for (int point = 0; point < pointCount; point++) {
                    sample.set(points[point * 3], points[point * 3 + 1], points[point * 3 + 2]);
                    int nearest = nearestPoint(otherPoints, sample) * 3;
                    meanNearest += sample.distance(otherPoints[nearest], otherPoints[nearest + 1],
                            otherPoints[nearest + 2]) / pointCount;
                }
                gap = Math.min(gap, (float) meanNearest);
            }
            // A steep stroke stretches its faces up its slope, so it refines finer by that slope.
            float slope = smoothByCopy[copy] ? 0f : FALLOFF_STEEPEST_SLOPE * Math.abs(strengthByCopy[copy] * strength)
                    * displacementScale / radiusByCopy[copy];
            targetByCopy[copy] = Math.max(minimumEdgeLength, gap * edgesPerWidth / Math.max(1f, slope));
        }
        GeometryBundle carried = base;
        float[] existingCreases = EdgeMarks.floats(base, CREASE_LABEL);
        int[] twinByActiveVertex = new int[0];
        Vector3f position = new Vector3f();
        Vector3f other = new Vector3f();
        for (int round = 0;; round++) {
            int vertexCount = mesh.vertexCount();
            int[] activeVertexById = new int[mesh.vertexIdBound()];
            float[] vertexXyz = packedPositions(mesh, activeVertexById);
            NearestVertex nearestVertex = NearestVertex.over(mesh);
            if (symmetryAxis >= 0) {
                twinByActiveVertex = new int[vertexCount];
                for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
                    position.set(vertexXyz[activeVertex * 3], vertexXyz[activeVertex * 3 + 1],
                            vertexXyz[activeVertex * 3 + 2]);
                    position.setComponent(symmetryAxis, -position.get(symmetryAxis));
                    twinByActiveVertex[activeVertex] = activeVertexById[nearestVertex.find(position.x, position.y,
                            position.z)];
                }
            }
            if (round == MAXIMUM_REFINEMENT_ROUNDS || !displace) {
                break;
            }
            float[] requiredByActiveVertex = new float[vertexCount];
            Arrays.fill(requiredByActiveVertex, Float.POSITIVE_INFINITY);
            for (int copy = 0; copy < copies; copy++) {
                float[] points = pointXyzByCopy[copy];
                float reach = radiusByCopy[copy];
                if (silhouetteByCopy[copy] || points.length == 0) {
                    continue;
                }
                // Reach is measured across the stroke's direction, as its falloff is, so the
                // walls of an earlier pit under the stroke refine too; depth is bounded by one
                // full-strength pass beyond the falloff gather.
                float depthReach = reach * GATHER_REACH + displacementScale;
                float[] low = { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY };
                float[] high = { Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY };
                Vector3f direction = new Vector3f();
                for (int coordinate = 0; coordinate < points.length; coordinate++) {
                    low[coordinate % 3] = Math.min(low[coordinate % 3], points[coordinate] - depthReach);
                    high[coordinate % 3] = Math.max(high[coordinate % 3], points[coordinate] + depthReach);
                    if (coordinate % 3 == 2 && mesh.vertexNormal(nearestVertex.find(points[coordinate - 2],
                            points[coordinate - 1], points[coordinate]), other).length() >= MINIMUM_NORMAL_LENGTH) {
                        direction.add(other.normalize());
                    }
                }
                if (direction.length() >= MINIMUM_NORMAL_LENGTH) {
                    direction.normalize();
                }
                for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
                    int at = activeVertex * 3;
                    if (requiredByActiveVertex[activeVertex] <= targetByCopy[copy]
                            || vertexXyz[at] < low[0] || vertexXyz[at] > high[0] || vertexXyz[at + 1] < low[1]
                            || vertexXyz[at + 1] > high[1] || vertexXyz[at + 2] < low[2]
                            || vertexXyz[at + 2] > high[2]) {
                        continue;
                    }
                    for (int point = 0; point < points.length; point += 3) {
                        position.set(vertexXyz[at] - points[point], vertexXyz[at + 1] - points[point + 1],
                                vertexXyz[at + 2] - points[point + 2]);
                        float along = position.dot(direction);
                        if (Math.abs(along) <= depthReach
                                && position.lengthSquared() - along * along <= reach * reach) {
                            // Refinement skips only what faces away; the depth test is left to the
                            // displacement, which casts one ray per vertex instead of one per round.
                            requiredByActiveVertex[activeVertex] = eyeForwardByCopy[copy] == null
                                    || visibility(null, copy, mesh, activeVertex,
                                            mesh.vertexNormal(mesh.vertexIdAt(activeVertex), other), 1f,
                                            eyeForwardByCopy[copy], null, null) > 0f ? targetByCopy[copy]
                                                    : requiredByActiveVertex[activeVertex];
                            break;
                        }
                    }
                }
            }
            for (int activeVertex = 0; activeVertex < twinByActiveVertex.length; activeVertex++) {
                requiredByActiveVertex[activeVertex] = Math.min(requiredByActiveVertex[activeVertex],
                        requiredByActiveVertex[twinByActiveVertex[activeVertex]]);
            }
            boolean[] splitByFaceId = new boolean[mesh.faceIdBound()];
            int[] splitFrontier = new int[mesh.faceCount()];
            int frontierLength = 0;
            boolean splitting = false;
            for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
                int faceId = mesh.faceIdAt(activeFace);
                int corners = mesh.faceVertexCount(faceId);
                float required = Float.POSITIVE_INFINITY;
                float longest = 0f;
                for (int corner = 0; corner < corners; corner++) {
                    int vertexId = mesh.faceVertexAt(faceId, corner);
                    required = Math.min(required, requiredByActiveVertex[activeVertexById[vertexId]]);
                    mesh.vertexPosition(vertexId, position);
                    mesh.vertexPosition(mesh.faceVertexAt(faceId, (corner + 1) % corners), other);
                    longest = Math.max(longest, position.distance(other));
                }
                splitting |= longest > required;
                splitByFaceId[faceId] = longest > required || corners > QUAD_CORNERS;
                if (splitByFaceId[faceId]) {
                    splitFrontier[frontierLength++] = faceId;
                }
            }
            if (!splitting) {
                break;
            }
            // Close the split region: a face left whole may have one split edge (a triangle or a
            // quad, closed by transition faces) or two opposite ones (a quad); any other face
            // splits too, worked outward from the split faces. The rule reads topology only, so it
            // mirrors.
            boolean[] splitByEdgeId = new boolean[mesh.edgeIdBound()];
            while (frontierLength > 0) {
                int faceId = splitFrontier[--frontierLength];
                int faceCorners = mesh.faceVertexCount(faceId);
                for (int corner = 0; corner < faceCorners; corner++) {
                    splitByEdgeId[mesh.edgeBetween(mesh.faceVertexAt(faceId, corner),
                            mesh.faceVertexAt(faceId, (corner + 1) % faceCorners))] = true;
                }
                for (int corner = 0; corner < faceCorners * 2; corner++) {
                    int neighbourId = mesh.edgeFace(mesh.edgeBetween(mesh.faceVertexAt(faceId, corner / 2),
                            mesh.faceVertexAt(faceId, (corner / 2 + 1) % faceCorners)), corner % 2);
                    if (neighbourId < 0 || splitByFaceId[neighbourId]) {
                        continue;
                    }
                    int corners = mesh.faceVertexCount(neighbourId);
                    int splitEdges = 0;
                    int splitMask = 0;
                    for (int neighbourCorner = 0; neighbourCorner < corners; neighbourCorner++) {
                        if (splitByEdgeId[mesh.edgeBetween(mesh.faceVertexAt(neighbourId, neighbourCorner),
                                mesh.faceVertexAt(neighbourId, (neighbourCorner + 1) % corners))]) {
                            splitEdges++;
                            splitMask |= 1 << neighbourCorner;
                        }
                    }
                    boolean closable = splitEdges <= 1 && corners <= QUAD_CORNERS
                            || corners == QUAD_CORNERS && splitEdges == 2 && (splitMask == 0b0101 || splitMask == 0b1010);
                    if (!closable) {
                        splitByFaceId[neighbourId] = true;
                        splitFrontier[frontierLength++] = neighbourId;
                    }
                }
            }
            HalfEdgeMesh refined = new HalfEdgeMesh();
            int[] refinedById = new int[mesh.vertexIdBound()];
            for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
                refinedById[mesh.vertexIdAt(activeVertex)] = refined.addVertex(vertexXyz[activeVertex * 3],
                        vertexXyz[activeVertex * 3 + 1], vertexXyz[activeVertex * 3 + 2]);
            }
            int[] midpointByEdgeId = new int[mesh.edgeIdBound()];
            Arrays.fill(midpointByEdgeId, -1);
            int[] edgeEnds = new int[2];
            for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
                int edgeId = mesh.edgeIdAt(activeEdge);
                if (splitByEdgeId[edgeId]) {
                    edgeEnds[0] = mesh.halfEdgeVertex(mesh.edgeHalfEdge(edgeId));
                    edgeEnds[1] = mesh.halfEdgeEndVertex(mesh.edgeHalfEdge(edgeId));
                    midpointByEdgeId[edgeId] = refined.addVertex(curvedCentroid(mesh, edgeEnds, 2, position));
                }
            }
            for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
                int faceId = mesh.faceIdAt(activeFace);
                int corners = mesh.faceVertexCount(faceId);
                int[] faceVertexIds = new int[corners];
                int[] cornerIds = new int[corners];
                int[] midpoints = new int[corners];
                int splitEdges = 0;
                int firstSplit = -1;
                for (int corner = 0; corner < corners; corner++) {
                    int vertexId = mesh.faceVertexAt(faceId, corner);
                    faceVertexIds[corner] = vertexId;
                    cornerIds[corner] = refinedById[vertexId];
                    midpoints[corner] = midpointByEdgeId[mesh.edgeBetween(vertexId,
                            mesh.faceVertexAt(faceId, (corner + 1) % corners))];
                    if (midpoints[corner] >= 0) {
                        firstSplit = firstSplit < 0 ? corner : firstSplit;
                        splitEdges++;
                    }
                }
                if (splitByFaceId[faceId] && corners == 3) {
                    refined.addFace(cornerIds[0], midpoints[0], midpoints[2]);
                    refined.addFace(midpoints[0], cornerIds[1], midpoints[1]);
                    refined.addFace(midpoints[2], midpoints[1], cornerIds[2]);
                    refined.addFace(midpoints[0], midpoints[1], midpoints[2]);
                } else if (splitByFaceId[faceId]) {
                    int centerId = refined.addVertex(curvedCentroid(mesh, faceVertexIds, corners, position));
                    for (int corner = 0; corner < corners; corner++) {
                        refined.addFace(cornerIds[corner], midpoints[corner], centerId,
                                midpoints[(corner + corners - 1) % corners]);
                    }
                } else if (splitEdges == 0) {
                    refined.addFace(cornerIds);
                } else {
                    int start = cornerIds[firstSplit];
                    int end = cornerIds[(firstSplit + 1) % corners];
                    int after = cornerIds[(firstSplit + 2) % corners];
                    int middle = midpoints[firstSplit];
                    if (corners == 3) {
                        refined.addFace(start, middle, after);
                        refined.addFace(middle, end, after);
                    } else if (splitEdges == 1) {
                        int last = cornerIds[(firstSplit + 3) % corners];
                        refined.addFace(start, middle, last);
                        refined.addFace(middle, end, after);
                        refined.addFace(middle, after, last);
                    } else {
                        int last = cornerIds[(firstSplit + 3) % corners];
                        int opposite = midpoints[(firstSplit + 2) % corners];
                        refined.addFace(start, middle, opposite, last);
                        refined.addFace(middle, end, after, opposite);
                    }
                }
            }
            refined.computeNormals();
            if (existingCreases != null) {
                float[] refinedCreases = new float[refined.edgeIdBound()];
                for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
                    int edgeId = mesh.edgeIdAt(activeEdge);
                    float crease = edgeId < existingCreases.length ? existingCreases[edgeId] : 0f;
                    int halfEdge = mesh.edgeHalfEdge(edgeId);
                    int from = refinedById[mesh.halfEdgeVertex(halfEdge)];
                    int to = refinedById[mesh.halfEdgeEndVertex(halfEdge)];
                    int middle = midpointByEdgeId[edgeId];
                    if (crease > 0f && middle < 0) {
                        refinedCreases[refined.edgeBetween(from, to)] = crease;
                    } else if (crease > 0f) {
                        refinedCreases[refined.edgeBetween(from, middle)] = crease;
                        refinedCreases[refined.edgeBetween(middle, to)] = crease;
                    }
                }
                existingCreases = refinedCreases;
            }
            mesh = refined;
            carried = GeometryBundle.ofMesh(refined);
        }

        int vertexCount = mesh.vertexCount();
        int[] activeVertexById = new int[mesh.vertexIdBound()];
        packedPositions(mesh, activeVertexById);
        float[] raiseByActiveVertex = new float[vertexCount];
        float[] lowerByActiveVertex = new float[vertexCount];
        float[] smoothByActiveVertex = new float[vertexCount];
        float[] dragByActiveVertexXyz = new float[vertexCount * 3];
        float[] strokeCreaseByEdgeId = new float[mesh.edgeIdBound()];
        EdgeDistances falloff = new EdgeDistances();
        StrokeChain chain = new StrokeChain();
        Vector3f normal = new Vector3f();
        float[] facingById = new float[mesh.vertexIdBound()];
        float[] raiseDirectionXyz = new float[vertexCount * 3];
        float[] lowerDirectionXyz = new float[vertexCount * 3];
        // Normals averaged over the one-ring, NORMAL_SMOOTHING_PASSES times: raw normals of an
        // already sculpted cage are noisy enough to fold the surface.
        float[] normalXyz = new float[vertexCount * 3];
        Vector3f center = mesh.center(new Vector3f());
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            mesh.vertexNormal(vertexId, normal);
            if (normal.length() < MINIMUM_NORMAL_LENGTH) {
                mesh.vertexPosition(vertexId, normal).sub(center);
            }
            normal.normalize();
            normalXyz[activeVertex * 3] = normal.x;
            normalXyz[activeVertex * 3 + 1] = normal.y;
            normalXyz[activeVertex * 3 + 2] = normal.z;
        }
        for (int pass = 0; pass < NORMAL_SMOOTHING_PASSES; pass++) {
            normalXyz = ringSum(mesh, activeVertexById, normalXyz, false);
        }
        TriangleBvh bvh = displace ? TriangleBvh.over(mesh, new int[mesh.triangleCount()]) : null;
        int[] bvhStack = new int[bvh == null ? 0 : Math.max(1, bvh.nodeCount)];
        float[][] visibilityByCopy = new float[copies][];
        EdgeDistances shadow = new EdgeDistances();
        int[] hiddenIds = new int[vertexCount];
        for (int copy = 0; copy < copies; copy++) {
            float strokeStrength = strengthByCopy[copy] * strength;
            float radius = radiusByCopy[copy];
            float[] pointXyz = pointXyzByCopy[copy];
            float[] pointWeight = pointWeightByCopy[copy];
            float[] eyeForward = eyeForwardByCopy[copy];
            boolean silhouette = silhouetteByCopy[copy];
            int pointCount = pointWeight.length;
            int[] sources = new int[Math.max(1, pointCount)];
            float[] dragXyz = new float[0];
            int sourceCount = 0;
            if (silhouette) {
                // An over-sketched outline: the contour vertices (vertex-normal facing changes
                // sign across an edge) whose drag onto the stroke, in the plane through the vertex
                // perpendicular to the view axis, is within reach and inside the stroke's ends.
                // Only contours the eye sees count, and of those the outline (the view ray just
                // past it meets nothing) wins over inner contours when the stroke reaches both.
                boolean[] outlineBySource = new boolean[vertexCount];
                boolean outlineReached = false;
                for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
                    int vertexId = mesh.vertexIdAt(activeVertex);
                    mesh.vertexPosition(vertexId, position).sub(eyeForward[0], eyeForward[1], eyeForward[2])
                            .normalize();
                    facingById[vertexId] = mesh.vertexNormal(vertexId, normal).normalize().dot(position);
                }
                sources = new int[vertexCount];
                dragXyz = new float[vertexCount * 3];
                float[] strokeAtDepth = new float[pointCount * 3];
                float[] best = new float[3];
                for (int activeVertex = 0; activeVertex < vertexCount && pointCount >= 2; activeVertex++) {
                    int vertexId = mesh.vertexIdAt(activeVertex);
                    float facing = facingById[vertexId];
                    boolean contour = false;
                    for (int adjacency = 0; adjacency < mesh.vertexEdgeCount(vertexId) && !contour; adjacency++) {
                        float neighbour = facingById[mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertexId, adjacency),
                                vertexId)];
                        contour = (neighbour > 0f) != (facing > 0f) && Math.abs(facing) <= Math.abs(neighbour);
                    }
                    if (!contour) {
                        continue;
                    }
                    mesh.vertexPosition(vertexId, position);
                    float depth = (position.x - eyeForward[0]) * eyeForward[3]
                            + (position.y - eyeForward[1]) * eyeForward[4]
                            + (position.z - eyeForward[2]) * eyeForward[5];
                    for (int ray = 0; ray < pointCount; ray++) {
                        float along = pointXyz[ray * 3] * eyeForward[3] + pointXyz[ray * 3 + 1] * eyeForward[4]
                                + pointXyz[ray * 3 + 2] * eyeForward[5];
                        for (int axis = 0; axis < 3; axis++) {
                            strokeAtDepth[ray * 3 + axis] = eyeForward[axis] + pointXyz[ray * 3 + axis] * depth / along;
                        }
                    }
                    float bestDistance = Float.POSITIVE_INFINITY;
                    boolean bestClamped = true;
                    for (int segment = 0; segment + 1 < pointCount; segment++) {
                        int start = segment * 3;
                        int end = start + 3;
                        float segmentX = strokeAtDepth[end] - strokeAtDepth[start];
                        float segmentY = strokeAtDepth[end + 1] - strokeAtDepth[start + 1];
                        float segmentZ = strokeAtDepth[end + 2] - strokeAtDepth[start + 2];
                        float lengthSquared = segmentX * segmentX + segmentY * segmentY + segmentZ * segmentZ;
                        float fraction = lengthSquared == 0f ? 0f : ((position.x - strokeAtDepth[start]) * segmentX
                                + (position.y - strokeAtDepth[start + 1]) * segmentY
                                + (position.z - strokeAtDepth[start + 2]) * segmentZ) / lengthSquared;
                        boolean clamped = segment == 0 && fraction < 0f || segment + 2 == pointCount && fraction > 1f;
                        fraction = Math.max(0f, Math.min(1f, fraction));
                        float nearestX = strokeAtDepth[start] + segmentX * fraction;
                        float nearestY = strokeAtDepth[start + 1] + segmentY * fraction;
                        float nearestZ = strokeAtDepth[start + 2] + segmentZ * fraction;
                        float distance = position.distance(nearestX, nearestY, nearestZ);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            bestClamped = clamped;
                            best[0] = nearestX - position.x;
                            best[1] = nearestY - position.y;
                            best[2] = nearestZ - position.z;
                        }
                    }
                    float seen = bestClamped || bestDistance > radius ? 0f : visibility(null, copy, mesh,
                            activeVertex, null, CONTOUR_DEPTH_SLACK_EDGES, eyeForward, bvh, bvhStack);
                    if (seen == 0f) {
                        continue;
                    }
                    outlineBySource[sourceCount] = seen == 1f;
                    outlineReached |= seen == 1f;
                    System.arraycopy(best, 0, dragXyz, sourceCount * 3, 3);
                    sources[sourceCount++] = vertexId;
                }
                int kept = 0;
                for (int source = 0; source < sourceCount; source++) {
                    if (outlineBySource[source] || !outlineReached) {
                        System.arraycopy(dragXyz, source * 3, dragXyz, kept * 3, 3);
                        sources[kept++] = sources[source];
                    }
                }
                sourceCount = kept;
            } else if (fillByCopy[copy]) {
                // Every vertex within the sample spacing of a sample seeds the search, so a region
                // refined finer than its samples is reached solid; on a coarser mesh each sample
                // still takes its nearest vertex.
                float spacingSquared = Float.POSITIVE_INFINITY;
                for (int point = 0; point < pointCount; point++) {
                    for (int otherPoint = point + 1; otherPoint < pointCount; otherPoint++) {
                        spacingSquared = Math.min(spacingSquared, Vector3f.distanceSquared(pointXyz[point * 3],
                                pointXyz[point * 3 + 1], pointXyz[point * 3 + 2], pointXyz[otherPoint * 3],
                                pointXyz[otherPoint * 3 + 1], pointXyz[otherPoint * 3 + 2]));
                    }
                }
                sources = new int[vertexCount];
                boolean[] taken = new boolean[mesh.vertexIdBound()];
                for (int point = 0; point < pointCount; point++) {
                    int nearestId = -1;
                    float nearestDistance = Float.POSITIVE_INFINITY;
                    for (int activeVertex = 0; activeVertex < vertexCount && pointWeight[point] > 0f; activeVertex++) {
                        int vertexId = mesh.vertexIdAt(activeVertex);
                        float distance = mesh.vertexPosition(vertexId, position).distanceSquared(
                                pointXyz[point * 3], pointXyz[point * 3 + 1], pointXyz[point * 3 + 2]);
                        if (distance < nearestDistance) {
                            nearestDistance = distance;
                            nearestId = vertexId;
                        }
                        if (distance <= spacingSquared && !taken[vertexId]) {
                            taken[vertexId] = true;
                            sources[sourceCount++] = vertexId;
                        }
                    }
                    if (nearestId >= 0 && !taken[nearestId]) {
                        taken[nearestId] = true;
                        sources[sourceCount++] = nearestId;
                    }
                }
            } else {
                chain.snap(mesh, pointXyz, pointCount);
                sources = chain.vertexIds;
                sourceCount = sources.length;
                float meanWeight = 0f;
                for (int source = 0; source < sourceCount; source++) {
                    int nearest = nearestPoint(pointXyz, mesh.vertexPosition(sources[source], position));
                    meanWeight += (nearest < 0 ? 0f : pointWeight[nearest]) / sourceCount;
                }
                for (int edgeId : smoothByCopy[copy] ? new int[0] : chain.edgeIds) {
                    strokeCreaseByEdgeId[edgeId] = Math.max(strokeCreaseByEdgeId[edgeId],
                            Math.abs(strokeStrength) * creaseScale * meanWeight);
                }
            }
            if (!displace || sourceCount == 0) {
                continue;
            }
            // One direction for the whole stroke, its seeds' mean normal, and a falloff measured
            // across it: per-vertex normals over an earlier pass's walls point sideways, and
            // distances through an earlier pit's depth cut a second stroke short of its rim.
            Vector3f direction = new Vector3f();
            for (int source = 0; source < sourceCount; source++) {
                int at = activeVertexById[sources[source]] * 3;
                direction.add(normal.set(normalXyz[at], normalXyz[at + 1], normalXyz[at + 2]).normalize());
            }
            direction.normalize();
            // The edge search only gathers the surface within reach; chains and fills measure their
            // falloff straight to the drawn stroke, since edge paths over mixed-density faces
            // zigzag and turn a smooth rim into the mesh's staircase.
            falloff.expand(mesh, sources, sourceCount, silhouette ? radius : radius * GATHER_REACH, -1,
                    silhouette ? null : direction);
            // A chain or fill moves only what its camera sees, and fades out over a feather before
            // what it cannot, so the edge of an occluder's shadow does not fold; a silhouette's
            // drag spreads to the far side of the outline it moves.
            int hiddenCount = 0;
            boolean seeing = !silhouette && eyeForward != null && bvh != null;
            for (int settled = 0; settled < falloff.settledLength && seeing; settled++) {
                int vertexId = falloff.settledVertexIds[settled];
                int at = activeVertexById[vertexId] * 3;
                if (falloff.distanceByVertexId[vertexId] < radius && visibility(visibilityByCopy, copy, mesh, at / 3,
                        normal.set(normalXyz[at], normalXyz[at + 1], normalXyz[at + 2]), 1f, eyeForward, bvh,
                        bvhStack) == 0f) {
                    hiddenIds[hiddenCount++] = vertexId;
                }
            }
            shadow.expand(mesh, hiddenIds, hiddenCount, radius * SHADOW_FEATHER, -1, null);
            for (int settled = 0; settled < falloff.settledLength; settled++) {
                int vertexId = falloff.settledVertexIds[settled];
                float fraction = falloff.distanceByVertexId[vertexId] / radius;
                float kernel = (1f - fraction * fraction) * (1f - fraction * fraction);
                if (!silhouette) {
                    // A chain takes the largest weighted bump of its segments. A fill takes the
                    // union of its samples' bumps, one minus the product of what each leaves
                    // uncovered: the largest alone scallops the rim between samples.
                    kernel = 0f;
                    float uncovered = 1f;
                    int elements = fillByCopy[copy] || pointCount == 1 ? pointCount : pointCount - 1;
                    for (int element = 0; element < elements; element++) {
                        int start = element * 3;
                        int end = fillByCopy[copy] || pointCount == 1 ? start : start + 3;
                        mesh.vertexPosition(vertexId, position).sub(pointXyz[start], pointXyz[start + 1],
                                pointXyz[start + 2]);
                        position.fma(-position.dot(direction), direction);
                        other.set(pointXyz[end] - pointXyz[start], pointXyz[end + 1] - pointXyz[start + 1],
                                pointXyz[end + 2] - pointXyz[start + 2]);
                        other.fma(-other.dot(direction), direction);
                        float lengthSquared = other.lengthSquared();
                        float along = lengthSquared == 0f ? 0f
                                : Math.max(0f, Math.min(1f, position.dot(other) / lengthSquared));
                        float elementFraction = position.fma(-along, other).length() / radius;
                        if (elementFraction < 1f) {
                            float weight = pointWeight[element] + (pointWeight[end / 3] - pointWeight[element]) * along;
                            float bump = weight * (1f - elementFraction * elementFraction)
                                    * (1f - elementFraction * elementFraction);
                            kernel = Math.max(kernel, bump);
                            uncovered *= 1f - bump;
                        }
                    }
                    kernel = fillByCopy[copy] ? 1f - uncovered : kernel;
                }
                int activeVertex = activeVertexById[vertexId];
                if (seeing && kernel > 0f) {
                    int at = activeVertex * 3;
                    kernel *= visibility(visibilityByCopy, copy, mesh, activeVertex, normal.set(normalXyz[at],
                            normalXyz[at + 1], normalXyz[at + 2]), 1f, eyeForward, bvh, bvhStack)
                            * Math.min(1f, shadow.distanceByVertexId[vertexId] / (radius * SHADOW_FEATHER));
                }
                float offset = strokeStrength * displacementScale * kernel;
                if (silhouette) {
                    int origin = falloff.originByVertexId[vertexId] * 3;
                    float scale = Math.abs(strokeStrength) * kernel;
                    float dragX = dragXyz[origin] * scale;
                    float dragY = dragXyz[origin + 1] * scale;
                    float dragZ = dragXyz[origin + 2] * scale;
                    int offsetIndex = activeVertex * 3;
                    float current = dragByActiveVertexXyz[offsetIndex] * dragByActiveVertexXyz[offsetIndex]
                            + dragByActiveVertexXyz[offsetIndex + 1] * dragByActiveVertexXyz[offsetIndex + 1]
                            + dragByActiveVertexXyz[offsetIndex + 2] * dragByActiveVertexXyz[offsetIndex + 2];
                    if (dragX * dragX + dragY * dragY + dragZ * dragZ > current) {
                        dragByActiveVertexXyz[offsetIndex] = dragX;
                        dragByActiveVertexXyz[offsetIndex + 1] = dragY;
                        dragByActiveVertexXyz[offsetIndex + 2] = dragZ;
                    }
                    continue;
                }
                if (smoothByCopy[copy]) {
                    smoothByActiveVertex[activeVertex] = Math.max(smoothByActiveVertex[activeVertex],
                            Math.min(1f, MAXIMUM_SMOOTH_STEP * Math.abs(offset) / displacementScale));
                    continue;
                }
                // Same-sign strokes keep the larger offset but blend their directions by offset, so
                // a stroke meeting its mirror copy on the midline leaves no seam.
                float[] directionXyz = offset > 0f ? raiseDirectionXyz : lowerDirectionXyz;
                raiseByActiveVertex[activeVertex] = Math.max(raiseByActiveVertex[activeVertex], offset);
                lowerByActiveVertex[activeVertex] = Math.min(lowerByActiveVertex[activeVertex], offset);
                directionXyz[activeVertex * 3] += Math.abs(offset) * direction.x;
                directionXyz[activeVertex * 3 + 1] += Math.abs(offset) * direction.y;
                directionXyz[activeVertex * 3 + 2] += Math.abs(offset) * direction.z;
            }
        }

        MeshTopology sculpted = mesh;
        if (displace) {
            // Each vertex took the drag of its nearest contour vertex, a field with seams between
            // neighbours that pulled different ways; blurring it keeps those seams from folding.
            for (int pass = 0; pass < DRAG_SMOOTHING_PASSES; pass++) {
                dragByActiveVertexXyz = ringSum(mesh, activeVertexById, dragByActiveVertexXyz, true);
            }
            float[] offsetXyz = new float[vertexCount * 3];
            for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
                int at = activeVertex * 3;
                normal.set(raiseDirectionXyz[at], raiseDirectionXyz[at + 1], raiseDirectionXyz[at + 2]);
                normal.mul(normal.length() < MINIMUM_NORMAL_LENGTH ? 0f
                        : raiseByActiveVertex[activeVertex] / normal.length());
                other.set(lowerDirectionXyz[at], lowerDirectionXyz[at + 1], lowerDirectionXyz[at + 2]);
                normal.fma(other.length() < MINIMUM_NORMAL_LENGTH ? 0f
                        : lowerByActiveVertex[activeVertex] / other.length(), other);
                offsetXyz[at] = normal.x + dragByActiveVertexXyz[at];
                offsetXyz[at + 1] = normal.y + dragByActiveVertexXyz[at + 1];
                offsetXyz[at + 2] = normal.z + dragByActiveVertexXyz[at + 2];
            }
            sculpted = MeshVertexOffset.apply(mesh, Vector3Field.copyOf(offsetXyz));

            // Relax what moved tangentially, which evens out stretched quads without shrinking
            // the bumps, and smooth fully where smooth strokes asked for it.
            float[] relaxWeight = new float[vertexCount];
            boolean relaxing = false;
            for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
                boolean moved = raiseByActiveVertex[activeVertex] != 0f
                        || lowerByActiveVertex[activeVertex] != 0f
                        || dragByActiveVertexXyz[activeVertex * 3] != 0f
                        || dragByActiveVertexXyz[activeVertex * 3 + 1] != 0f
                        || dragByActiveVertexXyz[activeVertex * 3 + 2] != 0f;
                relaxWeight[activeVertex] = moved ? tangentialRelax : 0f;
                relaxing |= moved || smoothByActiveVertex[activeVertex] > 0f;
            }
            if (relaxing) {
                int[] sculptedActiveById = new int[sculpted.vertexIdBound()];
                float[] original = packedPositions(sculpted, sculptedActiveById);
                float[] relaxed = original.clone();
                Vector3f laplacian = new Vector3f();
                for (int iteration = 0; iteration < RELAX_ITERATIONS; iteration++) {
                    float[] next = relaxed.clone();
                    float[] ring = ringSum(sculpted, sculptedActiveById, relaxed, false);
                    for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
                        float smooth = smoothByActiveVertex[activeVertex];
                        if (relaxWeight[activeVertex] == 0f && smooth == 0f) {
                            continue;
                        }
                        int vertexId = sculpted.vertexIdAt(activeVertex);
                        int at = activeVertex * 3;
                        laplacian.set(ring[at], ring[at + 1], ring[at + 2]).sub(relaxed[at], relaxed[at + 1], relaxed[at + 2])
                                .div(Math.max(1, sculpted.vertexEdgeCount(vertexId)))
                                .sub(relaxed[at], relaxed[at + 1], relaxed[at + 2]);
                        sculpted.vertexNormal(vertexId, normal);
                        float along = normal.dot(laplacian);
                        for (int axis = 0; axis < 3; axis++) {
                            next[activeVertex * 3 + axis] += smooth * laplacian.get(axis)
                                    + relaxWeight[activeVertex] * (laplacian.get(axis) - along * normal.get(axis));
                        }
                    }
                    relaxed = next;
                }
                for (int index = 0; index < relaxed.length; index++) {
                    relaxed[index] -= original[index];
                }
                sculpted = MeshVertexOffset.apply(sculpted, Vector3Field.copyOf(relaxed));
            }

            // Symmetrise: each vertex's displacement becomes the mean of its own and its mirror
            // twin's reflected displacement, so the pass leaves a symmetric input symmetric.
            if (symmetryAxis >= 0) {
                float[] moved = packedPositions(sculpted, null);
                float[] unmoved = packedPositions(mesh, null);
                for (int index = 0; index < moved.length; index++) {
                    moved[index] -= unmoved[index];
                }
                float[] symmetric = new float[vertexCount * 3];
                for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
                    int twin = twinByActiveVertex[activeVertex];
                    for (int axis = 0; axis < 3; axis++) {
                        float reflected = axis == symmetryAxis ? -moved[twin * 3 + axis] : moved[twin * 3 + axis];
                        symmetric[activeVertex * 3 + axis] = (moved[activeVertex * 3 + axis] + reflected) * 0.5f;
                    }
                }
                sculpted = MeshVertexOffset.apply(mesh, Vector3Field.copyOf(symmetric));
            }
        }

        float[] creases = new float[sculpted.edgeIdBound()];
        for (int edgeId = 0; edgeId < strokeCreaseByEdgeId.length; edgeId++) {
            float crease = Math.max(strokeCreaseByEdgeId[edgeId],
                    existingCreases != null && edgeId < existingCreases.length ? existingCreases[edgeId] : 0f);
            if (crease > 0f && mesh.hasEdge(edgeId)) {
                // The vertex offset keeps vertex order but not ids, so find the edge by its ends.
                int halfEdge = mesh.edgeHalfEdge(edgeId);
                int sculptedEdge = sculpted == mesh ? edgeId : sculpted.edgeBetween(
                        sculpted.vertexIdAt(activeVertexById[mesh.halfEdgeVertex(halfEdge)]),
                        sculpted.vertexIdAt(activeVertexById[mesh.halfEdgeEndVertex(halfEdge)]));
                if (sculptedEdge >= 0) {
                    creases[sculptedEdge] = Math.max(creases[sculptedEdge], crease);
                }
            }
        }
        return EdgeMarks.with(carried.withMesh(sculpted), CREASE_LABEL, creases);
    }

    /**
     * The point of a packed set nearest a position.
     *
     * @param pointXyz the points, packed xyz
     * @param position where to measure from
     * @return the nearest point's index, the first on a tie, or -1 for an empty set
     */
    private static int nearestPoint(float[] pointXyz, Vector3f position) {
        int nearest = -1;
        float nearestDistance = Float.POSITIVE_INFINITY;
        for (int point = 0; point < pointXyz.length / 3; point++) {
            float distance = position.distanceSquared(pointXyz[point * 3], pointXyz[point * 3 + 1],
                    pointXyz[point * 3 + 2]);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = point;
            }
        }
        return nearest;
    }

    /**
     * A mesh's vertex positions packed xyz in dense vertex order, the order a vertex offset keeps.
     *
     * @param mesh             surface to read
     * @param activeVertexById receives each sparse vertex id's dense index when not null; at least
     *                         the mesh's vertex id bound long
     * @return the packed positions, three floats per vertex
     */
    private static float[] packedPositions(MeshTopology mesh, int[] activeVertexById) {
        Vector3f position = new Vector3f();
        float[] xyz = new float[mesh.vertexCount() * 3];
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            if (activeVertexById != null) {
                activeVertexById[vertexId] = activeVertex;
            }
            mesh.vertexPosition(vertexId, position);
            xyz[activeVertex * 3] = position.x;
            xyz[activeVertex * 3 + 1] = position.y;
            xyz[activeVertex * 3 + 2] = position.z;
        }
        return xyz;
    }

    /**
     * One pass of one-ring smoothing over a per-vertex xyz field: each vertex's own value plus its
     * neighbours', summed or averaged.
     *
     * @param mesh             surface whose edges give the rings
     * @param activeVertexById dense index of each sparse vertex id
     * @param xyz              the field, packed xyz by dense vertex index; left unchanged
     * @param mean             whether to divide each sum by the number of values in it
     * @return the smoothed field, a new array
     */
    private static float[] ringSum(MeshTopology mesh, int[] activeVertexById, float[] xyz, boolean mean) {
        float[] summed = xyz.clone();
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            int neighbours = mesh.vertexEdgeCount(vertexId);
            for (int adjacency = 0; adjacency < neighbours; adjacency++) {
                int neighbour = activeVertexById[mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertexId, adjacency), vertexId)];
                for (int axis = 0; axis < 3; axis++) {
                    summed[activeVertex * 3 + axis] += xyz[neighbour * 3 + axis];
                }
            }
            for (int axis = 0; axis < 3 && mean; axis++) {
                summed[activeVertex * 3 + axis] /= neighbours + 1;
            }
        }
        return summed;
    }

    /**
     * Whether a stroke's camera sees a vertex: it faces the eye and, as a depth test against the
     * mesh, no surface lies between them. A grazing wall still counts as seen, so a fill moves it.
     *
     * @param cacheByEye   visibility by stroke copy then dense vertex index, NaN where not yet asked,
     *                     rows made on first use; null to compute without caching
     * @param eye          the copy's row in {@code cacheByEye}
     * @param mesh         surface the vertex is on, the one {@code bvh} was built over
     * @param activeVertex vertex asked about, a dense vertex index
     * @param normal       the vertex's normal, any length, or null for a contour vertex: then one on
     *                     the outline, {@link #INNER_CONTOUR_VISIBILITY} in front of more surface
     * @param slackEdges   how many of the vertex's longest edges in front of it still count as
     *                     itself, so a grazing ray through its own faces does not hide it
     * @param eyeForward   the camera's eye then axis
     * @param bvh          triangle tree over {@code mesh}, or null to test facing only
     * @param stack        scratch node stack of at least the tree's node count, null with no tree
     * @return one when seen, zero when not, and for a contour vertex as {@code normal} says
     */
    private static float visibility(float[][] cacheByEye, int eye, MeshTopology mesh, int activeVertex,
            Vector3f normal, float slackEdges, float[] eyeForward, TriangleBvh bvh, int[] stack) {
        if (cacheByEye != null && cacheByEye[eye] == null) {
            cacheByEye[eye] = new float[mesh.vertexCount()];
            Arrays.fill(cacheByEye[eye], Float.NaN);
        }
        if (cacheByEye != null && !Float.isNaN(cacheByEye[eye][activeVertex])) {
            return cacheByEye[eye][activeVertex];
        }
        int vertexId = mesh.vertexIdAt(activeVertex);
        Vector3f position = mesh.vertexPosition(vertexId, new Vector3f());
        float[] toVertex = { position.x - eyeForward[0], position.y - eyeForward[1], position.z - eyeForward[2] };
        float distance = (float) Math.sqrt(toVertex[0] * toVertex[0] + toVertex[1] * toVertex[1]
                + toVertex[2] * toVertex[2]);
        float facing = normal == null || normal.length() < MINIMUM_NORMAL_LENGTH ? 1f
                : -(normal.x * toVertex[0] + normal.y * toVertex[1] + normal.z * toVertex[2])
                        / (normal.length() * distance);
        float seen = facing > 0f ? 1f : 0f;
        if (seen > 0f && bvh != null) {
            float longestEdge = 0f;
            for (int adjacency = 0; adjacency < mesh.vertexEdgeCount(vertexId); adjacency++) {
                longestEdge = Math.max(longestEdge, mesh.edgeLength(mesh.vertexEdgeAt(vertexId, adjacency)));
            }
            seen = bvh.nearestHit(eyeForward, toVertex, 1.0 - slackEdges * longestEdge / distance, stack) >= 0
                    ? 0f : seen;
        }
        if (seen > 0f && bvh != null && normal == null) {
            // A seen contour is on the outline when the view ray just past it, outward across the
            // view, meets nothing; otherwise it is an inner contour in front of more surface.
            Vector3f outward = mesh.vertexNormal(vertexId, new Vector3f());
            Vector3f along = new Vector3f(toVertex[0], toVertex[1], toVertex[2]).div(distance);
            outward.fma(-outward.dot(along), along);
            seen = INNER_CONTOUR_VISIBILITY;
            if (outward.length() >= MINIMUM_NORMAL_LENGTH) {
                outward.normalize(OUTLINE_PROBE_FRACTION * distance).add(toVertex[0], toVertex[1], toVertex[2]);
                float[] probe = { outward.x, outward.y, outward.z };
                seen = bvh.nearestHit(eyeForward, probe, Double.POSITIVE_INFINITY, stack) < 0 ? 1f : seen;
            }
        }
        if (cacheByEye != null) {
            cacheByEye[eye][activeVertex] = seen;
        }
        return seen;
    }

    /**
     * Place a split vertex on the curved surface its corners sample, not their flat centroid, so
     * refined faces keep the surrounding curvature. Phong tessellation (Boubekeur and Alexa 2008),
     * shape factor one half.
     *
     * @param mesh      surface whose vertex normals give the corners' tangent planes
     * @param vertexIds corners, sparse vertex ids
     * @param count     how many leading entries of {@code vertexIds} are corners
     * @param dest      receives the position
     * @return {@code dest}: the centroid moved by half the mean of its projections onto the corners'
     *         tangent planes
     */
    private static Vector3f curvedCentroid(MeshTopology mesh, int[] vertexIds, int count, Vector3f dest) {
        Vector3f corner = new Vector3f();
        Vector3f normal = new Vector3f();
        dest.zero();
        for (int index = 0; index < count; index++) {
            dest.add(mesh.vertexPosition(vertexIds[index], corner));
        }
        dest.div(count);
        float liftX = 0f;
        float liftY = 0f;
        float liftZ = 0f;
        for (int index = 0; index < count; index++) {
            mesh.vertexPosition(vertexIds[index], corner);
            mesh.vertexNormal(vertexIds[index], normal);
            if (normal.length() < MINIMUM_NORMAL_LENGTH) {
                continue;
            }
            normal.normalize();
            float height = (dest.x - corner.x) * normal.x + (dest.y - corner.y) * normal.y
                    + (dest.z - corner.z) * normal.z;
            liftX -= height * normal.x;
            liftY -= height * normal.y;
            liftZ -= height * normal.z;
        }
        return dest.add(liftX * 0.5f / count, liftY * 0.5f / count, liftZ * 0.5f / count);
    }
}
