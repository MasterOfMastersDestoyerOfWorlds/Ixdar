package ixdar.geometry.mesh.quadlayout.motorcycle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.MetOtherTraceEntry;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.PatchPort;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TMeshNode;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TMeshPatch;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.Trace;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceArc;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceAxis;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceSegment;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * Assembles the T-mesh patches as faces of the trace arrangement: every arc
 * contributes two directed sides, each node orders its incident arc-ends
 * cyclically (by chart angle at intersection/termination nodes, by fan order at
 * singularities), and walking "arrive, then leave through the next port"
 * enumerates each arrangement face exactly once. This replaces any
 * triangle-level region growing, which breaks down as soon as several traces
 * cross one triangle.
 *
 * <p>
 * Each resulting cycle becomes a {@link TMeshPatch}; corners are hops where the
 * travel direction turns instead of continuing straight (T-junction
 * pass-throughs stay straight), and a patch with exactly four corners gets its
 * sides split for Lyon's eq. (2) consistency constraints. Known gaps that
 * surface as invalid patches rather than wrong constraints: traces terminating
 * exactly on singular vertices create disconnected terminal nodes, and
 * truncated traces leave dangling arcs whose cycles fold back on themselves.
 */
public final class PatchBoundaryBuilder {

    /** Full turn used to wrap negative within-wedge angles into [0, 2π). */
    public static final double TWO_PI = Math.PI * 2.0;

    private static final int CORNERS = SeamlessParameterization.CORNERS_PER_FACE;
    /** Tolerance for port directions lying exactly on a wedge's opening edge. */
    private static final double WEDGE_ANGLE_EPS = 1.0e-9;
    private static final int INVALID_CYCLE_SAMPLE_LIMIT = 4;
    /**
     * Temporary diagnostic focus: only sample-dump cycles with this corner count.
     */
    private static final int TWELVE_CORNER_FOCUS = 12;
    /** How many sampled cycles also get their full per-node port tables dumped. */
    private static final int PORT_TABLE_SAMPLE_LIMIT = 1;
    private static final int INVALID_CYCLE_HOP_DUMP_LIMIT = 24;

    public final MotorcycleGraph graph;

    /** Invalid-cycle histogram keyed by corner count. */
    public final Map<Integer, Integer> invalidCycleCountByCornerCount = new HashMap<>();

    /** Invalid cycles that traverse some arc twice (dead-end fold-backs). */
    public int invalidCycleFoldBackCount;

    private final HalfEdgeMesh mesh;
    private final Map<Integer, List<PatchPort>> portsByNode = new HashMap<>();
    private int invalidCycleSamplesPrinted;

    /**
     * Prepares a builder over a finished motorcycle graph (arcs subdivided, meeting
     * axes recorded).
     *
     * @param graph built motorcycle graph whose patches get rebuilt from the
     *              arrangement
     */
    public PatchBoundaryBuilder(MotorcycleGraph graph) {
        this.graph = graph;
        this.mesh = graph.seamless.mesh;
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
                    boolean straight = graph.nodes.get(headNodeId).vertexId < 0
                            && departure.directionU == -arrival.directionU
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
                    recordInvalidCycle(cycleArcIds, cornerPositions, resolved);
                }
                graph.patches.add(patch);
            }
        }
        System.out.printf(
                "[motorcycle] arrangement patches: %d cycles, %d valid rectangles, %d unresolved%n",
                graph.patches.size(), validCount, unresolvedCycles);
        logInvalidCycleDiagnostics();
    }

    /**
     * Classify one invalid cycle into the corner-count histogram and, for the first
     * few, dump every hop so the failure shape (dead-end fold-back, mixed-chart
     * corner detection, giant outer cycle) is visible in the log.
     *
     * @param cycleArcIds     arcs of the cycle in walk order
     * @param cornerPositions hop indices where the walk turned
     * @param resolved        whether the walk closed cleanly back at its start
     */
    private void recordInvalidCycle(List<Integer> cycleArcIds, List<Integer> cornerPositions,
            boolean resolved) {
        invalidCycleCountByCornerCount.merge(cornerPositions.size(), 1, Integer::sum);
        boolean foldBack = cycleArcIds.size() != new HashSet<>(cycleArcIds).size();
        if (foldBack) {
            invalidCycleFoldBackCount++;
        }
        if (cornerPositions.size() != TWELVE_CORNER_FOCUS
                || invalidCycleSamplesPrinted >= INVALID_CYCLE_SAMPLE_LIMIT) {
            return;
        }
        invalidCycleSamplesPrinted++;
        StringBuilder hops = new StringBuilder();
        for (int hop = 0; hop < cycleArcIds.size() && hop < INVALID_CYCLE_HOP_DUMP_LIMIT; hop++) {
            int arcId = cycleArcIds.get(hop);
            TraceArc arc = graph.arcs.get(arcId);
            int endNodeId = arc.endNodeId;
            TMeshNode endNode = graph.nodes.get(endNodeId);
            List<PatchPort> ports = portsByNode.get(endNodeId);
            hops.append(String.format(" (arc=%d trace=%d node=%d type=%s vtx=%d ports=%d)",
                    arcId, arc.traceId, endNodeId, endNode.type, endNode.vertexId,
                    ports == null ? 0 : ports.size()));
        }
        System.out.printf(
                "[patch-diag] invalid cycle: hops=%d corners=%d resolved=%b foldBack=%b%s%n",
                cycleArcIds.size(), cornerPositions.size(), resolved, foldBack, hops);
        if (invalidCycleSamplesPrinted > PORT_TABLE_SAMPLE_LIMIT) {
            return;
        }
        Set<Integer> dumpedNodes = new HashSet<>();
        for (int arcId : cycleArcIds) {
            TraceArc arc = graph.arcs.get(arcId);
            for (int nodeId : new int[] { arc.startNodeId, arc.endNodeId }) {
                if (!dumpedNodes.add(nodeId)) {
                    continue;
                }
                StringBuilder table = new StringBuilder();
                for (PatchPort port : portsByNode.get(nodeId)) {
                    table.append(String.format(" (arc=%d out=%b dir=%d,%d key=%.4f)",
                            port.arcId, port.outgoing, port.directionU, port.directionV,
                            port.sortKey));
                }
                System.out.printf("[patch-diag]   node=%d ports:%s%n", nodeId, table);
            }
        }
    }

    /**
     * One-line histogram of invalid cycles keyed by corner count plus the fold-back
     * tally, emitted after the arrangement walk.
     */
    private void logInvalidCycleDiagnostics() {
        if (invalidCycleCountByCornerCount.isEmpty()) {
            return;
        }
        System.out.printf("[patch-diag] invalid cycles by corner count=%s foldBacks=%d%n",
                new TreeMap<>(invalidCycleCountByCornerCount), invalidCycleFoldBackCount);
    }

    /**
     * Emit the two directed arc-end ports of every arc, with travel directions
     * taken from the meeting record at interior nodes, the spawn port at origins,
     * and the final walker state (or last feature segment) at terminals. Each port
     * also carries the chart face its direction is expressed in (spawn face,
     * meeting face, terminal face) so vertex-located nodes can fan-order ports that
     * arrive through different charts.
     */
    private void buildPorts() {
        for (Trace trace : graph.traces) {
            int arcCount = trace.chainArcIds.size();
            if (arcCount == 0) {
                continue;
            }
            for (int position = 0; position <= arcCount; position++) {
                int nodeId = trace.arcNodeIds.get(position);
                TraceAxis axis;
                int sign;
                int chartFace;
                MetOtherTraceEntry meeting = meetingAt(trace, nodeId,
                        trace.chainNodeLengths.get(position));
                if (position == 0) {
                    axis = trace.spawnAxis;
                    sign = trace.spawnSign;
                    chartFace = trace.spawnPort.activeFace;
                } else if (meeting != null) {
                    axis = meeting.ourAxis;
                    sign = meeting.ourSign;
                    chartFace = -1;
                } else if (trace.featureTrace) {
                    TraceSegment lastSegment = trace.segments.get(trace.segments.size() - 1);
                    axis = lastSegment.axis;
                    sign = lastSegment.sign;
                    chartFace = lastSegment.activeFace;
                } else {
                    axis = trace.state.axis;
                    sign = trace.state.sign;
                    chartFace = trace.state.activeFace;
                }
                double[] direction = axis.direction(sign);
                int travelU = (int) Math.round(direction[0]);
                int travelV = (int) Math.round(direction[1]);
                if (position < arcCount) {
                    addPort(new PatchPort(nodeId, trace.chainArcIds.get(position), true,
                            travelU, travelV, trace.traceId, chartFace));
                }
                if (position > 0) {
                    addPort(new PatchPort(nodeId, trace.chainArcIds.get(position - 1), false,
                            -travelU, -travelV, trace.traceId, chartFace));
                }
            }
        }
    }

    /**
     * The meeting at a given node that best matches a chain position's parametric
     * length. A trace that crosses its own path visits one node twice, at two
     * different parametric lengths and with two different travel directions; a
     * node-only lookup would give both chain positions the same arm's direction
     * and fold the patch back, so the matching trace length disambiguates them.
     *
     * @param trace  trace whose meetings are searched
     * @param nodeId T-mesh node id at this chain position
     * @param length cumulative parametric length of this chain position
     * @return the closest-length meeting recorded at that node, or {@code null}
     */
    private static MetOtherTraceEntry meetingAt(Trace trace, int nodeId, double length) {
        MetOtherTraceEntry best = null;
        double bestDifference = Double.MAX_VALUE;
        for (MetOtherTraceEntry meeting : trace.metOtherTraces) {
            if (meeting.intersectionNodeId != nodeId) {
                continue;
            }
            double difference = Math.abs(meeting.ourParametricLength - length);
            if (difference < bestDifference) {
                bestDifference = difference;
                best = meeting;
            }
        }
        return best;
    }

    private void addPort(PatchPort port) {
        portsByNode.computeIfAbsent(port.nodeId, nodeId -> new ArrayList<>()).add(port);
    }

    /**
     * Assign cyclic sort keys per node and order its ports: chart angle at
     * single-chart nodes, unrolled-fan angle at vertex-located nodes
     * (singularities, feature corners, singular-vertex terminals — their ports live
     * in different fan-face charts, so each face's wedge is laid out flat around
     * the vertex and a port's key is its face's accumulated base angle plus its CCW
     * angle inside that wedge).
     */
    private void sortPorts() {
        for (Map.Entry<Integer, List<PatchPort>> entry : portsByNode.entrySet()) {
            TMeshNode node = graph.nodes.get(entry.getKey());
            Map<Integer, double[]> fanFrames = node.vertexId >= 0
                    ? unrolledFanFrames(node.vertexId)
                    : null;
            for (PatchPort port : entry.getValue()) {
                double[] frame = fanFrames != null && port.activeFace >= 0
                        ? fanFrames.get(port.activeFace)
                        : null;
                if (frame == null) {
                    port.sortKey = Math.atan2(port.directionV, port.directionU);
                    continue;
                }
                double cross = frame[1] * port.directionV - frame[2] * port.directionU;
                double dot = frame[1] * port.directionU + frame[2] * port.directionV;
                double withinWedge = Math.atan2(cross, dot);
                if (withinWedge < -WEDGE_ANGLE_EPS) {
                    withinWedge += TWO_PI;
                }
                port.sortKey = frame[0] + withinWedge;
            }
            entry.getValue().sort(Comparator
                    .comparingDouble((PatchPort port) -> port.sortKey)
                    .thenComparingInt(port -> port.arcId)
                    .thenComparing(port -> port.outgoing));
        }
        logAmbiguousPortOrderings();
    }

    /**
     * Count nodes whose sorted ports contain near-identical sort keys — there the
     * cyclic order degrades to the arcId tie-break, which carries no geometric
     * meaning, and the arrangement walk fuses the surrounding patches into one big
     * invalid cycle. Dumps the first few offenders.
     */
    private void logAmbiguousPortOrderings() {
        int ambiguousNodes = 0;
        int printed = 0;
        for (Map.Entry<Integer, List<PatchPort>> entry : portsByNode.entrySet()) {
            List<PatchPort> ports = entry.getValue();
            boolean ambiguous = false;
            for (int i = 1; i < ports.size(); i++) {
                if (Math.abs(ports.get(i).sortKey - ports.get(i - 1).sortKey) < WEDGE_ANGLE_EPS) {
                    ambiguous = true;
                    break;
                }
            }
            if (!ambiguous) {
                continue;
            }
            ambiguousNodes++;
            if (printed >= INVALID_CYCLE_SAMPLE_LIMIT) {
                continue;
            }
            printed++;
            TMeshNode node = graph.nodes.get(entry.getKey());
            StringBuilder portDump = new StringBuilder();
            for (PatchPort port : ports) {
                portDump.append(String.format(" (arc=%d out=%b dir=%d,%d face=%d key=%.4f)",
                        port.arcId, port.outgoing, port.directionU, port.directionV,
                        port.activeFace, port.sortKey));
            }
            System.out.printf("[patch-diag] ambiguous ports node=%d type=%d vertex=%d:%s%n",
                    entry.getKey(), node.type, node.vertexId, portDump);
        }
        if (ambiguousNodes > 0) {
            System.out.printf("[patch-diag] nodes with ambiguous port order: %d%n", ambiguousNodes);
        }
    }

    /**
     * Unroll the face fan around a vertex into a flat angular layout: walk the fan
     * CCW (rewinding CW to a boundary first, when one exists), give each face a
     * base angle equal to the accumulated wedge angles before it, and record the
     * wedge's opening-edge direction in that face's own chart. Wedge angles and
     * port directions are measured per face chart, so seam transitions between fan
     * faces cancel out and the resulting keys are a consistent cyclic order even at
     * singular cones whose total angle is not 2π.
     *
     * @param vertexId mesh vertex whose fan to unroll
     * @return per active face: {base angle, opening-edge u, opening-edge v}
     */
    private Map<Integer, double[]> unrolledFanFrames(int vertexId) {
        Map<Integer, double[]> frameByActiveFace = new HashMap<>();
        int fanCount = mesh.vertexFaceCount(vertexId);
        if (fanCount == 0) {
            return frameByActiveFace;
        }
        int startActiveFace = graph.seamless.crossField.faceIdToActive
                .get(mesh.vertexFaceAt(vertexId, 0));
        int currentActiveFace = startActiveFace;
        for (int step = 0; step < fanCount; step++) {
            int clockwiseNeighbor = fanNeighbor(currentActiveFace, vertexId, false);
            if (clockwiseNeighbor < 0 || clockwiseNeighbor == startActiveFace) {
                break;
            }
            currentActiveFace = clockwiseNeighbor;
        }
        int walkStartActiveFace = currentActiveFace;
        double[] cornerUv = new double[ChartWalker.CORNER_UV_FLOATS];
        double baseAngle = 0.0;
        for (int step = 0; step < fanCount; step++) {
            int faceId = mesh.faceIdAt(currentActiveFace);
            int corner = cornerOfVertexInFace(faceId, vertexId);
            graph.seamless.faceCornerUv(currentActiveFace, cornerUv);
            int openingCorner = (corner + 1) % CORNERS;
            int closingCorner = (corner + 2) % CORNERS;
            double openingU = cornerUv[openingCorner * 2] - cornerUv[corner * 2];
            double openingV = cornerUv[openingCorner * 2 + 1] - cornerUv[corner * 2 + 1];
            double closingU = cornerUv[closingCorner * 2] - cornerUv[corner * 2];
            double closingV = cornerUv[closingCorner * 2 + 1] - cornerUv[corner * 2 + 1];
            frameByActiveFace.put(currentActiveFace, new double[] { baseAngle, openingU, openingV });
            double wedgeAngle = Math.atan2(
                    openingU * closingV - openingV * closingU,
                    openingU * closingU + openingV * closingV);
            baseAngle += Math.max(wedgeAngle, WEDGE_ANGLE_EPS);
            int counterClockwiseNeighbor = fanNeighbor(currentActiveFace, vertexId, true);
            if (counterClockwiseNeighbor < 0 || counterClockwiseNeighbor == walkStartActiveFace) {
                break;
            }
            currentActiveFace = counterClockwiseNeighbor;
        }
        return frameByActiveFace;
    }

    /**
     * The fan-adjacent face across one of the two vertex-incident edges of
     * {@code activeFace} at {@code vertexId}. With CCW-wound faces, local edge
     * {@code corner} (corner → corner+1) borders the clockwise neighbor and local
     * edge {@code corner+2} (corner+2 → corner) the counter-clockwise one.
     *
     * @param activeFace       active face to step from
     * @param vertexId         vertex the fan revolves around
     * @param counterClockwise true for the CCW neighbor, false for CW
     * @return neighbor active face, or -1 across a boundary
     */
    private int fanNeighbor(int activeFace, int vertexId, boolean counterClockwise) {
        int faceId = mesh.faceIdAt(activeFace);
        int corner = cornerOfVertexInFace(faceId, vertexId);
        int localEdge = counterClockwise ? (corner + 2) % CORNERS : corner;
        int edgeId = mesh.faceEdgeAt(faceId, localEdge);
        Integer activeEdge = graph.seamless.crossField.edgeIdToActive.get(edgeId);
        if (activeEdge == null) {
            return -1;
        }
        HalfEdgeMesh.EdgeFaceIds edgeFaces = mesh.edgeFaceIds(activeEdge);
        int neighborFaceId = edgeFaces.faceA == faceId ? edgeFaces.faceB : edgeFaces.faceA;
        if (neighborFaceId < 0) {
            return -1;
        }
        return graph.seamless.crossField.faceIdToActive.get(neighborFaceId);
    }

    private int cornerOfVertexInFace(int faceId, int vertexId) {
        for (int corner = 0; corner < CORNERS; corner++) {
            if (mesh.faceVertexAt(faceId, corner) == vertexId) {
                return corner;
            }
        }
        return 0;
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
     * Split the cycle at its four corners into sides; side {@code j} runs from just
     * after corner {@code j} through corner {@code j + 1}.
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
