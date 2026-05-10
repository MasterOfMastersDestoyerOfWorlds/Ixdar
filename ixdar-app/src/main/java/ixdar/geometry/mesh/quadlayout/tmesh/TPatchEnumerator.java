package ixdar.geometry.mesh.quadlayout.tmesh;

import ixdar.geometry.mesh.data.representation.ArrayMesh;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import java.util.HashSet;

import java.util.Map;

/**
 * PATCH-68: enumerate {@link TPatch}es from a planar T-mesh by walking
 * faces of the half-arc graph.
 *
 * <p><b>Algorithm.</b> Standard planar-DCEL face traversal:
 * <ol>
 *   <li>Build half-arcs: each {@link TArc} contributes a forward half
 *       (start → end) and a reverse half (end → start). Total {@code 2 *
 *       arcs.size()} half-arcs.</li>
 *   <li>For every {@link TNode} sort its outgoing half-arcs by parametric
 *       angle (signed 2D angle of the half-arc's first-step direction).
 *       This is the natural CCW order in surface tangent space because
 *       the seamless parametrization preserves orientation.</li>
 *   <li>{@code nextHalf(h)}: at {@code h}'s end node, pick the inverse of
 *       {@code h} (the half-arc going back along the same arc), then
 *       advance to the NEXT outgoing half-arc in CCW order. This is the
 *       "right-turn" rule that walks the face on the left of {@code h}.</li>
 *   <li>For each unvisited half-arc start a face walk via {@code nextHalf};
 *       collect cyclic half-arc sequence; mark all visited.</li>
 *   <li>Each face whose vertex count is &ge; 4 is a candidate TPatch.
 *       Sides are identified by ~90° turns at corners (cardinal change
 *       between consecutive half-arcs' parametric directions).</li>
 * </ol>
 *
 * <p><b>Perf.</b> Sort cost = {@code O(N · d log d)} where N = node count,
 * d = avg arc-degree (typically 4). Face walk = {@code O(H)} where
 * {@code H = 2 * arcCount}. Total {@code O(arcCount · log degree)} —
 * fast for our 332-arc Hand-30k case.
 */
public final class TPatchEnumerator {
    public static final String TRUE = "true";
    public static final String STR_3F = "%.3f";
    public static final String STR = ",";
    public static final int NUM_4 = 4;
    public static final int NUM_3 = 3;
    public static final int NUM_50 = 50;
    public static final int NUM_16 = 16;
    public static final int NUM_64 = 64;
    /** Upper bound on a face's half-arc count for the diagnostic histogram. */
    public static final int SIDE_HISTOGRAM_SIZE = 256;

    /** Debug counters; printed if {@code -Dixdar.quadlayout.tmesh.debug=true} is set. */
    public static int statHalfArcs;
    public static int statHalfArcsLinkable;
    public static int statFacesWalked;
    public static int statFacesShortCycle;
    public static int statFacesNonQuad;
    public static int statFacesEmittedAsPatches;
    public static final int[] statSideHistogram = new int[SIDE_HISTOGRAM_SIZE];

    /** PATCH-70 dump: first N non-quad face cycles' cardinal sequences. */
    public static List<int[]> nonQuadCardinals = new ArrayList<>();

    /** PATCH-91 D1: face-cycle length distribution (# half-arcs per cycle). */
    public static int[] statFaceLengthHist = new int[64];
    /**
     * PATCH-91 D2: arc-to-emitted-patch incidence histogram (in how many.
     */
    public static int[] statArcIncidenceHist = new int[8];
    /**
     * PATCH-91 F2: face-length cap. Cycles longer than this are DCEL
     *  outer-face walks around mesh holes / handles, NOT real layout patches.
     */
    public static int statFacesOuterBoundary;
    /**
     * PATCH-89 diagnostic: of the dropped long cycles (n>faceLengthCap),
     *  how many had ≥1 BOUNDARY-kind corner node (bolt-hole / outer-mesh-edge
     *  cycle) vs how many had only INTERSECTION corners (handle / planar
     *  artifact). Decides whether boundary tracing (PATCH-89) is the right
     */
    public static int statLongCyclesWithBoundaryCorner;
    public static int statLongCyclesAllIntersection;
    /** PATCH-89: lengths of dropped long cycles for inspection. */
    public static List<int[]> longCycleLengths = new ArrayList<>();

    /**
     * PATCH-92 angular-sort audit: per-node histogram of distinct mesh-face
     *  frames among incident half-arcs. Index = (#distinct frames - 1), so
     *  bucket 0 = nodes with all incident arcs in one face frame (sort safe);
     *  bucket 1+ = nodes spanning multiple frames (sort potentially wrong
     */
    public static int[] statNodeFrameCountHist = new int[8];
    /** PATCH-92: total nodes with multi-frame incidents (sum of bucket 1+). */
    public static int statMultiFrameNodes;
    /** PATCH-92: number of nodes that actually got fan-based sort (vs angle). */
    public static int statFanSortedNodes;

    /**
     * PATCH-91 H2 diagnostic: of the dropped triangle face cycles, how many
     *  have at least 1 SINGULARITY corner (real 3-valent wedge), vs how many.
     */
    public static int statTrianglesWithSingularityCorner;
    public static int statTrianglesAllIntersection;
    /**
     * PATCH-91 H2: dump first N triangle details for inspection.
     *  Each entry: {cardinal_sequence, corner_node_ids, corner_kinds (0=sing, 1=intersection),
     */
    public static List<String> triangleDumps = new ArrayList<>();

    /** Half-arc direction relative to its underlying TArc. */
    private static final int FORWARD = 0;
    private static final int REVERSE = 1;
    private static final int DUMP_NON_QUAD_LIMIT = 32;
    private static final int TRIANGLE_DUMP_LIMIT = 5;

    private TPatchEnumerator() {}

    /**
     * PATCH-92 fan-sort entry point. {@code mesh} and {@code singVertexToNode}
     *  enable mesh-fan-based sorting at multi-frame nodes (singularities)
     *  where outgoing half-arcs come from different mesh-face frames and
     *  raw parametric-angle sorting is not comparable across frames. Pass
     *  {@code null} for both to fall back to raw-angle sorting (legacy
     *  behavior, used by tests and pure-T-mesh fixtures).
     *
     * @param nodes            T-mesh nodes (singularity / intersection / boundary)
     * @param arcs             T-mesh arcs to walk into half-arcs
     * @param mesh             underlying triangle mesh used for fan-based sort at multi-frame nodes
     * @param singVertexToNode map from singularity mesh-vertex id to T-node id
     * @return enumerated patches; cycles longer than the cap or with too few corners are dropped
     */
    public static List<TPatch> enumerate(List<TNode> nodes, List<TArc> arcs,
                                         ArrayMesh mesh,
                                         Map<Integer, Integer> singVertexToNode) {
        return enumerateImpl(nodes, arcs, mesh, singVertexToNode);
    }

    /**
     * Mesh-agnostic overload: sorts incident half-arcs by raw parametric angle only
     * (no fan-based sort at multi-frame nodes).
     *
     * @param nodes T-mesh nodes
     * @param arcs  T-mesh arcs
     * @return enumerated patches from the simple planar-dual walk
     */
    public static List<TPatch> enumerate(List<TNode> nodes, List<TArc> arcs) {
        return enumerateImpl(nodes, arcs, null, null);
    }

    private static List<TPatch> enumerateImpl(List<TNode> nodes, List<TArc> arcs,
                                              ArrayMesh mesh,
                                              Map<Integer, Integer> singVertexToNode) {
        statHalfArcs = 0;
        statHalfArcsLinkable = 0;
        statFacesWalked = 0;
        statFacesShortCycle = 0;
        statFacesNonQuad = 0;
        statFacesEmittedAsPatches = 0;
        statFacesOuterBoundary = 0;
        statTrianglesWithSingularityCorner = 0;
        statTrianglesAllIntersection = 0;
        statLongCyclesWithBoundaryCorner = 0;
        statLongCyclesAllIntersection = 0;
        statMultiFrameNodes = 0;
        statFanSortedNodes = 0;
        Arrays.fill(statNodeFrameCountHist, 0);
        triangleDumps = new ArrayList<>();
        longCycleLengths = new ArrayList<>();
        Arrays.fill(statSideHistogram, 0);
        Arrays.fill(statFaceLengthHist, 0);
        Arrays.fill(statArcIncidenceHist, 0);
        nonQuadCardinals = new ArrayList<>();
        if (arcs.isEmpty()) return new ArrayList<>();
        int arcCount = arcs.size();
        int halfCount = 2 * arcCount;
        statHalfArcs = halfCount;

        // halfArcStartNode[h], halfArcEndNode[h], halfArcStartDirU/V[h]
        int[] startNodeOf = new int[halfCount];
        int[] endNodeOf = new int[halfCount];
        double[] startAngle = new double[halfCount];

        for (int aid = 0; aid < arcCount; aid++) {
            TArc a = arcs.get(aid);
            int hF = aid * 2 + FORWARD;
            int hR = aid * 2 + REVERSE;
            startNodeOf[hF] = a.startNode();
            endNodeOf[hF] = a.endNode();
            startNodeOf[hR] = a.endNode();
            endNodeOf[hR] = a.startNode();
            startAngle[hF] = forwardStartAngle(a);
            startAngle[hR] = reverseStartAngle(a);
        }

        // node id -> sorted list of outgoing half-arcs (CCW by startAngle).
        HashMap<Integer, int[]> outgoingByNode = new HashMap<>();
        HashMap<Integer, List<int[]>> tmp = new HashMap<>();
        for (int h = 0; h < halfCount; h++) {
            int n = startNodeOf[h];
            if (n < 0) continue;
            tmp.computeIfAbsent(n, k -> new ArrayList<>())
                    .add(new int[]{h, Float.floatToIntBits((float) startAngle[h])});
        }
        // PATCH-92: build inverse map (nodeId → mesh vertexId) so we can look
        // up the mesh vertex for multi-frame nodes (singularities) and use
        // mesh-fan-based sorting instead of incompatible cross-frame angles.
        HashMap<Integer, Integer> nodeIdToVertex = new HashMap<>();
        if (singVertexToNode != null) {
            for (var entry : singVertexToNode.entrySet()) {
                nodeIdToVertex.put(entry.getValue(), entry.getKey());
            }
        }

        for (var e : tmp.entrySet()) {
            int nodeId = e.getKey();
            List<int[]> list = e.getValue();

            // PATCH-92: count distinct mesh-face frames at this node.
            HashSet<Integer> framesAtNode = new HashSet<>();
            for (int[] entry : list) {
                int h = entry[0];
                int aid = h / 2;
                TArc a = arcs.get(aid);
                if (a.stepUvs() == null || a.stepUvs().isEmpty()) continue;
                int faceForFrame;
                if ((h & 1) == FORWARD) {
                    faceForFrame = arc_first_face(a);
                } else {
                    faceForFrame = arc_last_face(a);
                }
                if (faceForFrame >= 0) framesAtNode.add(faceForFrame);
            }
            int distinct = framesAtNode.size();
            int bucket = Math.min(Math.max(distinct - 1, 0),
                    statNodeFrameCountHist.length - 1);
            statNodeFrameCountHist[bucket]++;
            if (distinct > 1) statMultiFrameNodes++;

            // PATCH-92: choose sort. Multi-frame nodes (always singularities
            // in our impl) MAY use mesh-fan ordering. Empirically (rocker-
            // arm at α=15°) fan-sort doesn't reduce the giant DCEL cycle —
            // the actual root cause is upstream (abort-no-exit-edge fires
            // 65% of traces). Fan-sort is opt-in via system property; off
            // by default to preserve baseline behavior. See PATCH-92.
            boolean enableFanSort = TRUE.equals(
                    System.getProperty("ixdar.lyon.fanSort"));
            Integer vertexId = nodeIdToVertex.get(nodeId);
            if (enableFanSort && distinct > 1 && mesh != null && vertexId != null) {
                sortByMeshFan(list, arcs, startAngle, mesh, vertexId);
                statFanSortedNodes++;
            } else {
                list.sort((a, b) -> Double.compare(
                        startAngle[a[0]], startAngle[b[0]]));
            }

            int[] arr = new int[list.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = list.get(i)[0];
            outgoingByNode.put(nodeId, arr);
        }

        // For each half-arc, find its "next" half-arc via the right-turn rule.
        int[] nextHalf = new int[halfCount];
        Arrays.fill(nextHalf, -1);
        for (int h = 0; h < halfCount; h++) {
            int end = endNodeOf[h];
            if (end < 0) continue;
            int[] outs = outgoingByNode.get(end);
            if (outs == null) continue;
            // Inverse of h: same arc, opposite direction. From end node,
            // the "incoming-equivalent outgoing" is the inverse half-arc.
            int inverseHalf = h ^ 1;     // toggle FORWARD/REVERSE bit
            // Find inverseHalf in outs[].
            int idx = -1;
            for (int i = 0; i < outs.length; i++) {
                if (outs[i] == inverseHalf) { idx = i; break; }
            }
            if (idx < 0) continue;
            // Next outgoing in CCW order = outs[(idx + 1) mod n].
            nextHalf[h] = outs[(idx + 1) % outs.length];
            statHalfArcsLinkable++;
        }

        // Walk faces.
        boolean[] visited = new boolean[halfCount];
        ArrayList<int[]> faces = new ArrayList<>();
        for (int start = 0; start < halfCount; start++) {
            if (visited[start]) continue;
            if (nextHalf[start] < 0) { visited[start] = true; continue; }
            ArrayList<Integer> cycle = new ArrayList<>();
            int cur = start;
            int safety = halfCount + 1;
            while (!visited[cur] && safety-- > 0) {
                visited[cur] = true;
                cycle.add(cur);
                int n = nextHalf[cur];
                if (n < 0) break;
                cur = n;
            }
            if (cur != start) continue;
            statFacesWalked++;
            if (cycle.size() < NUM_4) {
                statFacesShortCycle++;
                continue;
            }
            int len = cycle.size();
            int bucket = Math.min(len, statFaceLengthHist.length - 1);
            statFaceLengthHist[bucket]++;
            int[] arr = new int[len];
            for (int i = 0; i < arr.length; i++) arr[i] = cycle.get(i);
            faces.add(arr);
        }

        // Convert each face cycle into a TPatch. 4-corner faces become quads;
        // 3-corner faces become triangle patches (arcsBySide.length == 3),
        // emitted alongside quads in the same list. Downstream code that
        // requires 4-sidedness (Quantization, QuadLayoutExtractor) checks
        // arcsBySide.length and routes triangles separately.
        ArrayList<TPatch> patches = new ArrayList<>();
        for (int[] face : faces) {
            int n = face.length;
            // PATCH-91 H2 fix: compute TWO cardinals per half-arc:
            //   - dirAtStart[i] = cardinal of half-arc i AT ITS startNode
            //                     (in startNode's face frame)
            //   - dirAtEnd[i]   = cardinal of half-arc i AT ITS endNode
            //                     (in endNode's face frame, after TRS rotations)
            // For corner detection between consecutive half-arcs h_(i-1) and h_i
            // (sharing node N = h_(i-1).endNode = h_i.startNode), compare:
            //   dirAtEnd[i-1] (incoming at N) vs dirAtStart[i] (outgoing at N).
            // Both are in N's local face frame ⇒ comparison is meaningful.
            //
            // Old code used arc.direction() (launch-frame cardinal) at BOTH
            // ends, producing incorrect cardinal changes whenever an arc had
            // crossed a seam. That caused 151 spurious "interior triangles"
            // on rocker-arm — face cycles whose cardinals didn't match
            // actual UV displacements at endpoints.
            int[] dirAtStart = new int[n];
            int[] dirAtEnd = new int[n];
            for (int i = 0; i < n; i++) {
                int h = face[i];
                int aid = h / 2;
                TArc arc = arcs.get(aid);
                if ((h & 1) == FORWARD) {
                    dirAtStart[i] = arc.directionAtStart();
                    dirAtEnd[i] = arc.directionAtEnd();
                } else {
                    // Reverse half-arc: heading OUT of arc.endNode and INTO
                    // arc.startNode, with cardinals flipped 180°.
                    dirAtStart[i] = (arc.directionAtEnd() + 2) & NUM_3;
                    dirAtEnd[i] = (arc.directionAtStart() + 2) & NUM_3;
                }
            }
            // dirOf for backwards-compat with non-quad-cardinal dump.
            int[] dirOf = dirAtEnd;
            // Identify corners: index i is a corner iff h_(i-1)'s incoming-at-N
            // cardinal differs from h_i's outgoing-at-N cardinal.
            ArrayList<Integer> cornerIdx = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int prev = (i - 1 + n) % n;
                if (dirAtEnd[prev] != dirAtStart[i]) cornerIdx.add(i);
            }
            int cIdxSize = cornerIdx.size();
            if (cIdxSize < statSideHistogram.length) statSideHistogram[cIdxSize]++;
            // PATCH-91 F2: filter DCEL outer-face walks (handle/boundary
            // cycles around mesh holes). These have very long face cycle
            // length — typically > 50 half-arcs on rocker-arm. They are
            // NOT real layout patches and must be excluded.
            int faceLengthCap = parseIntProp("ixdar.lyon.faceLengthCap", NUM_50);
            if (n > faceLengthCap) {
                statFacesOuterBoundary++;
                // PATCH-89 diagnostic: classify the dropped long cycle by
                // whether ≥1 of its corners is a BOUNDARY-kind node. If yes,
                // it's a bolt-hole / outer-mesh-edge DCEL cycle — boundary
                // tracing (PATCH-89) would seal it off. If no, it's a
                // handle or planar artifact (different root cause).
                boolean hasBoundaryCorner = false;
                int boundaryCornerCount = 0;
                int singCornerCount = 0;
                int intersectionCornerCount = 0;
                for (int ci : cornerIdx) {
                    int nodeId = startNodeOf[face[ci]];
                    if (nodeId < 0 || nodeId >= nodes.size()) continue;
                    TNode tn = nodes.get(nodeId);
                    switch (tn.kind()) {
                        case BOUNDARY -> { hasBoundaryCorner = true; boundaryCornerCount++; }
                        case SINGULARITY -> singCornerCount++;
                        case INTERSECTION -> intersectionCornerCount++;
                    }
                }
                if (hasBoundaryCorner) statLongCyclesWithBoundaryCorner++;
                else statLongCyclesAllIntersection++;
                if (longCycleLengths.size() < NUM_16) {
                    longCycleLengths.add(new int[]{n, cIdxSize,
                            boundaryCornerCount, singCornerCount, intersectionCornerCount});
                }
                continue;
            }
            // PATCH-86: drop 3-corner cells unless explicitly enabled. Lyon
            // paper §3 specifies T-mesh patches as rectangular. Triangle
            // wedges around 3-valent singularities aren't part of Lyon's
            // formulation. Set -Dixdar.lyon.allowTriangles=true to enable.
            boolean allowTriangles = TRUE.equals(
                    System.getProperty("ixdar.lyon.allowTriangles"));
            // PATCH-91 F3: emit 5+ corner cells as multi-side patches so
            // their arcs are no longer orphan. Strip equivalence skips
            // non-4-side patches but their arcs participate in arcIncidence
            // and can chain via shared boundaries with adjacent quads.
            int corCap = parseIntProp("ixdar.lyon.cornerCap", NUM_64);
            if (cIdxSize < NUM_3 || cIdxSize > corCap) {
                statFacesNonQuad++;
                if (nonQuadCardinals.size() < DUMP_NON_QUAD_LIMIT) {
                    nonQuadCardinals.add(dirOf.clone());
                }
                continue;
            }
            if (cIdxSize == NUM_3 && !allowTriangles) {
                // H2 classification: does this triangle touch a singularity corner?
                boolean hasSingCorner = false;
                int[] cornerNodeIds = new int[cIdxSize];
                String[] cornerKinds = new String[cIdxSize];
                float[][] cornerUv = new float[cIdxSize][NUM_3];
                for (int s = 0; s < cIdxSize; s++) {
                    int from = cornerIdx.get(s);
                    int nodeId = startNodeOf[face[from]];
                    cornerNodeIds[s] = nodeId;
                    if (nodeId >= 0 && nodeId < nodes.size()) {
                        TNode tn = nodes.get(nodeId);
                        cornerKinds[s] = tn.kind().toString().substring(0, Math.min(NUM_4, tn.kind().toString().length()));
                        cornerUv[s][0] = tn.meshFaceId();
                        cornerUv[s][1] = tn.u();
                        cornerUv[s][2] = tn.v();
                        if (tn.kind() == TNode.NodeKind.SINGULARITY) hasSingCorner = true;
                    }
                }
                if (hasSingCorner) statTrianglesWithSingularityCorner++;
                else {
                    statTrianglesAllIntersection++;
                    if (triangleDumps.size() < TRIANGLE_DUMP_LIMIT) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("triangle: nHalfArcs=").append(n)
                                .append(" cardinals=").append(Arrays.toString(dirOf))
                                .append("\n    corners: ");
                        for (int s = 0; s < cIdxSize; s++) {
                            sb.append("[node=").append(cornerNodeIds[s])
                                    .append(" kind=").append(cornerKinds[s])
                                    .append(" face=").append((int) cornerUv[s][0])
                                    .append(" uv=(").append(String.format(STR_3F, cornerUv[s][1]))
                                    .append(STR).append(String.format(STR_3F, cornerUv[s][2]))
                                    .append(")] ");
                        }
                        sb.append("\n    arcs:");
                        for (int s = 0; s < cIdxSize; s++) {
                            int from = cornerIdx.get(s);
                            int to = cornerIdx.get((s + 1) % cIdxSize);
                            int len = (to - from + n) % n;
                            if (len == 0) len = n;
                            sb.append(" side").append(s).append("=[");
                            for (int k = 0; k < len; k++) {
                                int h = face[(from + k) % n];
                                sb.append(h / 2).append((h & 1) == 0 ? "F" : "R").append(STR);
                            }
                            sb.append("]");
                        }
                        triangleDumps.add(sb.toString());
                    }
                }
                statFacesNonQuad++;
                continue;
            }
            // Build arcsBySide[cIdxSize]: arcs walked from corner i to (i+1)%cIdxSize.
            int sides = cIdxSize;
            int[][] arcsBySide = new int[sides][];
            int[] cornerNodes = new int[sides];
            for (int s = 0; s < sides; s++) {
                int from = cornerIdx.get(s);
                int to = cornerIdx.get((s + 1) % sides);
                int len = (to - from + n) % n;
                if (len == 0) len = n;
                int[] sideArcs = new int[len];
                for (int k = 0; k < len; k++) {
                    int h = face[(from + k) % n];
                    sideArcs[k] = h / 2;     // arc id
                }
                arcsBySide[s] = sideArcs;
                cornerNodes[s] = startNodeOf[face[from]];
            }
            patches.add(TPatch.multi(patches.size(), arcsBySide, cornerNodes));
            statFacesEmittedAsPatches++;
        }
        // D2: compute arc-incidence histogram across emitted patches.
        int[] perArcCount = new int[arcCount];
        for (TPatch p : patches) {
            int[][] sides = p.arcsBySide();
            if (sides == null) continue;
            for (int s = 0; s < sides.length; s++) {
                if (sides[s] == null) continue;
                for (int aId : sides[s]) {
                    if (aId >= 0 && aId < arcCount) perArcCount[aId]++;
                }
            }
        }
        for (int a = 0; a < arcCount; a++) {
            int c = Math.min(perArcCount[a], statArcIncidenceHist.length - 1);
            statArcIncidenceHist[c]++;
        }
        return patches;
    }

    /**
     * PATCH-92: sort incident half-arcs at a multi-frame node by the
     *  geometric CCW order of their launch faces around the mesh vertex,
     *  with raw parametric angle as the within-face tiebreaker.
     *
     *  <p>Background: at a singularity vertex, multiple motorcycles launch
     *  from different incident mesh faces (PATCH-60). Each arc's first-step
     *  parametric angle is in its OWN launch-face frame. Across faces, the
     *  frames differ by the seamless parametrization's TRS rotations. Raw
     *  numerical sort across these incompatible angles can produce a wrong
     *  CCW order, derailing the planar-dual face walk and producing giant
     *  artifact cycles.
     *
     *  <p>Mesh fan order — the CCW sequence of incident faces around the
     *  vertex (from the half-edge structure's vertex one-ring) — IS a
     *  geometrically-valid CCW reference regardless of UV frame.
     *  Sorting by {@code (fan_position, within_face_angle)} matches the
     *  planar walk's expectation of CCW-around-the-node ordering.
     *
     * @param halfArcs   list of {@code [halfArcId, angleBits]} pairs sorted in place
     * @param arcs       arc table indexed by {@code halfArcId / 2}
     * @param startAngle per-half-arc parametric start angles (used as within-face tiebreaker)
     * @param mesh       half-edge mesh whose vertex one-ring defines the fan order
     * @param vertexId   mesh-vertex id of the singularity at this T-node
     */
    private static void sortByMeshFan(List<int[]> halfArcs, List<TArc> arcs,
                                      double[] startAngle,
                                      ArrayMesh mesh,
                                      int vertexId) {
        // Build face → fan_position map by walking the vertex's outgoing
        // half-edge cycle (mesh half-edge structure is consistently CCW
        // around vertices for closed-orientable surfaces).
        HashMap<Integer, Integer> faceFanPos = new HashMap<>();
        int outCount = mesh.vertexOutgoingHalfEdgeCount(vertexId);
        // PATCH-92: empirical — vertexOutgoingHalfEdgeAt walks one way; the
        // planar-dual face walk (next-CCW-after-inverse) expects the OTHER
        // way at multi-frame nodes. System property lets us A/B test.
        boolean reverseFan = TRUE.equals(
                System.getProperty("ixdar.lyon.reverseFanSort"));
        for (int k = 0; k < outCount; k++) {
            int he = mesh.vertexOutgoingHalfEdgeAt(vertexId, k);
            int face = mesh.halfEdgeFace(he);
            int pos = reverseFan ? (outCount - 1 - k) : k;
            if (face >= 0) faceFanPos.putIfAbsent(face, pos);
        }
        halfArcs.sort((aRow, bRow) -> {
            int aH = aRow[0];
            int bH = bRow[0];
            int aArcId = aH / 2;
            int bArcId = bH / 2;
            TArc aArc = arcs.get(aArcId);
            TArc bArc = arcs.get(bArcId);
            int aFace = ((aH & 1) == FORWARD)
                    ? arc_first_face(aArc) : arc_last_face(aArc);
            int bFace = ((bH & 1) == FORWARD)
                    ? arc_first_face(bArc) : arc_last_face(bArc);
            Integer aPos = faceFanPos.get(aFace);
            Integer bPos = faceFanPos.get(bFace);
            int aFan = aPos == null ? Integer.MAX_VALUE : aPos;
            int bFan = bPos == null ? Integer.MAX_VALUE : bPos;
            if (aFan != bFan) return Integer.compare(aFan, bFan);
            return Double.compare(startAngle[aH], startAngle[bH]);
        });
    }

    /**
     * PATCH-92: mesh face the arc's first step traverses (= face frame at
     *  arc.startNode). Used to detect multi-frame angular-sort hazards.
     *
     * @param arc T-mesh arc whose first mesh-face crossing is read
     * @return mesh-face id of the first step, or {@code -1} if the arc has no crossings
     */
    private static int arc_first_face(TArc arc) {
        if (arc.meshFaceCrossings() == null || arc.meshFaceCrossings().isEmpty())
            return -1;
        return arc.meshFaceCrossings().get(0)[0];
    }

    /**
     * PATCH-92: mesh face the arc's last step traverses (= face frame at
     *  arc.endNode).
     *
     * @param arc T-mesh arc whose last mesh-face crossing is read
     * @return mesh-face id of the last step, or {@code -1} if the arc has no crossings
     */
    private static int arc_last_face(TArc arc) {
        if (arc.meshFaceCrossings() == null || arc.meshFaceCrossings().isEmpty())
            return -1;
        return arc.meshFaceCrossings().get(
                arc.meshFaceCrossings().size() - 1)[0];
    }

    /**
     * Parametric angle of an arc's outgoing direction at its start.
     *
     * @param arc arc whose first uv-step direction is measured
     * @return signed angle in radians; falls back to the arc's cardinal direction if it has no steps
     */
    private static double forwardStartAngle(TArc arc) {
        if (arc.stepUvs().isEmpty()) return cardinalAngle(arc.direction());
        float[] s = arc.stepUvs().get(0);
        return Math.atan2(s[NUM_3] - s[1], s[2] - s[0]);
    }

    /**
     * Parametric angle of the arc's REVERSE outgoing direction (from its end).
     *
     * @param arc arc whose last uv-step's reversed direction is measured
     * @return signed angle in radians; falls back to the arc's cardinal direction (rotated 180°) if it has no steps
     */
    private static double reverseStartAngle(TArc arc) {
        if (arc.stepUvs().isEmpty()) return cardinalAngle((arc.direction() + 2) % NUM_4);
        int last = arc.stepUvs().size() - 1;
        float[] s = arc.stepUvs().get(last);
        // Reverse: from (uOut, vOut) towards (uIn, vIn), i.e. direction = -(out-in).
        return Math.atan2(s[1] - s[NUM_3], s[0] - s[2]);
    }

    private static double cardinalAngle(int d) {
        return switch (d) {
            case 0 -> 0;
            case 1 -> Math.PI / 2;
            case 2 -> Math.PI;
            default -> -Math.PI / 2;
        };
    }

    private static int parseIntProp(String key, int defaultVal) {
        String s = System.getProperty(key);
        return s == null ? defaultVal : Integer.parseInt(s);
    }
}
