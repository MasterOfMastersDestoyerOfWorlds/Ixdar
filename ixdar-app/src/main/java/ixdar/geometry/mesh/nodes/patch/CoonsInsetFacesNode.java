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
import ixdar.geometry.mesh.data.HalfEdgeMeshEngine;
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

        ArrayList<Float> extraPos = new ArrayList<>(selectedCount * 4 * 3);
        int[][] innerVids = new int[origFaceCount][];
        float[][] innerUV = new float[origFaceCount][];  // per face: [u0,v0, u1,v1, u2,v2, u3,v3]
        for (int fi = 0; fi < origFaceCount; fi++) {
            if (selected[fi]) {
                innerVids[fi] = new int[4];
                innerUV[fi] = new float[8];
            }
        }

        // Per (face, corner): if the corner sits at a 3+ cage vertex, hold the
        // two cyan dots that replace the single face-local inner vert — one on
        // the face's back edge (shared with the previous face at the corner)
        // and one on the face's fwd edge (shared with the next face). The
        // face's inner region becomes a pentagon (or hexagon, heptagon, etc.
        // if multiple corners are 3+), emitted as a single n-gon for
        // coons_patch to route through the Gregory evaluator.
        int[][][] cyanAt3Plus = new int[origFaceCount][][];  // [fi][k] -> {backCyan, fwdCyan} or null

        // Per 3+ cage vertex: the N cyan dots in CCW order around the vertex.
        // Used to emit one central n-sided fill face per 3+ corner.
        Map<Integer, int[]> centralFillPerVertex = new HashMap<>();

        // Which (cage edge, endpoint-vid) pairs got an allocated merge point
        // (either a 2-face merge vert or a 3+-corner cyan dot) — used by the
        // side-quad emission pass to drop side quads where BOTH endpoints
        // have shared allocations.
        Set<Long> mergedEndpoint = new HashSet<>();

        int[] nextVidBox = {origVertCount};

        // Allocate inner verts per cage vertex. 2-face corners with a shared
        // cage edge get one merged vert on the edge curve (on the Coons surface
        // by construction). 3+ corners emit N cyan dots on the shared cage
        // edges around the corner plus a central n-gon fill. Everyone else
        // gets a face-local (t,t)-type vert.
        for (Map.Entry<Integer, List<int[]>> entry : facesAtVertex.entrySet()) {
            int denseVid = entry.getKey();
            List<int[]> atV = entry.getValue();
            int n = atV.size();
            boolean merged = false;

            if (n >= 3) {
                // 3+ cage-corner: emit N cyan dots (one per shared edge
                // emanating from v) at s=t along each edge's bezier curve.
                // Each face at v gets 2 cyan dots at its corner-near-v (one
                // on its back edge, one on its fwd edge), replacing the
                // single face-local inner vert. Adjacent faces share one
                // cyan dot each (the one on their common edge). All cyan
                // dots at v form the boundary of a central n-gon fill.
                allocate3PlusCorner(in, hStart, hEnd, denseVid, atV, sharedEdgeIds,
                        oldToDense, t, extraPos, cyanAt3Plus, innerUV,
                        centralFillPerVertex, mergedEndpoint, nextVidBox);
                continue;
            }

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
                    if (!sharedEdgeIds.contains(candA)) continue;
                    if (candA == eBfwd || candA == eBback) {
                        sharedEid = candA;
                        fwdFromA = fwd;
                    }
                }

                if (sharedEid >= 0) {
                    float[] uvA = edgePointUV(kA, t, fwdFromA);
                    Vector3f pos = evalFaceCoons(in, hStart, hEnd, fidA, uvA[0], uvA[1]);
                    int newVid = nextVidBox[0]++;
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
                    int newVid = nextVidBox[0]++;
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

        // Determine which shared cage edges get both endpoints merged (either
        // via 2-face shared-vert or via 3+ cyan-dot). For these edges both
        // side quads drop and the two inner polygons become edge-adjacent
        // through the shared endpoint verts.
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

        // Variable-vpf emission because 3+ cage-corner faces produce
        // pentagons/hexagons/... inner regions plus central n-gon fills.
        // coons_patch downstream routes n-gons through the Charrot-Gregory
        // evaluator for smooth subdivision (MESH-47 phase A+B).
        ArrayList<Integer> faceIdxList = new ArrayList<>();
        ArrayList<Integer> faceVpfList = new ArrayList<>();
        ArrayList<Boolean> generatedList = new ArrayList<>();

        for (int fi = 0; fi < origFaceCount; fi++) {
            int fid = in.faceIdAt(fi);
            if (!selected[fi]) {
                int fc = in.faceVertexCount(fid);
                for (int k = 0; k < fc; k++) {
                    faceIdxList.add(oldToDense.get(in.faceVertexAt(fid, k)));
                }
                faceVpfList.add(fc);
                generatedList.add(false);
                continue;
            }
            int[] dOrig = {
                    oldToDense.get(in.faceVertexAt(fid, 0)),
                    oldToDense.get(in.faceVertexAt(fid, 1)),
                    oldToDense.get(in.faceVertexAt(fid, 2)),
                    oldToDense.get(in.faceVertexAt(fid, 3)),
            };
            int[] ni = innerVids[fi];
            int[][] cyanPerCorner = cyanAt3Plus[fi];

            // Inner face: walk corners in CCW order. At normal corners, emit
            // one vert (ni[k]); at 3+ corners, emit two cyan dots (back, fwd)
            // REPLACING the single ni[k]. Result is quad (no 3+ corners),
            // pentagon (one 3+), hexagon (two), ..., octagon (all four).
            int innerStartIdx = faceIdxList.size();
            for (int k = 0; k < 4; k++) {
                if (cyanPerCorner != null && cyanPerCorner[k] != null) {
                    faceIdxList.add(cyanPerCorner[k][0]);  // back
                    faceIdxList.add(cyanPerCorner[k][1]);  // fwd
                } else {
                    faceIdxList.add(ni[k]);
                }
            }
            int innerVpf = faceIdxList.size() - innerStartIdx;
            faceVpfList.add(innerVpf);
            generatedList.add(true);

            // Side quads. For each cage edge k of this face:
            //   left-end inner (at v_k): fwd-cyan if corner k is 3+, else ni[k]
            //   right-end inner (at v_{k+1}): back-cyan if corner k+1 is 3+, else ni[k+1]
            for (int k = 0; k < 4; k++) {
                int eid = in.faceEdgeAt(fid, k);
                if (droppedSharedEdges.contains(eid)) continue;
                int kNext = (k + 1) % 4;
                int leftInner = (cyanPerCorner != null && cyanPerCorner[k] != null)
                        ? cyanPerCorner[k][1]  // fwd-cyan at corner k
                        : ni[k];
                int rightInner = (cyanPerCorner != null && cyanPerCorner[kNext] != null)
                        ? cyanPerCorner[kNext][0]  // back-cyan at corner k+1
                        : ni[kNext];
                faceIdxList.add(dOrig[k]);
                faceIdxList.add(dOrig[kNext]);
                faceIdxList.add(rightInner);
                faceIdxList.add(leftInner);
                faceVpfList.add(4);
                generatedList.add(false);
            }
        }

        // Central fill faces: one n-sided face per 3+ cage corner.
        for (Map.Entry<Integer, int[]> e : centralFillPerVertex.entrySet()) {
            int[] fill = e.getValue();
            for (int v : fill) faceIdxList.add(v);
            faceVpfList.add(fill.length);
            generatedList.add(true);  // part of the inset's generated region
        }

        int[] faceIdxFlat = new int[faceIdxList.size()];
        for (int i = 0; i < faceIdxList.size(); i++) faceIdxFlat[i] = faceIdxList.get(i);
        int[] faceVpfArr = new int[faceVpfList.size()];
        for (int i = 0; i < faceVpfList.size(); i++) faceVpfArr[i] = faceVpfList.get(i);
        boolean allQuads = true;
        for (int v : faceVpfArr) { if (v != 4) { allQuads = false; break; } }
        HalfEdgeMesh outMesh = allQuads
                ? HalfEdgeMesh.bulkAllocate(positions, faceIdxFlat, 4)
                : HalfEdgeMeshEngine.bulkAllocateMixed(positions, faceVpfArr, faceIdxFlat);
        outMesh.computeNormals();

        boolean[] generated = new boolean[generatedList.size()];
        for (int i = 0; i < generatedList.size(); i++) generated[i] = generatedList.get(i);
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

    /** Corner index within face {@code fid} whose vertex is {@code vid}, or -1. */
    private static int findCornerAtVertex(MeshTopology m, int fid, int vid) {
        int fvc = m.faceVertexCount(fid);
        for (int k = 0; k < fvc; k++) {
            if (m.faceVertexAt(fid, k) == vid) return k;
        }
        return -1;
    }

    /**
     * Allocate cyan-dot verts + central-fill corner list for a 3+ cage
     * vertex where 3 or more selected faces meet pairwise along shared cage
     * edges. Walks the selected-face fan CCW around the vertex via shared
     * edges; bails (leaving the corner face-local at all participating faces
     * via a default fallback) if the fan doesn't form a complete pairwise-
     * adjacent cycle.
     */
    private static void allocate3PlusCorner(MeshTopology in, float[] hStart, float[] hEnd,
            int denseVid, List<int[]> atV, Set<Integer> sharedEdgeIds,
            Map<Integer, Integer> oldToDense, float t,
            ArrayList<Float> extraPos, int[][][] cyanAt3Plus, float[][] innerUV,
            Map<Integer, int[]> centralFillPerVertex, Set<Long> mergedEndpoint,
            int[] nextVidBox) {

        int origVid = -1;
        for (Map.Entry<Integer, Integer> e : oldToDense.entrySet()) {
            if (e.getValue() == denseVid) { origVid = e.getKey(); break; }
        }
        if (origVid < 0) return;

        int n = atV.size();
        // Walk CCW around v from an arbitrary start face, collecting the fan
        // of (fi, k_at_v) and the shared edges between consecutive faces.
        int[] fiOrder = new int[n];
        int[] kOrder = new int[n];
        int[] sharedEdgeOrder = new int[n];  // sharedEdgeOrder[i] = shared edge between face i and face (i+1)%n
        int startFi = atV.get(0)[0];
        int startK = atV.get(0)[1];
        fiOrder[0] = startFi;
        kOrder[0] = startK;
        int fanLen = 1;
        int curFi = startFi;
        int curK = startK;
        for (int step = 0; step < n; step++) {
            int curFid = in.faceIdAt(curFi);
            int fwdEid = in.faceEdgeAt(curFid, curK);
            if (!sharedEdgeIds.contains(fwdEid)) return;  // bail — fan is not pairwise-shared
            int he = in.edgeHalfEdge(fwdEid);
            int twin = in.halfEdgeTwin(he);
            if (twin < 0) return;
            int f1 = in.halfEdgeFace(he);
            int f2 = in.halfEdgeFace(twin);
            int neighFid = (f1 == curFid) ? f2 : f1;
            if (neighFid == MeshTopology.NONE) return;
            int neighFi = faceIndexOfId(in, neighFid);
            if (neighFi < 0) return;
            sharedEdgeOrder[step] = fwdEid;
            if (neighFi == startFi) {
                // Completed cycle.
                if (fanLen != n) return;  // fan missed some faces — bail
                break;
            }
            int neighK = findCornerAtVertex(in, neighFid, origVid);
            if (neighK < 0) return;
            if (fanLen >= n) return;  // fan walked past its expected length
            fiOrder[fanLen] = neighFi;
            kOrder[fanLen] = neighK;
            fanLen++;
            curFi = neighFi;
            curK = neighK;
        }
        if (fanLen != n) return;  // didn't complete the cycle cleanly

        // Allocate N cyan dots — one per shared edge in the fan.
        // cyan[i] sits on sharedEdgeOrder[i], which is the fwd-edge of
        // fiOrder[i] AND the back-edge of fiOrder[(i+1) % n].
        int[] cyanVids = new int[n];
        for (int i = 0; i < n; i++) {
            int fi = fiOrder[i];
            int k = kOrder[i];
            int fid = in.faceIdAt(fi);
            // cyan sits at s=t along fwd-edge from v, which in this face's
            // uv-frame is edgePointUV(k, t, fwdEdge=true).
            float[] uv = edgePointUV(k, t, true);
            Vector3f pos = evalFaceCoons(in, hStart, hEnd, fid, uv[0], uv[1]);
            int newVid = nextVidBox[0]++;
            extraPos.add(pos.x); extraPos.add(pos.y); extraPos.add(pos.z);
            cyanVids[i] = newVid;
        }

        // Each face gets (backCyan, fwdCyan) at its corner-at-v: fwd is the
        // cyan on its fwd edge (sharedEdgeOrder[i]); back is the cyan on its
        // back edge (sharedEdgeOrder[(i-1+n) % n]).
        for (int i = 0; i < n; i++) {
            int fi = fiOrder[i];
            int k = kOrder[i];
            int fwdCyan = cyanVids[i];
            int backCyan = cyanVids[(i - 1 + n) % n];
            if (cyanAt3Plus[fi] == null) cyanAt3Plus[fi] = new int[4][];
            cyanAt3Plus[fi][k] = new int[]{backCyan, fwdCyan};

            // Record mergedEndpoint for both this face's v-incident edges so
            // the side-quad drop logic sees them as merged at this end.
            int fid = in.faceIdAt(fi);
            int fwdEid = in.faceEdgeAt(fid, k);
            int backEid = in.faceEdgeAt(fid, (k + 3) % 4);
            mergedEndpoint.add(packEdgeVertex(fwdEid, denseVid));
            mergedEndpoint.add(packEdgeVertex(backEid, denseVid));
        }

        // Central fill CCW around v — traversed opposite to the face fan so
        // each shared edge of the fill is traversed in the direction opposite
        // to its neighbor pentagon's traversal (manifold).
        int[] fillCCW = new int[n];
        for (int i = 0; i < n; i++) {
            fillCCW[i] = cyanVids[n - 1 - i];
        }
        centralFillPerVertex.put(denseVid, fillCCW);
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
