package ixdar.nodegraph.nodes.modifier;

import java.util.Objects;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.nodegraph.nodes.data.TagGeometryNode;

/**
 * Bridges two boundary edge loops of differing vertex counts: one transition ring fans from the
 * smaller loop to the larger, and the rest are quads.
 *
 * <p>With both loop tags set each loop is found by tag; with only the first, the target is the
 * nearest untagged loop.
 */
@MeshNodeAnnotation(id = "adaptive_bridge_loops", category = NodeCategory.MODIFIERS)
public class AdaptiveBridgeLoopsNode implements MeshNode {
    public static final String ADAPTIVE_BRIDGE_LOOPS_TAG = "adaptive_bridge_loops: tag '";
    public static final String NOT_FOUND = "' not found";
    public static final float ARC_LENGTH_EPSILON = 1e-8f;

    public static final InputPort GEOMETRY = InputPort.manifoldGeometry("geometry");
    public static final InputPort LOOP_A_TAG = new InputPort("loop_a_tag", PortType.STRING, "");
    public static final InputPort LOOP_B_TAG = new InputPort("loop_b_tag", PortType.STRING, "");
    public static final InputPort SEGMENTS = new InputPort("segments", PortType.INT, 1, 1f, 32f);
    public static final InputPort TWIST = new InputPort("twist", PortType.INT, 0, -32f, 32f);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, LOOP_A_TAG, LOOP_B_TAG, SEGMENTS, TWIST);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Bridges two boundary edge loops that may have different vertex counts, using a fan transition with quads and optional intermediate rings. Auto-discovers the nearest untagged loop if only one tag is provided.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input/output. The two tagged loops are bridged with quad faces; unequal vertex counts are handled via a fan transition.",
                LOOP_A_TAG.name, "Tag string identifying the first boundary loop (set upstream by attach_to_surface or tag_geometry).",
                LOOP_B_TAG.name, "Tag string identifying the second boundary loop. If empty, the nearest untagged loop is auto-discovered.",
                SEGMENTS.name, "Number of intermediate interpolation rings between the two loops. 0 = direct bridge; higher = smoother transition.",
                TWIST.name, "Rotation offset (radians) applied to the loop_b side when pairing vertices. Use to un-twist a natural misalignment."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = Objects.requireNonNullElse(ctx.getInput(GEOMETRY.name, GeometryBundle.class), GeometryBundle.empty());
        MeshTopology meshTopo = base.mesh();
        if (meshTopo == null) {
            ctx.setOutput(GEOMETRY.name, base);
            return;
        }

        String tagA = ctx.getInput(LOOP_A_TAG.name, String.class);
        if (tagA == null || tagA.isBlank()) {
            throw new IllegalArgumentException("adaptive_bridge_loops: loop_a_tag is required");
        }
        String tagB = ctx.getInput(LOOP_B_TAG.name, String.class);

        Number segIn = ctx.getInput(SEGMENTS.name, Number.class);
        int segments = Math.max(1, segIn == null ? 1 : segIn.intValue());

        Number twistIn = ctx.getInput(TWIST.name, Number.class);
        int twist = twistIn == null ? 0 : twistIn.intValue();

        HalfEdgeMesh mesh = (HalfEdgeMesh) meshTopo;

        Map<String, boolean[]> tags = TagGeometryNode.getTags(base);
        if (tags == null) {
            throw new IllegalArgumentException("adaptive_bridge_loops: geometry has no tags");
        }

        boolean[] maskA = tags.get(tagA.strip());
        if (maskA == null) {
            throw new IllegalArgumentException(ADAPTIVE_BRIDGE_LOOPS_TAG + tagA + NOT_FOUND);
        }

        List<Integer> loopA;
        List<Integer> loopB;

        if (tagB != null && !tagB.isBlank()) {
            boolean[] maskB = tags.get(tagB.strip());
            if (maskB == null) {
                throw new IllegalArgumentException(ADAPTIVE_BRIDGE_LOOPS_TAG + tagB + NOT_FOUND);
            }
            loopA = findBoundaryLoop(mesh, maskA);
            loopB = findBoundaryLoop(mesh, maskB);
        } else {
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

        if (twist != 0) {
            int n = loopB.size();
            int shift = ((twist % n) + n) % n;
            List<Integer> rotated = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                rotated.add(loopB.get((i + shift) % n));
            }
            loopB = rotated;
        }

        if (loopA.size() == loopB.size()) {
            bridgeEqual(mesh, loopA, loopB, segments);
        } else {
            bridgeAdaptive(mesh, loopA, loopB, segments);
        }

        mesh.computeNormals();
        ctx.setOutput(GEOMETRY.name, base.withMesh(mesh));
    }


    private static void bridgeEqual(HalfEdgeMesh mesh,
            List<Integer> loopA, List<Integer> loopB, int segments) {
        int n = loopA.size();

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


    private static void bridgeAdaptive(HalfEdgeMesh mesh,
            List<Integer> loopA, List<Integer> loopB, int segments) {

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

        if (L > 1 && directedHalfEdgeHasFace(mesh, largeLoop.get(0), largeLoop.get(1))) {
            Collections.reverse(largeLoop);
        }
        if (S > 1 && directedHalfEdgeHasFace(mesh, smallLoop.get(1), smallLoop.get(0))) {
            Collections.reverse(smallLoop);
        }

        int[] groupSize = new int[S];
        int base = L / S;
        int remainder = L % S;
        for (int i = 0; i < S; i++) {
            groupSize[i] = base + (i < remainder ? 1 : 0);
        }

        List<int[]> largeRings = new ArrayList<>();

        if (segments > 1) {
            Vector3f pSmall = new Vector3f();
            Vector3f pLarge = new Vector3f();

            for (int s = 1; s < segments; s++) {
                float t = (float) s / segments;
                int[] ring = new int[L];
                for (int i = 0; i < L; i++) {
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

        int[] firstLargeRing = largeRings.get(0);
        if (aIsSmall) {
            buildFanTransition(mesh, toArray(smallLoop), firstLargeRing, groupSize, S, L);
        } else {
            buildFanTransition(mesh, toArray(smallLoop), firstLargeRing, groupSize, S, L);
        }

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
     * Creates the fan transition faces from the small loop to the large loop, with small vertex
     * {@code i} fanning to {@code groupSize[i]} large vertices. Odd group sizes emit only quads,
     * each wide quad spanning two large-loop edges; an even group size additionally emits one
     * triangle, so the result is not all-quad.
     */
    private static void buildFanTransition(HalfEdgeMesh mesh,
            int[] small, int[] large, int[] groupSize, int S, int L) {

        int largeIdx = 0;
        for (int s = 0; s < S; s++) {
            int nextS = (s + 1) % S;
            int gs = groupSize[s];

            if (gs == 1) {
                int la = large[largeIdx % L];
                int nextGroupFirst = large[(largeIdx + 1) % L];
                mesh.addFace(small[s], la, nextGroupFirst, small[nextS]);
            } else if (gs % 2 == 1) {
                int nWideQuads = (gs - 1) / 2;
                for (int q = 0; q < nWideQuads; q++) {
                    int l0 = large[(largeIdx + q * 2) % L];
                    int l1 = large[(largeIdx + q * 2 + 1) % L];
                    int l2 = large[(largeIdx + q * 2 + 2) % L];
                    mesh.addFace(small[s], l0, l1, l2);
                }
                int lastL = large[(largeIdx + gs - 1) % L];
                int nextGroupFirst = large[(largeIdx + gs) % L];
                mesh.addFace(small[s], lastL, nextGroupFirst, small[nextS]);
            } else {
                int nWideQuads = (gs - 2) / 2;
                for (int q = 0; q < nWideQuads; q++) {
                    int l0 = large[(largeIdx + q * 2) % L];
                    int l1 = large[(largeIdx + q * 2 + 1) % L];
                    int l2 = large[(largeIdx + q * 2 + 2) % L];
                    mesh.addFace(small[s], l0, l1, l2);
                }
                int triStart = nWideQuads * 2;
                int la = large[(largeIdx + triStart) % L];
                int lb = large[(largeIdx + triStart + 1) % L];
                mesh.addFace(small[s], la, lb);
                int lastL = large[(largeIdx + gs - 1) % L];
                int nextGroupFirst = large[(largeIdx + gs) % L];
                mesh.addFace(small[s], lastL, nextGroupFirst, small[nextS]);
            }

            largeIdx += gs;
        }
    }


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
            boolean isSource = false;
            for (int vid : loop) {
                if (sourceVerts.contains(vid)) {
                    isSource = true;
                    break;
                }
            }
            if (isSource) continue;

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
        if (totalLen < ARC_LENGTH_EPSILON) {
            mesh.vertexPosition(loop.get(0), out);
            return;
        }

        float targetLen = t * totalLen;

        for (int i = 0; i < n; i++) {
            if (targetLen <= cumLen[i + 1] || i == n - 1) {
                float edgeLen = cumLen[i + 1] - cumLen[i];
                float localT = edgeLen > ARC_LENGTH_EPSILON ? (targetLen - cumLen[i]) / edgeLen : 0f;
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
