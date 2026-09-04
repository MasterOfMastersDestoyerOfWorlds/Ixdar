package ixdar.geometry.mesh.nodes.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;

/**
 * Face tracing over the embedded arc graph, interior left of walk. A dart is
 * one directed arc traversal; a node's darts are ordered by their departure
 * edges in its half-edge fan, faces are orbits of reverse-then-rotate, and a
 * patch is the one face split at its corners.
 */
public final class NetworkTracer {

    private final ArcNetwork net;
    private final HalfEdgeMesh copy;
    private final Map<Integer, List<Integer>> dartOrderByNode = new HashMap<>();

    private int candidateCount;
    private int[] candidateCycle;
    private int candidateSplit1;
    private int candidateSplit2;
    private int candidateSplit3;

    /**
     * Creates a tracer over an authored network's current arcs. The rotation
     * orders are cached per node, so create a fresh tracer after adding arcs.
     *
     * @param net network being authored
     */
    public NetworkTracer(ArcNetwork net) {
        this.net = net;
        this.copy = net.topology.copy;
    }

    /**
     * Authors one patch: traces every face incident to the first corner, finds the
     * one (face, corner split) matching the corner tuple and any given side
     * counts, adds the patch, and restates the boundary arcs' flanks from the
     * traced walk direction — the loop-arc-correct rule {@code addPatch}'s
     * endpoint test cannot express.
     *
     * @param a          corner node the first side starts at
     * @param b          corner node the second side starts at; corners may repeat
     * @param c          corner node the third side starts at
     * @param d          corner node the fourth side starts at
     * @param firstSide  arc count of the first side, or
     *                   {@link NetworkPatch#UNCONSTRAINED}
     * @param secondSide arc count of the second side, or unconstrained
     * @param thirdSide  arc count of the third side, or unconstrained
     * @param fourthSide arc count of the fourth side, or unconstrained
     * @throws IllegalStateException when no face admits the corners, or when more
     *                               than one split does and side counts must
     *                               disambiguate
     * @return the new patch's id
     */
    public int addPatch(int a, int b, int c, int d, int firstSide, int secondSide,
            int thirdSide, int fourthSide) {
        candidateCount = 0;
        candidateCycle = null;
        for (int[] cycle : uniqueFacesAt(a)) {
            for (int offset = 0; offset < cycle.length; offset++) {
                if (departNode(cycle[offset]) != a) {
                    continue;
                }
                considerSplits(rotate(cycle, offset), a, b, c, d,
                        firstSide, secondSide, thirdSide, fourthSide);
            }
        }
        String corners = a + ", " + b + ", " + c + ", " + d;
        if (candidateCount == 0) {
            throw new IllegalStateException("no traced face admits a patch with corners ("
                    + corners + ") and the given side counts; check the corner nodes");
        }
        if (candidateCount > 1) {
            throw new IllegalStateException("corners (" + corners + ") admit "
                    + candidateCount + " patch splits; add side counts to disambiguate");
        }
        return buildPatch(a);
    }

    /**
     * Adds the chosen candidate as a patch and restates its boundary arcs' flanks
     * from the traced step directions: start-to-end sets the left flank,
     * end-to-start the right. {@code addPatch}'s own flank writes are snapshot and
     * restored first, since its endpoint test cannot tell a loop's ends apart.
     *
     * @param firstCorner corner node the first side starts at
     * @return the new patch's id
     */
    private int buildPatch(int firstCorner) {
        int[] cycle = candidateCycle;
        int[] bounds = { 0, candidateSplit1, candidateSplit2, candidateSplit3, cycle.length };
        List<List<Integer>> sides = new ArrayList<>(EmbeddedPatch.SIDES);
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            List<Integer> sideArcs = new ArrayList<>(bounds[side + 1] - bounds[side]);
            for (int step = bounds[side]; step < bounds[side + 1]; step++) {
                sideArcs.add(arcOf(cycle[step]));
            }
            sides.add(sideArcs);
        }
        Map<Integer, int[]> flankSnapshot = new HashMap<>();
        for (int dart : cycle) {
            EmbeddedArc arc = net.arcs.get(arcOf(dart));
            flankSnapshot.putIfAbsent(arc.arcId,
                    new int[] { arc.leftPatchId, arc.rightPatchId });
        }
        int patchId = net.addPatch(ArcNetwork.NONE, sides, firstCorner);
        for (Map.Entry<Integer, int[]> entry : flankSnapshot.entrySet()) {
            EmbeddedArc arc = net.arcs.get(entry.getKey());
            arc.leftPatchId = entry.getValue()[0];
            arc.rightPatchId = entry.getValue()[1];
        }
        for (int dart : cycle) {
            EmbeddedArc arc = net.arcs.get(arcOf(dart));
            if (isForward(dart)) {
                arc.leftPatchId = patchId;
            } else {
                arc.rightPatchId = patchId;
            }
        }
        return patchId;
    }

    /**
     * Enumerates corner splits of one rotation of a face cycle and records each
     * match as a candidate.
     *
     * @param cycle      face cycle rotated so its first dart departs the first
     *                   corner
     * @param a          first corner node
     * @param b          second corner node
     * @param c          third corner node
     * @param d          fourth corner node
     * @param firstSide  arc count of the first side, or unconstrained
     * @param secondSide arc count of the second side, or unconstrained
     * @param thirdSide  arc count of the third side, or unconstrained
     * @param fourthSide arc count of the fourth side, or unconstrained
     */
    private void considerSplits(int[] cycle, int a, int b, int c, int d, int firstSide,
            int secondSide, int thirdSide, int fourthSide) {
        int length = cycle.length;
        int firstLow = firstSide == NetworkPatch.UNCONSTRAINED ? 0 : firstSide;
        int firstHigh = firstSide == NetworkPatch.UNCONSTRAINED ? length : firstSide;
        for (int split1 = firstLow; split1 <= Math.min(firstHigh, length); split1++) {
            if (nodeAt(cycle, a, split1) != b) {
                continue;
            }
            int secondLow = secondSide == NetworkPatch.UNCONSTRAINED
                    ? split1 : split1 + secondSide;
            int secondHigh = secondSide == NetworkPatch.UNCONSTRAINED
                    ? length : split1 + secondSide;
            for (int split2 = Math.max(split1, secondLow);
                    split2 <= Math.min(secondHigh, length); split2++) {
                if (nodeAt(cycle, a, split2) != c) {
                    continue;
                }
                int thirdLow = thirdSide == NetworkPatch.UNCONSTRAINED
                        ? split2 : split2 + thirdSide;
                int thirdHigh = thirdSide == NetworkPatch.UNCONSTRAINED
                        ? length : split2 + thirdSide;
                for (int split3 = Math.max(split2, thirdLow);
                        split3 <= Math.min(thirdHigh, length); split3++) {
                    if (nodeAt(cycle, a, split3) != d) {
                        continue;
                    }
                    if (fourthSide != NetworkPatch.UNCONSTRAINED
                            && length - split3 != fourthSide) {
                        continue;
                    }
                    candidateCount++;
                    candidateCycle = cycle;
                    candidateSplit1 = split1;
                    candidateSplit2 = split2;
                    candidateSplit3 = split3;
                }
            }
        }
    }

    /**
     * The node the walk stands at after a number of steps along a rotated cycle.
     *
     * @param cycle       rotated face cycle
     * @param firstCorner node the cycle's first dart departs from
     * @param position    step count in {@code [0, cycle.length]}
     * @return the node at that position; the first corner again at the full length
     */
    private int nodeAt(int[] cycle, int firstCorner, int position) {
        return position == cycle.length ? firstCorner : departNode(cycle[position]);
    }

    /**
     * The distinct faces incident to a node, one representative dart cycle each.
     * The same face reached from several of the node's darts is deduplicated by
     * its rotation-normalized dart sequence.
     *
     * @param nodeId node whose incident faces are traced
     * @return the distinct face cycles
     */
    private List<int[]> uniqueFacesAt(int nodeId) {
        List<int[]> faces = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int dart : dartsAt(nodeId)) {
            int[] cycle = traceFace(dart);
            if (seen.add(canonicalKey(cycle))) {
                faces.add(cycle);
            }
        }
        return faces;
    }

    /**
     * Traces the face a dart bounds: traverse the arc to its far end, then depart
     * along the rotational neighbour of the arrival's reverse dart, until the
     * walk returns to the start.
     *
     * @param startDart dart to trace from
     * @throws IllegalStateException when the walk exceeds the dart population, so
     *                               the rotation system is inconsistent
     * @return the face's darts in walk order, starting with {@code startDart}
     */
    private int[] traceFace(int startDart) {
        List<Integer> steps = new ArrayList<>();
        int guard = 2 * net.arcs.size();
        int dart = startDart;
        do {
            steps.add(dart);
            if (steps.size() > guard) {
                throw new IllegalStateException("face trace from arc " + arcOf(startDart)
                        + " did not close after " + guard + " steps");
            }
            dart = rotationalSuccessor(farNode(dart), reverse(dart));
        } while (dart != startDart);
        int[] cycle = new int[steps.size()];
        for (int index = 0; index < cycle.length; index++) {
            cycle[index] = steps.get(index);
        }
        return cycle;
    }

    /**
     * The next departure after arriving at a node: the neighbour of the arrival's
     * reverse dart in the node's rotation, on the side that keeps the traced
     * region left of the walk.
     *
     * @param nodeId node arrived at
     * @param dart   the arrival's reverse dart, departing that node
     * @return the next dart of the face walk
     */
    private int rotationalSuccessor(int nodeId, int dart) {
        List<Integer> order = dartOrderByNode.computeIfAbsent(nodeId, this::orderedDarts);
        int index = order.indexOf(dart);
        if (index < 0) {
            throw new IllegalStateException("arc " + arcOf(dart)
                    + " is not in the rotation of node " + nodeId);
        }
        return order.get((index + order.size() - 1) % order.size());
    }

    /**
     * A node's darts in the rotational order of their departure edges around the
     * node's copy vertex.
     *
     * @param nodeId node to order
     * @throws IllegalStateException when a dart's departure edge is not in the
     *                               vertex fan, or two darts share one
     * @return the darts in fan order
     */
    private List<Integer> orderedDarts(int nodeId) {
        int vertex = net.nodes.get(nodeId).copyVertex;
        List<Integer> fan = fanEdges(vertex);
        Map<Integer, Integer> positionByEdge = new HashMap<>();
        for (int position = 0; position < fan.size(); position++) {
            positionByEdge.put(fan.get(position), position);
        }
        List<Integer> darts = dartsAt(nodeId);
        Map<Integer, Integer> dartByPosition = new HashMap<>();
        for (int dart : darts) {
            Integer position = positionByEdge.get(departureEdge(dart));
            if (position == null) {
                throw new IllegalStateException("arc " + arcOf(dart)
                        + " departs node " + nodeId + " along an edge outside vertex "
                        + vertex + "'s fan");
            }
            Integer occupant = dartByPosition.put(position, dart);
            if (occupant != null) {
                throw new IllegalStateException("arcs " + arcOf(occupant) + " and "
                        + arcOf(dart) + " depart node " + nodeId + " along the same edge");
            }
        }
        darts.sort((left, right) -> Integer.compare(
                positionByEdge.get(departureEdge(left)),
                positionByEdge.get(departureEdge(right))));
        return darts;
    }

    /**
     * The edges around a copy vertex in one rotational direction, read off the
     * half-edge fan. On a border vertex the open fan is returned in order from
     * one side of the boundary gap to the other, so a cyclic read wraps across
     * the gap.
     *
     * @param vertex copy vertex
     * @throws IllegalStateException when the fan walk does not cover every
     *                               incident edge, so the vertex is not manifold
     * @return the incident edges in rotational order
     */
    private List<Integer> fanEdges(int vertex) {
        int spokes = copy.vertexEdgeCount(vertex);
        int start = MeshTopology.NONE;
        int firstOutgoing = MeshTopology.NONE;
        for (int spoke = 0; spoke < spokes; spoke++) {
            int half = copy.edgeHalfEdge(copy.vertexEdgeAt(vertex, spoke));
            if (copy.halfEdgeVertex(half) != vertex) {
                half = copy.halfEdgeTwin(half);
            }
            if (firstOutgoing == MeshTopology.NONE) {
                firstOutgoing = half;
            }
            if (copy.halfEdgeFace(copy.halfEdgeTwin(half)) == MeshTopology.NONE) {
                start = half;
            }
        }
        if (start == MeshTopology.NONE) {
            start = firstOutgoing;
        }
        List<Integer> edges = new ArrayList<>(spokes);
        int half = start;
        for (int step = 0; step < spokes; step++) {
            edges.add(copy.halfEdgeEdge(half));
            if (copy.halfEdgeFace(half) == MeshTopology.NONE) {
                break;
            }
            int next = copy.halfEdgeTwin(copy.halfEdgePrev(half));
            if (next == start) {
                break;
            }
            half = next;
        }
        if (edges.size() != spokes) {
            throw new IllegalStateException("vertex " + vertex + " fan walk covered "
                    + edges.size() + " of " + spokes + " incident edges");
        }
        return edges;
    }

    /**
     * The darts departing a node: one per non-loop arc end, two for a loop.
     *
     * @param nodeId node to read
     * @return the node's darts, unordered
     */
    private List<Integer> dartsAt(int nodeId) {
        List<Integer> darts = new ArrayList<>();
        for (EmbeddedArc arc : net.arcs) {
            if (!arc.alive) {
                continue;
            }
            if (arc.startNodeId == nodeId) {
                darts.add(arc.arcId * 2);
            }
            if (arc.endNodeId == nodeId) {
                darts.add(arc.arcId * 2 + 1);
            }
        }
        return darts;
    }

    /**
     * A rotation-normalized key of a face cycle, identical for every rotation of
     * the same cycle.
     *
     * @param cycle face cycle
     * @return the lexicographically minimal rotation, rendered as text
     */
    private static String canonicalKey(int[] cycle) {
        int best = 0;
        for (int offset = 1; offset < cycle.length; offset++) {
            for (int step = 0; step < cycle.length; step++) {
                int lhs = cycle[(offset + step) % cycle.length];
                int rhs = cycle[(best + step) % cycle.length];
                if (lhs != rhs) {
                    if (lhs < rhs) {
                        best = offset;
                    }
                    break;
                }
            }
        }
        StringBuilder key = new StringBuilder();
        for (int step = 0; step < cycle.length; step++) {
            key.append(cycle[(best + step) % cycle.length]).append(',');
        }
        return key.toString();
    }

    /**
     * One rotation of a face cycle.
     *
     * @param cycle  face cycle
     * @param offset rotation offset
     * @return the cycle starting at the offset
     */
    private static int[] rotate(int[] cycle, int offset) {
        int[] rotated = new int[cycle.length];
        for (int step = 0; step < cycle.length; step++) {
            rotated[step] = cycle[(offset + step) % cycle.length];
        }
        return rotated;
    }

    /**
     * The mesh edge a dart departs along: the arc path's first edge forward, its
     * last edge backward.
     *
     * @param dart dart to read
     * @throws IllegalStateException when the arc is embedded on a single vertex,
     *                               which has no direction to trace
     * @return the departure edge id
     */
    private int departureEdge(int dart) {
        EmbeddedArc arc = net.arcs.get(arcOf(dart));
        List<Integer> edges = arc.path.copyEdgePath;
        if (edges.isEmpty()) {
            throw new IllegalStateException("arc " + arc.arcId
                    + " is embedded on a single vertex and cannot be traced");
        }
        return isForward(dart) ? edges.get(0) : edges.get(edges.size() - 1);
    }

    /**
     * The node a dart departs from.
     *
     * @param dart dart to read
     * @return the departure node id
     */
    private int departNode(int dart) {
        EmbeddedArc arc = net.arcs.get(arcOf(dart));
        return isForward(dart) ? arc.startNodeId : arc.endNodeId;
    }

    /**
     * The node a dart arrives at.
     *
     * @param dart dart to read
     * @return the arrival node id
     */
    private int farNode(int dart) {
        EmbeddedArc arc = net.arcs.get(arcOf(dart));
        return isForward(dart) ? arc.endNodeId : arc.startNodeId;
    }

    private static int arcOf(int dart) {
        return dart >> 1;
    }

    private static boolean isForward(int dart) {
        return (dart & 1) == 0;
    }

    private static int reverse(int dart) {
        return dart ^ 1;
    }
}
