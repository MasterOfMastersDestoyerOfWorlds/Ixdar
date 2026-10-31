package ixdar.geometry.mesh.data.sculpt;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.ops.MeshVertexOffset;
import ixdar.geometry.mesh.data.paths.query.NearestVertex;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.nodegraph.field.Vector3Field;

/**
 * Sculpts a surface with weighted strokes: chains and fills push nearby vertices along their
 * normals with a geodesic falloff, silhouettes drag the outline onto the stroke, after the faces
 * they reach are refined.
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

    public static final float MINIMUM_NORMAL_LENGTH = 1e-6f;

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
     * [eye=...]: x y z [w], ..."}; same-sign overlaps keep the larger, opposite signs add. A set
     * {@link #symmetryAxis} needs a symmetric input.
     *
     * @param base    geometry to sculpt; its float crease marks are carried over
     * @param strokes strokes, each a header then its lifted points (silhouettes: view rays) with
     *                optional view weights
     * @throws IllegalArgumentException when a silhouette stroke's eye flag is malformed
     * @return the sculpted geometry with the strokes' crease marks merged in
     */
    public GeometryBundle apply(GeometryBundle base, String strokes) {
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
        int[] sourceByCopy = new int[copyBound];
        float[][] pointXyzByCopy = new float[copyBound][];
        float[][] pointWeightByCopy = new float[copyBound][];
        float[][] eyeForwardByCopy = new float[copyBound][];
        int copies = 0;
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
            float[] eyeForward = null;
            for (String flag : header) {
                radius = flag.startsWith(RADIUS_PREFIX)
                        ? Float.parseFloat(flag.substring(RADIUS_PREFIX.length())) : radius;
                fill |= FILL_FLAG.equals(flag);
                mirror |= MIRROR_FLAG.equals(flag);
                smooth |= SMOOTH_FLAG.equals(flag);
                if (flag.startsWith(EYE_PREFIX)) {
                    String[] values = flag.substring(EYE_PREFIX.length()).split(EYE_VALUE_SEPARATOR);
                    if (values.length != EYE_AND_FORWARD_VALUES) {
                        throw new IllegalArgumentException(
                                "silhouette stroke needs eye=ex/ey/ez/fx/fy/fz: " + flag);
                    }
                    eyeForward = new float[EYE_AND_FORWARD_VALUES];
                    for (int value = 0; value < EYE_AND_FORWARD_VALUES; value++) {
                        eyeForward[value] = Float.parseFloat(values[value]);
                    }
                }
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
                strengthByCopy[copies] = strokeStrength;
                radiusByCopy[copies] = radius;
                fillByCopy[copies] = fill;
                smoothByCopy[copies] = smooth;
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
            for (int other = 0; other < copies && pointCount > 0 && eyeForwardByCopy[copy] == null; other++) {
                float[] otherPoints = pointXyzByCopy[other];
                if (sourceByCopy[other] == sourceByCopy[copy] || eyeForwardByCopy[other] != null
                        || otherPoints.length == 0) {
                    continue;
                }
                double meanNearest = 0.0;
                for (int point = 0; point < pointCount; point++) {
                    float nearest = Float.POSITIVE_INFINITY;
                    for (int otherPoint = 0; otherPoint < otherPoints.length; otherPoint += 3) {
                        nearest = Math.min(nearest, Vector3f.distanceSquared(points[point * 3],
                                points[point * 3 + 1], points[point * 3 + 2], otherPoints[otherPoint],
                                otherPoints[otherPoint + 1], otherPoints[otherPoint + 2]));
                    }
                    meanNearest += Math.sqrt(nearest) / pointCount;
                }
                gap = Math.min(gap, (float) meanNearest);
            }
            targetByCopy[copy] = Math.max(minimumEdgeLength, gap * edgesPerWidth);
        }
        GeometryBundle carried = base;
        float[] existingCreases = EdgeMarks.floats(base, CREASE_LABEL);
        int[] twinByActiveVertex = new int[0];
        Vector3f position = new Vector3f();
        Vector3f other = new Vector3f();
        for (int round = 0;; round++) {
            int vertexCount = mesh.vertexCount();
            int[] activeVertexById = new int[mesh.vertexIdBound()];
            float[] vertexXyz = new float[vertexCount * 3];
            for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
                int vertexId = mesh.vertexIdAt(activeVertex);
                activeVertexById[vertexId] = activeVertex;
                mesh.vertexPosition(vertexId, position);
                vertexXyz[activeVertex * 3] = position.x;
                vertexXyz[activeVertex * 3 + 1] = position.y;
                vertexXyz[activeVertex * 3 + 2] = position.z;
            }
            if (symmetryAxis >= 0) {
                NearestVertex twins = NearestVertex.over(mesh);
                twinByActiveVertex = new int[vertexCount];
                for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
                    position.set(vertexXyz[activeVertex * 3], vertexXyz[activeVertex * 3 + 1],
                            vertexXyz[activeVertex * 3 + 2]);
                    position.setComponent(symmetryAxis, -position.get(symmetryAxis));
                    twinByActiveVertex[activeVertex] = activeVertexById[twins.find(position.x, position.y,
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
                if (eyeForwardByCopy[copy] != null || points.length == 0) {
                    continue;
                }
                float[] low = { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY };
                float[] high = { Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY };
                for (int coordinate = 0; coordinate < points.length; coordinate++) {
                    low[coordinate % 3] = Math.min(low[coordinate % 3], points[coordinate] - reach);
                    high[coordinate % 3] = Math.max(high[coordinate % 3], points[coordinate] + reach);
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
                        if (Vector3f.distanceSquared(vertexXyz[at], vertexXyz[at + 1], vertexXyz[at + 2],
                                points[point], points[point + 1], points[point + 2]) <= reach * reach) {
                            requiredByActiveVertex[activeVertex] = targetByCopy[copy];
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
                splitByFaceId[faceId] = longest > required;
                splitting |= splitByFaceId[faceId];
            }
            if (!splitting) {
                break;
            }
            // Close the split region: a face left whole may have one split edge (a triangle or a
            // quad, closed by transition faces) or two opposite ones (a quad); any other face
            // splits too, until none changes. The rule reads topology only, so it mirrors.
            boolean[] splitByEdgeId = new boolean[mesh.edgeIdBound()];
            for (boolean promoted = true; promoted;) {
                promoted = false;
                for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
                    int faceId = mesh.faceIdAt(activeFace);
                    int corners = mesh.faceVertexCount(faceId);
                    for (int corner = 0; corner < corners && splitByFaceId[faceId]; corner++) {
                        splitByEdgeId[mesh.edgeBetween(mesh.faceVertexAt(faceId, corner),
                                mesh.faceVertexAt(faceId, (corner + 1) % corners))] = true;
                    }
                }
                for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
                    int faceId = mesh.faceIdAt(activeFace);
                    int corners = mesh.faceVertexCount(faceId);
                    int splitEdges = 0;
                    int splitMask = 0;
                    for (int corner = 0; corner < corners && !splitByFaceId[faceId]; corner++) {
                        if (splitByEdgeId[mesh.edgeBetween(mesh.faceVertexAt(faceId, corner),
                                mesh.faceVertexAt(faceId, (corner + 1) % corners))]) {
                            splitEdges++;
                            splitMask |= 1 << corner;
                        }
                    }
                    boolean closable = splitEdges <= 1 && corners <= QUAD_CORNERS
                            || corners == QUAD_CORNERS && splitEdges == 2 && (splitMask == 0b0101 || splitMask == 0b1010);
                    if (!splitByFaceId[faceId] && !closable) {
                        splitByFaceId[faceId] = true;
                        promoted = true;
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
            for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
                int edgeId = mesh.edgeIdAt(activeEdge);
                if (splitByEdgeId[edgeId]) {
                    midpointByEdgeId[edgeId] = refined.addVertex(mesh.edgeMidpoint(edgeId, position));
                }
            }
            for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
                int faceId = mesh.faceIdAt(activeFace);
                int corners = mesh.faceVertexCount(faceId);
                int[] cornerIds = new int[corners];
                int[] midpoints = new int[corners];
                int splitEdges = 0;
                int firstSplit = -1;
                position.zero();
                for (int corner = 0; corner < corners; corner++) {
                    int vertexId = mesh.faceVertexAt(faceId, corner);
                    cornerIds[corner] = refinedById[vertexId];
                    midpoints[corner] = midpointByEdgeId[mesh.edgeBetween(vertexId,
                            mesh.faceVertexAt(faceId, (corner + 1) % corners))];
                    position.add(mesh.vertexPosition(vertexId, other));
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
                    int centerId = refined.addVertex(position.div(corners));
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
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            activeVertexById[mesh.vertexIdAt(activeVertex)] = activeVertex;
        }
        float[] raiseByActiveVertex = new float[vertexCount];
        float[] lowerByActiveVertex = new float[vertexCount];
        float[] smoothByActiveVertex = new float[vertexCount];
        float[] dragByActiveVertexXyz = new float[vertexCount * 3];
        float[] strokeCreaseByEdgeId = new float[mesh.edgeIdBound()];
        EdgeDistances falloff = new EdgeDistances();
        StrokeChain chain = new StrokeChain();
        Vector3f normal = new Vector3f();
        float[] facingById = new float[mesh.vertexIdBound()];
        for (int copy = 0; copy < copies; copy++) {
            float strokeStrength = strengthByCopy[copy] * strength;
            float radius = radiusByCopy[copy];
            float[] pointXyz = pointXyzByCopy[copy];
            float[] pointWeight = pointWeightByCopy[copy];
            float[] eyeForward = eyeForwardByCopy[copy];
            int pointCount = pointWeight.length;
            int[] sources = new int[Math.max(1, pointCount)];
            float[] sourceWeight = new float[sources.length];
            float[] dragXyz = new float[0];
            int sourceCount = 0;
            if (eyeForward != null) {
                // An over-sketched outline: the contour vertices (vertex-normal facing changes
                // sign across an edge) whose drag onto the stroke, in the plane through the vertex
                // perpendicular to the view axis, is within reach and inside the stroke's ends.
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
                    if (bestClamped || bestDistance > radius) {
                        continue;
                    }
                    System.arraycopy(best, 0, dragXyz, sourceCount * 3, 3);
                    sources[sourceCount++] = vertexId;
                }
                sourceWeight = new float[Math.max(1, sourceCount)];
            } else if (fillByCopy[copy]) {
                // Every vertex within the sample spacing of a sample is a source, with that
                // sample's weight, so a region refined finer than its samples fills solid; on a
                // coarser mesh each sample still takes its nearest vertex.
                float spacingSquared = Float.POSITIVE_INFINITY;
                for (int point = 0; point < pointCount; point++) {
                    for (int otherPoint = point + 1; otherPoint < pointCount; otherPoint++) {
                        spacingSquared = Math.min(spacingSquared, Vector3f.distanceSquared(pointXyz[point * 3],
                                pointXyz[point * 3 + 1], pointXyz[point * 3 + 2], pointXyz[otherPoint * 3],
                                pointXyz[otherPoint * 3 + 1], pointXyz[otherPoint * 3 + 2]));
                    }
                }
                sources = new int[vertexCount];
                sourceWeight = new float[vertexCount];
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
                            sourceWeight[sourceCount] = pointWeight[point];
                            sources[sourceCount++] = vertexId;
                        }
                    }
                    if (nearestId >= 0 && !taken[nearestId]) {
                        taken[nearestId] = true;
                        sourceWeight[sourceCount] = pointWeight[point];
                        sources[sourceCount++] = nearestId;
                    }
                }
            } else {
                chain.snap(mesh, pointXyz, pointCount);
                sources = chain.vertexIds;
                sourceWeight = new float[sources.length];
                sourceCount = sources.length;
                float meanWeight = 0f;
                for (int source = 0; source < sourceCount; source++) {
                    mesh.vertexPosition(sources[source], position);
                    float nearestDistance = Float.POSITIVE_INFINITY;
                    for (int point = 0; point < pointCount; point++) {
                        float distance = position.distanceSquared(pointXyz[point * 3],
                                pointXyz[point * 3 + 1], pointXyz[point * 3 + 2]);
                        if (distance < nearestDistance) {
                            nearestDistance = distance;
                            sourceWeight[source] = pointWeight[point];
                        }
                    }
                    meanWeight += sourceWeight[source] / sourceCount;
                }
                for (int edgeId : smoothByCopy[copy] ? new int[0] : chain.edgeIds) {
                    strokeCreaseByEdgeId[edgeId] = Math.max(strokeCreaseByEdgeId[edgeId],
                            Math.abs(strokeStrength) * creaseScale * meanWeight);
                }
            }
            if (!displace || sourceCount == 0) {
                continue;
            }
            falloff.expand(mesh, sources, sourceCount, radius, -1);
            for (int settled = 0; settled < falloff.settledLength; settled++) {
                int vertexId = falloff.settledVertexIds[settled];
                float fraction = falloff.distanceByVertexId[vertexId] / radius;
                float kernel = (1f - fraction * fraction) * (1f - fraction * fraction);
                float offset = strokeStrength * displacementScale * kernel
                        * sourceWeight[falloff.originByVertexId[vertexId]];
                int activeVertex = activeVertexById[vertexId];
                if (eyeForward != null) {
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
                raiseByActiveVertex[activeVertex] = Math.max(raiseByActiveVertex[activeVertex], offset);
                lowerByActiveVertex[activeVertex] = Math.min(lowerByActiveVertex[activeVertex], offset);
            }
        }

        MeshTopology sculpted = mesh;
        if (displace) {
            // Displace along normals averaged over the one-ring, NORMAL_SMOOTHING_PASSES times:
            // raw normals of an already sculpted cage are noisy enough to fold the surface.
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
                float[] averaged = normalXyz.clone();
                for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
                    int vertexId = mesh.vertexIdAt(activeVertex);
                    for (int adjacency = 0; adjacency < mesh.vertexEdgeCount(vertexId); adjacency++) {
                        int neighbour = activeVertexById[mesh.edgeOtherVertex(
                                mesh.vertexEdgeAt(vertexId, adjacency), vertexId)];
                        for (int axis = 0; axis < 3; axis++) {
                            averaged[activeVertex * 3 + axis] += normalXyz[neighbour * 3 + axis];
                        }
                    }
                }
                normalXyz = averaged;
            }
            // Each vertex took the drag of its nearest contour vertex, a field with seams between
            // neighbours that pulled different ways; blurring it keeps those seams from folding.
            for (int pass = 0; pass < DRAG_SMOOTHING_PASSES; pass++) {
                float[] blurred = dragByActiveVertexXyz.clone();
                for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
                    int vertexId = mesh.vertexIdAt(activeVertex);
                    int neighbours = mesh.vertexEdgeCount(vertexId);
                    for (int adjacency = 0; adjacency < neighbours; adjacency++) {
                        int neighbour = activeVertexById[mesh.edgeOtherVertex(
                                mesh.vertexEdgeAt(vertexId, adjacency), vertexId)];
                        for (int axis = 0; axis < 3; axis++) {
                            blurred[activeVertex * 3 + axis] += dragByActiveVertexXyz[neighbour * 3 + axis];
                        }
                    }
                    for (int axis = 0; axis < 3; axis++) {
                        blurred[activeVertex * 3 + axis] /= neighbours + 1;
                    }
                }
                dragByActiveVertexXyz = blurred;
            }
            float[] offsetXyz = new float[vertexCount * 3];
            for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
                normal.set(normalXyz[activeVertex * 3], normalXyz[activeVertex * 3 + 1],
                        normalXyz[activeVertex * 3 + 2]).normalize(
                        raiseByActiveVertex[activeVertex] + lowerByActiveVertex[activeVertex]);
                offsetXyz[activeVertex * 3] = normal.x + dragByActiveVertexXyz[activeVertex * 3];
                offsetXyz[activeVertex * 3 + 1] = normal.y + dragByActiveVertexXyz[activeVertex * 3 + 1];
                offsetXyz[activeVertex * 3 + 2] = normal.z + dragByActiveVertexXyz[activeVertex * 3 + 2];
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
                float[] original = new float[vertexCount * 3];
                int[] sculptedActiveById = new int[sculpted.vertexIdBound()];
                for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
                    sculptedActiveById[sculpted.vertexIdAt(activeVertex)] = activeVertex;
                    sculpted.vertexPosition(sculpted.vertexIdAt(activeVertex), position);
                    original[activeVertex * 3] = position.x;
                    original[activeVertex * 3 + 1] = position.y;
                    original[activeVertex * 3 + 2] = position.z;
                }
                float[] relaxed = original.clone();
                Vector3f laplacian = new Vector3f();
                for (int iteration = 0; iteration < RELAX_ITERATIONS; iteration++) {
                    float[] next = relaxed.clone();
                    for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
                        float smooth = smoothByActiveVertex[activeVertex];
                        if (relaxWeight[activeVertex] == 0f && smooth == 0f) {
                            continue;
                        }
                        int vertexId = sculpted.vertexIdAt(activeVertex);
                        int neighbours = sculpted.vertexEdgeCount(vertexId);
                        laplacian.zero();
                        for (int adjacency = 0; adjacency < neighbours; adjacency++) {
                            int neighbour = sculptedActiveById[sculpted.edgeOtherVertex(
                                    sculpted.vertexEdgeAt(vertexId, adjacency), vertexId)];
                            laplacian.add(relaxed[neighbour * 3], relaxed[neighbour * 3 + 1],
                                    relaxed[neighbour * 3 + 2]);
                        }
                        laplacian.div(Math.max(1, neighbours)).sub(relaxed[activeVertex * 3],
                                relaxed[activeVertex * 3 + 1], relaxed[activeVertex * 3 + 2]);
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
                float[] moved = new float[vertexCount * 3];
                for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
                    sculpted.vertexPosition(sculpted.vertexIdAt(activeVertex), position)
                            .sub(mesh.vertexPosition(mesh.vertexIdAt(activeVertex), other));
                    moved[activeVertex * 3] = position.x;
                    moved[activeVertex * 3 + 1] = position.y;
                    moved[activeVertex * 3 + 2] = position.z;
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
}
