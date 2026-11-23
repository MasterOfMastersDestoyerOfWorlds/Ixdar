package ixdar.geometry.mesh.data.paths.geodesic;

import java.util.Arrays;

import ixdar.geometry.mesh.data.analysis.segmentation.SemanticPatchDecomposer;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.PackedPoints;

/**
 * One region's air flowing to its border under width limits: faces supply their area, an edge
 * lets by its length's worth, halved for each wall end.
 */
public final class WidthLimitedFlow {

    public static final double SHORTFALL_STILL_MEETING_DEMAND_AS_SHARE_OF_SUPPLY = 1e-9;

    /** Debugging: the flows run since this was last zeroed. */
    public static int flowsRun;

    /** Debugging: the shares settled since this was last zeroed. */
    public static int levelsSettled;

    private final int nodes;

    private final int[] head;

    private final int[] next;

    private final int[] target;

    private final double[] capacity;

    private final double[] room;

    private final int[] drainNode;

    private final double[] drainCapacity;

    private final int[] level;

    private double total;

    /**
     * Lay out a region's faces, the edges between them and its drains.
     *
     * @param walker        the surface, triangulated
     * @param regionOfFace  the region of every face, by face id
     * @param region        the region whose air flows
     * @param borderEdge    the edges no air crosses, by edge id
     * @param drainFaces    the face inside the region beside each border edge that drains
     * @param drainLengths  the length of each of those border edges
     * @param wallVertex    the vertices that close half an edge, by vertex id; null for none
     * @param carriedByUnit the air a unit of length lets by
     */
    public WidthLimitedFlow(SurfaceLifetime walker, int[] regionOfFace, int region, boolean[] borderEdge,
            int[] drainFaces, double[] drainLengths, boolean[] wallVertex, double carriedByUnit) {
        MeshTopology mesh = walker.mesh;
        int[] nodeOfFace = new int[mesh.faceIdBound()];
        Arrays.fill(nodeOfFace, -1);
        int count = 0;
        for (int active = 0; active < mesh.faceCount(); active++) {
            if (regionOfFace[mesh.faceIdAt(active)] == region) {
                nodeOfFace[mesh.faceIdAt(active)] = count++;
            }
        }
        nodes = count;
        double[] supply = new double[nodes];
        for (int triangle = 0; triangle < walker.doubleArea.length; triangle++) {
            if (nodeOfFace[walker.faceOfTriangle[triangle]] >= 0) {
                supply[nodeOfFace[walker.faceOfTriangle[triangle]]] += walker.doubleArea[triangle] / 2;
            }
        }
        int arcBound = nodes + drainFaces.length + mesh.edgeCount();
        head = new int[nodes + 2];
        Arrays.fill(head, -1);
        next = new int[2 * arcBound];
        target = new int[2 * arcBound];
        capacity = new double[2 * arcBound];
        room = new double[2 * arcBound];
        level = new int[nodes + 2];
        drainNode = new int[drainFaces.length];
        drainCapacity = new double[drainFaces.length];
        PackedPoints positions = SemanticPatchDecomposer.positionsById(mesh);
        int arcs = 0;
        // Arcs in order: the source to every face, every drain to the sink, then face to face.
        for (int pass = 0; pass < SurfaceLifetime.CORNERS; pass++) {
            int items = pass == 0 ? nodes : pass == 1 ? drainFaces.length : mesh.edgeCount();
            for (int item = 0; item < items; item++) {
                int tail = nodes;
                int tip = item;
                double forward = 0;
                double back = 0;
                if (pass == 0) {
                    forward = supply[item];
                    total += forward;
                } else if (pass == 1) {
                    tail = nodeOfFace[drainFaces[item]];
                    tip = nodes + 1;
                    forward = drainLengths[item] * carriedByUnit;
                    drainNode[item] = tail;
                    drainCapacity[item] = forward;
                } else {
                    int edge = mesh.edgeIdAt(item);
                    int one = mesh.edgeFace(edge, 0);
                    int other = mesh.edgeFace(edge, 1);
                    if (borderEdge[edge] || one < 0 || other < 0 || nodeOfFace[one] < 0 || nodeOfFace[other] < 0) {
                        continue;
                    }
                    int from = mesh.halfEdgeVertex(mesh.edgeHalfEdge(edge));
                    int to = mesh.halfEdgeEndVertex(mesh.edgeHalfEdge(edge));
                    double length = 0;
                    for (int axis = 0; axis < SurfaceLifetime.COORDINATES; axis++) {
                        double span = positions.get(to, axis) - positions.get(from, axis);
                        length += span * span;
                    }
                    int openEnds = (wallVertex != null && wallVertex[from] ? 0 : 1)
                            + (wallVertex != null && wallVertex[to] ? 0 : 1);
                    tail = nodeOfFace[one];
                    tip = nodeOfFace[other];
                    forward = Math.sqrt(length) * carriedByUnit * openEnds / 2;
                    back = forward;
                }
                target[2 * arcs] = tip;
                capacity[2 * arcs] = forward;
                next[2 * arcs] = head[tail];
                head[tail] = 2 * arcs;
                target[2 * arcs + 1] = tail;
                capacity[2 * arcs + 1] = back;
                next[2 * arcs + 1] = head[tip];
                head[tip] = 2 * arcs + 1;
                arcs++;
            }
        }
    }

    /**
     * The most air that reaches the drains when each may take a share of its width's worth.
     * Afterwards a face still reachable from the supply has a level of zero or more.
     *
     * @param fillByDrain the share of its width's worth each drain may take
     * @return the air reaching the drains
     */
    public double flow(double[] fillByDrain) {
        flowsRun++;
        System.arraycopy(capacity, 0, room, 0, room.length);
        for (int drain = 0; drain < drainNode.length; drain++) {
            room[2 * (nodes + drain)] = fillByDrain[drain] * drainCapacity[drain];
        }
        int source = nodes;
        int sink = nodes + 1;
        double flow = 0;
        double none = SurfaceLifetime.ROUNDING_SHARE_OF_A_FLOWS_SUPPLY * total;
        int[] queue = new int[nodes + 2];
        int[] at = new int[nodes + 2];
        int[] pathArc = new int[nodes + 2];
        while (true) {
            Arrays.fill(level, -1);
            level[source] = 0;
            int queued = 0;
            queue[queued++] = source;
            for (int read = 0; read < queued; read++) {
                for (int arc = head[queue[read]]; arc >= 0; arc = next[arc]) {
                    if (room[arc] > none && level[target[arc]] < 0) {
                        level[target[arc]] = level[queue[read]] + 1;
                        queue[queued++] = target[arc];
                    }
                }
            }
            if (level[sink] < 0) {
                return flow;
            }
            System.arraycopy(head, 0, at, 0, head.length);
            int depth = 0;
            int node = source;
            while (node != source || at[source] >= 0) {
                if (node == sink) {
                    double pushed = Double.POSITIVE_INFINITY;
                    for (int step = 0; step < depth; step++) {
                        pushed = Math.min(pushed, room[pathArc[step]]);
                    }
                    for (int step = 0; step < depth; step++) {
                        room[pathArc[step]] -= pushed;
                        room[pathArc[step] ^ 1] += pushed;
                    }
                    flow += pushed;
                    depth = 0;
                    node = source;
                    continue;
                }
                while (at[node] >= 0 && !(room[at[node]] > none && level[target[at[node]]] == level[node] + 1)) {
                    at[node] = next[at[node]];
                }
                if (at[node] >= 0) {
                    pathArc[depth++] = at[node];
                    node = target[at[node]];
                } else if (depth > 0) {
                    level[node] = -1;
                    depth--;
                    node = target[pathArc[depth] ^ 1];
                }
            }
        }
    }

    /**
     * The fairest shares the drains can take: the least share is as large as it can be, then the
     * next least, and so on. The drains behind one bottleneck come out with one share between
     * them, and the drains no bottleneck starves with all of their width's worth.
     *
     * @return the share of its width's worth each drain takes, from 0 to 1
     */
    public double[] fairFill() {
        int drains = drainNode.length;
        double[] fill = new double[drains];
        boolean[] settled = new boolean[drains];
        int left = drains;
        while (left > 0) {
            // Lower the open drains' share until the flow meets it. The cut that stopped the last
            // flow short holds the drains this share is the most for.
            double share = 1;
            boolean[] choked = null;
            while (true) {
                double demand = 0;
                for (int drain = 0; drain < drains; drain++) {
                    fill[drain] = settled[drain] ? fill[drain] : share;
                    demand += fill[drain] * drainCapacity[drain];
                }
                double flowed = flow(fill);
                if (flowed >= demand - SHORTFALL_STILL_MEETING_DEMAND_AS_SHARE_OF_SUPPLY * total) {
                    break;
                }
                boolean[] behind = new boolean[drains];
                double settledDemand = 0;
                double openReached = 0;
                double openChoked = 0;
                for (int drain = 0; drain < drains; drain++) {
                    if (settled[drain]) {
                        settledDemand += fill[drain] * drainCapacity[drain];
                    } else if (level[drainNode[drain]] >= 0) {
                        openReached += drainCapacity[drain];
                    } else {
                        openChoked += drainCapacity[drain];
                        behind[drain] = true;
                    }
                }
                if (openChoked <= 0) {
                    break;
                }
                choked = behind;
                double lower = Math.max(0, (flowed - share * openReached - settledDemand) / openChoked);
                if (lower >= share) {
                    break;
                }
                share = lower;
            }
            levelsSettled++;
            for (int drain = 0; drain < drains; drain++) {
                if (!settled[drain] && (choked == null || choked[drain])) {
                    settled[drain] = true;
                    fill[drain] = share;
                    left--;
                }
            }
        }
        return fill;
    }
}
