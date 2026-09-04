package ixdar.geometry.mesh.nodes.patch;

import java.util.Objects;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

/**
 * Geometry-preserving loop cut for bezier Coons cages: edges aligned with the
 * given axis are split by exact de Casteljau subdivision so new vertices land on
 * the original curves; cross-edge handles come from the Coons cross-section
 * formula.
 *
 * <p>Cages without handle slots must use {@code loop_cut}.
 */
@MeshNodeAnnotation(id = "coons_loop_cut")
public class CoonsLoopCutNode implements MeshNode {
    public static final String X = "X";
    public static final float NUM_1e_8 = 1e-8f;
    public static final float NUM_1 = 1f;
    public static final float NUM_0_5 = 0.5f;
    public static final int NUM_3 = 3;
    public static final int NUM_4 = 4;
    public static final float NUM_2 = 2f;
    public static final float NUM_3_2 = 3f;

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort AXIS = new InputPort("axis", PortType.STRING, X);
    public static final InputPort CUTS = new InputPort("cuts", PortType.INT, 1, 1f, 8f);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public String description() {
        return "Inserts geometry-preserving loop cuts along an axis using exact de Casteljau subdivision, so new vertices land on the original Bezier curves. Input MUST carry bezier handle slots (use assign_bezier_handles upstream); for unhandled cages use loop_cut instead.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input/output bundle; MUST carry bezier handle slots from assign_bezier_handles. Unhandled input passes through with a warning.",
                AXIS.name, "Cuts are placed PERPENDICULAR to this axis. Accepted: X, Y, Z.",
                CUTS.name, "Number of new edge loops to insert (1..8)."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, AXIS, CUTS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = Objects.requireNonNullElse(ctx.getInput(GEOMETRY.name, GeometryBundle.class), GeometryBundle.empty());
        if (!CoonsHandleBuilder.hasHandles(base)) {
            System.err.println("[coons_loop_cut] WARNING: input lacks bezier handles; passing through unchanged. Use assign_bezier_handles upstream, or use loop_cut for straight midpoint cuts.");
            ctx.setOutput(GEOMETRY.name, base);
            return;
        }
        String axis = ctx.getInput(AXIS.name, String.class);
        if (axis == null) axis = X;
        Number cutsNum = ctx.getInput(CUTS.name, Number.class);
        int cuts = cutsNum == null ? 1 : Math.max(1, cutsNum.intValue());
        ctx.setOutput(GEOMETRY.name, loopCut(base, axis, cuts));
    }

    /**
     * Curve-preserving loop cut: entry point for callers that already have a
     * {@link GeometryBundle} with bezier handle slots. Used by {@code loop_cut}
     * to dispatch when the input cage carries bezier metadata.
     *
     * @param base bundle carrying mesh and bezier handle slots; passed through
     *        when the mesh is null or has no faces
     * @param axis cut axis label ({@code "X"}, {@code "Y"}, or {@code "Z"});
     *        cuts are placed perpendicular to this axis
     * @param cuts number of new edge loops to insert
     * @return new bundle with split mesh and rebuilt bezier handle slots
     */
    public static GeometryBundle loopCut(GeometryBundle base, String axis, int cuts) {
        MeshTopology mesh = base.mesh();
        if (mesh == null || mesh.faceCount() == 0) {
            return base;
        }
        return doLoopCut(base, axis, cuts);
    }

    private static GeometryBundle doLoopCut(GeometryBundle base, String axis, int cuts) {
        MeshTopology mesh = base.mesh();

        Vector3f axisVec = switch (axis.toUpperCase()) {
            case "Y" -> new Vector3f(0, 1, 0);
            case "Z" -> new Vector3f(0, 0, 1);
            default -> new Vector3f(1, 0, 0);
        };

        float[] hStart = CoonsHandleBuilder.readHandleSlot(base, AssignBezierHandlesNode.SLOT_HANDLES_START, mesh);
        float[] hEnd = CoonsHandleBuilder.readHandleSlot(base, AssignBezierHandlesNode.SLOT_HANDLES_END, mesh);

        // --- Phase 1: classify edges ---
        int maxEid = CoonsHandleBuilder.maxEdgeId(mesh);
        boolean[] edgeSplit = new boolean[maxEid + 1];
        Vector3f tmpA = new Vector3f(), tmpB = new Vector3f(), edgeDir = new Vector3f();
        for (int ei = 0; ei < mesh.edgeCount(); ei++) {
            int eid = mesh.edgeIdAt(ei);
            int he = mesh.edgeHalfEdge(eid);
            mesh.vertexPosition(mesh.halfEdgeVertex(he), tmpA);
            mesh.vertexPosition(mesh.halfEdgeEndVertex(he), tmpB);
            edgeDir.set(tmpB).sub(tmpA);
            float len = edgeDir.length();
            if (len > NUM_1e_8) {
                edgeDir.mul(NUM_1 / len);
                if (Math.abs(edgeDir.dot(axisVec)) > NUM_0_5) {
                    edgeSplit[eid] = true;
                }
            }
        }

        // --- Phase 2: collect original vertices, map IDs ---
        int origCount = mesh.vertexCount();
        float[] origPos = new float[origCount * NUM_3];
        Map<Integer, Integer> oldToNew = new HashMap<>();
        for (int i = 0; i < origCount; i++) {
            int vid = mesh.vertexIdAt(i);
            oldToNew.put(vid, i);
            mesh.vertexPosition(vid, tmpA);
            origPos[i * NUM_3] = tmpA.x;
            origPos[i * NUM_3 + 1] = tmpA.y;
            origPos[i * NUM_3 + 2] = tmpA.z;
        }

        // --- Phase 3: de Casteljau split each split-edge ---
        // splitMids[eid] = int[cuts] of new vertex indices in canonical (ca→cb) order
        Map<Integer, int[]> splitMids = new HashMap<>();
        ArrayList<Float> extraPos = new ArrayList<>();
        // Directed handle map: dirPack(fromNewVid, toNewVid) → float[3]
        Map<Long, float[]> dh = new HashMap<>();

        // Copy handles for unsplit edges
        for (int ei = 0; ei < mesh.edgeCount(); ei++) {
            int eid = mesh.edgeIdAt(ei);
            if (edgeSplit[eid]) continue;
            int he = mesh.edgeHalfEdge(eid);
            int ca = mesh.halfEdgeVertex(he), cb = mesh.halfEdgeEndVertex(he);
            int na = oldToNew.get(ca), nb = oldToNew.get(cb);
            int o = eid * NUM_3;
            dh.put(CoonsHandleBuilder.dirPack(na, nb), new float[]{hStart[o], hStart[o + 1], hStart[o + 2]});
            dh.put(CoonsHandleBuilder.dirPack(nb, na), new float[]{hEnd[o], hEnd[o + 1], hEnd[o + 2]});
        }

        int nextVid = origCount;

        for (int ei = 0; ei < mesh.edgeCount(); ei++) {
            int eid = mesh.edgeIdAt(ei);
            if (!edgeSplit[eid]) continue;

            int he = mesh.edgeHalfEdge(eid);
            int ca = mesh.halfEdgeVertex(he), cb = mesh.halfEdgeEndVertex(he);
            int nCa = oldToNew.get(ca), nCb = oldToNew.get(cb);
            int o = eid * NUM_3;

            // Bezier control points in canonical direction ca→cb
            mesh.vertexPosition(ca, tmpA);
            mesh.vertexPosition(cb, tmpB);
            Vector3f rP0 = new Vector3f(tmpA);
            Vector3f rP1 = new Vector3f(tmpA).add(hStart[o], hStart[o + 1], hStart[o + 2]);
            Vector3f rP2 = new Vector3f(tmpB).add(hEnd[o], hEnd[o + 1], hEnd[o + 2]);
            Vector3f rP3 = new Vector3f(tmpB);

            int[] mids = new int[cuts];
            int prevVid = nCa;

            for (int k = 0; k < cuts; k++) {
                float t = NUM_1 / (cuts + 1 - k);

                // de Casteljau split at t
                CoonsHandleBuilder.SplitResult sr = CoonsHandleBuilder.split(rP0, rP1, rP2, rP3, t);

                int sv = nextVid++;
                mids[k] = sv;
                extraPos.add(sr.splitPoint.x);
                extraPos.add(sr.splitPoint.y);
                extraPos.add(sr.splitPoint.z);

                // Left sub-curve handles: rP0, leftCp1, leftCp2, splitPoint
                dh.put(CoonsHandleBuilder.dirPack(prevVid, sv),
                        new float[]{sr.leftCp1.x - rP0.x, sr.leftCp1.y - rP0.y, sr.leftCp1.z - rP0.z});
                dh.put(CoonsHandleBuilder.dirPack(sv, prevVid),
                        new float[]{sr.leftCp2.x - sr.splitPoint.x, sr.leftCp2.y - sr.splitPoint.y, sr.leftCp2.z - sr.splitPoint.z});

                // Advance: right sub-curve becomes current. rightCp1 = E, rightCp2 = C.
                rP0.set(sr.splitPoint);
                rP1.set(sr.rightCp1);
                rP2.set(sr.rightCp2);
                // rP3 unchanged
                prevVid = sv;
            }

            // Final sub-curve: rP0→rP3 (last split → cb)
            dh.put(CoonsHandleBuilder.dirPack(prevVid, nCb), new float[]{rP1.x - rP0.x, rP1.y - rP0.y, rP1.z - rP0.z});
            dh.put(CoonsHandleBuilder.dirPack(nCb, prevVid), new float[]{rP2.x - rP3.x, rP2.y - rP3.y, rP2.z - rP3.z});

            splitMids.put(eid, mids);
        }

        // --- Phase 4: reconstruct faces, compute cross-edge handles ---
        ArrayList<int[]> outFaces = new ArrayList<>();

        for (int fi = 0; fi < mesh.faceCount(); fi++) {
            int fid = mesh.faceIdAt(fi);
            int fc = mesh.faceVertexCount(fid);
            if (fc != NUM_4) {
                int[] fv = new int[fc];
                for (int k = 0; k < fc; k++)
                    fv[k] = oldToNew.get(mesh.faceVertexAt(fid, k));
                outFaces.add(fv);
                continue;
            }

            int v0 = mesh.faceVertexAt(fid, 0);
            int v1 = mesh.faceVertexAt(fid, 1);
            int v2 = mesh.faceVertexAt(fid, 2);
            int v3 = mesh.faceVertexAt(fid, NUM_3);
            int e0 = mesh.faceEdgeAt(fid, 0); // v0→v1
            int e1 = mesh.faceEdgeAt(fid, 1); // v1→v2
            int e2 = mesh.faceEdgeAt(fid, 2); // v2→v3
            int e3 = mesh.faceEdgeAt(fid, NUM_3); // v3→v0

            boolean s0 = edgeSplit[e0], s1 = edgeSplit[e1];
            boolean s2 = edgeSplit[e2], s3 = edgeSplit[e3];

            int nv0 = oldToNew.get(v0), nv1 = oldToNew.get(v1);
            int nv2 = oldToNew.get(v2), nv3 = oldToNew.get(v3);

            if (s0 && s2 && !s1 && !s3) {
                // Pair A: split edges 0 (v0→v1) and 2 (v2→v3)
                // botMids: v0 → v1; topMids: v3 → v2 (parallel direction)
                int[] botMids = getMidsInDirection(mesh, e0, v0, splitMids);
                int[] topMids = getMidsInDirection(mesh, e2, v3, splitMids);

                for (int k = 0; k <= cuts; k++) {
                    int bl = k == 0 ? nv0 : botMids[k - 1];
                    int br = k == cuts ? nv1 : botMids[k];
                    int tr = k == cuts ? nv2 : topMids[k];
                    int tl = k == 0 ? nv3 : topMids[k - 1];
                    outFaces.add(new int[]{bl, br, tr, tl});
                }

                // Cross-edge handles: botMids[k] → topMids[k]
                computeCrossHandles(mesh, hStart, hEnd, e3, v0, v3, e1, v1, v2,
                        e0, v0, v1, e2, v3, v2,
                        botMids, topMids, cuts, origCount, origPos, extraPos, dh);

            } else if (!s0 && !s2 && s1 && s3) {
                // Pair B: split edges 1 (v1→v2) and 3 (v3→v0)
                // rightMids: v1 → v2; leftMids: v0 → v3 (parallel direction)
                int[] rightMids = getMidsInDirection(mesh, e1, v1, splitMids);
                int[] leftMids = getMidsInDirection(mesh, e3, v0, splitMids);

                for (int k = 0; k <= cuts; k++) {
                    int bl = k == 0 ? nv0 : leftMids[k - 1];
                    int br = k == 0 ? nv1 : rightMids[k - 1];
                    int tr = k == cuts ? nv2 : rightMids[k];
                    int tl = k == cuts ? nv3 : leftMids[k];
                    outFaces.add(new int[]{bl, br, tr, tl});
                }

                // Cross-edge handles: leftMids[k] → rightMids[k]
                computeCrossHandles(mesh, hStart, hEnd, e0, v0, v1, e2, v3, v2,
                        e3, v0, v3, e1, v1, v2,
                        leftMids, rightMids, cuts, origCount, origPos, extraPos, dh);

            } else {
                // No split or unsupported: pass through
                outFaces.add(new int[]{nv0, nv1, nv2, nv3});
            }
        }

        // --- Phase 5: build output mesh ---
        int totalVerts = origCount + extraPos.size() / NUM_3;
        float[] positions = new float[totalVerts * NUM_3];
        System.arraycopy(origPos, 0, positions, 0, origPos.length);
        for (int i = 0; i < extraPos.size(); i++) {
            positions[origPos.length + i] = extraPos.get(i);
        }

        int[] faceIdx = new int[outFaces.size() * NUM_4];
        int w = 0;
        for (int[] q : outFaces) {
            faceIdx[w++] = q[0];
            faceIdx[w++] = q[1];
            faceIdx[w++] = q[2];
            faceIdx[w++] = q[NUM_3];
        }

        HalfEdgeMesh outMesh = HalfEdgeMesh.bulkAllocate(positions, faceIdx, NUM_4);

        // --- Phase 6: build handle arrays from directed map ---
        float[][] handles = CoonsHandleBuilder.flushDirectedHandles(outMesh, dh);

        outMesh.computeNormals();

        HashMap<String, Object> nextSlots = new HashMap<>(base.slots());
        nextSlots.put(AssignBezierHandlesNode.SLOT_HANDLES_START, handles[0]);
        nextSlots.put(AssignBezierHandlesNode.SLOT_HANDLES_END, handles[1]);
        return new GeometryBundle(outMesh, Map.copyOf(nextSlots));
    }

    // ---- helpers ----

    /**
     * Returns split mid vertices for an edge going in the direction starting from
     * {@code fromVid}. If the canonical direction is reversed, the array is flipped.
     *
     * @param mesh source topology used to resolve the edge's canonical direction
     * @param eid edge whose mid-vertex array is being requested
     * @param fromVid endpoint that should appear first in the returned array
     * @param splitMids edge id to canonical-order mid-vertex array
     * @return mid-vertex ids ordered {@code fromVid -> other endpoint}; empty array if {@code eid} wasn't split
     */
    private static int[] getMidsInDirection(MeshTopology mesh, int eid, int fromVid,
            Map<Integer, int[]> splitMids) {
        int[] mids = splitMids.get(eid);
        if (mids == null) return new int[0];
        int he = mesh.edgeHalfEdge(eid);
        int ca = mesh.halfEdgeVertex(he);
        if (fromVid == ca) return mids;
        // Reverse
        int[] rev = new int[mids.length];
        for (int i = 0; i < mids.length; i++)
            rev[i] = mids[mids.length - 1 - i];
        return rev;
    }

    /**
     * Computes cross-edge bezier handles using the linear-blend Coons cross-section
     * formula. For each cut k, the cross-edge connects mids0[k] to mids1[k].
     *
     * @param perpEid0   perpendicular edge 0 (connects split0.start to split1.start corners)
     * @param perpFrom0  start vertex of perp edge 0
     * @param perpTo0    end vertex of perp edge 0
     * @param perpEid1   perpendicular edge 1 (connects split0.end to split1.end corners)
     * @param perpFrom1  start vertex of perp edge 1
     * @param perpTo1    end vertex of perp edge 1
     * @param splitEid0  split edge 0
     * @param splitFrom0 start vertex of split edge 0
     * @param splitTo0   end vertex of split edge 0
     * @param splitEid1  opposite split edge 1
     * @param splitFrom1 start vertex of split edge 1
     * @param splitTo1   end vertex of split edge 1
     * @param mids0      split vertices on split edge 0 (from splitFrom0 toward splitTo0)
     * @param mids1      split vertices on split edge 1 (from splitFrom1 toward splitTo1)
     * @param mesh       input mesh providing canonical edge directions and vertex positions
     * @param hStart     start-handle slot data on the input mesh
     * @param hEnd       end-handle slot data on the input mesh
     * @param cuts       number of cuts (length of {@code mids0} and {@code mids1})
     * @param origCount  count of original vertices retained from the input mesh
     * @param origPos    flat xyz array for the original vertices
     * @param extraPos   flat xyz array for newly-inserted split vertices
     * @param dh         directed-handle map; receives one entry per direction per cut
     */
    private static void computeCrossHandles(
            MeshTopology mesh, float[] hStart, float[] hEnd,
            int perpEid0, int perpFrom0, int perpTo0,
            int perpEid1, int perpFrom1, int perpTo1,
            int splitEid0, int splitFrom0, int splitTo0,
            int splitEid1, int splitFrom1, int splitTo1,
            int[] mids0, int[] mids1,
            int cuts, int origCount, float[] origPos, ArrayList<Float> extraPos,
            Map<Long, float[]> dh) {

        // Get control points for the perpendicular bezier edges
        Vector3f[] perpCp0 = getEdgeCp(mesh, hStart, hEnd, perpEid0, perpFrom0);
        Vector3f[] perpCp1 = getEdgeCp(mesh, hStart, hEnd, perpEid1, perpFrom1);

        // Corner positions (endpoints of split edges)
        Vector3f splitStart0 = getPos(mesh, splitFrom0);
        Vector3f splitEnd0 = getPos(mesh, splitTo0);
        Vector3f splitStart1 = getPos(mesh, splitFrom1);
        Vector3f splitEnd1 = getPos(mesh, splitTo1);

        for (int k = 0; k < cuts; k++) {
            float t = (float) (k + 1) / (cuts + 1);

            Vector3f m0 = getNewPos(mids0[k], origCount, origPos, extraPos);
            Vector3f m1 = getNewPos(mids1[k], origCount, origPos, extraPos);

            // Coons correction vectors
            Vector3f c0 = new Vector3f(m0).sub(new Vector3f(splitStart0).lerp(splitEnd0, t));
            Vector3f c1 = new Vector3f(m1).sub(new Vector3f(splitStart1).lerp(splitEnd1, t));

            // Cross control points:
            // C1 = lerp(perp0.P1, perp1.P1, t) + c0*(2/3) + c1*(1/3)
            // C2 = lerp(perp0.P2, perp1.P2, t) + c0*(1/3) + c1*(2/3)
            Vector3f crossP1 = new Vector3f(perpCp0[1]).lerp(perpCp1[1], t)
                    .add(new Vector3f(c0).mul(NUM_2 / NUM_3_2))
                    .add(new Vector3f(c1).mul(NUM_1 / NUM_3_2));
            Vector3f crossP2 = new Vector3f(perpCp0[2]).lerp(perpCp1[2], t)
                    .add(new Vector3f(c0).mul(NUM_1 / NUM_3_2))
                    .add(new Vector3f(c1).mul(NUM_2 / NUM_3_2));

            // Handle offsets
            float[] hs = {crossP1.x - m0.x, crossP1.y - m0.y, crossP1.z - m0.z};
            float[] he = {crossP2.x - m1.x, crossP2.y - m1.y, crossP2.z - m1.z};
            dh.put(CoonsHandleBuilder.dirPack(mids0[k], mids1[k]), hs);
            dh.put(CoonsHandleBuilder.dirPack(mids1[k], mids0[k]), he);
        }
    }

    /**
     * Returns the 4 control points of the bezier on edge {@code eid} going from
     * {@code fromVid} to the other endpoint.
     *
     * @param mesh    source topology
     * @param hStart  start-handle slot data
     * @param hEnd    end-handle slot data
     * @param eid     edge id
     * @param fromVid endpoint of {@code eid} that should map to {@code P0}
     * @return four-element {@code [P0, P1, P2, P3]} array
     */
    private static Vector3f[] getEdgeCp(MeshTopology mesh, float[] hStart, float[] hEnd,
            int eid, int fromVid) {
        int he = mesh.edgeHalfEdge(eid);
        int ca = mesh.halfEdgeVertex(he), cb = mesh.halfEdgeEndVertex(he);
        int o = eid * NUM_3;
        Vector3f posCa = getPos(mesh, ca), posCb = getPos(mesh, cb);
        Vector3f offS = new Vector3f(hStart[o], hStart[o + 1], hStart[o + 2]);
        Vector3f offE = new Vector3f(hEnd[o], hEnd[o + 1], hEnd[o + 2]);
        if (fromVid == ca) {
            return new Vector3f[]{
                    new Vector3f(posCa),
                    new Vector3f(posCa).add(offS),
                    new Vector3f(posCb).add(offE),
                    new Vector3f(posCb)};
        } else {
            return new Vector3f[]{
                    new Vector3f(posCb),
                    new Vector3f(posCb).add(offE),
                    new Vector3f(posCa).add(offS),
                    new Vector3f(posCa)};
        }
    }

    private static Vector3f getPos(MeshTopology mesh, int vid) {
        return mesh.vertexPosition(vid, new Vector3f());
    }

    private static Vector3f getNewPos(int newVid, int origCount, float[] origPos,
            ArrayList<Float> extraPos) {
        if (newVid < origCount) {
            int o = newVid * NUM_3;
            return new Vector3f(origPos[o], origPos[o + 1], origPos[o + 2]);
        }
        int o = (newVid - origCount) * NUM_3;
        return new Vector3f(extraPos.get(o), extraPos.get(o + 1), extraPos.get(o + 2));
    }

}
