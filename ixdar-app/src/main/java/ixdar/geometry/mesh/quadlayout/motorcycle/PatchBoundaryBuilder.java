package ixdar.geometry.mesh.quadlayout.motorcycle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

/**
 * Assembles the T-mesh patches as faces of the trace arrangement: every arc
 * contributes two directed sides, each node orders its incident arc-ends
 * cyclically (by chart angle at intersection/termination nodes, by fan order
 * at singularities), and walking "arrive, then leave through the next port"
 * enumerates each arrangement face exactly once. This replaces any
 * triangle-level region growing, which breaks down as soon as several traces
 * cross one triangle.
 *
 * <p>
 * Each resulting cycle becomes a {@link TMeshPatch}; corners are hops where
 * the travel direction turns instead of continuing straight (T-junction
 * pass-throughs stay straight), and a patch with exactly four corners gets its
 * sides split for Lyon's eq. (2) consistency constraints. Known gaps that
 * surface as invalid patches rather than wrong constraints: traces terminating
 * exactly on singular vertices create disconnected terminal nodes, and
 * truncated traces leave dangling arcs whose cycles fold back on themselves.
 */
public final class PatchBoundaryBuilder {

    /** Normalizer keeping the within-wedge fraction of a fan sort key below one. */
    public static final double TWO_PI = Math.PI * 2.0;

    public final MotorcycleGraph graph;

    private final HalfEdgeMesh mesh;
    private final ChartWalker walker;
    private final Map<Integer, List<PatchPort>> portsByNode = new HashMap<>();

    /**
     * Prepares a builder over a finished motorcycle graph (arcs subdivided,
     * meeting axes recorded).
     *
     * @param graph built motorcycle graph whose patches get rebuilt from the
     *              arrangement
     */
    public PatchBoundaryBuilder(MotorcycleGraph graph) {
        this.graph = graph;
        this.mesh = graph.seamless.mesh;
        this.walker = new ChartWalker(graph.seamless);
    }

    /**
     * Rebuild {@code graph.patches} from the arrangement walk and log how many
     * resolved to clean four-sided rectangles.
     */
    public void build() {
        buildPorts();
        sortPorts();

        int arcTotal = graph.arcs.size();
        boolean[] visitedDirected = new boolean[2 * arcTotal];
        graph.patches.clear();
        int validCount = 0;
        int unresolvedCycles = 0;
        for (int arcId = 0; arcId < arcTotal; arcId++) {
            for (int directionFlag = 0; directionFlag < 2; directionFlag++) {
                int directedStart = arcId * 2 + directionFlag;
                if (visitedDirected[directedStart]) {
                    continue;
                }
                List<Integer> cycleArcIds = new ArrayList<>();
                List<Boolean> hopIsCorner = new ArrayList<>();
                boolean resolved = true;
                int directed = directedStart;
                while (true) {
                    visitedDirected[directed] = true;
                    int currentArcId = directed / 2;
                    boolean forward = directed % 2 == 0;
                    cycleArcIds.add(currentArcId);
                    TraceArc arc = graph.arcs.get(currentArcId);
                    int headNodeId = forward ? arc.endNodeId : arc.startNodeId;
                    List<PatchPort> nodePorts = portsByNode.get(headNodeId);
                    int arrivalIndex = indexOfPort(nodePorts, currentArcId, !forward);
                    if (arrivalIndex < 0) {
                        resolved = false;
                        break;
                    }
                    PatchPort arrival = nodePorts.get(arrivalIndex);
                    PatchPort departure = nodePorts.get((arrivalIndex + 1) % nodePorts.size());
                    boolean straight = departure.directionU == -arrival.directionU
                            && departure.directionV == -arrival.directionV;
                    hopIsCorner.add(!straight);
                    int nextDirected = departure.arcId * 2 + (departure.outgoing ? 0 : 1);
                    if (nextDirected == directedStart) {
                        break;
                    }
                    if (visitedDirected[nextDirected]) {
                        resolved = false;
                        break;
                    }
                    directed = nextDirected;
                }

                TMeshPatch patch = new TMeshPatch(graph.patches.size());
                patch.boundingArcIds.addAll(cycleArcIds);
                List<Integer> cornerPositions = new ArrayList<>();
                for (int hop = 0; hop < hopIsCorner.size(); hop++) {
                    if (hopIsCorner.get(hop)) {
                        cornerPositions.add(hop);
                    }
                }
                if (resolved && cornerPositions.size() == 4) {
                    splitSides(patch, cycleArcIds, cornerPositions);
                    patch.validRectangle = true;
                    validCount++;
                } else {
                    patch.validRectangle = false;
                    if (!resolved) {
                        unresolvedCycles++;
                    }
                }
                graph.patches.add(patch);
            }
        }
        System.out.printf(
                "[motorcycle] arrangement patches: %d cycles, %d valid rectangles, %d unresolved%n",
                graph.patches.size(), validCount, unresolvedCycles);
    }

    /**
     * Emit the two directed arc-end ports of every arc, with travel directions
     * taken from the meeting record at interior nodes, the spawn port at
     * origins, and the final walker state (or last feature segment) at
     * terminals.
     */
    private void buildPorts() {
        for (Trace trace : graph.traces) {
            int arcCount = trace.chainArcIds.size();
            if (arcCount == 0) {
                continue;
            }
            Map<Integer, MetOtherTraceEntry> meetingByNode = new HashMap<>();
            for (MetOtherTraceEntry meeting : trace.metOtherTraces) {
                if (meeting.intersectionNodeId >= 0) {
                    meetingByNode.putIfAbsent(meeting.intersectionNodeId, meeting);
                }
            }
            for (int position = 0; position <= arcCount; position++) {
                int nodeId = trace.arcNodeIds.get(position);
                int[] travel = travelDirectionAt(trace, nodeId, position, meetingByNode);
                if (position < arcCount) {
                    addPort(new PatchPort(nodeId, trace.chainArcIds.get(position), true,
                            travel[0], travel[1], trace.traceId));
                }
                if (position > 0) {
                    addPort(new PatchPort(nodeId, trace.chainArcIds.get(position - 1), false,
                            -travel[0], -travel[1], trace.traceId));
                }
            }
        }
    }

    /**
     * The trace's travel direction at one of its chain nodes, in the chart the
     * node's other incident arcs use: meeting axes at meeting nodes (both
     * traces recorded theirs in the shared face), the spawn port at the
     * origin, and the last known walker state or feature segment at terminals.
     */
    private int[] travelDirectionAt(Trace trace, int nodeId, int position,
            Map<Integer, MetOtherTraceEntry> meetingByNode) {
        TraceAxis axis;
        int sign;
        MetOtherTraceEntry meeting = meetingByNode.get(nodeId);
        if (position == 0) {
            axis = trace.spawnAxis;
            sign = trace.spawnSign;
        } else if (meeting != null) {
            axis = meeting.ourAxis;
            sign = meeting.ourSign;
        } else if (trace.featureTrace) {
            TraceSegment lastSegment = trace.segments.get(trace.segments.size() - 1);
            axis = lastSegment.axis;
            sign = lastSegment.sign;
        } else {
            axis = trace.state.axis;
            sign = trace.state.sign;
        }
        double[] direction = axis.direction(sign);
        return new int[] { (int) Math.round(direction[0]), (int) Math.round(direction[1]) };
    }

    private void addPort(PatchPort port) {
        portsByNode.computeIfAbsent(port.nodeId, nodeId -> new ArrayList<>()).add(port);
    }

    /**
     * Assign cyclic sort keys per node and order its ports: chart angle at
     * single-chart nodes, vertex-fan position plus within-wedge angle at
     * singularity origins (whose ports live in different fan-face charts).
     */
    private void sortPorts() {
        for (Map.Entry<Integer, List<PatchPort>> entry : portsByNode.entrySet()) {
            TMeshNode node = graph.nodes.get(entry.getKey());
            boolean singularityFan = node.type == TMeshNode.TYPE_SINGULARITY
                    && node.singularityVertexId >= 0;
            for (PatchPort port : entry.getValue()) {
                port.sortKey = singularityFan
                        ? fanSortKey(node.singularityVertexId, port)
                        : Math.atan2(port.directionV, port.directionU);
            }
            entry.getValue().sort(Comparator
                    .comparingDouble((PatchPort port) -> port.sortKey)
                    .thenComparingInt(port -> port.arcId)
                    .thenComparing(port -> port.outgoing));
        }
    }

    /**
     * Fan-order key for a port at a singularity: the index of its spawn face
     * in the vertex's face fan, plus the CCW angle of the port direction from
     * the spawn wedge's opening edge as a sub-unit fraction.
     */
    private double fanSortKey(int singularityVertexId, PatchPort port) {
        TracePort spawnPort = graph.traces.get(port.traceId).spawnPort;
        int spawnFaceId = mesh.faceIdAt(spawnPort.activeFace);
        int fanIndex = 0;
        int fanCount = mesh.vertexFaceCount(singularityVertexId);
        for (int i = 0; i < fanCount; i++) {
            if (mesh.vertexFaceAt(singularityVertexId, i) == spawnFaceId) {
                fanIndex = i;
                break;
            }
        }
        double[] cornerUv = new double[ChartWalker.CORNER_UV_FLOATS];
        walker.faceCornerUv(spawnPort.activeFace, cornerUv);
        int corner = spawnPort.cornerIndex;
        int nextCorner = (corner + 1) % 3;
        double edgeU = cornerUv[nextCorner * 2] - cornerUv[corner * 2];
        double edgeV = cornerUv[nextCorner * 2 + 1] - cornerUv[corner * 2 + 1];
        double cross = edgeU * port.directionV - edgeV * port.directionU;
        double dot = edgeU * port.directionU + edgeV * port.directionV;
        double relativeAngle = Math.atan2(cross, dot);
        if (relativeAngle < 0.0) {
            relativeAngle += TWO_PI;
        }
        return fanIndex + relativeAngle / (TWO_PI + 1.0);
    }

    private int indexOfPort(List<PatchPort> nodePorts, int arcId, boolean outgoing) {
        if (nodePorts == null) {
            return -1;
        }
        for (int i = 0; i < nodePorts.size(); i++) {
            PatchPort port = nodePorts.get(i);
            if (port.arcId == arcId && port.outgoing == outgoing) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Split the cycle at its four corners into sides; side {@code j} runs from
     * just after corner {@code j} through corner {@code j + 1}.
     */
    private void splitSides(TMeshPatch patch, List<Integer> arcCycle, List<Integer> cornerPositions) {
        int cycleLength = arcCycle.size();
        for (int j = 0; j < cornerPositions.size(); j++) {
            int from = (cornerPositions.get(j) + 1) % cycleLength;
            int to = cornerPositions.get((j + 1) % cornerPositions.size());
            List<Integer> side = new ArrayList<>();
            int position = from;
            while (true) {
                side.add(arcCycle.get(position));
                if (position == to) {
                    break;
                }
                position = (position + 1) % cycleLength;
            }
            patch.sides.add(side);
        }
    }
}
