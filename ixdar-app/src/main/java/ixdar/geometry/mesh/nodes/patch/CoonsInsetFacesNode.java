package ixdar.geometry.mesh.nodes.patch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.BoolField;
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
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Curve-preserving inset for bezier Coons cages. Unlike {@code inset_faces}
 * (which places the inner vertices by lerping each cage corner toward the
 * face centroid — a flat-cage operation), this node evaluates the Coons
 * surface at inner-corner parameters and places vertices <em>on the surface
 * itself</em>. New edges carry handles derived from sub-curves of the face's
 * iso-curves, so the subsequent {@code coons_patch} reproduces the original
 * smooth surface everywhere except inside the inset, which becomes a proper
 * depression in the surface rather than a collection of bumpy quads (the
 * "cheese grater" artifact).
 *
 * <p>Input geometry <strong>must</strong> carry bezier handle slots produced
 * by {@code assign_bezier_handles}. Input without handles is passed through
 * unchanged with a console warning; use plain {@code inset_faces} for
 * topology-only insets on unhandled meshes.
 *
 * <p>Typical workflow:
 * {@code cube → loop_cut → assign_bezier_handles → coons_inset_faces →
 * coons_patch → merge_by_distance}.
 */
@MeshNodeAnnotation(id = "coons_inset_faces")
public class CoonsInsetFacesNode implements MeshNode {

    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort INSET = new InputPort("inset", PortType.FLOAT, 0.2f, 0f, 0.5f);
    private static final InputPort SELECTION = new InputPort("selection", PortType.BOOLEAN, true);
    private static final OutputPort GEOMETRY_OUT = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);
    private static final OutputPort MESH_OUT = new OutputPort("mesh", PortType.MESH);
    private static final OutputPort GENERATED_OUT = new OutputPort("generated", PortType.BOOLEAN);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, INSET, SELECTION);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH_OUT, GEOMETRY_OUT, GENERATED_OUT);
    }

    @Override
    public String description() {
        return "Curve-preserving inset on a bezier-handled cage: inner corners land on the Coons surface (not on the flat cage), and new edges carry sub-curve handles so coons_patch produces a clean depression without the cheese-grater artifact from plain inset_faces.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                "geometry", "Input/output bundle; MUST carry bezier handle slots from assign_bezier_handles upstream. Unhandled input passes through unchanged with a warning.",
                "inset", "Inset amount t in [0, 0.5]. Inner corners are placed at Coons surface parameters (t, t), (1-t, t), (1-t, 1-t), (t, 1-t) on each selected face. 0 = no-op; 0.5 = inner quad collapses to face center.",
                "selection", "Per-face BOOLEAN mask. Selected quads are inset; others pass through.",
                "mesh", "Topology-only output.",
                "generated", "Per-output-face BOOLEAN: true for the newly-created inner face of each inset. Thread into the next op's selection to chain features (e.g. extrude_mesh for recessed features like eye sockets)."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput("geometry", Object.class));
        MeshTopology in = base.mesh();
        if (in == null || in.vertexCount() == 0) {
            ctx.setOutput("mesh", null);
            ctx.setOutput("geometry", GeometryBundle.empty());
            ctx.setOutput("generated", new BoolField(new boolean[0]));
            return;
        }
        if (!CoonsHandleBuilder.hasHandles(base)) {
            System.err.println("[coons_inset_faces] WARNING: input lacks bezier handles; passing through unchanged. Use assign_bezier_handles upstream, or use plain inset_faces for topology-only insets.");
            ctx.setOutput("mesh", in);
            ctx.setOutput("geometry", base);
            ctx.setOutput("generated", new BoolField(new boolean[in.faceCount()]));
            return;
        }

        Object insObj = FieldBroadcast.getInputOrDefault(ctx, "inset", INSET.defaultValue());
        float t = Math.max(0f, Math.min(0.5f, FieldBroadcast.floatScalarOrDefault(insObj, 0.2f)));
        Object selObj = FieldBroadcast.getInputOrDefault(ctx, "selection", SELECTION.defaultValue());

        float[] hStart = CoonsHandleBuilder.readHandleSlot(base, AssignBezierHandlesNode.SLOT_HANDLES_START, in);
        float[] hEnd = CoonsHandleBuilder.readHandleSlot(base, AssignBezierHandlesNode.SLOT_HANDLES_END, in);

        InsetResult r = doInset(in, hStart, hEnd, t, selObj);

        GeometryBundle outBundle = new GeometryBundle(r.outMesh, copySlotsWithHandles(base.slots(), r.handles));
        ctx.setOutput("mesh", r.outMesh);
        ctx.setOutput("geometry", outBundle);
        ctx.setOutput("generated", new BoolField(r.generated));
    }

    private static Map<String, Object> copySlotsWithHandles(Map<String, Object> src, float[][] handles) {
        HashMap<String, Object> out = new HashMap<>(src);
        out.put(AssignBezierHandlesNode.SLOT_HANDLES_START, handles[0]);
        out.put(AssignBezierHandlesNode.SLOT_HANDLES_END, handles[1]);
        return Map.copyOf(out);
    }

    private record InsetResult(HalfEdgeMesh outMesh, float[][] handles, boolean[] generated) {}

    private static InsetResult doInset(
            MeshTopology in, float[] hStart, float[] hEnd, float t, Object selection) {
        int origFaceCount = in.faceCount();
        int origVertCount = in.vertexCount();

        // Classify selected faces (must be quads — non-quads pass through).
        boolean[] selected = new boolean[origFaceCount];
        int selectedCount = 0;
        for (int fi = 0; fi < origFaceCount; fi++) {
            boolean sel = FieldBroadcast.boolAt(selection, fi, true);
            int fid = in.faceIdAt(fi);
            if (sel && in.faceVertexCount(fid) == 4) {
                selected[fi] = true;
                selectedCount++;
            }
        }

        if (selectedCount == 0 || t <= 0f) {
            HalfEdgeMesh passThrough = copyMesh(in);
            float[][] passHandles = copyHandles(in, passThrough, hStart, hEnd);
            return new InsetResult(passThrough, passHandles, new boolean[passThrough.faceCount()]);
        }

        float[] origPos = new float[origVertCount * 3];
        Vector3f tmp = new Vector3f();
        for (int i = 0; i < origVertCount; i++) {
            int vid = in.vertexIdAt(i);
            in.vertexPosition(vid, tmp);
            origPos[i * 3] = tmp.x;
            origPos[i * 3 + 1] = tmp.y;
            origPos[i * 3 + 2] = tmp.z;
        }
        Map<Integer, Integer> oldToDense = new HashMap<>();
        for (int i = 0; i < origVertCount; i++) {
            oldToDense.put(in.vertexIdAt(i), i);
        }

        // Shared cage edges: both incident faces selected → candidates for merge.
        Set<Integer> sharedEdgeIds = new HashSet<>();
        for (int ei = 0; ei < in.edgeCount(); ei++) {
            int eid = in.edgeIdAt(ei);
            if (in.isBoundaryEdge(eid)) continue;
            int he = in.edgeHalfEdge(eid);
            int twin = in.halfEdgeTwin(he);
            int f1 = in.halfEdgeFace(he);
            int f2 = twin >= 0 ? in.halfEdgeFace(twin) : MeshTopology.NONE;
            if (f1 == MeshTopology.NONE || f2 == MeshTopology.NONE) continue;
            int fi1 = faceIndexOfId(in, f1);
            int fi2 = faceIndexOfId(in, f2);
            if (fi1 >= 0 && fi2 >= 0 && selected[fi1] && selected[fi2]) {
                sharedEdgeIds.add(eid);
            }
        }

        // Map each cage vertex touched by a selected face to its (face, corner) occurrences.
        Map<Integer, List<int[]>> facesAtVertex = new HashMap<>();
        for (int fi = 0; fi < origFaceCount; fi++) {
            if (!selected[fi]) continue;
            int fid = in.faceIdAt(fi);
            for (int k = 0; k < 4; k++) {
                int vid = in.faceVertexAt(fid, k);
                int dense = oldToDense.get(vid);
                facesAtVertex.computeIfAbsent(dense, x -> new ArrayList<>()).add(new int[]{fi, k});
            }
        }

        // Only merge shared edges where BOTH endpoints are 2-face corners.
        // Partial merges (one endpoint 2-face, the other 3+) create manifold
        // violations downstream because the side quad kept at the 3+ end
        // claims an edge along the cage that the neighbor's inner quad also
        // traverses in the same direction.
        Set<Integer> fullyMergeableEdges = new HashSet<>();
        for (int eid : sharedEdgeIds) {
            int he = in.edgeHalfEdge(eid);
            int va = in.halfEdgeVertex(he);
            int vb = in.halfEdgeEndVertex(he);
            Integer denseA = oldToDense.get(va);
            Integer denseB = oldToDense.get(vb);
            if (denseA == null || denseB == null) continue;
            List<int[]> atA = facesAtVertex.get(denseA);
            List<int[]> atB = facesAtVertex.get(denseB);
            if (atA != null && atA.size() == 2 && atB != null && atB.size() == 2) {
                fullyMergeableEdges.add(eid);
            }
        }

        ArrayList<Float> extraPos = new ArrayList<>(selectedCount * 4 * 3);
        int[][] innerVids = new int[origFaceCount][];
        float[][] innerUV = new float[origFaceCount][];  // per face: [u0,v0, u1,v1, u2,v2, u3,v3]
        for (int fi = 0; fi < origFaceCount; fi++) {
            if (selected[fi]) {
                innerVids[fi] = new int[4];
                innerUV[fi] = new float[8];
            }
        }

        // Which (cage edge, endpoint-vid) pairs successfully merged? Used by the
        // side-quad emission pass to drop side quads where BOTH endpoints merged.
        Set<Long> mergedEndpoint = new HashSet<>();

        int nextVid = origVertCount;

        // Allocate inner verts per cage vertex. 2-face corners with a shared
        // cage edge get one merged vert on the edge curve (on the Coons surface
        // by construction); everyone else gets a face-local (t,t)-type vert.
        for (Map.Entry<Integer, List<int[]>> entry : facesAtVertex.entrySet()) {
            int denseVid = entry.getKey();
            List<int[]> atV = entry.getValue();
            int n = atV.size();
            boolean merged = false;

            if (n == 2) {
                int[] a = atV.get(0);
                int[] b = atV.get(1);
                int fiA = a[0], kA = a[1];
                int fiB = b[0], kB = b[1];
                int fidA = in.faceIdAt(fiA);
                int fidB = in.faceIdAt(fiB);
                int eAfwd = in.faceEdgeAt(fidA, kA);
                int eAback = in.faceEdgeAt(fidA, (kA + 3) % 4);
                int eBfwd = in.faceEdgeAt(fidB, kB);
                int eBback = in.faceEdgeAt(fidB, (kB + 3) % 4);

                int sharedEid = -1;
                boolean fwdFromA = false;
                for (int pass = 0; pass < 2 && sharedEid < 0; pass++) {
                    boolean fwd = (pass == 0);
                    int candA = fwd ? eAfwd : eAback;
                    if (!fullyMergeableEdges.contains(candA)) continue;
                    if (candA == eBfwd || candA == eBback) {
                        sharedEid = candA;
                        fwdFromA = fwd;
                    }
                }

                if (sharedEid >= 0) {
                    float[] uvA = edgePointUV(kA, t, fwdFromA);
                    Vector3f pos = evalFaceCoons(in, hStart, hEnd, fidA, uvA[0], uvA[1]);
                    int newVid = nextVid++;
                    extraPos.add(pos.x); extraPos.add(pos.y); extraPos.add(pos.z);
                    innerVids[fiA][kA] = newVid;
                    innerVids[fiB][kB] = newVid;
                    innerUV[fiA][2 * kA] = uvA[0];
                    innerUV[fiA][2 * kA + 1] = uvA[1];
                    boolean fwdFromB = (sharedEid == eBfwd);
                    float[] uvB = edgePointUV(kB, t, fwdFromB);
                    innerUV[fiB][2 * kB] = uvB[0];
                    innerUV[fiB][2 * kB + 1] = uvB[1];
                    mergedEndpoint.add(packEdgeVertex(sharedEid, denseVid));
                    merged = true;
                }
            }

            if (!merged) {
                for (int[] pair : atV) {
                    int fi = pair[0];
                    int k = pair[1];
                    int fid = in.faceIdAt(fi);
                    float[] uv = faceLocalUV(k, t);
                    Vector3f pos = evalFaceCoons(in, hStart, hEnd, fid, uv[0], uv[1]);
                    int newVid = nextVid++;
                    extraPos.add(pos.x); extraPos.add(pos.y); extraPos.add(pos.z);
                    innerVids[fi][k] = newVid;
                    innerUV[fi][2 * k] = uv[0];
                    innerUV[fi][2 * k + 1] = uv[1];
                }
            }
        }

        // Seed original edge handles into the directed-handle map.
        Map<Long, float[]> dh = new HashMap<>();
        for (int ei = 0; ei < in.edgeCount(); ei++) {
            int eid = in.edgeIdAt(ei);
            int he = in.edgeHalfEdge(eid);
            int ca = in.halfEdgeVertex(he);
            int cb = in.halfEdgeEndVertex(he);
            int dca = oldToDense.get(ca);
            int dcb = oldToDense.get(cb);
            int o = eid * 3;
            dh.put(CoonsHandleBuilder.dirPack(dca, dcb),
                    new float[]{hStart[o], hStart[o + 1], hStart[o + 2]});
            dh.put(CoonsHandleBuilder.dirPack(dcb, dca),
                    new float[]{hEnd[o], hEnd[o + 1], hEnd[o + 2]});
        }

        // Per-face handle computation: iso-curve handles for the 4 inner-quad
        // edges, radial handles for the 4 cage-corner → inner-corner edges.
        // Parameterized by innerUV so merged inner verts that sit on shared
        // cage edges get their handles from the actual edge bezier sub-curves.
        for (int fi = 0; fi < origFaceCount; fi++) {
            if (!selected[fi]) continue;
            int fid = in.faceIdAt(fi);
            int fv0 = in.faceVertexAt(fid, 0);
            int fv1 = in.faceVertexAt(fid, 1);
            int fv3 = in.faceVertexAt(fid, 3);
            int fe0 = in.faceEdgeAt(fid, 0);
            int fe1 = in.faceEdgeAt(fid, 1);
            int fe2 = in.faceEdgeAt(fid, 2);
            int fe3 = in.faceEdgeAt(fid, 3);
            int[] dOrig = {
                    oldToDense.get(in.faceVertexAt(fid, 0)),
                    oldToDense.get(in.faceVertexAt(fid, 1)),
                    oldToDense.get(in.faceVertexAt(fid, 2)),
                    oldToDense.get(in.faceVertexAt(fid, 3)),
            };
            int[] ni = innerVids[fi];
            float[] uv = innerUV[fi];

            for (int k = 0; k < 4; k++) {
                int kNext = (k + 1) % 4;
                float uA = uv[2 * k];
                float vA = uv[2 * k + 1];
                float uB = uv[2 * kNext];
                float vB = uv[2 * kNext + 1];
                Vector3f pA = CoonsHandleBuilder.evalCoonsSurface(in, hStart, hEnd, fv0, fv1, fv3,
                        fe0, fe1, fe2, fe3, uA, vA);
                Vector3f pB = CoonsHandleBuilder.evalCoonsSurface(in, hStart, hEnd, fv0, fv1, fv3,
                        fe0, fe1, fe2, fe3, uB, vB);
                addIsoCurveHandles(in, hStart, hEnd, fv0, fv1, fv3, fe0, fe1, fe2, fe3,
                        uA, vA, uB, vB, pA, pB, ni[k], ni[kNext], dh);
            }

            float[] cornerUV = {0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f};
            for (int k = 0; k < 4; k++) {
                addRadialHandles(in, hStart, hEnd, fv0, fv1, fv3, fe0, fe1, fe2, fe3,
                        cornerUV[2 * k], cornerUV[2 * k + 1],
                        uv[2 * k], uv[2 * k + 1],
                        dOrig[k], ni[k], dh);
            }
        }

        // Pass 2: assemble output mesh topology.
        int totalVerts = origVertCount + extraPos.size() / 3;
        float[] positions = new float[totalVerts * 3];
        System.arraycopy(origPos, 0, positions, 0, origPos.length);
        for (int i = 0; i < extraPos.size(); i++) {
            positions[origVertCount * 3 + i] = extraPos.get(i);
        }

        // Determine which cage edges get both endpoints merged — those drop
        // both their side quads (2 per shared edge), and the two inner quads
        // become directly edge-adjacent through the shared endpoint verts.
        Set<Integer> droppedSharedEdges = new HashSet<>();
        for (int eid : sharedEdgeIds) {
            int he = in.edgeHalfEdge(eid);
            int va = in.halfEdgeVertex(he);
            int vb = in.halfEdgeEndVertex(he);
            int denseA = oldToDense.get(va);
            int denseB = oldToDense.get(vb);
            if (mergedEndpoint.contains(packEdgeVertex(eid, denseA))
                    && mergedEndpoint.contains(packEdgeVertex(eid, denseB))) {
                droppedSharedEdges.add(eid);
            }
        }
        int droppedSideQuads = 2 * droppedSharedEdges.size();

        int outFaceCount = 0;
        for (int fi = 0; fi < origFaceCount; fi++) {
            outFaceCount += selected[fi] ? 5 : 1;
        }
        outFaceCount -= droppedSideQuads;

        int[] faceIdx = new int[outFaceCount * 4];
        boolean[] generated = new boolean[outFaceCount];

        int w = 0;
        int faceWriteIdx = 0;
        for (int fi = 0; fi < origFaceCount; fi++) {
            int fid = in.faceIdAt(fi);
            if (!selected[fi]) {
                int fc = in.faceVertexCount(fid);
                if (fc == 4) {
                    for (int k = 0; k < 4; k++) {
                        faceIdx[w++] = oldToDense.get(in.faceVertexAt(fid, k));
                    }
                    generated[faceWriteIdx++] = false;
                } else {
                    for (int k = 0; k < 4; k++) faceIdx[w++] = 0;
                    generated[faceWriteIdx++] = false;
                }
                continue;
            }
            int[] dOrig = {
                    oldToDense.get(in.faceVertexAt(fid, 0)),
                    oldToDense.get(in.faceVertexAt(fid, 1)),
                    oldToDense.get(in.faceVertexAt(fid, 2)),
                    oldToDense.get(in.faceVertexAt(fid, 3)),
            };
            int[] ni = innerVids[fi];

            // Inner face (generated — threaded into follow-up selections)
            faceIdx[w++] = ni[0];
            faceIdx[w++] = ni[1];
            faceIdx[w++] = ni[2];
            faceIdx[w++] = ni[3];
            generated[faceWriteIdx++] = true;

            // Side quads: one per cage edge of this face, skipping those
            // where both endpoint inner corners were merged on a shared edge.
            for (int k = 0; k < 4; k++) {
                int eid = in.faceEdgeAt(fid, k);
                if (droppedSharedEdges.contains(eid)) continue;
                int kNext = (k + 1) % 4;
                faceIdx[w++] = dOrig[k];
                faceIdx[w++] = dOrig[kNext];
                faceIdx[w++] = ni[kNext];
                faceIdx[w++] = ni[k];
                generated[faceWriteIdx++] = false;
            }
        }

        HalfEdgeMesh outMesh = HalfEdgeMesh.bulkAllocate(positions, faceIdx, 4);
        outMesh.computeNormals();

        float[][] handles = CoonsHandleBuilder.flushDirectedHandles(outMesh, dh);
        return new InsetResult(outMesh, handles, generated);
    }

    /** Linear lookup of a face's sequence index by its face id. */
    private static int faceIndexOfId(MeshTopology m, int fid) {
        for (int i = 0; i < m.faceCount(); i++) {
            if (m.faceIdAt(i) == fid) return i;
        }
        return -1;
    }

    /** Pack a (cage edge id, dense vertex id) pair into a long for hashset keys. */
    private static long packEdgeVertex(int eid, int denseVid) {
        return ((long) eid << 32) | (denseVid & 0xFFFFFFFFL);
    }

    /**
     * (u,v) params for the face-local inner-corner position at corner {@code k}
     * with inset amount {@code t}. Places the vert at {@code (t,t)} near corner 0,
     * {@code (1-t, t)} near corner 1, and so on — matches the legacy single-face
     * inset when no merge is happening.
     */
    private static float[] faceLocalUV(int k, float t) {
        switch (k) {
            case 0: return new float[]{t, t};
            case 1: return new float[]{1f - t, t};
            case 2: return new float[]{1f - t, 1f - t};
            case 3: return new float[]{t, 1f - t};
            default: throw new IllegalArgumentException("corner k=" + k);
        }
    }

    /**
     * (u,v) params for the point {@code s=t} along an edge incident to corner
     * {@code k}. The "fwd" edge is {@code faceEdgeAt(fid, k)} (from corner k to
     * k+1); the "back" edge is {@code faceEdgeAt(fid, (k+3)%4)} (from corner
     * k-1 to k). Used when the inner vert at this corner is merged onto the
     * shared edge's bezier curve — the resulting point lies on the Coons
     * surface by construction.
     */
    private static float[] edgePointUV(int k, float t, boolean fwdEdge) {
        if (fwdEdge) {
            switch (k) {
                case 0: return new float[]{t, 0f};
                case 1: return new float[]{1f, t};
                case 2: return new float[]{1f - t, 1f};
                case 3: return new float[]{0f, 1f - t};
                default: throw new IllegalArgumentException("corner k=" + k);
            }
        }
        switch (k) {
            case 0: return new float[]{0f, t};
            case 1: return new float[]{1f - t, 0f};
            case 2: return new float[]{1f, 1f - t};
            case 3: return new float[]{t, 1f};
            default: throw new IllegalArgumentException("corner k=" + k);
        }
    }

    /** Small wrapper around {@link CoonsHandleBuilder#evalCoonsSurface} keyed by face id. */
    private static Vector3f evalFaceCoons(MeshTopology in, float[] hStart, float[] hEnd,
                                          int fid, float u, float v) {
        int v0 = in.faceVertexAt(fid, 0);
        int v1 = in.faceVertexAt(fid, 1);
        int v3 = in.faceVertexAt(fid, 3);
        int e0 = in.faceEdgeAt(fid, 0);
        int e1 = in.faceEdgeAt(fid, 1);
        int e2 = in.faceEdgeAt(fid, 2);
        int e3 = in.faceEdgeAt(fid, 3);
        return CoonsHandleBuilder.evalCoonsSurface(in, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3, u, v);
    }

    /**
     * Writes directed handles for an inner-boundary edge that follows the
     * face's iso-curve between two parameter-space points {@code (u0, v0)} and
     * {@code (u1, v1)}. The handle magnitudes are 1/3 of the segment arc — a
     * standard cubic-bezier approximation — and directions come from tangent
     * samples at the endpoints.
     */
    private static void addIsoCurveHandles(
            MeshTopology mesh, float[] hStart, float[] hEnd,
            int v0, int v1, int v3, int e0, int e1, int e2, int e3,
            float uStart, float vStart, float uEnd, float vEnd,
            Vector3f pStart, Vector3f pEnd,
            int denseStart, int denseEnd,
            Map<Long, float[]> dh) {
        float eps = 1e-3f;
        // Tangent at start: finite-difference toward the end parameter.
        float duStart = (uEnd - uStart) * eps;
        float dvStart = (vEnd - vStart) * eps;
        Vector3f pStartAhead = CoonsHandleBuilder.evalCoonsSurface(
                mesh, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3,
                uStart + duStart, vStart + dvStart);
        Vector3f tanStart = new Vector3f(pStartAhead).sub(pStart);

        // Tangent at end: finite-difference back toward the start parameter.
        Vector3f pEndBehind = CoonsHandleBuilder.evalCoonsSurface(
                mesh, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3,
                uEnd - duStart, vEnd - dvStart);
        Vector3f tanEnd = new Vector3f(pEndBehind).sub(pEnd);

        // Cubic bezier with control points 1/3 and 2/3 of chord length along
        // the end-tangent directions (standard approximation).
        float chord = pEnd.distance(pStart);
        float mag = chord / 3f;

        float tl = tanStart.length();
        if (tl > 1e-8f) tanStart.mul(mag / tl);
        else tanStart.zero();

        float tle = tanEnd.length();
        if (tle > 1e-8f) tanEnd.mul(mag / tle);
        else tanEnd.zero();

        dh.put(CoonsHandleBuilder.dirPack(denseStart, denseEnd),
                new float[]{tanStart.x, tanStart.y, tanStart.z});
        dh.put(CoonsHandleBuilder.dirPack(denseEnd, denseStart),
                new float[]{tanEnd.x, tanEnd.y, tanEnd.z});
    }

    /**
     * Writes directed handles for a radial edge from an outer cage corner at
     * parameter {@code (uStart, vStart)} to an inner corner at
     * {@code (uEnd, vEnd)}. The path traces a diagonal through the Coons
     * surface; we fit a cubic bezier using tangent samples at both endpoints
     * (finite difference along the diagonal direction) with magnitude = chord
     * length / 3, the standard cubic approximation.
     */
    private static void addRadialHandles(
            MeshTopology mesh, float[] hStart, float[] hEnd,
            int v0, int v1, int v3, int e0, int e1, int e2, int e3,
            float uStart, float vStart, float uEnd, float vEnd,
            int denseStart, int denseEnd,
            Map<Long, float[]> dh) {
        float eps = 1e-3f;
        float du = uEnd - uStart;
        float dv = vEnd - vStart;
        float duEps = du * eps;
        float dvEps = dv * eps;

        Vector3f pStart = CoonsHandleBuilder.evalCoonsSurface(
                mesh, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3, uStart, vStart);
        Vector3f pStartAhead = CoonsHandleBuilder.evalCoonsSurface(
                mesh, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3,
                uStart + duEps, vStart + dvEps);
        Vector3f tanStart = new Vector3f(pStartAhead).sub(pStart);

        Vector3f pEnd = CoonsHandleBuilder.evalCoonsSurface(
                mesh, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3, uEnd, vEnd);
        Vector3f pEndBehind = CoonsHandleBuilder.evalCoonsSurface(
                mesh, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3,
                uEnd - duEps, vEnd - dvEps);
        Vector3f tanEnd = new Vector3f(pEndBehind).sub(pEnd);

        float chord = pEnd.distance(pStart);
        float mag = chord / 3f;

        float tl = tanStart.length();
        if (tl > 1e-8f) tanStart.mul(mag / tl);
        else tanStart.zero();

        float tle = tanEnd.length();
        if (tle > 1e-8f) tanEnd.mul(mag / tle);
        else tanEnd.zero();

        dh.put(CoonsHandleBuilder.dirPack(denseStart, denseEnd),
                new float[]{tanStart.x, tanStart.y, tanStart.z});
        dh.put(CoonsHandleBuilder.dirPack(denseEnd, denseStart),
                new float[]{tanEnd.x, tanEnd.y, tanEnd.z});
    }

    /**
     * Straight mesh copy used for no-op / zero-inset path.
     */
    private static HalfEdgeMesh copyMesh(MeshTopology in) {
        int vn = in.vertexCount();
        float[] positions = new float[vn * 3];
        Vector3f tmp = new Vector3f();
        for (int i = 0; i < vn; i++) {
            in.vertexPosition(in.vertexIdAt(i), tmp);
            positions[i * 3] = tmp.x;
            positions[i * 3 + 1] = tmp.y;
            positions[i * 3 + 2] = tmp.z;
        }
        int fn = in.faceCount();
        // Assume uniform quads for cage work; fall back to first face's vpf.
        int vpf = fn == 0 ? 4 : in.faceVertexCount(in.faceIdAt(0));
        int[] faceIdx = new int[fn * vpf];
        int w = 0;
        for (int fi = 0; fi < fn; fi++) {
            int fid = in.faceIdAt(fi);
            int fvc = in.faceVertexCount(fid);
            for (int k = 0; k < vpf; k++) {
                faceIdx[w++] = k < fvc ? denseVid(in, in.faceVertexAt(fid, k)) : 0;
            }
        }
        HalfEdgeMesh out = HalfEdgeMesh.bulkAllocate(positions, faceIdx, vpf);
        out.computeNormals();
        return out;
    }

    private static int denseVid(MeshTopology m, int vid) {
        for (int i = 0; i < m.vertexCount(); i++) {
            if (m.vertexIdAt(i) == vid) return i;
        }
        return 0;
    }

    private static float[][] copyHandles(
            MeshTopology in, MeshTopology out, float[] inHS, float[] inHE) {
        // For the no-op path, topology is preserved; just rebuild arrays keyed
        // on the output mesh's edge IDs from matching (dense-vid pair) keys.
        Map<Long, float[]> dh = new HashMap<>();
        Map<Integer, Integer> oldToDense = new HashMap<>();
        for (int i = 0; i < in.vertexCount(); i++) oldToDense.put(in.vertexIdAt(i), i);
        for (int ei = 0; ei < in.edgeCount(); ei++) {
            int eid = in.edgeIdAt(ei);
            int he = in.edgeHalfEdge(eid);
            int dca = oldToDense.get(in.halfEdgeVertex(he));
            int dcb = oldToDense.get(in.halfEdgeEndVertex(he));
            int o = eid * 3;
            dh.put(CoonsHandleBuilder.dirPack(dca, dcb),
                    new float[]{inHS[o], inHS[o + 1], inHS[o + 2]});
            dh.put(CoonsHandleBuilder.dirPack(dcb, dca),
                    new float[]{inHE[o], inHE[o + 1], inHE[o + 2]});
        }
        return CoonsHandleBuilder.flushDirectedHandles(out, dh);
    }
}
