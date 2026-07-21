package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.Trace;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceSegment;

/**
 * Decides, for every recorded trace/mesh-edge crossing, whether the carve may realize it on an
 * existing mesh vertex instead of splitting the edge it crosses — LCBK19 §6.1's snapping rule:
 * <em>"Instead of arc integration through mesh splitting, we propose to snap all nodes and arcs
 * onto nearby vertices and edges. Only if there are not enough vertices or edges is the mesh
 * split."</em>
 *
 * <p>Snapping is decided globally, before any arc is carved, because it cannot be decided per arc.
 * Moving a crossing to an endpoint moves it <em>past</em> any other crossing lying between the two,
 * which inverts their order along the edge — and two arcs whose order along a shared edge is
 * inverted have crossed. A per-arc greedy snap cannot see the crossings of arcs not yet carved, so
 * it inverts orders it has no way to detect; this class sees every crossing of every trace at once.
 *
 * <p>The rule is therefore: a crossing may snap onto an endpoint of the source edge it crosses when
 * it is the <em>extremal</em> crossing toward that endpoint among all crossings of that edge, and
 * that endpoint is the nearer of the two. Extremality is what makes the snap safe, and the argument
 * is short. Snapping a crossing X on edge {@code AB} to {@code A} sweeps the arc's chord across a
 * region bounded by three curves: the old chord, the new chord, and the stretch of {@code AB}
 * between {@code A} and X. Another trace inside that region must enter and leave it through those
 * three boundaries. It cannot cross either chord — traces meet only at nodes, and a node on the old
 * chord is itself a carve point that subdivides it — and it cannot cross {@code A}–X, because
 * extremality says no crossing lies there. So the swept region is empty and the snap changes no
 * arc's relative position. Restricting to the nearer endpoint is the second half of the rule: it
 * bounds the motion to half an edge, so a crossing never lands somewhere the trace never went.
 *
 * <p>Two crossings on different edges may still both be extremal toward the same shared vertex.
 * That contention is not resolved here but at carve time, by the claim map: the first arc takes the
 * vertex and the second finds it claimed and splits instead. That is exactly the paper's fallback —
 * the mesh is split only when there are not enough vertices.
 */
public final class EdgeCrossingSnap {

    /** Corners (and edges) of a triangle. */
    private static final int CORNERS = 3;

    /** A crossing may only snap onto the endpoint of its own half of the edge. */
    private static final double NEARER_HALF = 0.5;

    /**
     * System property that suppresses every snap, so the carve falls back to splitting at every
     * crossing — [Myles et al. 2014]'s always-split. This exists to attribute a downstream change
     * to the snapping and nothing else, by running the same mesh both ways.
     */
    private static final String DISABLE_PROPERTY = "embeddedTMesh.disableCrossingSnap";

    public final EmbeddedMeshTopology topology;
    public final HalfEdgeMesh sourceMesh;

    /** Source vertex each snappable crossing may move onto, keyed by the segment that records it. */
    public final Map<TraceSegment, Integer> snapSourceVertexBySegment;

    /** Recorded crossings the plan considered. */
    public int crossingCount;

    /**
     * Builds the snap plan for every crossing the tracer recorded.
     *
     * @param topology        working copy the carve will write into
     * @param motorcycleGraph traced T-mesh whose segments carry the crossings
     */
    public EdgeCrossingSnap(EmbeddedMeshTopology topology, MotorcycleGraph motorcycleGraph) {
        this.topology = topology;
        this.sourceMesh = motorcycleGraph.seamless.mesh;
        this.snapSourceVertexBySegment = new IdentityHashMap<>();

        Map<Long, TraceSegment> extremalTowardStart = new HashMap<>();
        Map<Long, TraceSegment> extremalTowardEnd = new HashMap<>();
        for (Trace trace : motorcycleGraph.traces) {
            for (TraceSegment segment : trace.segments) {
                if (segment.exitLocalEdgeIndex < 0 || Double.isNaN(segment.exitEdgeParameter)) {
                    continue;
                }
                crossingCount++;
                long edgeKey = edgeKey(segment);
                double parameter = canonicalParameter(segment);
                TraceSegment towardStart = extremalTowardStart.get(edgeKey);
                if (towardStart == null || parameter < canonicalParameter(towardStart)) {
                    extremalTowardStart.put(edgeKey, segment);
                }
                TraceSegment towardEnd = extremalTowardEnd.get(edgeKey);
                if (towardEnd == null || parameter > canonicalParameter(towardEnd)) {
                    extremalTowardEnd.put(edgeKey, segment);
                }
            }
        }

        for (Map.Entry<Long, TraceSegment> entry : extremalTowardStart.entrySet()) {
            TraceSegment segment = entry.getValue();
            if (canonicalParameter(segment) < NEARER_HALF) {
                snapSourceVertexBySegment.put(segment, (int) (entry.getKey() >> Integer.SIZE));
            }
        }
        for (Map.Entry<Long, TraceSegment> entry : extremalTowardEnd.entrySet()) {
            TraceSegment segment = entry.getValue();
            if (canonicalParameter(segment) > NEARER_HALF) {
                snapSourceVertexBySegment.put(segment, (int) (entry.getKey() & 0xFFFFFFFFL));
            }
        }
    }

    /**
     * The copy vertex a crossing may be realized on instead of splitting its edge, when the plan
     * admits the snap and the vertex is still available to the arc being carved.
     *
     * <p>Available means one of two things. Either no T-mesh element has claimed the vertex since
     * the plan was made — the ordinary snap — or the carve is standing on it already, which is the
     * arc clipping a mesh corner: it crosses two edges of one triangle either side of a vertex, and
     * both crossings are extremal toward it. The arc then passes through the corner once rather
     * than splitting the second edge to land beside a vertex it is already on. Anything else is the
     * paper's contention case, where a second element wants a vertex the first has taken, and the
     * mesh is split because there are not enough vertices.
     *
     * @param segment  segment whose recorded exit crossing is being carved
     * @param headVertex copy vertex the carve currently stands on
     * @return the copy vertex to snap onto, or {@link EmbeddedMeshTopology#UNCLAIMED} when the
     *         crossing must be materialized by splitting
     */
    public int snapCopyVertex(TraceSegment segment, int headVertex) {
        Integer sourceVertexId = snapSourceVertexBySegment.get(segment);
        if (sourceVertexId == null || Boolean.getBoolean(DISABLE_PROPERTY)) {
            return EmbeddedMeshTopology.UNCLAIMED;
        }
        int copyVertex = topology.copyVertexForSourceVertexId(sourceVertexId);
        if (copyVertex == EmbeddedMeshTopology.UNCLAIMED) {
            return EmbeddedMeshTopology.UNCLAIMED;
        }
        if (copyVertex == headVertex) {
            return copyVertex;
        }
        if (topology.ownerNodeByCopyVertex[copyVertex] != EmbeddedMeshTopology.UNCLAIMED
                || topology.ownerArcByCopyVertex[copyVertex] != EmbeddedMeshTopology.UNCLAIMED) {
            return EmbeddedMeshTopology.UNCLAIMED;
        }
        return copyVertex;
    }

    /**
     * Identity of the source edge a crossing lies on, orientation-independent so that the two faces
     * sharing the edge agree on it.
     *
     * @param segment segment whose exit crossing is being keyed
     * @return the edge's two source vertex ids packed lower-first into a long
     */
    private long edgeKey(TraceSegment segment) {
        int fromVertexId = exitFromVertexId(segment);
        int toVertexId = exitToVertexId(segment);
        int lower = Math.min(fromVertexId, toVertexId);
        int upper = Math.max(fromVertexId, toVertexId);
        return ((long) lower << Integer.SIZE) | (upper & 0xFFFFFFFFL);
    }

    /**
     * Parameter of a crossing along its source edge, measured from the edge's lower-id endpoint so
     * that crossings recorded from either incident face are directly comparable.
     *
     * @param segment segment whose exit crossing is being measured
     * @return the crossing's parameter in the edge's canonical direction
     */
    private double canonicalParameter(TraceSegment segment) {
        return exitFromVertexId(segment) < exitToVertexId(segment)
                ? segment.exitEdgeParameter
                : 1.0 - segment.exitEdgeParameter;
    }

    /**
     * Source vertex the exit edge's parameter is measured from, in the recording face's own
     * orientation.
     *
     * @param segment segment whose exit edge is being read
     * @return the source vertex id at parameter zero
     */
    private int exitFromVertexId(TraceSegment segment) {
        return sourceMesh.faceVertexAt(sourceMesh.faceIdAt(segment.activeFace),
                segment.exitLocalEdgeIndex);
    }

    /**
     * Source vertex the exit edge's parameter runs toward, in the recording face's own orientation.
     *
     * @param segment segment whose exit edge is being read
     * @return the source vertex id at parameter one
     */
    private int exitToVertexId(TraceSegment segment) {
        return sourceMesh.faceVertexAt(sourceMesh.faceIdAt(segment.activeFace),
                (segment.exitLocalEdgeIndex + 1) % CORNERS);
    }
}
