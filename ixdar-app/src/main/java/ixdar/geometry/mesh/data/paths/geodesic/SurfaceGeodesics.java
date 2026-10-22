package ixdar.geometry.mesh.data.paths.geodesic;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.numeric.graph.Dijkstra;

/**
 * The geodesic primitives a spline is built from, over a shared {@link SurfaceMetric}: the
 * shortest path between two vertices, a point along it, and a straight walk.
 *
 * <p>
 * One engine per user holds its flips, buffers and last path. See b/Surf §5.2 for the A* seed.
 */
public final class SurfaceGeodesics {

    public static final int COORDINATES_PER_POINT = 3;

    public static final int DEFAULT_SEARCH_BUDGET = 200000;

    /** Metric every path here is measured in. */
    public SurfaceMetric metric;

    /** Surface every path here runs on, the metric's source mesh. */
    public MeshTopology mesh;

    /** This engine's flippable view of the metric, every flip discarded once a path is traced. */
    public IntrinsicTriangulation intrinsic;

    /** Tracer that lands a tightened path on the surface. */
    public IntrinsicPathTracer tracer;

    /** Packed xyz of the last geodesic, one point per vertex, edge crossing or inside-face point. */
    public double[] tracedXyz = new double[0];

    /** Mesh vertex id per traced point, or -1 elsewhere. */
    public int[] tracedVertexId = new int[0];

    /** Crossed mesh edge id per traced point, or -1 elsewhere. */
    public int[] tracedEdgeId = new int[0];

    /** Mesh face id per traced point strictly inside a polygon, or -1 elsewhere. */
    public int[] tracedFaceId = new int[0];

    /** Crossing parameter per traced point, or -1 off an edge. */
    public double[] tracedFraction = new double[0];

    /** Arc length from the start of the last geodesic to each of its points. */
    public double[] tracedArcLength = new double[0];

    /** Points stored in the traced arrays. */
    public int tracedPointCount;

    /** Euclidean length of the last geodesic. */
    public double pathLength;

    /** Vertices one seed search may settle. */
    public int searchBudget = DEFAULT_SEARCH_BUDGET;

    /** Geodesics computed since this engine was built, the measure of a trace's cost. */
    public long geodesicCount;

    private final FlipGeodesics flipper = new FlipGeodesics();
    private final Dijkstra search = new Dijkstra();
    private final Vector3f scratchPosition = new Vector3f();
    private final Vector3f otherPosition = new Vector3f();
    private final Vector3f targetPosition = new Vector3f();
    private final Vector3f walkDirection = new Vector3f();
    private final Vector3f walkNormal = new Vector3f();
    private int[] seedVertex = new int[0];
    private int seedVertexCount;
    private int[] seedHalfEdge = new int[0];

    private SurfaceGeodesics() {
    }

    /**
     * An engine over a metric; it allocates only its own buffers, so one per user is cheap.
     *
     * @param metric the surface's metric, shared and never written
     * @return an engine bound to the metric's source mesh
     */
    public static SurfaceGeodesics over(SurfaceMetric metric) {
        SurfaceGeodesics geodesics = new SurfaceGeodesics();
        geodesics.metric = metric;
        geodesics.mesh = metric.sourceMesh;
        geodesics.intrinsic = IntrinsicTriangulation.over(metric);
        geodesics.tracer = IntrinsicPathTracer.over(metric);
        return geodesics;
    }

    /**
     * The locally shortest path between two mesh vertices, left in the traced arrays.
     *
     * @param fromVertexId mesh vertex the path starts at
     * @param toVertexId   mesh vertex the path ends at
     * @return true when a path was found and traced
     */
    public boolean geodesic(int fromVertexId, int toVertexId) {
        tracedPointCount = 0;
        pathLength = 0.0;
        if (fromVertexId == toVertexId || !mesh.hasVertex(fromVertexId)
                || !mesh.hasVertex(toVertexId)) {
            return false;
        }
        if (!seedWalk(fromVertexId, toVertexId)) {
            return false;
        }
        geodesicCount++;
        if (seedHalfEdge.length != seedVertexCount - 1) {
            seedHalfEdge = new int[seedVertexCount - 1];
        }
        for (int step = 0; step < seedVertexCount - 1; step++) {
            int halfEdge = intrinsic.halfEdgeBetween(
                    metric.vertexIndexByVertexId[seedVertex[step]],
                    metric.vertexIndexByVertexId[seedVertex[step + 1]]);
            if (halfEdge < 0) {
                return false;
            }
            seedHalfEdge[step] = halfEdge;
        }
        int[] tightened = flipper.shorten(intrinsic, seedHalfEdge, false,
                FlipGeodesics.UNBOUNDED_ITERATIONS);
        TracedSurfacePath traced = tracer.trace(intrinsic, tightened, false);
        intrinsic.discardFlips();
        keep(traced);
        return tracedPointCount > 1;
    }

    /**
     * The point at a fraction of the last geodesic's arc length, and the mesh vertex nearest it.
     *
     * @param fraction  fraction of the arc length, clamped into {@code [0, 1]}
     * @param pointXyz  filled with the exact surface point, or left alone when null
     * @return the nearest mesh vertex id, or -1 when no geodesic is held
     */
    public int vertexAtFraction(double fraction, float[] pointXyz) {
        if (tracedPointCount < 2) {
            return -1;
        }
        double target = Math.max(0.0, Math.min(1.0, fraction)) * pathLength;
        int point = 1;
        while (point < tracedPointCount - 1 && tracedArcLength[point] < target) {
            point++;
        }
        int previous = point - 1;
        double span = tracedArcLength[point] - tracedArcLength[previous];
        double along = span <= 0.0 ? 0.0 : (target - tracedArcLength[previous]) / span;
        double x = tracedXyz[COORDINATES_PER_POINT * previous]
                + along * (tracedXyz[COORDINATES_PER_POINT * point]
                        - tracedXyz[COORDINATES_PER_POINT * previous]);
        double y = tracedXyz[COORDINATES_PER_POINT * previous + 1]
                + along * (tracedXyz[COORDINATES_PER_POINT * point + 1]
                        - tracedXyz[COORDINATES_PER_POINT * previous + 1]);
        double z = tracedXyz[COORDINATES_PER_POINT * previous + 2]
                + along * (tracedXyz[COORDINATES_PER_POINT * point + 2]
                        - tracedXyz[COORDINATES_PER_POINT * previous + 2]);
        if (pointXyz != null) {
            pointXyz[0] = (float) x;
            pointXyz[1] = (float) y;
            pointXyz[2] = (float) z;
        }
        int best = -1;
        double bestSquared = Double.POSITIVE_INFINITY;
        for (int side = previous; side <= point; side++) {
            int faceId = tracedFaceId[side];
            int candidates = faceId >= 0 ? mesh.faceVertexCount(faceId) : 2;
            for (int end = 0; end < candidates; end++) {
                int candidate = tracedVertexId[side] >= 0 ? tracedVertexId[side]
                        : faceId >= 0 ? mesh.faceVertexAt(faceId, end)
                        : edgeEnd(tracedEdgeId[side], end);
                if (candidate < 0) {
                    continue;
                }
                mesh.vertexPosition(candidate, scratchPosition);
                double squared = scratchPosition.distanceSquared((float) x, (float) y, (float) z);
                if (squared < bestSquared) {
                    bestSquared = squared;
                    best = candidate;
                }
            }
        }
        return best;
    }

    /**
     * Surface distance over mesh edges from one vertex to the nearest vertex carrying each label,
     * searched outward until every label is reached or {@link #searchBudget} vertices are settled.
     *
     * @param fromVertexId    vertex the search starts at
     * @param labelByVertexId label per vertex id, -1 for none
     * @param labels          labels in use, numbered from zero
     * @return the distance per label, positive infinity for a label the search never reached
     */
    public double[] distanceToLabels(int fromVertexId, int[] labelByVertexId, int labels) {
        double[] distance = new double[labels];
        Arrays.fill(distance, Double.POSITIVE_INFINITY);
        if (!mesh.hasVertex(fromVertexId)) {
            return distance;
        }
        search.start(metric.vertexIdBound);
        search.relax(fromVertexId, 0.0, -1);
        int found = 0;
        int settled = 0;
        while (found < labels && settled < searchBudget) {
            int vertexId = search.settle();
            if (vertexId < 0) {
                break;
            }
            settled++;
            double cost = search.distance(vertexId);
            int label = vertexId < labelByVertexId.length ? labelByVertexId[vertexId] : -1;
            if (label >= 0 && distance[label] == Double.POSITIVE_INFINITY) {
                distance[label] = cost;
                found++;
            }
            mesh.vertexPosition(vertexId, scratchPosition);
            for (int spoke = 0; spoke < mesh.vertexEdgeCount(vertexId); spoke++) {
                int other = mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertexId, spoke), vertexId);
                if (other < 0) {
                    continue;
                }
                mesh.vertexPosition(other, otherPosition);
                search.relax(other, cost + scratchPosition.distance(otherPosition), vertexId);
            }
        }
        return distance;
    }

    /**
     * Walks the surface from a vertex in a direction, the straight-line extension the tangent
     * handles of a spline are built with.
     *
     * @param fromVertexId vertex the walk starts at
     * @param directionXyz direction to head in, packed xyz, need not be normalised
     * @param distance     Euclidean distance to cover
     * @return the vertex the walk stopped at, which is {@code fromVertexId} when it could not move
     */
    public int walkFrom(int fromVertexId, float[] directionXyz, double distance) {
        walkDirection.set(directionXyz[0], directionXyz[1], directionXyz[2]);
        if (walkDirection.lengthSquared() <= 0f || distance <= 0.0) {
            return fromVertexId;
        }
        walkDirection.normalize();
        int current = fromVertexId;
        double covered = 0.0;
        int guard = 0;
        while (guard++ < mesh.vertexCount()) {
            mesh.vertexPosition(current, scratchPosition);
            mesh.vertexNormal(current, walkNormal);
            if (walkNormal.lengthSquared() > 0f) {
                walkNormal.normalize();
                walkDirection.fma(-walkDirection.dot(walkNormal), walkNormal);
                if (walkDirection.lengthSquared() <= 0f) {
                    return current;
                }
                walkDirection.normalize();
            }
            int best = -1;
            double bestAlignment = 0.0;
            double bestStep = 0.0;
            int spokes = mesh.vertexEdgeCount(current);
            for (int spoke = 0; spoke < spokes; spoke++) {
                int other = mesh.edgeOtherVertex(mesh.vertexEdgeAt(current, spoke), current);
                if (other < 0) {
                    continue;
                }
                mesh.vertexPosition(other, otherPosition);
                otherPosition.sub(scratchPosition);
                double step = otherPosition.length();
                if (step <= 0.0) {
                    continue;
                }
                double alignment = otherPosition.dot(walkDirection) / step;
                if (alignment > bestAlignment) {
                    bestAlignment = alignment;
                    best = other;
                    bestStep = step * alignment;
                }
            }
            if (best < 0 || Math.abs(covered + bestStep - distance) > Math.abs(covered - distance)) {
                return current;
            }
            current = best;
            covered += bestStep;
        }
        return current;
    }

    private int edgeEnd(int edgeId, int end) {
        if (edgeId < 0) {
            return -1;
        }
        int halfEdge = mesh.edgeHalfEdge(edgeId);
        return end == 0 ? mesh.halfEdgeVertex(halfEdge) : mesh.halfEdgeEndVertex(halfEdge);
    }

    private void keep(TracedSurfacePath traced) {
        tracedPointCount = traced.pointCount;
        if (tracedArcLength.length < tracedPointCount) {
            tracedArcLength = new double[tracedPointCount];
        }
        tracedXyz = traced.positions;
        tracedVertexId = traced.vertexId;
        tracedEdgeId = traced.edgeId;
        tracedFaceId = traced.faceId;
        tracedFraction = traced.fraction;
        tracedArcLength[0] = 0.0;
        for (int point = 1; point < tracedPointCount; point++) {
            double dx = tracedXyz[COORDINATES_PER_POINT * point]
                    - tracedXyz[COORDINATES_PER_POINT * (point - 1)];
            double dy = tracedXyz[COORDINATES_PER_POINT * point + 1]
                    - tracedXyz[COORDINATES_PER_POINT * (point - 1) + 1];
            double dz = tracedXyz[COORDINATES_PER_POINT * point + 2]
                    - tracedXyz[COORDINATES_PER_POINT * (point - 1) + 2];
            tracedArcLength[point] =
                    tracedArcLength[point - 1] + Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        pathLength = tracedPointCount == 0 ? 0.0 : tracedArcLength[tracedPointCount - 1];
    }

    /**
     * A* over mesh edges from one vertex to another, weighted as b/Surf §5.2 does with the
     * straight-line distance still to go, so the search stays in the corridor between the two.
     */
    private boolean seedWalk(int fromVertexId, int toVertexId) {
        mesh.vertexPosition(toVertexId, targetPosition);
        search.start(metric.vertexIdBound);
        mesh.vertexPosition(fromVertexId, scratchPosition);
        search.relax(fromVertexId, 0.0, scratchPosition.distance(targetPosition), -1);
        int settled = 0;
        boolean reached = false;
        while (settled < searchBudget) {
            int vertexId = search.settle();
            if (vertexId < 0) {
                break;
            }
            if (vertexId == toVertexId) {
                reached = true;
                break;
            }
            settled++;
            double cost = search.distance(vertexId);
            mesh.vertexPosition(vertexId, scratchPosition);
            int spokes = mesh.vertexEdgeCount(vertexId);
            for (int spoke = 0; spoke < spokes; spoke++) {
                int other = mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertexId, spoke), vertexId);
                if (other < 0) {
                    continue;
                }
                mesh.vertexPosition(other, otherPosition);
                double relaxed = cost + scratchPosition.distance(otherPosition);
                search.relax(other, relaxed, relaxed + otherPosition.distance(targetPosition),
                        vertexId);
            }
        }
        if (!reached) {
            return false;
        }
        seedVertexCount = 0;
        int walk = toVertexId;
        while (walk >= 0) {
            if (seedVertexCount == seedVertex.length) {
                seedVertex = Arrays.copyOf(seedVertex, Math.max(64, 2 * seedVertex.length));
            }
            seedVertex[seedVertexCount++] = walk;
            walk = search.via(walk);
        }
        for (int front = 0, back = seedVertexCount - 1; front < back; front++, back--) {
            int swap = seedVertex[front];
            seedVertex[front] = seedVertex[back];
            seedVertex[back] = swap;
        }
        return seedVertexCount > 1;
    }
}
