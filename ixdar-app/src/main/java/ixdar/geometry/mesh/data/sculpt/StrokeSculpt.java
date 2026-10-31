package ixdar.geometry.mesh.data.sculpt;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.ops.MeshVertexOffset;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.nodegraph.field.Vector3Field;

/**
 * Sculpts a surface with weighted strokes: each stroke snaps to an edge chain (or, for a filled
 * region, to the vertices under it), pushes the vertices near it along their normals by its
 * strength with a smooth geodesic falloff, and creases a chain's edges by its absolute strength.
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

    public static final int WEIGHTED_POINT_FIELDS = 4;

    public static final int NORMAL_SMOOTHING_PASSES = 2;

    public static final int RELAX_ITERATIONS = 4;

    public static final int DRAG_SMOOTHING_PASSES = 6;

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

    /**
     * Sculpt a bundle's mesh with strokes written {@code "s [r=R] [fill] [mirror] [smooth]: x y z [w],
     * ..."}. Overlapping same-sign displacements keep the larger, opposite signs add.
     *
     * @param base    geometry to sculpt; its float crease marks are carried over
     * @param strokes strokes, each a header then its lifted points with optional view weights
     * @return the sculpted geometry with the strokes' crease marks merged in
     */
    public GeometryBundle apply(GeometryBundle base, String strokes) {
        MeshTopology mesh = base.mesh();
        if (mesh == null || mesh.vertexCount() == 0 || strokes == null || strokes.isBlank()) {
            return base;
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
        Vector3f position = new Vector3f();
        for (String strokeText : strokes.split(STROKE_SEPARATOR)) {
            int colon = strokeText.indexOf(HEADER_SEPARATOR);
            if (colon < 0) {
                continue;
            }
            String[] header = strokeText.substring(0, colon).trim().split(FIELD_SEPARATOR);
            float strength = Math.max(-1f, Math.min(1f, Float.parseFloat(header[0])));
            float radius = falloffRadius;
            boolean fill = false;
            boolean mirror = false;
            boolean smooth = false;
            SilhouetteDrag silhouette = null;
            for (String flag : header) {
                radius = flag.startsWith(RADIUS_PREFIX)
                        ? Float.parseFloat(flag.substring(RADIUS_PREFIX.length())) : radius;
                fill |= FILL_FLAG.equals(flag);
                mirror |= MIRROR_FLAG.equals(flag);
                smooth |= SMOOTH_FLAG.equals(flag);
                if (flag.startsWith(SilhouetteDrag.EYE_PREFIX)) {
                    silhouette = silhouette == null ? new SilhouetteDrag() : silhouette;
                    silhouette.parse(flag);
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
            for (int side = 0; side < (mirror ? 2 : 1); side++) {
                if (side == 1) {
                    for (int point = 0; point < pointCount; point++) {
                        pointXyz[point * 3] = -pointXyz[point * 3];
                    }
                }
                int[] sources = new int[Math.max(1, pointCount)];
                float[] sourceWeight = new float[sources.length];
                int sourceCount = 0;
                if (silhouette != null) {
                    if (side == 1) {
                        silhouette.mirrorX();
                    }
                    silhouette.capture(mesh, pointXyz, pointCount, radius);
                    sources = silhouette.sourceVertexIds;
                    sourceCount = silhouette.sourceLength;
                    sourceWeight = new float[Math.max(1, sourceCount)];
                } else if (fill) {
                    boolean[] taken = new boolean[mesh.vertexIdBound()];
                    for (int point = 0; point < pointCount; point++) {
                        int nearestId = -1;
                        float nearestDistance = Float.POSITIVE_INFINITY;
                        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
                            int vertexId = mesh.vertexIdAt(activeVertex);
                            float distance = mesh.vertexPosition(vertexId, position).distanceSquared(
                                    pointXyz[point * 3], pointXyz[point * 3 + 1], pointXyz[point * 3 + 2]);
                            if (distance < nearestDistance) {
                                nearestDistance = distance;
                                nearestId = vertexId;
                            }
                        }
                        if (nearestId >= 0 && !taken[nearestId] && pointWeight[point] > 0f) {
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
                    for (int edgeId : smooth ? new int[0] : chain.edgeIds) {
                        strokeCreaseByEdgeId[edgeId] = Math.max(strokeCreaseByEdgeId[edgeId],
                                Math.abs(strength) * creaseScale * meanWeight);
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
                    float offset = strength * displacementScale * kernel
                            * sourceWeight[falloff.originByVertexId[vertexId]];
                    int activeVertex = activeVertexById[vertexId];
                    if (silhouette != null) {
                        int origin = falloff.originByVertexId[vertexId] * 3;
                        float scale = Math.abs(strength) * kernel;
                        float dragX = silhouette.dragXyz[origin] * scale;
                        float dragY = silhouette.dragXyz[origin + 1] * scale;
                        float dragZ = silhouette.dragXyz[origin + 2] * scale;
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
                    if (smooth) {
                        smoothByActiveVertex[activeVertex] = Math.max(smoothByActiveVertex[activeVertex],
                                MAXIMUM_SMOOTH_STEP * Math.abs(offset) / displacementScale);
                        continue;
                    }
                    raiseByActiveVertex[activeVertex] = Math.max(raiseByActiveVertex[activeVertex], offset);
                    lowerByActiveVertex[activeVertex] = Math.min(lowerByActiveVertex[activeVertex], offset);
                }
            }
        }

        MeshTopology sculpted = mesh;
        if (displace) {
            // Displace along normals averaged over the one-ring, NORMAL_SMOOTHING_PASSES times:
            // raw normals of an already sculpted cage are noisy enough to fold the surface.
            float[] normalXyz = new float[vertexCount * 3];
            Vector3f normal = new Vector3f();
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
        }

        float[] creases = new float[sculpted.edgeIdBound()];
        float[] existing = EdgeMarks.floats(base, CREASE_LABEL);
        for (int edgeId = 0; edgeId < strokeCreaseByEdgeId.length; edgeId++) {
            float crease = Math.max(strokeCreaseByEdgeId[edgeId],
                    existing != null && edgeId < existing.length ? existing[edgeId] : 0f);
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
        return EdgeMarks.with(base.withMesh(sculpted), CREASE_LABEL, creases);
    }
}
