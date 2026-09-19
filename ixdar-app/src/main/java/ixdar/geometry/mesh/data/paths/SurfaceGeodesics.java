package ixdar.geometry.mesh.data.paths;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;

/**
 * The geodesic primitives a spline is built from, over one cached triangulation: the shortest path
 * between two vertices, a point along it, and a straight walk.
 *
 * <p>
 * Every run undoes its own flips, so repeating a call gives the same path. See b/Surf §5.2 for the
 * A* seed.
 */
public final class SurfaceGeodesics {

    /** Coordinates per point in every packed position here. */
    public static final int COORDINATES_PER_POINT = 3;

    /** Vertices one seed search may settle before it gives up. */
    public static final int DEFAULT_SEARCH_BUDGET = 200000;

    /** Surface every path here runs on. */
    public MeshTopology mesh;

    /** Signpost triangulation the tightening flips and then puts back. */
    public IntrinsicTriangulation intrinsic;

    /** Tracer bound to the unflipped triangulation, which lands a tightened path on the surface. */
    public IntrinsicPathTracer tracer;

    /** Mean Euclidean edge length, the resolution every tolerance here is measured against. */
    public double meanEdgeLength;

    /**
     * One past the largest vertex id the surface holds, the length the visit buffers need. It is a
     * property of the mesh, so it is measured once here rather than per geodesic.
     */
    public int vertexIdBound;

    /** Packed xyz of the last geodesic, one point per vertex or edge crossing. */
    public double[] tracedXyz = new double[0];

    /** Mesh vertex id per traced point, or -1 at an edge crossing. */
    public int[] tracedVertexId = new int[0];

    /** Crossed mesh edge id per traced point, or -1 at a vertex. */
    public int[] tracedEdgeId = new int[0];

    /** Crossing parameter per traced point, or -1 at a vertex. */
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
    private final Vector3f scratchPosition = new Vector3f();
    private final Vector3f otherPosition = new Vector3f();
    private final Vector3f targetPosition = new Vector3f();
    private final Vector3f walkDirection = new Vector3f();
    private final Vector3f walkNormal = new Vector3f();
    private int[] visitStamp = new int[0];
    private double[] costToVertex = new double[0];
    private int[] visitParent = new int[0];
    private int currentStamp;
    private double[] heapKey = new double[0];
    private int[] heapVertex = new int[0];
    private int heapSize;
    private int[] seedVertex = new int[0];
    private int seedVertexCount;
    private int[] seedHalfEdge = new int[0];

    private SurfaceGeodesics() {
    }

    /**
     * Builds the triangulation and the tracer a run of splines shares.
     *
     * @param surface triangle mesh every path will run on
     * @return an engine bound to that surface
     */
    public static SurfaceGeodesics over(MeshTopology surface) {
        SurfaceGeodesics geodesics = new SurfaceGeodesics();
        geodesics.mesh = surface;
        geodesics.intrinsic = IntrinsicTriangulation.over(surface);
        geodesics.tracer = IntrinsicPathTracer.snapshotOf(geodesics.intrinsic);
        geodesics.intrinsic.recordFlips = true;
        geodesics.meanEdgeLength = meanEdgeLengthOf(surface);
        for (int index = 0; index < surface.vertexCount(); index++) {
            geodesics.vertexIdBound =
                    Math.max(geodesics.vertexIdBound, surface.vertexIdAt(index) + 1);
        }
        return geodesics;
    }

    /**
     * Mean Euclidean edge length of a mesh, the unit a spline's tolerances are quoted in.
     *
     * @param surface mesh to measure
     * @return the mean length, or zero when the mesh has no edges
     */
    public static double meanEdgeLengthOf(MeshTopology surface) {
        double total = 0.0;
        Vector3f tail = new Vector3f();
        Vector3f head = new Vector3f();
        for (int index = 0; index < surface.edgeCount(); index++) {
            int halfEdge = surface.edgeHalfEdge(surface.edgeIdAt(index));
            surface.vertexPosition(surface.halfEdgeVertex(halfEdge), tail);
            surface.vertexPosition(surface.halfEdgeEndVertex(halfEdge), head);
            total += tail.distance(head);
        }
        return surface.edgeCount() == 0 ? 0.0 : total / surface.edgeCount();
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
                    intrinsic.vertexIndexByVertexId[seedVertex[step]],
                    intrinsic.vertexIndexByVertexId[seedVertex[step + 1]]);
            if (halfEdge < 0) {
                return false;
            }
            seedHalfEdge[step] = halfEdge;
        }
        int[] tightened = flipper.shorten(intrinsic, seedHalfEdge, false,
                FlipGeodesics.UNBOUNDED_ITERATIONS);
        TracedSurfacePath traced = tracer.trace(intrinsic, tightened, false);
        intrinsic.undoFlips();
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
            for (int end = 0; end < 2; end++) {
                int candidate = tracedVertexId[side] >= 0 ? tracedVertexId[side]
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
                int other = neighbourAcross(current, mesh.vertexEdgeAt(current, spoke));
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

    /**
     * The other end of an edge from one of its vertices.
     *
     * @param vertexId vertex the edge is walked from
     * @param edgeId   edge to cross
     * @return the vertex at the far end, or -1 when the edge is dangling
     */
    public int neighbourAcross(int vertexId, int edgeId) {
        int halfEdge = mesh.edgeHalfEdge(edgeId);
        int start = mesh.halfEdgeVertex(halfEdge);
        return start == vertexId ? mesh.halfEdgeEndVertex(halfEdge) : start;
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
        prepareVisitBuffers();
        mesh.vertexPosition(toVertexId, targetPosition);
        currentStamp++;
        heapSize = 0;
        visitStamp[fromVertexId] = currentStamp;
        costToVertex[fromVertexId] = 0.0;
        visitParent[fromVertexId] = -1;
        mesh.vertexPosition(fromVertexId, scratchPosition);
        heapPush(scratchPosition.distance(targetPosition), fromVertexId);
        int settled = 0;
        boolean reached = false;
        while (heapSize > 0 && settled < searchBudget) {
            int vertexId = heapPop();
            if (vertexId == toVertexId) {
                reached = true;
                break;
            }
            settled++;
            mesh.vertexPosition(vertexId, scratchPosition);
            int spokes = mesh.vertexEdgeCount(vertexId);
            for (int spoke = 0; spoke < spokes; spoke++) {
                int other = neighbourAcross(vertexId, mesh.vertexEdgeAt(vertexId, spoke));
                if (other < 0) {
                    continue;
                }
                mesh.vertexPosition(other, otherPosition);
                double relaxed = costToVertex[vertexId] + scratchPosition.distance(otherPosition);
                if (visitStamp[other] == currentStamp && relaxed >= costToVertex[other]) {
                    continue;
                }
                visitStamp[other] = currentStamp;
                costToVertex[other] = relaxed;
                visitParent[other] = vertexId;
                heapPush(relaxed + otherPosition.distance(targetPosition), other);
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
            walk = visitParent[walk];
        }
        for (int front = 0, back = seedVertexCount - 1; front < back; front++, back--) {
            int swap = seedVertex[front];
            seedVertex[front] = seedVertex[back];
            seedVertex[back] = swap;
        }
        return seedVertexCount > 1;
    }

    private void prepareVisitBuffers() {
        if (visitStamp.length >= vertexIdBound) {
            return;
        }
        visitStamp = new int[vertexIdBound];
        costToVertex = new double[vertexIdBound];
        visitParent = new int[vertexIdBound];
        heapKey = new double[vertexIdBound + 1];
        heapVertex = new int[vertexIdBound + 1];
        currentStamp = 0;
    }

    private void heapPush(double key, int vertexId) {
        if (heapSize == heapKey.length) {
            heapKey = Arrays.copyOf(heapKey, Math.max(64, 2 * heapKey.length));
            heapVertex = Arrays.copyOf(heapVertex, heapKey.length);
        }
        int slot = heapSize++;
        while (slot > 0) {
            int parent = (slot - 1) / 2;
            if (heapKey[parent] <= key) {
                break;
            }
            heapKey[slot] = heapKey[parent];
            heapVertex[slot] = heapVertex[parent];
            slot = parent;
        }
        heapKey[slot] = key;
        heapVertex[slot] = vertexId;
    }

    private int heapPop() {
        int top = heapVertex[0];
        heapSize--;
        if (heapSize == 0) {
            return top;
        }
        double key = heapKey[heapSize];
        int vertexId = heapVertex[heapSize];
        int slot = 0;
        while (true) {
            int child = 2 * slot + 1;
            if (child >= heapSize) {
                break;
            }
            if (child + 1 < heapSize && heapKey[child + 1] < heapKey[child]) {
                child++;
            }
            if (heapKey[child] >= key) {
                break;
            }
            heapKey[slot] = heapKey[child];
            heapVertex[slot] = heapVertex[child];
            slot = child;
        }
        heapKey[slot] = key;
        heapVertex[slot] = vertexId;
        return top;
    }
}
