package ixdar.geometry.mesh.nodes.patch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Curve-preserving extrude for bezier Coons cages: each top-face corner is offset
 * along the Coons surface normal at that corner's parameter on the originating face.
 *
 * <p>Input geometry <strong>must</strong> carry bezier handle slots produced by
 * {@code assign_bezier_handles}; input without handles passes through unchanged.
 */
@MeshNodeAnnotation(id = "coons_extrude_mesh")
public class CoonsExtrudeMeshNode implements MeshNode {
    public static final float NUM_0_1 = 0.1f;
    public static final int NUM_4 = 4;
    public static final float NUM_0 = 0f;
    public static final int NUM_3 = 3;
    public static final float NUM_1 = 1f;
    public static final float NUM_1e_8 = 1e-8f;
    public static final int NUM_32 = 32;
    public static final long NUM_0xFFFFFFFF = 0xFFFFFFFFL;

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort OFFSET = new InputPort("offset", PortType.FLOAT, 0.1f, -10f, 10f);
    public static final InputPort SELECTION = new InputPort("selection", PortType.BOOLEAN, true);
    public static final InputPort REGION = new InputPort("region", PortType.BOOLEAN, false);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);
    public static final OutputPort MESH_OUT = new OutputPort("mesh", PortType.MESH);
    public static final OutputPort GENERATED_OUT = new OutputPort("generated", PortType.BOOLEAN);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, OFFSET, SELECTION, REGION);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH_OUT, GEOMETRY_OUT, GENERATED_OUT);
    }

    @Override
    public String description() {
        return "Curve-preserving extrude on a bezier-handled cage: top-face corners are offset along Coons surface normals (not flat cage face normals) and top-face edges copy their handles from the bottom. Pairs with coons_inset_faces for the cage-first workflow.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input/output bundle; MUST carry bezier handle slots from assign_bezier_handles upstream. Unhandled input passes through unchanged with a warning.",
                OFFSET.name, "Signed distance to push extruded corners along the Coons surface normal. Positive = outward protrusion; negative = inward depression (e.g. eye socket).",
                SELECTION.name, "Per-face BOOLEAN mask. Selected QUADS (non-quads pass through) get extruded.",
                REGION.name, "If true, each connected component of the selection (by face-face edge adjacency) extrudes as its own region — adjacent selected faces within the component share extruded vertices and average their surface normals at shared corners. Disconnected clusters in the same mask produce independent, correctly-formed regions (no cross-cluster averaging). If false (default), each face extrudes independently, producing a separate protrusion per face.",
                MESH_OUT.name, "Topology-only output.",
                GENERATED_OUT.name, "Per-output-face BOOLEAN: true for the newly-created top face of each extrusion. Thread into the next op's selection to chain features (e.g. another coons_extrude inward for deeper recesses)."
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
        if (!CoonsHandleBuilder.hasHandles(base)) {
            System.err.println("[coons_extrude_mesh] WARNING: input lacks bezier handles; passing through unchanged. Use assign_bezier_handles upstream, or use plain extrude_mesh for topology-only extrudes.");
            ctx.setOutput(MESH_OUT.name, in);
            ctx.setOutput(GEOMETRY.name, base);
            ctx.setOutput(GENERATED_OUT.name, new BoolField(new boolean[in.faceCount()]));
            return;
        }

        Object offObj = FieldBroadcast.getInputOrDefault(ctx, OFFSET.name, OFFSET.defaultValue);
        float offset = FieldBroadcast.floatScalarOrDefault(offObj, NUM_0_1);
        Object selObj = FieldBroadcast.getInputOrDefault(ctx, SELECTION.name, SELECTION.defaultValue);
        Object regObj = FieldBroadcast.getInputOrDefault(ctx, REGION.name, REGION.defaultValue);
        boolean region = FieldBroadcast.boolAt(regObj, 0, false);

        float[] hStart = CoonsHandleBuilder.readHandleSlot(base, AssignBezierHandlesNode.SLOT_HANDLES_START, in);
        float[] hEnd = CoonsHandleBuilder.readHandleSlot(base, AssignBezierHandlesNode.SLOT_HANDLES_END, in);

        ExtrudeResult r = region
                ? doExtrudeRegion(in, hStart, hEnd, offset, selObj)
                : doExtrudeIndividual(in, hStart, hEnd, offset, selObj);

        Map<String, Object> outSlots = new HashMap<>(base.slots());
        outSlots.put(AssignBezierHandlesNode.SLOT_HANDLES_START, r.handles[0]);
        outSlots.put(AssignBezierHandlesNode.SLOT_HANDLES_END, r.handles[1]);
        GeometryBundle outBundle = new GeometryBundle(r.outMesh, Map.copyOf(outSlots));

        ctx.setOutput(MESH_OUT.name, r.outMesh);
        ctx.setOutput(GEOMETRY.name, outBundle);
        ctx.setOutput(GENERATED_OUT.name, new BoolField(r.generated));
    }

    // ----------------------------------------------------------------------
    // INDIVIDUAL mode: each selected face extrudes independently. Even when
    // two selected faces share a cage corner, each gets its own fresh top
    // vertex offset by the face-local surface normal.
    // ----------------------------------------------------------------------

    private static ExtrudeResult doExtrudeIndividual(
            MeshTopology in, float[] hStart, float[] hEnd, float offset, Object selection) {
        int origFaceCount = in.faceCount();
        int origVertCount = in.vertexCount();

        boolean[] selected = new boolean[origFaceCount];
        int selectedCount = 0;
        for (int fi = 0; fi < origFaceCount; fi++) {
            int fid = in.faceIdAt(fi);
            boolean sel = FieldBroadcast.boolAt(selection, fi, true) && in.faceVertexCount(fid) == NUM_4;
            selected[fi] = sel;
            if (sel) selectedCount++;
        }
        if (selectedCount == 0 || offset == NUM_0) {
            return passThrough(in, hStart, hEnd);
        }

        Map<Integer, Integer> oldToDense = new HashMap<>();
        float[] origPos = new float[origVertCount * NUM_3];
        Vector3f tmp = new Vector3f();
        for (int i = 0; i < origVertCount; i++) {
            int vid = in.vertexIdAt(i);
            oldToDense.put(vid, i);
            in.vertexPosition(vid, tmp);
            origPos[i * NUM_3] = tmp.x;
            origPos[i * NUM_3 + 1] = tmp.y;
            origPos[i * NUM_3 + 2] = tmp.z;
        }

        // Seed the directed-handle map with every original edge's handles.
        // Edges NOT touched by a selected face keep them unchanged. Edges on
        // the boundary between selected and unselected are reused as side-quad
        // bottom edges.
        Map<Long, float[]> dh = new HashMap<>();
        seedOriginalEdgeHandles(in, hStart, hEnd, oldToDense, dh);

        int newVertsPerFace = NUM_4;
        int newVertTotal = selectedCount * newVertsPerFace;
        ArrayList<Float> extraPos = new ArrayList<>(newVertTotal * NUM_3);
        // Per face, new vertex dense indices in face-winding order (or null if unselected).
        int[][] topVids = new int[origFaceCount][];
        int nextVid = origVertCount;

        for (int fi = 0; fi < origFaceCount; fi++) {
            if (!selected[fi]) continue;
            int fid = in.faceIdAt(fi);
            int v0 = in.faceVertexAt(fid, 0);
            int v1 = in.faceVertexAt(fid, 1);
            int v2 = in.faceVertexAt(fid, 2);
            int v3 = in.faceVertexAt(fid, NUM_3);
            int e0 = in.faceEdgeAt(fid, 0);
            int e1 = in.faceEdgeAt(fid, 1);
            int e2 = in.faceEdgeAt(fid, 2);
            int e3 = in.faceEdgeAt(fid, NUM_3);

            Vector3f[] corners = {
                    in.vertexPosition(v0, new Vector3f()),
                    in.vertexPosition(v1, new Vector3f()),
                    in.vertexPosition(v2, new Vector3f()),
                    in.vertexPosition(v3, new Vector3f()),
            };
            Vector3f[] normals = {
                    CoonsHandleBuilder.coonsSurfaceNormal(in, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3, NUM_0, NUM_0),
                    CoonsHandleBuilder.coonsSurfaceNormal(in, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3, NUM_1, NUM_0),
                    CoonsHandleBuilder.coonsSurfaceNormal(in, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3, NUM_1, NUM_1),
                    CoonsHandleBuilder.coonsSurfaceNormal(in, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3, NUM_0, NUM_1),
            };

            int[] tops = new int[NUM_4];
            for (int k = 0; k < NUM_4; k++) {
                Vector3f p = corners[k];
                Vector3f n = normals[k];
                extraPos.add(p.x + n.x * offset);
                extraPos.add(p.y + n.y * offset);
                extraPos.add(p.z + n.z * offset);
                tops[k] = nextVid++;
            }
            topVids[fi] = tops;
        }

        // Plumb top-face edge handles: copy from bottom-face edges.
        for (int fi = 0; fi < origFaceCount; fi++) {
            if (!selected[fi]) continue;
            int fid = in.faceIdAt(fi);
            int[] tops = topVids[fi];
            int[] origIds = {
                    in.faceVertexAt(fid, 0),
                    in.faceVertexAt(fid, 1),
                    in.faceVertexAt(fid, 2),
                    in.faceVertexAt(fid, NUM_3),
            };
            int[] origEdges = {
                    in.faceEdgeAt(fid, 0),
                    in.faceEdgeAt(fid, 1),
                    in.faceEdgeAt(fid, 2),
                    in.faceEdgeAt(fid, NUM_3),
            };
            for (int k = 0; k < NUM_4; k++) {
                int a = tops[k];
                int b = tops[(k + 1) % NUM_4];
                int origA = origIds[k];
                int origB = origIds[(k + 1) % NUM_4];
                int eid = origEdges[k];
                copyEdgeHandlesDirectional(in, hStart, hEnd, eid, origA, origB, a, b, dh);
            }
            // Vertical side-wall edges: zero handles.
            for (int k = 0; k < NUM_4; k++) {
                int origDense = oldToDense.get(origIds[k]);
                int topVid = tops[k];
                dh.put(CoonsHandleBuilder.dirPack(origDense, topVid), new float[NUM_3]);
                dh.put(CoonsHandleBuilder.dirPack(topVid, origDense), new float[NUM_3]);
            }
        }

        return buildOutput(in, origVertCount, origPos, extraPos, selected, topVids, dh, oldToDense);
    }

    // ----------------------------------------------------------------------
    // REGION mode: the selection is partitioned into connected components by
    // face-face edge adjacency. Each component becomes its own extrusion with
    // shared extruded vertices and per-vertex averaged surface normals — faces
    // in different components never share top vertices, even when they touch
    // the same cage vid at a pinch point. This prevents cross-cluster normal
    // averaging that produces cheese-grater artifacts when a single mask
    // covers multiple disjoint features (e.g. select_by_normal picking up
    // tops of brow + maxilla + chin simultaneously).
    // ----------------------------------------------------------------------

    private static ExtrudeResult doExtrudeRegion(
            MeshTopology in, float[] hStart, float[] hEnd, float offset, Object selection) {
        int origFaceCount = in.faceCount();
        int origVertCount = in.vertexCount();

        boolean[] selected = new boolean[origFaceCount];
        int selectedCount = 0;
        for (int fi = 0; fi < origFaceCount; fi++) {
            int fid = in.faceIdAt(fi);
            boolean sel = FieldBroadcast.boolAt(selection, fi, true) && in.faceVertexCount(fid) == NUM_4;
            selected[fi] = sel;
            if (sel) selectedCount++;
        }
        if (selectedCount == 0 || offset == NUM_0) {
            return passThrough(in, hStart, hEnd);
        }

        Map<Integer, Integer> oldToDense = new HashMap<>();
        float[] origPos = new float[origVertCount * NUM_3];
        Vector3f tmp = new Vector3f();
        for (int i = 0; i < origVertCount; i++) {
            int vid = in.vertexIdAt(i);
            oldToDense.put(vid, i);
            in.vertexPosition(vid, tmp);
            origPos[i * NUM_3] = tmp.x;
            origPos[i * NUM_3 + 1] = tmp.y;
            origPos[i * NUM_3 + 2] = tmp.z;
        }

        // Partition selected faces into connected components (edge adjacency).
        int[] componentId = computeSelectionComponents(in, selected);

        // Per (origVid, componentId): list of (selected face index, corner index)
        // so top-vertex averaging stays scoped to a component.
        Map<Long, List<int[]>> vertIncidence = new HashMap<>();
        for (int fi = 0; fi < origFaceCount; fi++) {
            if (!selected[fi]) continue;
            int comp = componentId[fi];
            int fid = in.faceIdAt(fi);
            for (int k = 0; k < NUM_4; k++) {
                int vid = in.faceVertexAt(fid, k);
                long key = packVidComp(vid, comp);
                vertIncidence.computeIfAbsent(key, x -> new ArrayList<>()).add(new int[]{fi, k});
            }
        }

        // Allocate one top vertex per unique (origVid, componentId) pair. A vid
        // shared by two disjoint components gets two top vertices — one per
        // component — so each component's top face stays internally consistent.
        Map<Long, Integer> topVidByVidComp = new HashMap<>();
        ArrayList<Float> extraPos = new ArrayList<>();
        int nextVid = origVertCount;

        for (Map.Entry<Long, List<int[]>> e : vertIncidence.entrySet()) {
            long key = e.getKey();
            int origVid = (int) (key >>> NUM_32);
            List<int[]> uses = e.getValue();

            Vector3f avgN = new Vector3f();
            int count = 0;
            for (int[] use : uses) {
                int fi = use[0];
                int k = use[1];
                int fid = in.faceIdAt(fi);
                int v0 = in.faceVertexAt(fid, 0);
                int v1 = in.faceVertexAt(fid, 1);
                int v3 = in.faceVertexAt(fid, NUM_3);
                int e0 = in.faceEdgeAt(fid, 0);
                int e1 = in.faceEdgeAt(fid, 1);
                int e2 = in.faceEdgeAt(fid, 2);
                int e3 = in.faceEdgeAt(fid, NUM_3);
                float u = (k == 1 || k == 2) ? NUM_1 : NUM_0;
                float v = (k == 2 || k == NUM_3) ? NUM_1 : NUM_0;
                Vector3f n = CoonsHandleBuilder.coonsSurfaceNormal(in, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3, u, v);
                avgN.add(n);
                count++;
            }
            if (count > 0) avgN.mul(NUM_1 / count);
            float len = avgN.length();
            if (len > NUM_1e_8) avgN.mul(NUM_1 / len);

            Vector3f p = in.vertexPosition(origVid, new Vector3f());
            extraPos.add(p.x + avgN.x * offset);
            extraPos.add(p.y + avgN.y * offset);
            extraPos.add(p.z + avgN.z * offset);
            topVidByVidComp.put(key, nextVid++);
        }

        Map<Long, float[]> dh = new HashMap<>();
        seedOriginalEdgeHandles(in, hStart, hEnd, oldToDense, dh);

        int[][] topVids = new int[origFaceCount][];
        for (int fi = 0; fi < origFaceCount; fi++) {
            if (!selected[fi]) continue;
            int comp = componentId[fi];
            int fid = in.faceIdAt(fi);
            int[] tops = new int[NUM_4];
            for (int k = 0; k < NUM_4; k++) {
                int vid = in.faceVertexAt(fid, k);
                tops[k] = topVidByVidComp.get(packVidComp(vid, comp));
            }
            topVids[fi] = tops;

            int[] origIds = {
                    in.faceVertexAt(fid, 0),
                    in.faceVertexAt(fid, 1),
                    in.faceVertexAt(fid, 2),
                    in.faceVertexAt(fid, NUM_3),
            };
            int[] origEdges = {
                    in.faceEdgeAt(fid, 0),
                    in.faceEdgeAt(fid, 1),
                    in.faceEdgeAt(fid, 2),
                    in.faceEdgeAt(fid, NUM_3),
            };
            for (int k = 0; k < NUM_4; k++) {
                int a = tops[k];
                int b = tops[(k + 1) % NUM_4];
                int origA = origIds[k];
                int origB = origIds[(k + 1) % NUM_4];
                copyEdgeHandlesDirectional(in, hStart, hEnd, origEdges[k], origA, origB, a, b, dh);
            }
        }

        // Boundary edges (selected face ↔ unselected face or mesh boundary):
        // emit side quads using the OWNING face's component's top vertices.
        for (int fi = 0; fi < origFaceCount; fi++) {
            if (!selected[fi]) continue;
            int comp = componentId[fi];
            int fid = in.faceIdAt(fi);
            for (int k = 0; k < NUM_4; k++) {
                int origA = in.faceVertexAt(fid, k);
                int origB = in.faceVertexAt(fid, (k + 1) % NUM_4);
                int eid = in.faceEdgeAt(fid, k);
                if (isBoundaryEdge(in, eid, selected)) {
                    int denseA = oldToDense.get(origA);
                    int denseB = oldToDense.get(origB);
                    int topA = topVidByVidComp.get(packVidComp(origA, comp));
                    int topB = topVidByVidComp.get(packVidComp(origB, comp));
                    dh.put(CoonsHandleBuilder.dirPack(denseA, topA), new float[NUM_3]);
                    dh.put(CoonsHandleBuilder.dirPack(topA, denseA), new float[NUM_3]);
                    dh.put(CoonsHandleBuilder.dirPack(denseB, topB), new float[NUM_3]);
                    dh.put(CoonsHandleBuilder.dirPack(topB, denseB), new float[NUM_3]);
                }
            }
        }

        return buildOutputRegion(in, origVertCount, origPos, extraPos, selected, topVids,
                componentId, topVidByVidComp, oldToDense, dh);
    }

    /**
     * BFS-label selected faces into connected components by face-face edge
     * adjacency. Returns an array indexed by face sequence index; unselected
     * faces get component id -1. Pure topology walk; no position data consulted.
     */
    private static int[] computeSelectionComponents(MeshTopology in, boolean[] selected) {
        int n = in.faceCount();
        int[] comp = new int[n];
        Arrays.fill(comp, -1);
        int nextComp = 0;
        Deque<Integer> stack = new ArrayDeque<>();
        for (int seed = 0; seed < n; seed++) {
            if (!selected[seed] || comp[seed] != -1) continue;
            comp[seed] = nextComp;
            stack.push(seed);
            while (!stack.isEmpty()) {
                int fi = stack.pop();
                int fid = in.faceIdAt(fi);
                int fvc = in.faceVertexCount(fid);
                for (int k = 0; k < fvc; k++) {
                    int eid = in.faceEdgeAt(fid, k);
                    int he = in.edgeHalfEdge(eid);
                    int twin = in.halfEdgeTwin(he);
                    int f1 = in.halfEdgeFace(he);
                    int f2 = twin >= 0 ? in.halfEdgeFace(twin) : MeshTopology.NONE;
                    int neighFid = (f1 == fid) ? f2 : f1;
                    if (neighFid == MeshTopology.NONE) continue;
                    int neighFi = faceIndex(in, neighFid);
                    if (neighFi < 0 || !selected[neighFi] || comp[neighFi] != -1) continue;
                    comp[neighFi] = nextComp;
                    stack.push(neighFi);
                }
            }
            nextComp++;
        }
        return comp;
    }

    /** Packs (vertexId, componentId) into a long for use as a hash map key. */
    private static long packVidComp(int vid, int compId) {
        return ((long) vid << NUM_32) | (compId & NUM_0xFFFFFFFF);
    }

    // ----------------------------------------------------------------------
    // Shared helpers
    // ----------------------------------------------------------------------

    /** Passes topology through unchanged. Used for selection=empty / offset=0. */
    private static ExtrudeResult passThrough(MeshTopology in, float[] hStart, float[] hEnd) {
        int vn = in.vertexCount();
        float[] positions = new float[vn * NUM_3];
        Vector3f tmp = new Vector3f();
        Map<Integer, Integer> oldToDense = new HashMap<>();
        for (int i = 0; i < vn; i++) {
            int vid = in.vertexIdAt(i);
            oldToDense.put(vid, i);
            in.vertexPosition(vid, tmp);
            positions[i * NUM_3] = tmp.x;
            positions[i * NUM_3 + 1] = tmp.y;
            positions[i * NUM_3 + 2] = tmp.z;
        }
        // Preserve each face's original vertex count — previous code padded
        // to the first face's vpf which broke mixed-topology inputs by
        // creating duplicate-edge non-manifold configurations.
        int fn = in.faceCount();
        ArrayList<Integer> faceIdxList = new ArrayList<>();
        ArrayList<Integer> faceVpfList = new ArrayList<>();
        for (int fi = 0; fi < fn; fi++) {
            int fid = in.faceIdAt(fi);
            int fvc = in.faceVertexCount(fid);
            for (int k = 0; k < fvc; k++) {
                faceIdxList.add(oldToDense.get(in.faceVertexAt(fid, k)));
            }
            faceVpfList.add(fvc);
        }
        HalfEdgeMesh out = finalizeMixedMesh(positions, faceIdxList, faceVpfList);
        out.computeNormals();
        Map<Long, float[]> dh = new HashMap<>();
        seedOriginalEdgeHandles(in, hStart, hEnd, oldToDense, dh);
        float[][] handles = CoonsHandleBuilder.flushDirectedHandles(out, dh);
        return new ExtrudeResult(out, handles, new boolean[fn]);
    }

    private static void seedOriginalEdgeHandles(
            MeshTopology in, float[] hStart, float[] hEnd,
            Map<Integer, Integer> oldToDense, Map<Long, float[]> dh) {
        for (int ei = 0; ei < in.edgeCount(); ei++) {
            int eid = in.edgeIdAt(ei);
            int he = in.edgeHalfEdge(eid);
            int ca = in.halfEdgeVertex(he);
            int cb = in.halfEdgeEndVertex(he);
            int dca = oldToDense.get(ca);
            int dcb = oldToDense.get(cb);
            int o = eid * NUM_3;
            dh.put(CoonsHandleBuilder.dirPack(dca, dcb),
                    new float[]{hStart[o], hStart[o + 1], hStart[o + 2]});
            dh.put(CoonsHandleBuilder.dirPack(dcb, dca),
                    new float[]{hEnd[o], hEnd[o + 1], hEnd[o + 2]});
        }
    }

    /**
     * Copies bezier handles from an original cage edge onto a parallel top-face
     * edge. The top edge runs {@code a→b}, corresponding to original {@code
     * origA→origB}. Input canonical direction is read from the half-edge; if
     * it matches origA→origB we use hStart/hEnd as-is, otherwise we swap.
     */
    private static void copyEdgeHandlesDirectional(
            MeshTopology in, float[] hStart, float[] hEnd,
            int eid, int origA, int origB,
            int a, int b,
            Map<Long, float[]> dh) {
        int he = in.edgeHalfEdge(eid);
        int ca = in.halfEdgeVertex(he);
        int cb = in.halfEdgeEndVertex(he);
        int o = eid * NUM_3;
        float[] atStart = {hStart[o], hStart[o + 1], hStart[o + 2]};
        float[] atEnd = {hEnd[o], hEnd[o + 1], hEnd[o + 2]};
        // handle-at-ca is atStart; handle-at-cb is atEnd.
        float[] atOrigA = (ca == origA) ? atStart : atEnd;
        float[] atOrigB = (cb == origB) ? atEnd : atStart;
        dh.put(CoonsHandleBuilder.dirPack(a, b), atOrigA);
        dh.put(CoonsHandleBuilder.dirPack(b, a), atOrigB);
    }

    /** Builds the output mesh for INDIVIDUAL mode. */
    private static ExtrudeResult buildOutput(
            MeshTopology in, int origVertCount, float[] origPos, ArrayList<Float> extraPos,
            boolean[] selected, int[][] topVids,
            Map<Long, float[]> dh, Map<Integer, Integer> oldToDense) {
        int totalVerts = origVertCount + extraPos.size() / NUM_3;
        float[] positions = new float[totalVerts * NUM_3];
        System.arraycopy(origPos, 0, positions, 0, origPos.length);
        for (int i = 0; i < extraPos.size(); i++) {
            positions[origPos.length + i] = extraPos.get(i);
        }

        int origFaceCount = in.faceCount();
        // Selected faces: 1 top quad + 4 side quads. Unselected faces: 1
        // pass-through of original vpf (preserves non-quad faces rather than
        // padding/corrupting, so upstream coons_patch n-gons flow through).
        ArrayList<Integer> faceIdxList = new ArrayList<>();
        ArrayList<Integer> faceVpfList = new ArrayList<>();
        ArrayList<Boolean> generatedList = new ArrayList<>();

        for (int fi = 0; fi < origFaceCount; fi++) {
            int fid = in.faceIdAt(fi);
            if (!selected[fi]) {
                int fvc = in.faceVertexCount(fid);
                for (int k = 0; k < fvc; k++) {
                    faceIdxList.add(oldToDense.get(in.faceVertexAt(fid, k)));
                }
                faceVpfList.add(fvc);
                generatedList.add(false);
                continue;
            }
            int[] tops = topVids[fi];
            int[] origDenseIds = {
                    oldToDense.get(in.faceVertexAt(fid, 0)),
                    oldToDense.get(in.faceVertexAt(fid, 1)),
                    oldToDense.get(in.faceVertexAt(fid, 2)),
                    oldToDense.get(in.faceVertexAt(fid, NUM_3)),
            };
            faceIdxList.add(tops[0]); faceIdxList.add(tops[1]);
            faceIdxList.add(tops[2]); faceIdxList.add(tops[NUM_3]);
            faceVpfList.add(NUM_4);
            generatedList.add(true);
            for (int k = 0; k < NUM_4; k++) {
                int origA = origDenseIds[k];
                int origB = origDenseIds[(k + 1) % NUM_4];
                int topA = tops[k];
                int topB = tops[(k + 1) % NUM_4];
                faceIdxList.add(origA); faceIdxList.add(origB);
                faceIdxList.add(topB); faceIdxList.add(topA);
                faceVpfList.add(NUM_4);
                generatedList.add(false);
            }
        }

        HalfEdgeMesh out = finalizeMixedMesh(positions, faceIdxList, faceVpfList);
        out.computeNormals();
        float[][] handles = CoonsHandleBuilder.flushDirectedHandles(out, dh);
        boolean[] generated = new boolean[generatedList.size()];
        for (int i = 0; i < generatedList.size(); i++) generated[i] = generatedList.get(i);
        return new ExtrudeResult(out, handles, generated);
    }

    private static HalfEdgeMesh finalizeMixedMesh(float[] positions,
                                                  ArrayList<Integer> faceIdxList,
                                                  ArrayList<Integer> faceVpfList) {
        int[] faceIdxFlat = new int[faceIdxList.size()];
        for (int i = 0; i < faceIdxList.size(); i++) faceIdxFlat[i] = faceIdxList.get(i);
        int[] faceVpfArr = new int[faceVpfList.size()];
        for (int i = 0; i < faceVpfList.size(); i++) faceVpfArr[i] = faceVpfList.get(i);
        boolean allQuads = true;
        for (int v : faceVpfArr) { if (v != NUM_4) { allQuads = false; break; } }
        if (allQuads) {
            return HalfEdgeMesh.bulkAllocate(positions, faceIdxFlat, NUM_4);
        }
        return HalfEdgeMeshEngine.bulkAllocateMixed(positions, faceVpfArr, faceIdxFlat);
    }

    /** Builds the output mesh for REGION mode: side walls only on boundary edges. */
    private static ExtrudeResult buildOutputRegion(
            MeshTopology in, int origVertCount, float[] origPos, ArrayList<Float> extraPos,
            boolean[] selected, int[][] topVids,
            int[] componentId,
            Map<Long, Integer> topVidByVidComp, Map<Integer, Integer> oldToDense,
            Map<Long, float[]> dh) {
        int totalVerts = origVertCount + extraPos.size() / NUM_3;
        float[] positions = new float[totalVerts * NUM_3];
        System.arraycopy(origPos, 0, positions, 0, origPos.length);
        for (int i = 0; i < extraPos.size(); i++) {
            positions[origPos.length + i] = extraPos.get(i);
        }

        int origFaceCount = in.faceCount();

        // Each boundary edge (selected face edge where the neighbor is
        // unselected or out of mesh) becomes one side quad, owned by its
        // selected-face side so the top-vert lookup uses that face's component.
        int[] boundarySideCount = new int[1];
        boolean[] sideEmitted = new boolean[in.edgeCount()];

        record SideEdge(int origA, int origB, int topA, int topB) {}
        ArrayList<SideEdge> sides = new ArrayList<>();
        for (int fi = 0; fi < origFaceCount; fi++) {
            if (!selected[fi]) continue;
            int comp = componentId[fi];
            int fid = in.faceIdAt(fi);
            for (int k = 0; k < NUM_4; k++) {
                int eid = in.faceEdgeAt(fid, k);
                if (!isBoundaryEdge(in, eid, selected)) continue;
                int seq = edgeIndex(in, eid);
                if (seq >= 0 && seq < sideEmitted.length && sideEmitted[seq]) continue;
                if (seq >= 0 && seq < sideEmitted.length) sideEmitted[seq] = true;
                int origA = in.faceVertexAt(fid, k);
                int origB = in.faceVertexAt(fid, (k + 1) % NUM_4);
                int denseA = oldToDense.get(origA);
                int denseB = oldToDense.get(origB);
                int topA = topVidByVidComp.get(packVidComp(origA, comp));
                int topB = topVidByVidComp.get(packVidComp(origB, comp));
                sides.add(new SideEdge(denseA, denseB, topA, topB));
                boundarySideCount[0]++;
            }
        }

        // Selected faces → 1 top quad (replacing the original slot).
        // Unselected faces → 1 pass-through preserving original vpf.
        // Boundary edges → 1 side quad each.
        ArrayList<Integer> faceIdxList = new ArrayList<>();
        ArrayList<Integer> faceVpfList = new ArrayList<>();
        ArrayList<Boolean> generatedList = new ArrayList<>();

        for (int fi = 0; fi < origFaceCount; fi++) {
            int fid = in.faceIdAt(fi);
            if (selected[fi]) {
                int[] tops = topVids[fi];
                faceIdxList.add(tops[0]); faceIdxList.add(tops[1]);
                faceIdxList.add(tops[2]); faceIdxList.add(tops[NUM_3]);
                faceVpfList.add(NUM_4);
                generatedList.add(true);
            } else {
                int fvc = in.faceVertexCount(fid);
                for (int k = 0; k < fvc; k++) {
                    faceIdxList.add(oldToDense.get(in.faceVertexAt(fid, k)));
                }
                faceVpfList.add(fvc);
                generatedList.add(false);
            }
        }
        for (SideEdge se : sides) {
            faceIdxList.add(se.origA); faceIdxList.add(se.origB);
            faceIdxList.add(se.topB); faceIdxList.add(se.topA);
            faceVpfList.add(NUM_4);
            generatedList.add(false);
        }

        HalfEdgeMesh out = finalizeMixedMesh(positions, faceIdxList, faceVpfList);
        out.computeNormals();
        float[][] handles = CoonsHandleBuilder.flushDirectedHandles(out, dh);
        boolean[] generated = new boolean[generatedList.size()];
        for (int i = 0; i < generatedList.size(); i++) generated[i] = generatedList.get(i);
        return new ExtrudeResult(out, handles, generated);
    }

    /**
     * True if the edge lies on the selection boundary — it has an incident
     * face that is NOT in the selected set (including the mesh boundary case
     * where one side has no face at all).
     */
    private static boolean isBoundaryEdge(MeshTopology in, int eid, boolean[] selected) {
        if (in.isBoundaryEdge(eid)) return true;
        int he = in.edgeHalfEdge(eid);
        int twin = in.halfEdgeTwin(he);
        int f1 = in.halfEdgeFace(he);
        int f2 = twin >= 0 ? in.halfEdgeFace(twin) : MeshTopology.NONE;
        return !( isFaceSelected(f1, in, selected) && isFaceSelected(f2, in, selected));
    }

    private static boolean isFaceSelected(int fid, MeshTopology in, boolean[] selected) {
        if (fid == MeshTopology.NONE) return false;
        int fi = faceIndex(in, fid);
        return fi >= 0 && fi < selected.length && selected[fi];
    }

    /** Linear lookup of face sequence index by face id (HalfEdgeMesh has no direct map). */
    private static int faceIndex(MeshTopology mesh, int fid) {
        for (int i = 0; i < mesh.faceCount(); i++) {
            if (mesh.faceIdAt(i) == fid) return i;
        }
        return -1;
    }

    /** Linear lookup of edge sequence index by edge id. */
    private static int edgeIndex(MeshTopology mesh, int eid) {
        for (int i = 0; i < mesh.edgeCount(); i++) {
            if (mesh.edgeIdAt(i) == eid) return i;
        }
        return -1;
    }

    private record ExtrudeResult(HalfEdgeMesh outMesh, float[][] handles, boolean[] generated) {}

}
