package ixdar.geometry.mesh.nodes.modifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.nodes.data.TagGeometryNode;

/**
 * Bridges two boundary edge loops, handling mismatched vertex counts.
 * <p>
 * Finds a tagged boundary loop (the source) and the nearest untagged boundary
 * loop (the target), then connects them with quad faces. If the two loops have
 * different vertex counts, the transition ring uses a mix of quads and triangles
 * to fan from the smaller loop to the larger one. Additional intermediate rings
 * (controlled by {@code segments}) are pure-quad interpolations at the larger
 * loop's vertex count.
 * <p>
 * If both {@code loop_a_tag} and {@code loop_b_tag} are provided, both loops
 * are found by tag. If only {@code loop_a_tag} is provided, the target loop is
 * auto-discovered as the nearest boundary loop to the tagged one.
 * <p>
 * <b>Recommended workflow:</b> Use {@code attach_to_surface} to create
 * attachment holes on a mesh using spherical coordinates (theta/phi). That node
 * finds the correct face automatically, insets it, removes the inner face, and
 * tags the resulting boundary loop. Then use this node to bridge a tube's tagged
 * base loop to the nearest attachment hole. This avoids manually specifying face
 * indices, which are fragile and change when upstream topology changes.
 * <pre>{@code
 * # Create hole on palm at spherical direction (theta, phi)
 * thumb_attach = attach_to_surface(geometry=palm.geometry, theta=0.0, phi=1.5708, tag="th_hole")
 * # Position tube at the attachment point
 * th_finger = transform_geometry(geometry=tube.geometry,
 *     translation=thumb_attach.attach_position, rotation=thumb_attach.attach_rotation)
 * th_tagged = tag_geometry(geometry=th_finger.geometry, tags="th_base")
 * # Join and bridge
 * joined = join_geometry(a=thumb_attach.geometry, b=th_tagged.geometry)
 * bridged = adaptive_bridge_loops(geometry=joined.geometry, loop_a_tag="th_base")
 * }</pre>
 */
@MeshNodeAnnotation(id = "adaptive_bridge_loops")
public class AdaptiveBridgeLoopsNode implements MeshNode {

    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort LOOP_A_TAG = new InputPort("loop_a_tag", PortType.STRING, "");
    private static final InputPort LOOP_B_TAG = new InputPort("loop_b_tag", PortType.STRING, "");
    private static final InputPort SEGMENTS = new InputPort("segments", PortType.INT, 1);
    private static final InputPort TWIST = new InputPort("twist", PortType.INT, 0);
    private static final OutputPort GEOMETRY_OUT = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, LOOP_A_TAG, LOOP_B_TAG, SEGMENTS, TWIST);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput("geometry", Object.class));
        MeshTopology meshTopo = base.mesh();
        if (meshTopo == null) {
            ctx.setOutput("geometry", base);
            return;
        }

        String tagA = ctx.getInput("loop_a_tag", String.class);
        if (tagA == null || tagA.isBlank()) {
            throw new IllegalArgumentException("adaptive_bridge_loops: loop_a_tag is required");
        }
        String tagB = ctx.getInput("loop_b_tag", String.class);

        Number segIn = ctx.getInput("segments", Number.class);
        int segments = Math.max(1, segIn == null ? 1 : segIn.intValue());

        Number twistIn = ctx.getInput("twist", Number.class);
        int twist = twistIn == null ? 0 : twistIn.intValue();

        if (!(meshTopo instanceof HalfEdgeMesh)) {
            throw new IllegalArgumentException("adaptive_bridge_loops: requires HalfEdgeMesh input");
        }
        HalfEdgeMesh mesh = (HalfEdgeMesh) meshTopo;

        Map<String, boolean[]> tags = TagGeometryNode.getTags(base);
        if (tags == null) {
            throw new IllegalArgumentException("adaptive_bridge_loops: geometry has no tags");
        }

        // Find loop A from tag
        boolean[] maskA = tags.get(tagA.strip());
        if (maskA == null) {
            throw new IllegalArgumentException("adaptive_bridge_loops: tag '" + tagA + "' not found");
        }

        List<Integer> loopA;
        List<Integer> loopB;

        if (tagB != null && !tagB.isBlank()) {
            // Both tags provided — find both tagged loops
            boolean[] maskB = tags.get(tagB.strip());
            if (maskB == null) {
                throw new IllegalArgumentException("adaptive_bridge_loops: tag '" + tagB + "' not found");
            }
            loopA = findBoundaryLoop(mesh, maskA);
            loopB = findBoundaryLoop(mesh, maskB);
        } else {
            // Only tag A provided — auto-find nearest boundary loop
            List<List<Integer>> allLoops = findAllBoundaryLoops(mesh);
            loopA = findTaggedLoop(allLoops, maskA);
            if (loopA == null) {
                throw new IllegalArgumentException(
                        "adaptive_bridge_loops: no boundary loop found for tag '" + tagA + "'");
            }
            loopB = findNearestLoop(mesh, allLoops, loopA, maskA);
            if (loopB == null) {
                throw new IllegalArgumentException(
                        "adaptive_bridge_loops: no nearby untagged boundary loop found for tag '" + tagA + "'");
            }
        }

        // Apply twist to loop B
        if (twist != 0) {
            int n = loopB.size();
            int shift = ((twist % n) + n) % n;
            List<Integer> rotated = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                rotated.add(loopB.get((i + shift) % n));
            }
            loopB = rotated;
        }

        // Bridge
        if (loopA.size() == loopB.size()) {
            bridgeEqual(mesh, loopA, loopB, segments);
        } else {
            bridgeAdaptive(mesh, loopA, loopB, segments);
        }

        mesh.computeNormals();
        ctx.setOutput("geometry", base.withMesh(mesh));
    }

    // ── Equal-size bridge (same as BridgeEdgeLoopsNode) ───────────────────

    private static void bridgeEqual(HalfEdgeMesh mesh,
            List<Integer> loopA, List<Integer> loopB, int segments) {
        int n = loopA.size();

        // Orient loops: bridge creates forward on loopA, reverse on loopB
        if (n > 1) {
            if (directedHalfEdgeHasFace(mesh, loopA.get(0), loopA.get(1))) {
                Collections.reverse(loopA);
            }
            if (directedHalfEdgeHasFace(mesh, loopB.get(1), loopB.get(0))) {
                Collections.reverse(loopB);
            }
        }

        Vector3f posA = new Vector3f();
        Vector3f posB = new Vector3f();

        List<int[]> rings = new ArrayList<>();
        rings.add(toArray(loopA));

        for (int s = 1; s < segments; s++) {
            float t = (float) s / segments;
            int[] ring = new int[n];
            for (int i = 0; i < n; i++) {
                mesh.vertexPosition(loopA.get(i), posA);
                mesh.vertexPosition(loopB.get(i), posB);
                ring[i] = mesh.addVertex(
                        posA.x + (posB.x - posA.x) * t,
                        posA.y + (posB.y - posA.y) * t,
                        posA.z + (posB.z - posA.z) * t);
            }
            rings.add(ring);
        }
        rings.add(toArray(loopB));

        for (int s = 0; s < rings.size() - 1; s++) {
            int[] r0 = rings.get(s);
            int[] r1 = rings.get(s + 1);
            for (int i = 0; i < n; i++) {
                int next = (i + 1) % n;
                mesh.addFace(r0[i], r0[next], r1[next], r1[i]);
            }
        }
    }

    // ── Adaptive bridge (different sizes) ─────────────────────────────────

    private static void bridgeAdaptive(HalfEdgeMesh mesh,
            List<Integer> loopA, List<Integer> loopB, int segments) {

        // Identify which is smaller and which is larger
        List<Integer> smallLoop, largeLoop;
        boolean aIsSmall;
        if (loopA.size() <= loopB.size()) {
            smallLoop = loopA;
            largeLoop = loopB;
            aIsSmall = true;
        } else {
            smallLoop = loopB;
            largeLoop = loopA;
            aIsSmall = false;
        }

        int S = smallLoop.size();
        int L = largeLoop.size();

        // Orient loops so bridge faces use boundary (free) half-edges.
        // Fan transition creates large-forward edges (large[k]→large[k+1]) and
        // small-backward edges (small[nextS]→small[s]) that must be free.
        // L→L ring pairs (flipped winding) also create large-forward on the last ring.
        if (L > 1 && directedHalfEdgeHasFace(mesh, largeLoop.get(0), largeLoop.get(1))) {
            Collections.reverse(largeLoop);
        }
        if (S > 1 && directedHalfEdgeHasFace(mesh, smallLoop.get(1), smallLoop.get(0))) {
            Collections.reverse(smallLoop);
        }

        // Compute group sizes: distribute L vertices across S groups
        // groupSize[i] = number of large-loop vertices assigned to small-loop vertex i
        int[] groupSize = new int[S];
        int base = L / S;
        int remainder = L % S;
        for (int i = 0; i < S; i++) {
            groupSize[i] = base + (i < remainder ? 1 : 0);
        }

        // Build intermediate rings at the large-loop vertex count
        // Ring layout: smallLoop → [intermediate rings at size L] → largeLoop
        // The fan transition (S→L) happens at the first ring boundary.
        // Subsequent rings are L→L quads.
        List<int[]> largeRings = new ArrayList<>();

        if (segments > 1) {
            // Create intermediate rings of size L, interpolated between small and large
            Vector3f pSmall = new Vector3f();
            Vector3f pLarge = new Vector3f();

            for (int s = 1; s < segments; s++) {
                float t = (float) s / segments;
                int[] ring = new int[L];
                for (int i = 0; i < L; i++) {
                    // Map large vertex i to its position on the small loop via arc-length
                    interpolateOnSmallLoop(mesh, smallLoop, (float) i / L, pSmall);
                    mesh.vertexPosition(largeLoop.get(i), pLarge);
                    ring[i] = mesh.addVertex(
                            pSmall.x + (pLarge.x - pSmall.x) * t,
                            pSmall.y + (pLarge.y - pSmall.y) * t,
                            pSmall.z + (pLarge.z - pSmall.z) * t);
                }
                largeRings.add(ring);
            }
        }
        largeRings.add(toArray(largeLoop));

        // First ring of faces: fan transition from smallLoop (S) to first large ring (L)
        int[] firstLargeRing = largeRings.get(0);
        if (aIsSmall) {
            buildFanTransition(mesh, toArray(smallLoop), firstLargeRing, groupSize, S, L);
        } else {
            // Loop A is larger — the fan goes from small (B) to large (first intermediate or A)
            // But we built largeRings relative to the largeLoop. Need to reverse direction.
            buildFanTransition(mesh, toArray(smallLoop), firstLargeRing, groupSize, S, L);
        }

        // Subsequent rings: L→L quads (flipped winding so r0 uses reverse = twin
        // of the fan's forward, and r1 uses forward = free for the large loop)
        for (int s = 0; s < largeRings.size() - 1; s++) {
            int[] r0 = largeRings.get(s);
            int[] r1 = largeRings.get(s + 1);
            for (int i = 0; i < L; i++) {
                int next = (i + 1) % L;
                mesh.addFace(r0[next], r0[i], r1[i], r1[next]);
            }
        }
    }

    /**
     * Creates the fan transition faces from a small loop (S vertices) to a large
     * loop (L vertices). Each small vertex fans to {@code groupSize[i]} large vertices.
     * <p>
     * For odd group sizes (the common case when L/S divides evenly, e.g. 12/4=3),
     * consecutive triangle pairs are merged into "wide quads" — each spanning two
     * large-loop edges — producing an all-quad output compatible with CC subdivision.
     * <p>
     * For each group of gs large vertices assigned to small vertex s:
     * <ul>
     * <li>gs==1: one quad (small[s], large[0], large[1 of next group], small[nextS])</li>
     * <li>Odd gs≥3: (gs-1)/2 wide quads (small[s], l[2k], l[2k+1], l[2k+2]) +
     *     1 inter-group quad (small[s], l[gs-1], l[gs], small[nextS])</li>
     * <li>Even gs: (gs-2)/2 wide quads + 1 triangle + 1 inter-group quad (fallback)</li>
     * </ul>
     */
    private static void buildFanTransition(HalfEdgeMesh mesh,
            int[] small, int[] large, int[] groupSize, int S, int L) {

        int largeIdx = 0;
        for (int s = 0; s < S; s++) {
            int nextS = (s + 1) % S;
            int gs = groupSize[s];

            if (gs == 1) {
                // Single large vertex per group: one inter-group quad
                int la = large[largeIdx % L];
                int nextGroupFirst = large[(largeIdx + 1) % L];
                mesh.addFace(small[s], la, nextGroupFirst, small[nextS]);
            } else if (gs % 2 == 1) {
                // Odd gs (e.g. 3): pair consecutive triangles into wide quads → all quads
                int nWideQuads = (gs - 1) / 2;
                for (int q = 0; q < nWideQuads; q++) {
                    int l0 = large[(largeIdx + q * 2) % L];
                    int l1 = large[(largeIdx + q * 2 + 1) % L];
                    int l2 = large[(largeIdx + q * 2 + 2) % L];
                    mesh.addFace(small[s], l0, l1, l2);
                }
                // Inter-group quad
                int lastL = large[(largeIdx + gs - 1) % L];
                int nextGroupFirst = large[(largeIdx + gs) % L];
                mesh.addFace(small[s], lastL, nextGroupFirst, small[nextS]);
            } else {
                // Even gs (fallback): wide quads + 1 remaining triangle + inter-group quad
                int nWideQuads = (gs - 2) / 2;
                for (int q = 0; q < nWideQuads; q++) {
                    int l0 = large[(largeIdx + q * 2) % L];
                    int l1 = large[(largeIdx + q * 2 + 1) % L];
                    int l2 = large[(largeIdx + q * 2 + 2) % L];
                    mesh.addFace(small[s], l0, l1, l2);
                }
                // One remaining triangle
                int triStart = nWideQuads * 2;
                int la = large[(largeIdx + triStart) % L];
                int lb = large[(largeIdx + triStart + 1) % L];
                mesh.addFace(small[s], la, lb);
                // Inter-group quad
                int lastL = large[(largeIdx + gs - 1) % L];
                int nextGroupFirst = large[(largeIdx + gs) % L];
                mesh.addFace(small[s], lastL, nextGroupFirst, small[nextS]);
            }

            largeIdx += gs;
        }
    }

    // ── Boundary loop discovery ───────────────────────────────────────────

    /**
     * Find all distinct boundary loops in the mesh. Each loop is an ordered list
     * of vertex IDs connected by boundary edges.
     */
    private static List<List<Integer>> findAllBoundaryLoops(HalfEdgeMesh mesh) {
        Set<Integer> visited = new HashSet<>();
        List<List<Integer>> loops = new ArrayList<>();

        int nv = mesh.vertexCount();
        for (int vi = 0; vi < nv; vi++) {
            int vid = mesh.vertexIdAt(vi);
            if (visited.contains(vid)) continue;
            if (!isBoundaryVertex(mesh, vid)) continue;

            List<Integer> loop = walkBoundaryLoop(mesh, vid);
            for (int v : loop) visited.add(v);
            loops.add(loop);
        }
        return loops;
    }

    private static boolean isBoundaryVertex(HalfEdgeMesh mesh, int vid) {
        int edgeCount = mesh.vertexEdgeCount(vid);
        for (int j = 0; j < edgeCount; j++) {
            int eid = mesh.vertexEdgeAt(vid, j);
            if (mesh.isBoundaryEdge(eid)) return true;
        }
        return false;
    }

    private static List<Integer> walkBoundaryLoop(HalfEdgeMesh mesh, int startVid) {
        List<Integer> loop = new ArrayList<>();
        loop.add(startVid);
        int current = startVid;
        int prev = -1;
        int maxIter = mesh.vertexCount() + 1;
        for (int iter = 0; iter < maxIter; iter++) {
            int next = findNextBoundaryVertex(mesh, current, prev);
            if (next < 0 || next == startVid) break;
            loop.add(next);
            prev = current;
            current = next;
        }
        return loop;
    }

    private static int findNextBoundaryVertex(HalfEdgeMesh mesh, int currentVid, int prevVid) {
        int edgeCount = mesh.vertexEdgeCount(currentVid);
        for (int j = 0; j < edgeCount; j++) {
            int eid = mesh.vertexEdgeAt(currentVid, j);
            if (!mesh.isBoundaryEdge(eid)) continue;

            int he = mesh.edgeHalfEdge(eid);
            if (he == MeshTopology.NONE) continue;
            int startV = mesh.halfEdgeVertex(he);
            int endV = mesh.halfEdgeEndVertex(he);
            int otherVid = (startV == currentVid) ? endV : startV;

            if (otherVid != prevVid && otherVid >= 0) {
                return otherVid;
            }
        }
        return -1;
    }

    /**
     * Find a boundary loop from the given tag mask. Walks boundary edges
     * starting from any tagged boundary vertex.
     */
    private static List<Integer> findBoundaryLoop(HalfEdgeMesh mesh, boolean[] tagMask) {
        int nv = mesh.vertexCount();
        for (int vi = 0; vi < nv; vi++) {
            int vid = mesh.vertexIdAt(vi);
            if (vid >= tagMask.length || !tagMask[vid]) continue;
            if (!isBoundaryVertex(mesh, vid)) continue;
            return walkBoundaryLoop(mesh, vid);
        }
        throw new IllegalArgumentException("adaptive_bridge_loops: no boundary vertices found in tag group");
    }

    /**
     * From a list of all boundary loops, find the one containing tagged vertices.
     */
    private static List<Integer> findTaggedLoop(List<List<Integer>> allLoops, boolean[] tagMask) {
        for (List<Integer> loop : allLoops) {
            for (int vid : loop) {
                if (vid < tagMask.length && tagMask[vid]) return loop;
            }
        }
        return null;
    }

    /**
     * Find the nearest boundary loop to the source loop that does NOT contain
     * vertices tagged by the source mask.
     */
    private static List<Integer> findNearestLoop(HalfEdgeMesh mesh,
            List<List<Integer>> allLoops, List<Integer> sourceLoop, boolean[] sourceMask) {
        Set<Integer> sourceVerts = new HashSet<>(sourceLoop);
        Vector3f sourceCenter = loopCentroid(mesh, sourceLoop);
        Vector3f candidateCenter = new Vector3f();

        List<Integer> best = null;
        float bestDist = Float.MAX_VALUE;

        for (List<Integer> loop : allLoops) {
            // Skip the source loop itself
            boolean isSource = false;
            for (int vid : loop) {
                if (sourceVerts.contains(vid)) {
                    isSource = true;
                    break;
                }
            }
            if (isSource) continue;

            // Skip loops that contain tagged vertices (they belong to other fingers)
            boolean hasTagged = false;
            for (int vid : loop) {
                if (vid < sourceMask.length && sourceMask[vid]) {
                    hasTagged = true;
                    break;
                }
            }
            if (hasTagged) continue;

            loopCentroid(mesh, loop, candidateCenter);
            float dist = sourceCenter.distance(candidateCenter);
            if (dist < bestDist) {
                bestDist = dist;
                best = loop;
            }
        }
        return best;
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    /**
     * Check whether the directed half-edge vid0→vid1 already has a face.
     * Returns false if the edge between vid0 and vid1 doesn't exist yet.
     */
    private static boolean directedHalfEdgeHasFace(HalfEdgeMesh mesh, int vid0, int vid1) {
        int edgeCount = mesh.vertexEdgeCount(vid0);
        for (int j = 0; j < edgeCount; j++) {
            int eid = mesh.vertexEdgeAt(vid0, j);
            int he = mesh.edgeHalfEdge(eid);
            int twin = mesh.halfEdgeTwin(he);
            int heStart = mesh.halfEdgeVertex(he);

            // Identify which half-edge goes from vid0 and what the far vertex is
            int heFromVid0 = (heStart == vid0) ? he : twin;
            int farVid = mesh.halfEdgeVertex((heStart == vid0) ? twin : he);
            if (farVid != vid1) continue;

            return mesh.halfEdgeFace(heFromVid0) != MeshTopology.NONE;
        }
        return false;
    }

    private static Vector3f loopCentroid(HalfEdgeMesh mesh, List<Integer> loop) {
        Vector3f center = new Vector3f();
        loopCentroid(mesh, loop, center);
        return center;
    }

    private static void loopCentroid(HalfEdgeMesh mesh, List<Integer> loop, Vector3f out) {
        out.set(0, 0, 0);
        Vector3f pos = new Vector3f();
        for (int vid : loop) {
            mesh.vertexPosition(vid, pos);
            out.add(pos);
        }
        out.div(loop.size());
    }

    /**
     * Interpolate a position on the small loop at normalized parameter t ∈ [0, 1).
     * Uses arc-length parameterization so vertices are evenly distributed.
     */
    private static void interpolateOnSmallLoop(HalfEdgeMesh mesh,
            List<Integer> loop, float t, Vector3f out) {
        int n = loop.size();
        if (n == 0) {
            out.set(0, 0, 0);
            return;
        }

        // Compute cumulative arc lengths
        float[] cumLen = new float[n + 1];
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        cumLen[0] = 0;
        for (int i = 0; i < n; i++) {
            mesh.vertexPosition(loop.get(i), a);
            mesh.vertexPosition(loop.get((i + 1) % n), b);
            cumLen[i + 1] = cumLen[i] + a.distance(b);
        }
        float totalLen = cumLen[n];
        if (totalLen < 1e-8f) {
            mesh.vertexPosition(loop.get(0), out);
            return;
        }

        float targetLen = t * totalLen;

        // Find the edge segment containing targetLen
        for (int i = 0; i < n; i++) {
            if (targetLen <= cumLen[i + 1] || i == n - 1) {
                float edgeLen = cumLen[i + 1] - cumLen[i];
                float localT = edgeLen > 1e-8f ? (targetLen - cumLen[i]) / edgeLen : 0f;
                mesh.vertexPosition(loop.get(i), a);
                mesh.vertexPosition(loop.get((i + 1) % n), b);
                out.set(a).lerp(b, localT);
                return;
            }
        }
        mesh.vertexPosition(loop.get(0), out);
    }

    private static int[] toArray(List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
        return arr;
    }
}
