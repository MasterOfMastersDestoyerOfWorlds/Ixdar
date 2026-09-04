package ixdar.geometry.mesh.quadlayout.motorcycle;

import java.util.ArrayList;
import java.util.Collections;
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
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedNode;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.Trace;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceAxis;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceSegment;
import ixdar.platform.Platforms;

/**
 * Assembles the T-mesh patches as faces of the trace arrangement: every arc
 * gives two directed sides, each node orders its arc-ends cyclically, and
 * walking "arrive, leave through the next port" enumerates each face once as a
 * {@link EmbeddedPatch}, cornered where travel turns.
 *
 * <p>
 * See also: Lyon 2021 Section 4
 */
public final class PatchBoundaryBuilder {

    /** Full turn used to wrap negative within-wedge angles into [0, 2π). */
    public static final double TWO_PI = Math.PI * 2.0;

    /** Tolerance for port directions lying exactly on a wedge's opening edge. */
    private static final double WEDGE_ANGLE_EPS = 1.0e-9;
    private static final int INVALID_CYCLE_SAMPLE_LIMIT = 4;
    /**
     * Temporary diagnostic focus: only sample-dump cycles with this corner count.
     */
    /** How many sampled cycles also get their full per-node port tables dumped. */
    private static final int PORT_TABLE_SAMPLE_LIMIT = 1;
    private static final int INVALID_CYCLE_HOP_DUMP_LIMIT = 24;

    /** Sides of a rectangular patch. */
    private static final int SIDES = 4;

    /**
     * Relative gap between opposite parametric side lengths that still counts as a
     * rectangle.
     */
    private static final double RECTANGULARITY_TOLERANCE = 1.0e-6;

    public final MotorcycleGraph graph;

    /** Invalid-cycle histogram keyed by corner count. */
    public final Map<Integer, Integer> invalidCycleCountByCornerCount = new HashMap<>();

    /** Invalid cycles that traverse some arc twice (dead-end fold-backs). */
    public int invalidCycleFoldBackCount;

    /**
     * Hops away from a singularity whose turn is neither flat nor a
     * counter-clockwise π/2 corner, so LCBK19 §4's direction law fails and the
     * cycle bounding them is not a rectangle.
     */
    public int cornerLawViolationCount;

    /**
     * Hops away from a singularity that carry straight on in the same parametric
     * direction.
     */
    public int flatHopCount;

    /** Hops away from a singularity that turn counter-clockwise by π/2. */
    public int ccwCornerHopCount;

    /** Hops away from a singularity that turn clockwise by π/2. */
    public int cwCornerHopCount;

    /**
     * Hops at a singularity, where the cone angle is not π/2 and the law does not
     * apply.
     */
    public int singularityHopCount;

    /**
     * Opposite side pairs whose parametric lengths differ by more than
     * {@link #RECTANGULARITY_TOLERANCE}, so the patch is not the rectangle LCBK19
     * Def 3.1 requires.
     */
    public int rectangularityViolationCount;

    /** Largest relative gap between a patch's opposite parametric side lengths. */
    public double worstRectangularityError;

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
        this.mesh = graph.mesh;
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
                    EmbeddedArc arc = graph.arcs.get(currentArcId);
                    int headNodeId = forward ? arc.endNodeId : arc.startNodeId;
                    List<PatchPort> nodePorts = portsByNode.get(headNodeId);
                    int arrivalIndex = indexOfPort(nodePorts, currentArcId, !forward);
                    if (arrivalIndex < 0) {
                        resolved = false;
                        break;
                    }
                    PatchPort arrival = nodePorts.get(arrivalIndex);
                    PatchPort departure = nodePorts.get((arrivalIndex + 1) % nodePorts.size());
                    // The in-port points back along the arrival, so the incoming travel is its
                    // negation. LCBK19 §4: a flat halfarc keeps that direction, a corner rotates
                    // it counter-clockwise by π/2. A singularity is always a corner, and the cone
                    // angle there is not π/2, so the law is only meaningful away from one.
                    int incomingU = -arrival.directionU;
                    int incomingV = -arrival.directionV;
                    boolean atSingularity = graph.nodes.get(headNodeId).vertexId >= 0;
                    boolean flat = departure.directionU == incomingU
                            && departure.directionV == incomingV;
                    boolean turnsCcw = departure.directionU == -incomingV
                            && departure.directionV == incomingU;
                    boolean turnsCw = departure.directionU == incomingV
                            && departure.directionV == -incomingU;
                    if (!atSingularity) {
                        flatHopCount += flat ? 1 : 0;
                        ccwCornerHopCount += turnsCcw ? 1 : 0;
                        cwCornerHopCount += turnsCw ? 1 : 0;
                        if (!flat && !turnsCcw && !turnsCw) {
                            cornerLawViolationCount++;
                        }
                    } else {
                        singularityHopCount++;
                    }
                    boolean straight = !atSingularity && flat;

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

                EmbeddedPatch patch = new EmbeddedPatch(graph.patches.size(), graph.patches.size());
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
        Platforms.log(
                "[motorcycle] arrangement patches: %d cycles, %d valid rectangles, %d unresolved%n",
                graph.patches.size(), validCount, unresolvedCycles);
        Platforms.log(
                "[motorcycle] hop turns: flat=%d ccwCorner=%d cwCorner=%d atSingularity=%d"
                        + " lawViolations=%d%n",
                flatHopCount, ccwCornerHopCount, cwCornerHopCount, singularityHopCount,
                cornerLawViolationCount);
        Platforms.log(
                "[motorcycle] Def 3.1 rectangularity: violations=%d worstRelativeError=%.6f%n",
                rectangularityViolationCount, worstRectangularityError);
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
        if (invalidCycleSamplesPrinted >= INVALID_CYCLE_SAMPLE_LIMIT) {
            return;
        }
        invalidCycleSamplesPrinted++;
        StringBuilder hops = new StringBuilder();
        for (int hop = 0; hop < cycleArcIds.size() && hop < INVALID_CYCLE_HOP_DUMP_LIMIT; hop++) {
            int arcId = cycleArcIds.get(hop);
            EmbeddedArc arc = graph.arcs.get(arcId);
            int endNodeId = arc.endNodeId;
            EmbeddedNode endNode = graph.nodes.get(endNodeId);
            List<PatchPort> ports = portsByNode.get(endNodeId);
            hops.append(String.format(" (arc=%d trace=%d node=%d critical=%b border=%b vtx=%d ports=%d)",
                    arcId, arc.traceId, endNodeId, endNode.critical, endNode.border,
                    endNode.vertexId, ports == null ? 0 : ports.size()));
        }
        Platforms.log(
                "[patch-diag] invalid cycle: hops=%d corners=%d resolved=%b foldBack=%b%s%n",
                cycleArcIds.size(), cornerPositions.size(), resolved, foldBack, hops);
        if (invalidCycleSamplesPrinted > PORT_TABLE_SAMPLE_LIMIT) {
            return;
        }
        Set<Integer> dumpedNodes = new HashSet<>();
        for (int arcId : cycleArcIds) {
            EmbeddedArc arc = graph.arcs.get(arcId);
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
                Platforms.log("[patch-diag]   node=%d ports:%s%n", nodeId, table);
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
        Platforms.log("[patch-diag] invalid cycles by corner count=%s foldBacks=%d%n",
                new TreeMap<>(invalidCycleCountByCornerCount), invalidCycleFoldBackCount);
    }

    /**
     * Emits the two directed arc-end ports of every arc, taking travel directions
     * from the meeting record at interior nodes, the spawn port at origins, and the
     * final walker state at terminals. Each port carries the chart face its
     * direction is expressed in, so vertex-located nodes can fan-order them.
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
     * length. A self-crossing trace visits one node twice with different travel
     * directions, so the length is required to disambiguate the two chain
     * positions.
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
     * Assigns cyclic sort keys per node and orders its ports: chart angle at
     * single-chart nodes, unrolled-fan angle at vertex-located nodes, whose ports
     * live in different fan-face charts and are keyed by their face's accumulated
     * base angle plus the CCW angle inside that wedge.
     */
    private void sortPorts() {
        for (Map.Entry<Integer, List<PatchPort>> entry : portsByNode.entrySet()) {
            EmbeddedNode node = graph.nodes.get(entry.getKey());
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
            EmbeddedNode node = graph.nodes.get(entry.getKey());
            StringBuilder portDump = new StringBuilder();
            for (PatchPort port : ports) {
                portDump.append(String.format(" (arc=%d out=%b dir=%d,%d face=%d key=%.4f)",
                        port.arcId, port.outgoing, port.directionU, port.directionV,
                        port.activeFace, port.sortKey));
            }
            Platforms.log("[patch-diag] ambiguous ports node=%d critical=%b border=%b vertex=%d:%s%n",
                    entry.getKey(), node.critical, node.border, node.vertexId, portDump);
        }
        if (ambiguousNodes > 0) {
            Platforms.log("[patch-diag] nodes with ambiguous port order: %d%n", ambiguousNodes);
        }
    }

    /**
     * Unrolls the face fan around a vertex into a flat angular layout: each face
     * gets a base angle equal to the accumulated wedge angles before it, plus its
     * wedge's opening-edge direction. Everything is measured per face chart, so the
     * order stays consistent at singular cones.
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
        int startActiveFace = mesh.activeFaceIndexOf(mesh.vertexFaceAt(vertexId, 0));
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
            graph.uv.faceCornerUv(faceId, cornerUv);
            int openingCorner = (corner + 1) % HalfEdgeMesh.TRIANGLE_CORNERS;
            int closingCorner = (corner + 2) % HalfEdgeMesh.TRIANGLE_CORNERS;
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
        int localEdge = counterClockwise ? (corner + 2) % HalfEdgeMesh.TRIANGLE_CORNERS : corner;
        int edgeId = mesh.faceEdgeAt(faceId, localEdge);
        int activeEdge = mesh.activeEdgeIndexOf(edgeId);
        if (activeEdge < 0) {
            return -1;
        }
        HalfEdgeMesh.EdgeFaceIds edgeFaces = mesh.edgeFaceIds(activeEdge);
        int neighborFaceId = edgeFaces.faceA == faceId ? edgeFaces.faceB : edgeFaces.faceA;
        if (neighborFaceId < 0) {
            return -1;
        }
        return mesh.activeFaceIndexOf(neighborFaceId);
    }

    private int cornerOfVertexInFace(int faceId, int vertexId) {
        for (int corner = 0; corner < HalfEdgeMesh.TRIANGLE_CORNERS; corner++) {
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
     * Split the cycle at its four corners into sides, then rewind them interior-left:
     * the arrangement walk circles a face interior-right, so each side is reversed
     * and sides 1 and 3 trade places, keeping side 0 the width side.
     */
    private void splitSides(EmbeddedPatch patch, List<Integer> arcCycle, List<Integer> cornerPositions) {
        int cycleLength = arcCycle.size();
        List<List<Integer>> walkedSides = new ArrayList<>(SIDES);
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
            walkedSides.add(side);
        }
        for (int j = 0; j < SIDES; j++) {
            List<Integer> side = walkedSides.get((SIDES - j) % SIDES);
            Collections.reverse(side);
            patch.sideArcIds.get(j).addAll(side);
        }
        measureRectangularity(patch);
    }

    /**
     * Checks LCBK19 Definition 3.1 on a split patch: opposite sides must carry
     * equal parametric length, since the patch is meant to map onto an axis-aligned
     * rectangle.
     *
     * <p>
     * Nothing else verifies this, and the quantization's objective averages the two
     * opposite sides, which is exactly the operation that hides an unequal pair.
     *
     * @param patch patch whose four sides have just been filled
     */
    private void measureRectangularity(EmbeddedPatch patch) {
        double[] sideLength = new double[SIDES];
        for (int side = 0; side < SIDES; side++) {
            for (int arcId : patch.sideArcIds.get(side)) {
                sideLength[side] += graph.arcs.get(arcId).parametricLength;
            }
        }
        for (int side = 0; side < 2; side++) {
            double here = sideLength[side];
            double opposite = sideLength[side + 2];
            double larger = Math.max(here, opposite);
            if (larger <= 0.0) {
                continue;
            }
            double error = Math.abs(here - opposite) / larger;
            worstRectangularityError = Math.max(worstRectangularityError, error);
            if (error > RECTANGULARITY_TOLERANCE) {
                rectangularityViolationCount++;
            }
        }
    }
}
