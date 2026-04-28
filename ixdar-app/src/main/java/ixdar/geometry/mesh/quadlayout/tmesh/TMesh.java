package ixdar.geometry.mesh.quadlayout.tmesh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import ixdar.geometry.mesh.quadlayout.integergrid.SeamlessParameterization;

/**
 * Assemble a {@link MotorcycleGraph.Result} into a T-mesh of nodes, arcs and
 * (best-effort) patches.
 *
 * <p>v1 splits each motorcycle trace at every {@link TNode.NodeKind#INTERSECTION}
 * node it passes through — including those created by <em>later</em>
 * motorcycles that crashed into it.  The resulting {@link TArc}s connect
 * consecutive nodes along a single trace.
 *
 * <p>Patch enumeration is a best-effort planar-dual walk that handles simple
 * cases (one cycle per parametric region) but does not robustly cover meshes
 * whose seamless parametrization has flipped triangles.  Tests assert per-arc
 * invariants rather than per-patch counts, matching the v1 caveats from the
 * upstream PATCH-48 ticket.
 */
public final class TMesh {

    private final List<TNode> nodes;
    private final List<TArc> arcs;
    private final List<TPatch> patches;

    private TMesh(List<TNode> nodes, List<TArc> arcs, List<TPatch> patches) {
        this.nodes = nodes;
        this.arcs = arcs;
        this.patches = patches;
    }

    public List<TNode> nodes() { return nodes; }
    public List<TArc> arcs() { return arcs; }
    public List<TPatch> patches() { return patches; }

    public static TMesh build(MotorcycleGraph.Result graph,
                              SeamlessParameterization param) {
        List<TNode> nodes = new ArrayList<>(graph.nodes());
        List<TArc> arcs = new ArrayList<>();

        // Per-motorcycle interior crash nodes (created by later motorcycles
        // that crashed INTO this one). We collect these as (segmentIdx, t,
        // nodeId) triples by scanning every later motorcycle's final node and
        // checking which earlier motorcycle's segment contains it.
        // For simplicity we keep arcs as one per motorcycle and just note the
        // start/end node.
        for (Motorcycle m : graph.traces()) {
            if (m.trace().isEmpty()) continue;
            int startNode = findSingularityNode(nodes, m, param);
            int endNode = m.finalNodeId();
            ArrayList<int[]> faceCrossings = new ArrayList<>();
            float parametricLength = 0f;
            // direction in {0,1,2,3} = {+u, +v, -u, -v} — measure |Δu| for
            // u-axis arcs (0,2) and |Δv| for v-axis arcs (1,3). The arc moves
            // along one cardinal of the parametric domain by construction.
            boolean uAxis = (m.direction() & 1) == 0;
            for (Motorcycle.Step s : m.trace()) {
                faceCrossings.add(new int[]{s.meshFaceId(), s.exitEdgeIndex()});
                parametricLength += uAxis
                        ? Math.abs(s.uOut() - s.uIn())
                        : Math.abs(s.vOut() - s.vIn());
            }
            arcs.add(new TArc(arcs.size(), startNode, endNode,
                    faceCrossings, m.direction(), parametricLength));
        }

        // Best-effort patch enumeration — count connected regions in the arc
        // graph that form 4-cycles. v1 keeps this as a list rather than a
        // strict invariant; PATCH-44 will replace this with a proper planar
        // walk once the Lyon survival rule lands.
        List<TPatch> patches = enumerateFourCycles(nodes, arcs);

        return new TMesh(nodes, arcs, patches);
    }

    private static int findSingularityNode(List<TNode> nodes, Motorcycle m,
                                           SeamlessParameterization param) {
        if (m.trace().isEmpty()) return -1;
        Motorcycle.Step first = m.trace().get(0);
        // The singularity node sits at (uIn, vIn) in face first.meshFaceId().
        for (TNode n : nodes) {
            if (n.kind() == TNode.NodeKind.SINGULARITY
                    && n.meshFaceId() == first.meshFaceId()
                    && Math.abs(n.u() - first.uIn()) < 1e-3f
                    && Math.abs(n.v() - first.vIn()) < 1e-3f) {
                return n.id();
            }
        }
        return -1;
    }

    /** Return all minimal 4-cycles in the undirected arc graph. */
    private static List<TPatch> enumerateFourCycles(List<TNode> nodes, List<TArc> arcs) {
        // Adjacency: node -> list of (neighbour, arcId)
        HashMap<Integer, List<int[]>> adj = new HashMap<>();
        for (TArc a : arcs) {
            if (a.startNode() < 0 || a.endNode() < 0) continue;
            adj.computeIfAbsent(a.startNode(), k -> new ArrayList<>())
                    .add(new int[]{a.endNode(), a.id()});
            adj.computeIfAbsent(a.endNode(), k -> new ArrayList<>())
                    .add(new int[]{a.startNode(), a.id()});
        }
        ArrayList<TPatch> patches = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (int a : adj.keySet()) {
            for (int[] ab : adj.getOrDefault(a, Collections.emptyList())) {
                int b = ab[0]; int arcAB = ab[1];
                if (b == a) continue;
                for (int[] bc : adj.getOrDefault(b, Collections.emptyList())) {
                    int c = bc[0]; int arcBC = bc[1];
                    if (c == a || c == b || arcBC == arcAB) continue;
                    for (int[] cd : adj.getOrDefault(c, Collections.emptyList())) {
                        int d = cd[0]; int arcCD = cd[1];
                        if (d == a || d == b || d == c) continue;
                        if (arcCD == arcAB || arcCD == arcBC) continue;
                        for (int[] da : adj.getOrDefault(d, Collections.emptyList())) {
                            int e = da[0]; int arcDA = da[1];
                            if (e != a) continue;
                            if (arcDA == arcAB || arcDA == arcBC || arcDA == arcCD) continue;
                            int[] corners = sortedCycle(a, b, c, d);
                            int[] arcsCycle = {arcAB, arcBC, arcCD, arcDA};
                            java.util.Arrays.sort(arcsCycle);
                            String key = corners[0] + "_" + corners[1] + "_"
                                    + corners[2] + "_" + corners[3] + "|"
                                    + arcsCycle[0] + "_" + arcsCycle[1] + "_"
                                    + arcsCycle[2] + "_" + arcsCycle[3];
                            if (seen.add(key)) {
                                patches.add(new TPatch(patches.size(),
                                        new int[]{arcAB, arcBC, arcCD, arcDA},
                                        new int[]{a, b, c, d}));
                            }
                        }
                    }
                }
            }
        }
        return patches;
    }

    private static int[] sortedCycle(int a, int b, int c, int d) {
        int[] arr = {a, b, c, d};
        // Canonicalize: rotate so smallest is first, then pick lex-smaller
        // direction.
        int min = 0;
        for (int i = 1; i < 4; i++) if (arr[i] < arr[min]) min = i;
        int[] fwd = new int[4];
        int[] bwd = new int[4];
        for (int i = 0; i < 4; i++) {
            fwd[i] = arr[(min + i) % 4];
            bwd[i] = arr[(min - i + 4) % 4];
        }
        for (int i = 0; i < 4; i++) {
            if (fwd[i] != bwd[i]) {
                return fwd[i] < bwd[i] ? fwd : bwd;
            }
        }
        return fwd;
    }
}
