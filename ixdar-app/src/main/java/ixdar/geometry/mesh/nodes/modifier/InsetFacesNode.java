package ixdar.geometry.mesh.nodes.modifier;

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
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.ArrayMeshEngine;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;
import ixdar.geometry.mesh.nodes.patch.AssignBezierHandlesNode;
import ixdar.geometry.mesh.nodes.patch.CoonsHandleBuilder;

/**
 * Insets selected faces: each face's vertices are lerped toward its centre to form an inner face,
 * and side quads bridge the original boundary to the inner boundary.
 *
 * <p>An all-quad input stays all-quad.
 */
@MeshNodeAnnotation(id = "inset_faces")
public class InsetFacesNode implements MeshNode {
    public static final float NUM_0_1 = 0.1f;
    public static final int NUM_3 = 3;
    public static final int NUM_4 = 4;
    public static final float NUM_0 = 0f;
    public static final float NUM_0_25 = 0.25f;
    public static final int NUM_32 = 32;
    public static final long NUM_0xFFFFFFFF = 0xFFFFFFFFL;
    public static final float NUM_1 = 1f;

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort INSET = new InputPort("inset", PortType.FLOAT, 0.1f, 0f, 1f);
    public static final InputPort SELECTION = new InputPort("selection", PortType.BOOLEAN, true);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);
    public static final OutputPort MESH_OUT = new OutputPort("mesh", PortType.MESH);
    public static final OutputPort GENERATED_OUT = new OutputPort("generated", PortType.BOOLEAN);

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
        return "Insets selected faces by creating a smaller inner face connected to the original boundary by side quads, useful for preparing faces for extrusion.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input/output cage. Preserves bezier handle slots via rebuild when _bezier_handle_weight is set.",
                INSET.name, "Inset amount in [0, 1] — fraction of the way from each corner toward the face centroid. 0 = no inset; 0.5 = halfway.",
                SELECTION.name, "Per-face BOOLEAN mask. True = face gets inset (replaced by inner quad + 4 side quads).",
                MESH_OUT.name, "Topology-only output.",
                GENERATED_OUT.name, "Per-output-face BOOLEAN: true for the newly-created inner face of each inset; false for pass-through and side quads. Thread into the selection of the next op to chain features."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput(GEOMETRY.name, Object.class));
        MeshTopology in = base.mesh();
        if (in == null || in.vertexCount() == 0) {
            ctx.setOutput(MESH_OUT.name, null);
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            ctx.setOutput(GENERATED_OUT.name, new BoolField(new boolean[0]));
            return;
        }

        Object insetObj = FieldBroadcast.getInputOrDefault(ctx, INSET.name, INSET.defaultValue);
        float inset = FieldBroadcast.floatScalarOrDefault(insetObj, NUM_0_1);

        Object selObj = FieldBroadcast.getInputOrDefault(ctx, SELECTION.name, SELECTION.defaultValue);

        ArrayMesh am = in instanceof ArrayMesh m ? m : ArrayMeshEngine.fromUniformMeshTopology(in);

        // Compute selection mask before we lose it (insetFaces takes selObj directly).
        int origFaceCount = am.faceCount();
        boolean[] selected = new boolean[origFaceCount];
        for (int fi = 0; fi < origFaceCount; fi++) {
            selected[fi] = FieldBroadcast.boolAt(selObj, fi, true);
        }

        MeshTopology out = insetFaces(in, am, inset, selObj);

        // Generated mask: the inner face (replacing the selected face) lives at
        // the original face's index; side walls appear after origFaceCount.
        int outFaceCount = out == null ? 0 : out.faceCount();
        boolean[] genMask = new boolean[outFaceCount];
        for (int fi = 0; fi < Math.min(origFaceCount, outFaceCount); fi++) {
            genMask[fi] = selected[fi];
        }

        GeometryBundle outBundle = base.withMesh(out);

        // Handle preservation — prefer the globally-consistent rebuild path
        // (via the _bezier_handle_weight slot stashed by assign_bezier_handles)
        // so every output edge gets handles computed by the same algorithm.
        // This fixes coons_patch surface divergence at shared cage edges when
        // multiple inset/extrude operations chain. Falls back to edge-by-edge
        // copying only if handles exist without a weight slot.
        if (out != null && CoonsHandleBuilder.hasHandles(base)) {
            Object w = base.slots().get(AssignBezierHandlesNode.SLOT_WEIGHT);
            if (w instanceof Number num) {
                outBundle = AssignBezierHandlesNode.computeHandles(outBundle, num.floatValue());
            } else {
                outBundle = preserveOuterHandles(base, in, out, outBundle);
            }
        }

        ctx.setOutput(MESH_OUT.name, out);
        ctx.setOutput(GEOMETRY.name, outBundle);
        ctx.setOutput(GENERATED_OUT.name, new BoolField(genMask));
    }

    /**
     * Copies input handles onto output edges whose both endpoints are original
     * vertex IDs (i.e. the outer boundary edges that were not replaced by
     * inner geometry). Edges involving extruded/inset inner vertices get zero
     * handles.
     */
    private static GeometryBundle preserveOuterHandles(GeometryBundle base,
            MeshTopology inMesh, MeshTopology outMesh, GeometryBundle outBundle) {
        float[] inHS = CoonsHandleBuilder.readHandleSlot(base,
                AssignBezierHandlesNode.SLOT_HANDLES_START, inMesh);
        float[] inHE = CoonsHandleBuilder.readHandleSlot(base,
                AssignBezierHandlesNode.SLOT_HANDLES_END, inMesh);

        Map<Long, float[]> inEdgeToStart = new HashMap<>();
        Map<Long, float[]> inEdgeToEnd = new HashMap<>();
        for (int ei = 0; ei < inMesh.edgeCount(); ei++) {
            int eid = inMesh.edgeIdAt(ei);
            int he = inMesh.edgeHalfEdge(eid);
            int va = inMesh.halfEdgeVertex(he);
            int vb = inMesh.halfEdgeEndVertex(he);
            int o = eid * NUM_3;
            inEdgeToStart.put(CoonsHandleBuilder.dirPack(va, vb),
                    new float[]{inHS[o], inHS[o + 1], inHS[o + 2]});
            inEdgeToEnd.put(CoonsHandleBuilder.dirPack(va, vb),
                    new float[]{inHE[o], inHE[o + 1], inHE[o + 2]});
        }

        int origVc = inMesh.vertexCount();
        Map<Long, float[]> dh = new HashMap<>();
        for (int ei = 0; ei < outMesh.edgeCount(); ei++) {
            int eid = outMesh.edgeIdAt(ei);
            int he = outMesh.edgeHalfEdge(eid);
            int a = outMesh.halfEdgeVertex(he);
            int b = outMesh.halfEdgeEndVertex(he);
            if (a >= origVc || b >= origVc) continue;

            long keyAB = CoonsHandleBuilder.dirPack(a, b);
            long keyBA = CoonsHandleBuilder.dirPack(b, a);
            float[] startAB = inEdgeToStart.get(keyAB);
            float[] endAB = inEdgeToEnd.get(keyAB);
            if (startAB == null) {
                startAB = inEdgeToEnd.get(keyBA);
                endAB = inEdgeToStart.get(keyBA);
            }
            if (startAB == null) continue;
            dh.put(CoonsHandleBuilder.dirPack(a, b),
                    new float[]{startAB[0], startAB[1], startAB[2]});
            dh.put(CoonsHandleBuilder.dirPack(b, a),
                    new float[]{endAB[0], endAB[1], endAB[2]});
        }

        float[][] handles = CoonsHandleBuilder.flushDirectedHandles(outMesh, dh);
        return outBundle
                .withSlot(AssignBezierHandlesNode.SLOT_HANDLES_START, handles[0])
                .withSlot(AssignBezierHandlesNode.SLOT_HANDLES_END, handles[1]);
    }

    /**
     * Quad-only fast path merging inner vertices along cage edges shared by two selected faces, so
     * adjacent faces yield one connected inset region rather than two side quads across the edge.
     *
     * <p>Corners where three or more selected faces meet stay unmerged, keeping a face-local
     * centroid-lerp inner vertex per face.
     */
    private static MeshTopology insetFacesQuadWithSharedEdgeMerge(
            MeshTopology topology, float[] srcPos, int[] srcFaces,
            int vertCount, int faceCount, boolean[] selected, float t) {

        // Shared cage edges: both incident faces selected.
        Set<Integer> sharedEdgeIds = new HashSet<>();
        for (int ei = 0; ei < topology.edgeCount(); ei++) {
            int eid = topology.edgeIdAt(ei);
            if (topology.isBoundaryEdge(eid)) continue;
            int he = topology.edgeHalfEdge(eid);
            int twin = topology.halfEdgeTwin(he);
            int f1 = topology.halfEdgeFace(he);
            int f2 = twin >= 0 ? topology.halfEdgeFace(twin) : MeshTopology.NONE;
            if (f1 == MeshTopology.NONE || f2 == MeshTopology.NONE) continue;
            int fi1 = faceIndexOfId(topology, f1);
            int fi2 = faceIndexOfId(topology, f2);
            if (fi1 >= 0 && fi2 >= 0 && selected[fi1] && selected[fi2]) {
                sharedEdgeIds.add(eid);
            }
        }

        // (dense vid → list of (fi, corner k)) — dense = ArrayMesh packed index.
        Map<Integer, List<int[]>> facesAtVertex = new HashMap<>();
        for (int fi = 0; fi < faceCount; fi++) {
            if (!selected[fi]) continue;
            int fb = fi * NUM_4;
            for (int k = 0; k < NUM_4; k++) {
                int vid = srcFaces[fb + k];
                facesAtVertex.computeIfAbsent(vid, x -> new ArrayList<>()).add(new int[]{fi, k});
            }
        }

        // Dry-run the 3+ fan walks first to determine which 3+ corners will
        // successfully allocate cyan dots (complete pairwise-shared cycle
        // around the vertex). 2-face merges are then restricted to shared
        // edges where BOTH endpoints are allocated (n==2 corner OR succeeded
        // 3+ fan); this prevents partial merges (one end merged, the other
        // kept face-local on a failed 3+ fan) from producing duplicate-edge
        // non-manifold output.
        Set<Integer> succeeded3PlusVids = new HashSet<>();
        for (Map.Entry<Integer, List<int[]>> entry : facesAtVertex.entrySet()) {
            if (entry.getValue().size() == NUM_3
                    && fanCompletes(topology, entry.getKey(), entry.getValue(), sharedEdgeIds)) {
                succeeded3PlusVids.add(entry.getKey());
            }
        }
        Set<Integer> fullyMergeableEdges = new HashSet<>();
        for (int eid : sharedEdgeIds) {
            int he = topology.edgeHalfEdge(eid);
            int va = topology.halfEdgeVertex(he);
            int vb = topology.halfEdgeEndVertex(he);
            boolean aOk = succeeded3PlusVids.contains(va)
                    || (facesAtVertex.get(va) != null && facesAtVertex.get(va).size() == 2);
            boolean bOk = succeeded3PlusVids.contains(vb)
                    || (facesAtVertex.get(vb) != null && facesAtVertex.get(vb).size() == 2);
            if (aOk && bOk) fullyMergeableEdges.add(eid);
        }

        int[][] innerVerts = new int[faceCount][];
        for (int fi = 0; fi < faceCount; fi++) {
            if (selected[fi]) innerVerts[fi] = new int[NUM_4];
        }

        // Per (face, corner): two cyan dots that replace the single face-local
        // inner vert when the corner sits at a 3+ cage vertex (MESH_OUT.name-48).
        int[][][] cyanAt3Plus = new int[faceCount][][];

        // Central n-sided fill face per 3+ cage vertex — list of cyan dot
        // dense vids in CCW order around the vertex (opposite of the face fan
        // direction for manifold correctness).
        Map<Integer, int[]> centralFillPerVertex = new HashMap<>();

        // New inner-vert positions; growable because merges + 3+ corner cyan
        // dots change the final count from the naive 4*selectedCount.
        ArrayList<Float> extraPos = new ArrayList<>();
        int[] nextVidBox = {vertCount};
        Set<Long> mergedEndpoint = new HashSet<>();

        // Per-face centroid cache — for face-local lerp at 1-face and 3+ corners.
        float[] centroids = new float[faceCount * NUM_3];
        for (int fi = 0; fi < faceCount; fi++) {
            if (!selected[fi]) continue;
            int fb = fi * NUM_4;
            float cx = NUM_0, cy = NUM_0, cz = NUM_0;
            for (int k = 0; k < NUM_4; k++) {
                int vid = srcFaces[fb + k];
                cx += srcPos[vid * NUM_3];
                cy += srcPos[vid * NUM_3 + 1];
                cz += srcPos[vid * NUM_3 + 2];
            }
            centroids[fi * NUM_3] = cx * NUM_0_25;
            centroids[fi * NUM_3 + 1] = cy * NUM_0_25;
            centroids[fi * NUM_3 + 2] = cz * NUM_0_25;
        }

        for (Map.Entry<Integer, List<int[]>> entry : facesAtVertex.entrySet()) {
            int denseVid = entry.getKey();
            List<int[]> atV = entry.getValue();
            int n = atV.size();

            if (n == NUM_3) {
                // MESH_OUT.name-47/48: cube-corner 3-face emission (triangle fill).
                // N=4+ falls through to face-local — current fan walk produces
                // non-manifold output on subdivided-cage interior corners.
                boolean ok = allocate3PlusCornerFlat(topology, denseVid, atV,
                        sharedEdgeIds, srcPos, t, extraPos,
                        cyanAt3Plus, centralFillPerVertex, mergedEndpoint, nextVidBox);
                if (ok) continue;
                // Fall through to face-local if the fan didn't form a clean
                // cycle (non-manifold-ish configuration).
            }

            boolean merged = false;

            if (n == 2) {
                int[] a = atV.get(0);
                int[] b = atV.get(1);
                int fiA = a[0], kA = a[1];
                int fiB = b[0], kB = b[1];
                int fidA = topology.faceIdAt(fiA);
                int fidB = topology.faceIdAt(fiB);
                int eAfwd = topology.faceEdgeAt(fidA, kA);
                int eAback = topology.faceEdgeAt(fidA, (kA + NUM_3) % NUM_4);
                int eBfwd = topology.faceEdgeAt(fidB, kB);
                int eBback = topology.faceEdgeAt(fidB, (kB + NUM_3) % NUM_4);

                int sharedEid = -1;
                for (int pass = 0; pass < 2 && sharedEid < 0; pass++) {
                    int candA = (pass == 0) ? eAfwd : eAback;
                    if (!fullyMergeableEdges.contains(candA)) continue;
                    if (candA == eBfwd || candA == eBback) {
                        sharedEid = candA;
                    }
                }

                if (sharedEid >= 0) {
                    int heS = topology.edgeHalfEdge(sharedEid);
                    int va = topology.halfEdgeVertex(heS);
                    int vb = topology.halfEdgeEndVertex(heS);
                    int otherVid = (va == denseVid) ? vb : va;
                    float px = srcPos[denseVid * NUM_3]
                            + (srcPos[otherVid * NUM_3] - srcPos[denseVid * NUM_3]) * t;
                    float py = srcPos[denseVid * NUM_3 + 1]
                            + (srcPos[otherVid * NUM_3 + 1] - srcPos[denseVid * NUM_3 + 1]) * t;
                    float pz = srcPos[denseVid * NUM_3 + 2]
                            + (srcPos[otherVid * NUM_3 + 2] - srcPos[denseVid * NUM_3 + 2]) * t;
                    int newVid = nextVidBox[0]++;
                    extraPos.add(px); extraPos.add(py); extraPos.add(pz);
                    innerVerts[fiA][kA] = newVid;
                    innerVerts[fiB][kB] = newVid;
                    mergedEndpoint.add(packEdgeVertex(sharedEid, denseVid));
                    merged = true;
                }
            }

            if (!merged) {
                for (int[] pair : atV) {
                    int fi = pair[0], k = pair[1];
                    float ox = srcPos[denseVid * NUM_3];
                    float oy = srcPos[denseVid * NUM_3 + 1];
                    float oz = srcPos[denseVid * NUM_3 + 2];
                    float cx = centroids[fi * NUM_3];
                    float cy = centroids[fi * NUM_3 + 1];
                    float cz = centroids[fi * NUM_3 + 2];
                    int newVid = nextVidBox[0]++;
                    extraPos.add(ox + (cx - ox) * t);
                    extraPos.add(oy + (cy - oy) * t);
                    extraPos.add(oz + (cz - oz) * t);
                    innerVerts[fi][k] = newVid;
                }
            }
        }

        // Shared cage edges with BOTH endpoints merged (2-face merge OR 3+
        // cyan dot) → drop their 2 side quads. Inner polygons become
        // edge-adjacent through the shared endpoint verts.
        Set<Integer> droppedSharedEdges = new HashSet<>();
        for (int eid : sharedEdgeIds) {
            int he = topology.edgeHalfEdge(eid);
            int va = topology.halfEdgeVertex(he);
            int vb = topology.halfEdgeEndVertex(he);
            if (mergedEndpoint.contains(packEdgeVertex(eid, va))
                    && mergedEndpoint.contains(packEdgeVertex(eid, vb))) {
                droppedSharedEdges.add(eid);
            }
        }

        int outV = vertCount + extraPos.size() / NUM_3;
        float[] outPos = new float[outV * NUM_3];
        System.arraycopy(srcPos, 0, outPos, 0, vertCount * NUM_3);
        for (int i = 0; i < extraPos.size(); i++) {
            outPos[vertCount * NUM_3 + i] = extraPos.get(i);
        }

        // Variable-vpf output. Inner polygons may be pentagons / hexagons /
        // octagons when one or more corners are 3+, and per-3+-corner central
        // fill faces are n-sided.
        ArrayList<Integer> faceIdxList = new ArrayList<>();
        ArrayList<Integer> faceVpfList = new ArrayList<>();

        for (int fi = 0; fi < faceCount; fi++) {
            if (selected[fi]) {
                int[] iv = innerVerts[fi];
                int[][] cyanPerCorner = cyanAt3Plus[fi];
                int innerStart = faceIdxList.size();
                for (int k = 0; k < NUM_4; k++) {
                    if (cyanPerCorner != null && cyanPerCorner[k] != null) {
                        faceIdxList.add(cyanPerCorner[k][0]);
                        faceIdxList.add(cyanPerCorner[k][1]);
                    } else {
                        faceIdxList.add(iv[k]);
                    }
                }
                faceVpfList.add(faceIdxList.size() - innerStart);
            } else {
                int fb = fi * NUM_4;
                faceIdxList.add(srcFaces[fb]);
                faceIdxList.add(srcFaces[fb + 1]);
                faceIdxList.add(srcFaces[fb + 2]);
                faceIdxList.add(srcFaces[fb + NUM_3]);
                faceVpfList.add(NUM_4);
            }
        }

        // Side quads: one per non-dropped cage edge of each selected face.
        for (int fi = 0; fi < faceCount; fi++) {
            if (!selected[fi]) continue;
            int fid = topology.faceIdAt(fi);
            int[] iv = innerVerts[fi];
            int[][] cyanPerCorner = cyanAt3Plus[fi];
            int fb = fi * NUM_4;
            for (int k = 0; k < NUM_4; k++) {
                int eid = topology.faceEdgeAt(fid, k);
                if (droppedSharedEdges.contains(eid)) continue;
                int kNext = (k + 1) & NUM_3;
                int leftInner = (cyanPerCorner != null && cyanPerCorner[k] != null)
                        ? cyanPerCorner[k][1]  // fwd-cyan at corner k
                        : iv[k];
                int rightInner = (cyanPerCorner != null && cyanPerCorner[kNext] != null)
                        ? cyanPerCorner[kNext][0]  // back-cyan at corner k+1
                        : iv[kNext];
                faceIdxList.add(srcFaces[fb + k]);
                faceIdxList.add(srcFaces[fb + kNext]);
                faceIdxList.add(rightInner);
                faceIdxList.add(leftInner);
                faceVpfList.add(NUM_4);
            }
        }

        // Central fill faces: one n-sided face per 3+ cage corner.
        for (Map.Entry<Integer, int[]> e : centralFillPerVertex.entrySet()) {
            int[] fill = e.getValue();
            for (int v : fill) faceIdxList.add(v);
            faceVpfList.add(fill.length);
        }

        int[] faceIdxFlat = new int[faceIdxList.size()];
        for (int i = 0; i < faceIdxList.size(); i++) faceIdxFlat[i] = faceIdxList.get(i);
        int[] faceVpfArr = new int[faceVpfList.size()];
        for (int i = 0; i < faceVpfList.size(); i++) faceVpfArr[i] = faceVpfList.get(i);
        boolean allQuads = true;
        for (int v : faceVpfArr) { if (v != NUM_4) { allQuads = false; break; } }
        if (allQuads) {
            ArrayMesh out = new ArrayMesh(outPos, null, faceIdxFlat, NUM_4);
            out.computeNormals();
            return out;
        }
        HalfEdgeMesh out = HalfEdgeMeshEngine.bulkAllocateMixed(outPos, faceVpfArr, faceIdxFlat);
        out.computeNormals();
        return out;
    }

    /**
     * Dry-run equivalent of the fan walk in {@link #allocate3PlusCornerFlat}.
     * Returns whether the selected-face fan around {@code denseVid} would
     * form a complete pairwise-shared-edge cycle, without allocating any
     * cyan-dot verts. Used in a pre-pass so that 2-face merges can avoid
     * partial-merge non-manifold configurations at shared edges adjacent to
     * failed 3+ corners.
     */
    private static boolean fanCompletes(MeshTopology topology, int denseVid,
                                        List<int[]> atV, Set<Integer> sharedEdgeIds) {
        int n = atV.size();
        int startFi = atV.get(0)[0];
        int startK = atV.get(0)[1];
        int curFi = startFi;
        int curK = startK;
        int fanLen = 1;
        for (int step = 0; step < n; step++) {
            int curFid = topology.faceIdAt(curFi);
            int fwdEid = topology.faceEdgeAt(curFid, curK);
            if (!sharedEdgeIds.contains(fwdEid)) return false;
            int he = topology.edgeHalfEdge(fwdEid);
            int twin = topology.halfEdgeTwin(he);
            if (twin < 0) return false;
            int f1 = topology.halfEdgeFace(he);
            int f2 = topology.halfEdgeFace(twin);
            int neighFid = (f1 == curFid) ? f2 : f1;
            if (neighFid == MeshTopology.NONE) return false;
            int neighFi = faceIndexOfId(topology, neighFid);
            if (neighFi < 0) return false;
            if (neighFi == startFi) return fanLen == n;
            int neighK = -1;
            int nfvc = topology.faceVertexCount(neighFid);
            for (int k = 0; k < nfvc; k++) {
                if (topology.faceVertexAt(neighFid, k) == denseVid) {
                    neighK = k;
                    break;
                }
            }
            if (neighK < 0) return false;
            if (fanLen >= n) return false;
            fanLen++;
            curFi = neighFi;
            curK = neighK;
        }
        return fanLen == n;
    }

    /**
     * Allocates flat-lerp inset vertices at a corner where three or more selected faces meet: one
     * vertex per shared cage edge plus a central fill face. Returns false when the fan around the
     * vertex is not a complete cycle, leaving the caller to fall back to face-local corners.
     */
    private static boolean allocate3PlusCornerFlat(MeshTopology topology,
            int denseVid, List<int[]> atV, Set<Integer> sharedEdgeIds,
            float[] srcPos, float t, ArrayList<Float> extraPos,
            int[][][] cyanAt3Plus, Map<Integer, int[]> centralFillPerVertex,
            Set<Long> mergedEndpoint, int[] nextVidBox) {

        int n = atV.size();
        int[] fiOrder = new int[n];
        int[] kOrder = new int[n];
        int[] sharedEdgeOrder = new int[n];
        int startFi = atV.get(0)[0];
        int startK = atV.get(0)[1];
        fiOrder[0] = startFi;
        kOrder[0] = startK;
        int fanLen = 1;
        int curFi = startFi;
        int curK = startK;
        for (int step = 0; step < n; step++) {
            int curFid = topology.faceIdAt(curFi);
            int fwdEid = topology.faceEdgeAt(curFid, curK);
            if (!sharedEdgeIds.contains(fwdEid)) return false;
            int he = topology.edgeHalfEdge(fwdEid);
            int twin = topology.halfEdgeTwin(he);
            if (twin < 0) return false;
            int f1 = topology.halfEdgeFace(he);
            int f2 = topology.halfEdgeFace(twin);
            int neighFid = (f1 == curFid) ? f2 : f1;
            if (neighFid == MeshTopology.NONE) return false;
            int neighFi = faceIndexOfId(topology, neighFid);
            if (neighFi < 0) return false;
            sharedEdgeOrder[step] = fwdEid;
            if (neighFi == startFi) {
                if (fanLen != n) return false;
                break;
            }
            // Find the corner of neighFi that sits at denseVid.
            int neighK = -1;
            int nfvc = topology.faceVertexCount(neighFid);
            for (int k = 0; k < nfvc; k++) {
                if (topology.faceVertexAt(neighFid, k) == denseVid) {
                    neighK = k;
                    break;
                }
            }
            if (neighK < 0) return false;
            if (fanLen >= n) return false;
            fiOrder[fanLen] = neighFi;
            kOrder[fanLen] = neighK;
            fanLen++;
            curFi = neighFi;
            curK = neighK;
        }
        if (fanLen != n) return false;

        // Allocate N cyan dots — straight-line lerp along each shared edge
        // at fraction t from the 3+ corner toward the other endpoint.
        int[] cyanVids = new int[n];
        for (int i = 0; i < n; i++) {
            int eid = sharedEdgeOrder[i];
            int he = topology.edgeHalfEdge(eid);
            int va = topology.halfEdgeVertex(he);
            int vb = topology.halfEdgeEndVertex(he);
            int otherVid = (va == denseVid) ? vb : va;
            float px = srcPos[denseVid * NUM_3] + (srcPos[otherVid * NUM_3] - srcPos[denseVid * NUM_3]) * t;
            float py = srcPos[denseVid * NUM_3 + 1] + (srcPos[otherVid * NUM_3 + 1] - srcPos[denseVid * NUM_3 + 1]) * t;
            float pz = srcPos[denseVid * NUM_3 + 2] + (srcPos[otherVid * NUM_3 + 2] - srcPos[denseVid * NUM_3 + 2]) * t;
            int newVid = nextVidBox[0]++;
            extraPos.add(px); extraPos.add(py); extraPos.add(pz);
            cyanVids[i] = newVid;
        }

        // Attach (backCyan, fwdCyan) to each face's corner-at-v. fwd = cyan
        // on its fwd edge (sharedEdgeOrder[i]); back = cyan on its back edge
        // (sharedEdgeOrder[(i-1+n) % n]).
        for (int i = 0; i < n; i++) {
            int fi = fiOrder[i];
            int k = kOrder[i];
            int fwdCyan = cyanVids[i];
            int backCyan = cyanVids[(i - 1 + n) % n];
            if (cyanAt3Plus[fi] == null) cyanAt3Plus[fi] = new int[NUM_4][];
            cyanAt3Plus[fi][k] = new int[]{backCyan, fwdCyan};

            int fid = topology.faceIdAt(fi);
            int fwdEid = topology.faceEdgeAt(fid, k);
            int backEid = topology.faceEdgeAt(fid, (k + NUM_3) % NUM_4);
            mergedEndpoint.add(packEdgeVertex(fwdEid, denseVid));
            mergedEndpoint.add(packEdgeVertex(backEid, denseVid));
        }

        // Central fill CCW — traversed opposite of the face fan so each edge
        // opposes its neighbor pentagon's traversal (manifold).
        int[] fillCCW = new int[n];
        for (int i = 0; i < n; i++) fillCCW[i] = cyanVids[n - 1 - i];
        centralFillPerVertex.put(denseVid, fillCCW);
        return true;
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
        return ((long) eid << NUM_32) | (denseVid & NUM_0xFFFFFFFF);
    }

    private static MeshTopology insetFaces(MeshTopology topology, ArrayMesh mesh, float inset, Object selection) {
        int vpf = mesh.getVertsPerFace();
        int vertCount = mesh.vertexCount();
        int faceCount = mesh.faceCount();
        float[] srcPos = mesh.copyPositions();
        int[] srcFaces = mesh.copyFaceIndices();

        boolean[] selected = new boolean[faceCount];
        int selectedCount = 0;
        for (int fi = 0; fi < faceCount; fi++) {
            boolean sel = FieldBroadcast.boolAt(selection, fi, true);
            selected[fi] = sel;
            if (sel) selectedCount++;
        }

        if (selectedCount == 0 || inset <= NUM_0) {
            return new ArrayMesh(srcPos, null, srcFaces, vpf);
        }

        // Fast path: uniform quad input. Same cage-vertex-keyed merge scheme
        // as CoonsInsetFacesNode (MESH_OUT.name-45) but using straight-line lerps along
        // the shared cage edge rather than Coons surface evaluations, since
        // plain inset_faces is flat-lerp by design.
        if (vpf == NUM_4) {
            return insetFacesQuadWithSharedEdgeMerge(topology, srcPos, srcFaces,
                    vertCount, faceCount, selected, Math.min(inset, NUM_1));
        }

        // Each selected face: vpf new inner vertices + vpf side quads
        int newVertCount = selectedCount * vpf;
        int sideFaceCount = selectedCount * vpf;

        // Fallback for non-quad input (triangles etc): sides would be quads breaking uniformity
        HalfEdgeMesh out = new HalfEdgeMesh(
                vertCount + newVertCount,
                0,
                faceCount + sideFaceCount,
                (faceCount + sideFaceCount) * vpf * 2
        );

        for (int vi = 0; vi < vertCount; vi++) {
            out.addVertex(srcPos[vi * NUM_3], srcPos[vi * NUM_3 + 1], srcPos[vi * NUM_3 + 2]);
        }

        Vector3f center = new Vector3f();
        int[][] faceInnerVerts = new int[faceCount][];

        for (int fi = 0; fi < faceCount; fi++) {
            if (!selected[fi]) continue;

            center.set(NUM_0, NUM_0, NUM_0);
            for (int k = 0; k < vpf; k++) {
                int vid = srcFaces[fi * vpf + k];
                center.add(srcPos[vid * NUM_3], srcPos[vid * NUM_3 + 1], srcPos[vid * NUM_3 + 2]);
            }
            center.div(vpf);

            int[] innerVerts = new int[vpf];
            for (int k = 0; k < vpf; k++) {
                int vid = srcFaces[fi * vpf + k];
                float ox = srcPos[vid * NUM_3];
                float oy = srcPos[vid * NUM_3 + 1];
                float oz = srcPos[vid * NUM_3 + 2];
                float t = Math.min(inset, 1.0f);
                float nx = ox + (center.x - ox) * t;
                float ny = oy + (center.y - oy) * t;
                float nz = oz + (center.z - oz) * t;
                innerVerts[k] = out.addVertex(nx, ny, nz);
            }
            faceInnerVerts[fi] = innerVerts;
        }

        for (int fi = 0; fi < faceCount; fi++) {
            if (selected[fi]) {
                out.addFace(faceInnerVerts[fi]);
            } else {
                int[] vids = new int[vpf];
                for (int k = 0; k < vpf; k++) {
                    vids[k] = srcFaces[fi * vpf + k];
                }
                out.addFace(vids);
            }
        }

        for (int fi = 0; fi < faceCount; fi++) {
            if (!selected[fi]) continue;
            int[] innerVerts = faceInnerVerts[fi];
            for (int k = 0; k < vpf; k++) {
                int next = (k + 1) % vpf;
                int origA = srcFaces[fi * vpf + k];
                int origB = srcFaces[fi * vpf + next];
                int innerA = innerVerts[k];
                int innerB = innerVerts[next];
                out.addFace(origA, origB, innerB, innerA);
            }
        }

        out.computeNormals();
        return out;
    }
}
