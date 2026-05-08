package ixdar.geometry.mesh.quadlayout.extraction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * Stage 4 of QEx (Ebke 2013): assemble {@link QFace}s by walking 4-cycles
 * in the port graph.
 *
 * <p>Algorithm (mirroring metriko's {@code generate_q_faces}):
 * <ol>
 *   <li>For each unvisited "half" of each QEdge, treat it as a starting
 *       quad-half-edge.</li>
 *   <li>Walk 4 hops via {@code edge.portB → port.prev → connected-edge}:
 *       at each hop, jump to the OTHER end of the current edge, then to
 *       its previous port in the QVert's prev/next ring, then follow that
 *       port's connected-edge to its OTHER end.</li>
 *   <li>If after 4 hops we've returned to the starting port and haven't
 *       reused any edge, we have a valid quad face.</li>
 *   <li>Mark all 4 traversed half-edges as visited so the same face isn't
 *       re-emitted from another start.</li>
 * </ol>
 */
public final class QuadFaceGenerator {
    public static final int NUM_4 = 4;
    public static final long NUM_1000003 = 1000003L;
    public static final long NUM_0xFFFFFFFF = 0xFFFFFFFFL;

    private QuadFaceGenerator() {}

    /**
     * Walk the port graph and return one {@link QFace} per closed 4-cycle.
     * Cycles whose canonical (sorted) corner-QVert tuple has already been
     * emitted are dropped, which removes the CCW/CW duplicate pair the walk
     * naturally produces on open surfaces.
     *
     * @param ports all ports from Stage 2
     * @param edges all edges from Stage 3
     * @return list of distinct quad faces in emission order
     */
    public static List<QFace> generate(List<QPort> ports, List<QEdge> edges) {
        ArrayList<QFace> faces = new ArrayList<>();
        boolean[] visited = new boolean[edges.size() * 2];
        // Dedupe cycles by canonical (sorted) cornerQVerts. The 4-cycle walk
        // visits both CCW and CW orientations of the same quad on an open
        // surface (= 2 emitted cycles per geometric quad). On a closed
        // manifold, adjacent quads share at most 2 corners so canonical
        // tuples collide only for the same geometric quad — making the
        // dedupe safe in both regimes.
        HashSet<Long> seenCorners = new HashSet<>();

        for (int eId = 0; eId < edges.size(); eId++) {
            for (int side = 0; side < 2; side++) {
                if (visited[eId * 2 + side]) continue;
                int[] cycle = tryWalkFourCycle(ports, edges, visited, eId, side);
                if (cycle == null) continue;
                int[] cornerQVerts = new int[NUM_4];
                int[] edgeIds = new int[NUM_4];
                for (int i = 0; i < NUM_4; i++) {
                    int eHalfId = cycle[i];
                    edgeIds[i] = eHalfId / 2;
                    int sideI = eHalfId % 2;
                    QEdge eRef = edges.get(edgeIds[i]);
                    int startPort = (sideI == 0) ? eRef.portA() : eRef.portB();
                    cornerQVerts[i] = ports.get(startPort).qVertId;
                }
                long key = canonicalKey(cornerQVerts);
                if (!seenCorners.add(key)) continue;
                faces.add(new QFace(faces.size(), cornerQVerts, edgeIds));
            }
        }
        return faces;
    }

    /**
     * 64-bit canonical hash of 4 corner ids (sorted). Quads that differ
     */
    private static long canonicalKey(int[] corners) {
        int[] sorted = corners.clone();
        Arrays.sort(sorted);
        long k = 0;
        for (int c : sorted) k = k * NUM_1000003 + (c & NUM_0xFFFFFFFF);
        return k;
    }

    /**
     * Try to walk a 4-cycle starting at edge {@code startEdge} from {@code startSide}.
     * Returns the 4 half-edge ids ({@code edgeId * 2 + side}) on success, null on failure.
     * Marks all 4 halves visited iff the cycle is valid.
     */
    private static int[] tryWalkFourCycle(List<QPort> ports, List<QEdge> edges,
                                          boolean[] visited, int startEdge, int startSide) {
        int[] cycle = new int[NUM_4];
        HashSet<Integer> seenEdges = new HashSet<>();
        int curEdge = startEdge;
        int curSide = startSide;
        for (int hop = 0; hop < NUM_4; hop++) {
            if (!seenEdges.add(curEdge)) return null;
            cycle[hop] = curEdge * 2 + curSide;

            QEdge e = edges.get(curEdge);
            // The "exit" port of this half-edge is the one we're walking AWAY
            // from — i.e., the OTHER end. (Side 0 means we entered at portA
            // and exit at portB.)
            int exitPort = (curSide == 0) ? e.portB() : e.portA();
            QPort exit = ports.get(exitPort);

            // Turn: take the previous port in the QVert's CCW ring (a 90°
            // turn under the +u/+v/-u/-v port ordering).
            int turnPortId = exit.prevPort;
            if (turnPortId < 0) return null;
            QPort turn = ports.get(turnPortId);
            if (!turn.connected) return null;

            int nextEdgeId = turn.connectedEdgeId;
            QEdge nextEdge = edges.get(nextEdgeId);
            int nextSide = (nextEdge.portA() == turnPortId) ? 0 : 1;
            curEdge = nextEdgeId;
            curSide = nextSide;
        }
        // Check we closed the loop.
        if (curEdge != startEdge || curSide != startSide) return null;
        // Commit visits.
        for (int eHalfId : cycle) visited[eHalfId] = true;
        return cycle;
    }
}
