package ixdar.geometry.mesh.data.rings;

import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.paths.geodesic.ConformingLoopSnap;
import ixdar.geometry.mesh.data.paths.geodesic.FlipGeodesics;
import ixdar.geometry.mesh.data.paths.geodesic.GeodesicSeedPath;
import ixdar.geometry.mesh.data.paths.geodesic.IntrinsicPathTracer;
import ixdar.geometry.mesh.data.paths.geodesic.IntrinsicTriangulation;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceMetric;
import ixdar.geometry.mesh.data.paths.geodesic.TracedSurfacePath;
import ixdar.geometry.mesh.data.paths.query.NearestVertex;
import ixdar.geometry.mesh.data.paths.spline.SurfaceWaypoints;
import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * One ring through authored surface waypoints: the seed edge walk, the geodesic FlipOut tightens
 * it to, and the mesh edges each of them runs along.
 */
public final class SurfaceRing {

    /** The tightened geodesic, measured; closed when the walk returned to its first waypoint. */
    public TracedSurfacePath path;

    /** The untightened seed walk, measured; its length is always at least {@link #path}'s. */
    public TracedSurfacePath seed;

    /** The mesh edges {@link ConformingLoopSnap} puts nearest the tightened path. */
    public EdgeMarks marks = EdgeMarks.NONE;

    /** The mesh edges the seed walk runs along. */
    public EdgeMarks seedMarks = EdgeMarks.NONE;

    /** Mesh vertex ids the waypoints snapped to, in waypoint order. */
    public int[] waypointVertexIds = new int[0];

    /**
     * Mesh vertex ids the tightened path turns at, in travel order. Pinning any of them leaves
     * the ring where it is, so these are the waypoints a written statement reloads exactly from.
     */
    public int[] pathVertexIds = new int[0];

    /** Leading waypoints held fixed while the rest of the ring straightened. */
    public int pinnedWaypointCount;

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
        return build(mesh, SurfaceWaypoints.snap(NearestVertex.over(mesh), packedXyz, waypointCount), closed, pinned,
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
        return ring.marks.markedEdgeCount == 0 || ring.path.pointCount == 0 ? null : ring;
    }

    private static SurfaceRing build(MeshTopology mesh, int[] waypointVertexIds, boolean closed,
            int pinned, int iterations) {
        SurfaceRing ring = new SurfaceRing();
        ring.waypointVertexIds = waypointVertexIds;
        ring.pinnedWaypointCount = Math.min(Math.max(0, pinned), waypointVertexIds.length);

        SurfaceMetric metric = SurfaceMetric.of(mesh);
        IntrinsicTriangulation intrinsic = IntrinsicTriangulation.over(metric);
        IntrinsicPathTracer tracer = IntrinsicPathTracer.over(metric);
        int[] seed = GeodesicSeedPath.throughVertices(intrinsic, ring.waypointVertexIds, closed);
        ring.seed = tracer.trace(intrinsic, seed, closed).measure();
        ring.seedMarks = new ConformingLoopSnap().edgeLoop(mesh, ring.seed);

        FlipGeodesics flipper = new FlipGeodesics();
        if (ring.pinnedWaypointCount > 0) {
            flipper.vertexIsPinned = new boolean[metric.sourceVertexId.length];
            for (int waypoint = 0; waypoint < ring.pinnedWaypointCount; waypoint++) {
                flipper.vertexIsPinned[
                        metric.vertexIndexByVertexId[waypointVertexIds[waypoint]]] = true;
            }
        }
        int[] tightened = flipper.shorten(intrinsic, seed, closed, iterations);
        TracedSurfacePath trace = tracer.trace(intrinsic, tightened, closed).measure();
        ring.path = trace;
        ring.marks = new ConformingLoopSnap().edgeLoop(mesh, trace);
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
        return ring;
    }
}
