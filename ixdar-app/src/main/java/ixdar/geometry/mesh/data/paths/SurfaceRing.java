package ixdar.geometry.mesh.data.paths;

import ixdar.geometry.mesh.data.MeshTopology;

/**
 * One ring through authored surface waypoints: the seed edge walk, the geodesic FlipOut tightens
 * it to, and the mesh edges each of them runs along.
 */
public final class SurfaceRing {

    /** Packed xyz of the tightened polyline; a closed ring repeats its first point at the end. */
    public float[] polyline = new float[0];

    /** Packed xyz of the untightened seed walk, in the same form as {@link #polyline}. */
    public float[] seedPolyline = new float[0];

    /** Edge-id-indexed mask, true on every mesh edge of the cycle nearest the tightened path. */
    public boolean[] markedByEdgeId = new boolean[0];

    /** Edge-id-indexed mask, true on every mesh edge the seed walk runs along. */
    public boolean[] seedMarkedByEdgeId = new boolean[0];

    /** Ascending mesh edge ids of the cycle nearest the tightened path. */
    public int[] markedEdgeIds = new int[0];

    /** Mesh edges marked by {@link #markedByEdgeId}. */
    public int markedEdgeCount;

    /** Gaps {@link ConformingLoopSnap} could not close on the tightened path. */
    public int unresolvedGaps;

    /** Mean distance from the centroid to the tightened polyline's points. */
    public double meanRadius;

    /** Euclidean length of the tightened polyline. */
    public double length;

    /** Euclidean length of the seed walk, always at least {@link #length}. */
    public double seedLength;

    /** Length-weighted centroid x of the tightened ring, the position a joint would sit at. */
    public float centroidX;

    /** Length-weighted centroid y of the tightened ring. */
    public float centroidY;

    /** Length-weighted centroid z of the tightened ring. */
    public float centroidZ;

    /** Mesh vertex ids the waypoints snapped to, in waypoint order. */
    public int[] waypointVertexIds = new int[0];

    /**
     * Mesh vertex ids the tightened path turns at, in travel order. Pinning any of them leaves
     * the ring where it is, so these are the waypoints a written statement reloads exactly from.
     */
    public int[] pathVertexIds = new int[0];

    /** Leading waypoints held fixed while the rest of the ring straightened. */
    public int pinnedWaypointCount;

    /** Whether the walk closed back onto its first waypoint. */
    public boolean closed;

    private SurfaceRing() {
    }

    /**
     * Chains the waypoints with shortest edge walks, closes the cycle, and tightens it with
     * FlipOut (Sharp and Crane 2020).
     *
     * @param mesh          surface the ring is snapped and tightened on
     * @param packedXyz     authored waypoint coordinates, three floats per waypoint
     * @param waypointCount waypoints to use from the front of {@code packedXyz}
     * @param closed        whether the walk returns from the last waypoint to the first
     * @param pinned        leading waypoints the tightening must keep the ring on; {@code 0}
     *                      lets the whole ring slide to the nearest geodesic
     * @param iterations    cap on wedge straightenings; {@code -1} runs to convergence and
     *                      {@code 0} leaves the seed walk untightened
     * @throws IllegalArgumentException when too few waypoints are given for the requested shape
     * @return the finished ring
     */
    public static SurfaceRing through(MeshTopology mesh, float[] packedXyz, int waypointCount,
            boolean closed, int pinned, int iterations) {
        int minimum = closed ? SurfaceWaypoints.CLOSED_LOOP_MINIMUM
                : SurfaceWaypoints.OPEN_PATH_MINIMUM;
        if (waypointCount < minimum) {
            throw new IllegalArgumentException("a " + (closed ? "closed ring" : "path")
                    + " needs at least " + minimum + " surface points, got " + waypointCount
                    + (closed ? "; two points bound a loop only with a third side point" : ""));
        }
        return build(mesh, SurfaceWaypoints.snap(mesh, packedXyz, waypointCount), closed, pinned,
                iterations);
    }

    /**
     * The same ring from vertex ids the caller already picked, for the automatic producers that
     * must reject a bad seed rather than report it.
     *
     * @param mesh              surface the ring is tightened on
     * @param waypointVertexIds mesh vertex ids the seed walk passes through
     * @param closed            whether the walk returns from the last waypoint to the first
     * @param pinned            leading waypoints the tightening must keep the ring on
     * @param iterations        cap on wedge straightenings; {@code -1} runs to convergence
     * @return the finished ring, or null when the seed walk or the tightening produced nothing
     */
    public static SurfaceRing tightenedOrNull(MeshTopology mesh, int[] waypointVertexIds,
            boolean closed, int pinned, int iterations) {
        int minimum = closed ? SurfaceWaypoints.CLOSED_LOOP_MINIMUM
                : SurfaceWaypoints.OPEN_PATH_MINIMUM;
        if (waypointVertexIds.length < minimum) {
            return null;
        }
        SurfaceRing ring;
        try {
            ring = build(mesh, waypointVertexIds, closed, pinned, iterations);
        } catch (IllegalArgumentException unreachableWaypoint) {
            return null;
        }
        return ring.markedEdgeCount == 0 || ring.polyline.length == 0 ? null : ring;
    }

    private static SurfaceRing build(MeshTopology mesh, int[] waypointVertexIds, boolean closed,
            int pinned, int iterations) {
        SurfaceRing ring = new SurfaceRing();
        ring.closed = closed;
        ring.waypointVertexIds = waypointVertexIds;
        ring.pinnedWaypointCount = Math.min(Math.max(0, pinned), waypointVertexIds.length);

        IntrinsicTriangulation intrinsic = IntrinsicTriangulation.over(mesh);
        IntrinsicPathTracer tracer = IntrinsicPathTracer.snapshotOf(intrinsic);
        int[] seed = GeodesicSeedPath.throughVertices(intrinsic, ring.waypointVertexIds, closed);
        TracedSurfacePath seedTrace = tracer.trace(intrinsic, seed, closed);
        ring.seedPolyline = closedPolyline(seedTrace);
        ring.seedLength = seedTrace.polylineLength();
        ring.seedMarkedByEdgeId = new ConformingLoopSnap().snap(mesh, seedTrace);

        FlipGeodesics flipper = new FlipGeodesics();
        if (ring.pinnedWaypointCount > 0) {
            flipper.vertexIsPinned = new boolean[intrinsic.vertexCount];
            for (int waypoint = 0; waypoint < ring.pinnedWaypointCount; waypoint++) {
                flipper.vertexIsPinned[
                        intrinsic.vertexIndexByVertexId[waypointVertexIds[waypoint]]] = true;
            }
        }
        int[] tightened = flipper.shorten(intrinsic, seed, closed, iterations);
        TracedSurfacePath trace = tracer.trace(intrinsic, tightened, closed);
        ring.polyline = closedPolyline(trace);
        ring.length = trace.polylineLength();
        ConformingLoopSnap tightSnap = new ConformingLoopSnap();
        ring.markedByEdgeId = tightSnap.snap(mesh, trace);
        ring.unresolvedGaps = tightSnap.unresolvedGaps;
        for (boolean marked : ring.markedByEdgeId) {
            if (marked) {
                ring.markedEdgeCount++;
            }
        }
        ring.markedEdgeIds = new int[ring.markedEdgeCount];
        int next = 0;
        for (int edgeId = 0; edgeId < ring.markedByEdgeId.length; edgeId++) {
            if (ring.markedByEdgeId[edgeId]) {
                ring.markedEdgeIds[next++] = edgeId;
            }
        }
        int corners = 0;
        for (int point = 0; point < trace.pointCount; point++) {
            if (trace.vertexId[point] >= 0) {
                corners++;
            }
        }
        ring.pathVertexIds = new int[corners];
        int corner = 0;
        for (int point = 0; point < trace.pointCount; point++) {
            if (trace.vertexId[point] >= 0) {
                ring.pathVertexIds[corner++] = trace.vertexId[point];
            }
        }
        ring.setCentroid(trace);
        ring.setMeanRadius();
        return ring;
    }

    private static float[] closedPolyline(TracedSurfacePath traced) {
        int pointCount = traced.closed ? traced.pointCount + 1 : traced.pointCount;
        float[] packed = new float[SurfaceWaypoints.COORDINATES_PER_WAYPOINT * pointCount];
        for (int point = 0; point < pointCount; point++) {
            int source = SurfaceWaypoints.COORDINATES_PER_WAYPOINT
                    * (point % Math.max(1, traced.pointCount));
            int target = SurfaceWaypoints.COORDINATES_PER_WAYPOINT * point;
            packed[target] = (float) traced.positions[source];
            packed[target + 1] = (float) traced.positions[source + 1];
            packed[target + 2] = (float) traced.positions[source + 2];
        }
        return packed;
    }

    private void setCentroid(TracedSurfacePath traced) {
        double weightedX = 0.0;
        double weightedY = 0.0;
        double weightedZ = 0.0;
        double totalWeight = 0.0;
        int spans = traced.closed ? traced.pointCount : traced.pointCount - 1;
        for (int span = 0; span < spans; span++) {
            int here = SurfaceWaypoints.COORDINATES_PER_WAYPOINT * span;
            int there = SurfaceWaypoints.COORDINATES_PER_WAYPOINT
                    * ((span + 1) % traced.pointCount);
            double dx = traced.positions[there] - traced.positions[here];
            double dy = traced.positions[there + 1] - traced.positions[here + 1];
            double dz = traced.positions[there + 2] - traced.positions[here + 2];
            double weight = Math.sqrt(dx * dx + dy * dy + dz * dz);
            weightedX += weight * 0.5 * (traced.positions[here] + traced.positions[there]);
            weightedY += weight * 0.5 * (traced.positions[here + 1] + traced.positions[there + 1]);
            weightedZ += weight * 0.5 * (traced.positions[here + 2] + traced.positions[there + 2]);
            totalWeight += weight;
        }
        if (totalWeight <= 0.0) {
            return;
        }
        centroidX = (float) (weightedX / totalWeight);
        centroidY = (float) (weightedY / totalWeight);
        centroidZ = (float) (weightedZ / totalWeight);
    }

    private void setMeanRadius() {
        int points = polyline.length / SurfaceWaypoints.COORDINATES_PER_WAYPOINT;
        if (points == 0) {
            return;
        }
        double radiusSum = 0.0;
        for (int point = 0; point < points; point++) {
            int base = SurfaceWaypoints.COORDINATES_PER_WAYPOINT * point;
            double dx = polyline[base] - centroidX;
            double dy = polyline[base + 1] - centroidY;
            double dz = polyline[base + 2] - centroidZ;
            radiusSum += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        meanRadius = radiusSum / points;
    }
}
