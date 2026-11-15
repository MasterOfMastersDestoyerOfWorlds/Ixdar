package ixdar.nodegraph.nodes.patch;

import java.util.Objects;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.nodegraph.field.BoolField;
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
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.nodegraph.field.FieldBroadcast;

import ixdar.geometry.mesh.data.patches.CoonsHandleBuilder;
/**
 * Curve-preserving extrude for bezier Coons cages: each top-face corner is offset
 * along the Coons surface normal at that corner's parameter on the originating face.
 *
 * <p>Input geometry <strong>must</strong> carry bezier handle slots produced by
 * {@code assign_bezier_handles}; input without handles passes through unchanged.
 */
@MeshNodeAnnotation(id = "coons_extrude_mesh", category = NodeCategory.PATCH_SURFACE)
public class CoonsExtrudeMeshNode implements MeshNode {
    public static final float MIN_NORMAL_LENGTH = 1e-8f;

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort OFFSET = new InputPort("offset", PortType.FLOAT, 0.1f, -10f, 10f);
    public static final InputPort SELECTION = new InputPort("selection", PortType.BOOLEAN, true);
    public static final InputPort REGION = new InputPort("region", PortType.BOOLEAN, false);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);
    public static final OutputPort GENERATED_OUT = new OutputPort("generated", PortType.BOOLEAN);

    private float[][] outHandles;
    private boolean[] outGenerated;

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, OFFSET, SELECTION, REGION);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT, GENERATED_OUT);
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
                GENERATED_OUT.name, "Per-output-face BOOLEAN: true for the newly-created top face of each extrusion. Thread into the next op's selection to chain features (e.g. another coons_extrude inward for deeper recesses)."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = Objects.requireNonNullElse(ctx.getInput(GEOMETRY.name, GeometryBundle.class), GeometryBundle.empty());
        MeshTopology in = base.mesh();
        if (in == null || in.vertexCount() == 0) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            ctx.setOutput(GENERATED_OUT.name, new BoolField(new boolean[0]));
            return;
        }
        if (!CoonsHandleBuilder.hasHandles(base)) {
            System.err.println("[coons_extrude_mesh] WARNING: input lacks bezier handles; passing through unchanged. Use assign_bezier_handles upstream, or use plain extrude_mesh for topology-only extrudes.");
            ctx.setOutput(GEOMETRY.name, base);
            ctx.setOutput(GENERATED_OUT.name, new BoolField(new boolean[in.faceCount()]));
            return;
        }

        Object offObj = FieldBroadcast.getInputOrDefault(ctx, OFFSET.name, OFFSET.defaultValue);
        float offset = FieldBroadcast.floatScalarOrDefault(offObj, 0.1f);
        Object selObj = FieldBroadcast.getInputOrDefault(ctx, SELECTION.name, SELECTION.defaultValue);
        Object regObj = FieldBroadcast.getInputOrDefault(ctx, REGION.name, REGION.defaultValue);
        boolean region = FieldBroadcast.boolAt(regObj, 0, false);

        float[] hStart = CoonsHandleBuilder.readHandleSlot(base, AssignBezierHandlesNode.SLOT_HANDLES_START, in);
        float[] hEnd = CoonsHandleBuilder.readHandleSlot(base, AssignBezierHandlesNode.SLOT_HANDLES_END, in);

        HalfEdgeMesh outMesh = region
                ? doExtrudeRegion(in, hStart, hEnd, offset, selObj)
                : doExtrudeIndividual(in, hStart, hEnd, offset, selObj);

        Map<String, Object> outSlots = new HashMap<>(base.slots());
        outSlots.put(AssignBezierHandlesNode.SLOT_HANDLES_START, outHandles[0]);
        outSlots.put(AssignBezierHandlesNode.SLOT_HANDLES_END, outHandles[1]);
        GeometryBundle outBundle = new GeometryBundle(outMesh, Map.copyOf(outSlots));

        ctx.setOutput(GEOMETRY.name, outBundle);
        ctx.setOutput(GENERATED_OUT.name, new BoolField(outGenerated));
    }


    private HalfEdgeMesh doExtrudeIndividual(
            MeshTopology in, float[] hStart, float[] hEnd, float offset, Object selection) {
        int origFaceCount = in.faceCount();
        int origVertCount = in.vertexCount();

        boolean[] selected = new boolean[origFaceCount];
        int selectedCount = 0;
        for (int fi = 0; fi < origFaceCount; fi++) {
            int fid = in.faceIdAt(fi);
            boolean sel = FieldBroadcast.boolAt(selection, fi, true) && in.faceVertexCount(fid) == 4;
            selected[fi] = sel;
            if (sel) selectedCount++;
        }
        if (selectedCount == 0 || offset == 0f) {
            return passThrough(in, hStart, hEnd);
        }

        Map<Integer, Integer> oldToDense = new HashMap<>();
        float[] origPos = new float[origVertCount * 3];
        Vector3f tmp = new Vector3f();
        for (int i = 0; i < origVertCount; i++) {
            int vid = in.vertexIdAt(i);
            oldToDense.put(vid, i);
            in.vertexPosition(vid, tmp);
            PackedPoints.store(origPos, i * 3, tmp);
        }

        Map<Long, Vector3f> dh = new HashMap<>();
        seedOriginalEdgeHandles(in, hStart, hEnd, oldToDense, dh);

        int newVertsPerFace = 4;
        int newVertTotal = selectedCount * newVertsPerFace;
        ArrayList<Float> extraPos = new ArrayList<>(newVertTotal * 3);
        int[][] topVids = new int[origFaceCount][];
        int nextVid = origVertCount;

        for (int fi = 0; fi < origFaceCount; fi++) {
            if (!selected[fi]) continue;
            int fid = in.faceIdAt(fi);
            int v0 = in.faceVertexAt(fid, 0);
            int v1 = in.faceVertexAt(fid, 1);
            int v2 = in.faceVertexAt(fid, 2);
            int v3 = in.faceVertexAt(fid, 3);
            int e0 = in.faceEdgeAt(fid, 0);
            int e1 = in.faceEdgeAt(fid, 1);
            int e2 = in.faceEdgeAt(fid, 2);
            int e3 = in.faceEdgeAt(fid, 3);

            Vector3f[] corners = {
                    in.vertexPosition(v0),
                    in.vertexPosition(v1),
                    in.vertexPosition(v2),
                    in.vertexPosition(v3),
            };
            Vector3f[] normals = {
                    CoonsHandleBuilder.coonsSurfaceNormal(in, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3, 0f, 0f),
                    CoonsHandleBuilder.coonsSurfaceNormal(in, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3, 1f, 0f),
                    CoonsHandleBuilder.coonsSurfaceNormal(in, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3, 1f, 1f),
                    CoonsHandleBuilder.coonsSurfaceNormal(in, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3, 0f, 1f),
            };

            int[] tops = new int[4];
            for (int k = 0; k < 4; k++) {
                Vector3f p = corners[k];
                Vector3f n = normals[k];
                extraPos.add(p.x + n.x * offset);
                extraPos.add(p.y + n.y * offset);
                extraPos.add(p.z + n.z * offset);
                tops[k] = nextVid++;
            }
            topVids[fi] = tops;
        }

        for (int fi = 0; fi < origFaceCount; fi++) {
            if (!selected[fi]) continue;
            int fid = in.faceIdAt(fi);
            int[] tops = topVids[fi];
            int[] origIds = {
                    in.faceVertexAt(fid, 0),
                    in.faceVertexAt(fid, 1),
                    in.faceVertexAt(fid, 2),
                    in.faceVertexAt(fid, 3),
            };
            int[] origEdges = {
                    in.faceEdgeAt(fid, 0),
                    in.faceEdgeAt(fid, 1),
                    in.faceEdgeAt(fid, 2),
                    in.faceEdgeAt(fid, 3),
            };
            for (int k = 0; k < 4; k++) {
                int a = tops[k];
                int b = tops[(k + 1) % 4];
                int origA = origIds[k];
                int origB = origIds[(k + 1) % 4];
                int eid = origEdges[k];
                copyEdgeHandlesDirectional(in, hStart, hEnd, eid, origA, origB, a, b, dh);
            }
            for (int k = 0; k < 4; k++) {
                int origDense = oldToDense.get(origIds[k]);
                int topVid = tops[k];
                dh.put(CoonsHandleBuilder.dirPack(origDense, topVid), new Vector3f());
                dh.put(CoonsHandleBuilder.dirPack(topVid, origDense), new Vector3f());
            }
        }

        return buildOutput(in, origVertCount, origPos, extraPos, selected, topVids, dh, oldToDense);
    }


    private HalfEdgeMesh doExtrudeRegion(
            MeshTopology in, float[] hStart, float[] hEnd, float offset, Object selection) {
        int origFaceCount = in.faceCount();
        int origVertCount = in.vertexCount();

        boolean[] selected = new boolean[origFaceCount];
        int selectedCount = 0;
        for (int fi = 0; fi < origFaceCount; fi++) {
            int fid = in.faceIdAt(fi);
            boolean sel = FieldBroadcast.boolAt(selection, fi, true) && in.faceVertexCount(fid) == 4;
            selected[fi] = sel;
            if (sel) selectedCount++;
        }
        if (selectedCount == 0 || offset == 0f) {
            return passThrough(in, hStart, hEnd);
        }

        Map<Integer, Integer> oldToDense = new HashMap<>();
        float[] origPos = new float[origVertCount * 3];
        Vector3f tmp = new Vector3f();
        for (int i = 0; i < origVertCount; i++) {
            int vid = in.vertexIdAt(i);
            oldToDense.put(vid, i);
            in.vertexPosition(vid, tmp);
            PackedPoints.store(origPos, i * 3, tmp);
        }

        int[] componentId = computeSelectionComponents(in, selected);

        Map<Long, List<int[]>> vertIncidence = new HashMap<>();
        for (int fi = 0; fi < origFaceCount; fi++) {
            if (!selected[fi]) continue;
            int comp = componentId[fi];
            int fid = in.faceIdAt(fi);
            for (int k = 0; k < 4; k++) {
                int vid = in.faceVertexAt(fid, k);
                long key = packVidComp(vid, comp);
                vertIncidence.computeIfAbsent(key, x -> new ArrayList<>()).add(new int[]{fi, k});
            }
        }

        Map<Long, Integer> topVidByVidComp = new HashMap<>();
        ArrayList<Float> extraPos = new ArrayList<>();
        int nextVid = origVertCount;

        for (Map.Entry<Long, List<int[]>> e : vertIncidence.entrySet()) {
            long key = e.getKey();
            int origVid = (int) (key >>> 32);
            List<int[]> uses = e.getValue();

            Vector3f avgN = new Vector3f();
            int count = 0;
            for (int[] use : uses) {
                int fi = use[0];
                int k = use[1];
                int fid = in.faceIdAt(fi);
                int v0 = in.faceVertexAt(fid, 0);
                int v1 = in.faceVertexAt(fid, 1);
                int v3 = in.faceVertexAt(fid, 3);
                int e0 = in.faceEdgeAt(fid, 0);
                int e1 = in.faceEdgeAt(fid, 1);
                int e2 = in.faceEdgeAt(fid, 2);
                int e3 = in.faceEdgeAt(fid, 3);
                float u = (k == 1 || k == 2) ? 1f : 0f;
                float v = (k == 2 || k == 3) ? 1f : 0f;
                Vector3f n = CoonsHandleBuilder.coonsSurfaceNormal(in, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3, u, v);
                avgN.add(n);
                count++;
            }
            if (count > 0) avgN.mul(1f / count);
            float len = avgN.length();
            if (len > MIN_NORMAL_LENGTH) avgN.mul(1f / len);

            Vector3f p = in.vertexPosition(origVid);
            extraPos.add(p.x + avgN.x * offset);
            extraPos.add(p.y + avgN.y * offset);
            extraPos.add(p.z + avgN.z * offset);
            topVidByVidComp.put(key, nextVid++);
        }

        Map<Long, Vector3f> dh = new HashMap<>();
        seedOriginalEdgeHandles(in, hStart, hEnd, oldToDense, dh);

        int[][] topVids = new int[origFaceCount][];
        for (int fi = 0; fi < origFaceCount; fi++) {
            if (!selected[fi]) continue;
            int comp = componentId[fi];
            int fid = in.faceIdAt(fi);
            int[] tops = new int[4];
            for (int k = 0; k < 4; k++) {
                int vid = in.faceVertexAt(fid, k);
                tops[k] = topVidByVidComp.get(packVidComp(vid, comp));
            }
            topVids[fi] = tops;

            int[] origIds = {
                    in.faceVertexAt(fid, 0),
                    in.faceVertexAt(fid, 1),
                    in.faceVertexAt(fid, 2),
                    in.faceVertexAt(fid, 3),
            };
            int[] origEdges = {
                    in.faceEdgeAt(fid, 0),
                    in.faceEdgeAt(fid, 1),
                    in.faceEdgeAt(fid, 2),
                    in.faceEdgeAt(fid, 3),
            };
            for (int k = 0; k < 4; k++) {
                int a = tops[k];
                int b = tops[(k + 1) % 4];
                int origA = origIds[k];
                int origB = origIds[(k + 1) % 4];
                copyEdgeHandlesDirectional(in, hStart, hEnd, origEdges[k], origA, origB, a, b, dh);
            }
        }

        for (int fi = 0; fi < origFaceCount; fi++) {
            if (!selected[fi]) continue;
            int comp = componentId[fi];
            int fid = in.faceIdAt(fi);
            for (int k = 0; k < 4; k++) {
                int origA = in.faceVertexAt(fid, k);
                int origB = in.faceVertexAt(fid, (k + 1) % 4);
                int eid = in.faceEdgeAt(fid, k);
                if (isBoundaryEdge(in, eid, selected)) {
                    int denseA = oldToDense.get(origA);
                    int denseB = oldToDense.get(origB);
                    int topA = topVidByVidComp.get(packVidComp(origA, comp));
                    int topB = topVidByVidComp.get(packVidComp(origB, comp));
                    dh.put(CoonsHandleBuilder.dirPack(denseA, topA), new Vector3f());
                    dh.put(CoonsHandleBuilder.dirPack(topA, denseA), new Vector3f());
                    dh.put(CoonsHandleBuilder.dirPack(denseB, topB), new Vector3f());
                    dh.put(CoonsHandleBuilder.dirPack(topB, denseB), new Vector3f());
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
        return ((long) vid << 32) | (compId & 0xFFFFFFFFL);
    }


    /** Passes topology through unchanged. Used for selection=empty / offset=0. */
    private HalfEdgeMesh passThrough(MeshTopology in, float[] hStart, float[] hEnd) {
        int vn = in.vertexCount();
        float[] positions = new float[vn * 3];
        Vector3f tmp = new Vector3f();
        Map<Integer, Integer> oldToDense = new HashMap<>();
        for (int i = 0; i < vn; i++) {
            int vid = in.vertexIdAt(i);
            oldToDense.put(vid, i);
            in.vertexPosition(vid, tmp);
            PackedPoints.store(positions, i * 3, tmp);
        }
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
        Map<Long, Vector3f> dh = new HashMap<>();
        seedOriginalEdgeHandles(in, hStart, hEnd, oldToDense, dh);
        outHandles = CoonsHandleBuilder.flushDirectedHandles(out, dh);
        outGenerated = new boolean[fn];
        return out;
    }

    private static void seedOriginalEdgeHandles(
            MeshTopology in, float[] hStart, float[] hEnd,
            Map<Integer, Integer> oldToDense, Map<Long, Vector3f> dh) {
        for (int ei = 0; ei < in.edgeCount(); ei++) {
            int eid = in.edgeIdAt(ei);
            int he = in.edgeHalfEdge(eid);
            int ca = in.halfEdgeVertex(he);
            int cb = in.halfEdgeEndVertex(he);
            int dca = oldToDense.get(ca);
            int dcb = oldToDense.get(cb);
            int o = eid * 3;
            dh.put(CoonsHandleBuilder.dirPack(dca, dcb),
                    PackedPoints.at(hStart, o));
            dh.put(CoonsHandleBuilder.dirPack(dcb, dca),
                    PackedPoints.at(hEnd, o));
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
            Map<Long, Vector3f> dh) {
        int he = in.edgeHalfEdge(eid);
        int ca = in.halfEdgeVertex(he);
        int cb = in.halfEdgeEndVertex(he);
        int o = eid * 3;
        Vector3f atStart = PackedPoints.at(hStart, o);
        Vector3f atEnd = PackedPoints.at(hEnd, o);
        Vector3f atOrigA = (ca == origA) ? atStart : atEnd;
        Vector3f atOrigB = (cb == origB) ? atEnd : atStart;
        dh.put(CoonsHandleBuilder.dirPack(a, b), atOrigA);
        dh.put(CoonsHandleBuilder.dirPack(b, a), atOrigB);
    }

    /** Builds the output mesh for INDIVIDUAL mode. */
    private HalfEdgeMesh buildOutput(
            MeshTopology in, int origVertCount, float[] origPos, ArrayList<Float> extraPos,
            boolean[] selected, int[][] topVids,
            Map<Long, Vector3f> dh, Map<Integer, Integer> oldToDense) {
        int totalVerts = origVertCount + extraPos.size() / 3;
        float[] positions = new float[totalVerts * 3];
        System.arraycopy(origPos, 0, positions, 0, origPos.length);
        for (int i = 0; i < extraPos.size(); i++) {
            positions[origPos.length + i] = extraPos.get(i);
        }

        int origFaceCount = in.faceCount();
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
                    oldToDense.get(in.faceVertexAt(fid, 3)),
            };
            faceIdxList.add(tops[0]); faceIdxList.add(tops[1]);
            faceIdxList.add(tops[2]); faceIdxList.add(tops[3]);
            faceVpfList.add(4);
            generatedList.add(true);
            for (int k = 0; k < 4; k++) {
                int origA = origDenseIds[k];
                int origB = origDenseIds[(k + 1) % 4];
                int topA = tops[k];
                int topB = tops[(k + 1) % 4];
                faceIdxList.add(origA); faceIdxList.add(origB);
                faceIdxList.add(topB); faceIdxList.add(topA);
                faceVpfList.add(4);
                generatedList.add(false);
            }
        }

        HalfEdgeMesh out = finalizeMixedMesh(positions, faceIdxList, faceVpfList);
        out.computeNormals();
        outHandles = CoonsHandleBuilder.flushDirectedHandles(out, dh);
        outGenerated = new boolean[generatedList.size()];
        for (int i = 0; i < generatedList.size(); i++) outGenerated[i] = generatedList.get(i);
        return out;
    }

    private static HalfEdgeMesh finalizeMixedMesh(float[] positions,
                                                  ArrayList<Integer> faceIdxList,
                                                  ArrayList<Integer> faceVpfList) {
        int[] faceIdxFlat = new int[faceIdxList.size()];
        for (int i = 0; i < faceIdxList.size(); i++) faceIdxFlat[i] = faceIdxList.get(i);
        int[] faceVpfArr = new int[faceVpfList.size()];
        for (int i = 0; i < faceVpfList.size(); i++) faceVpfArr[i] = faceVpfList.get(i);
        boolean allQuads = true;
        for (int v : faceVpfArr) { if (v != 4) { allQuads = false; break; } }
        if (allQuads) {
            return HalfEdgeMesh.bulkAllocate(positions, faceIdxFlat, 4);
        }
        return HalfEdgeMeshOps.bulkAllocateMixed(positions, faceVpfArr, faceIdxFlat);
    }

    /** Builds the output mesh for REGION mode: side walls only on boundary edges. */
    private HalfEdgeMesh buildOutputRegion(
            MeshTopology in, int origVertCount, float[] origPos, ArrayList<Float> extraPos,
            boolean[] selected, int[][] topVids,
            int[] componentId,
            Map<Long, Integer> topVidByVidComp, Map<Integer, Integer> oldToDense,
            Map<Long, Vector3f> dh) {
        int totalVerts = origVertCount + extraPos.size() / 3;
        float[] positions = new float[totalVerts * 3];
        System.arraycopy(origPos, 0, positions, 0, origPos.length);
        for (int i = 0; i < extraPos.size(); i++) {
            positions[origPos.length + i] = extraPos.get(i);
        }

        int origFaceCount = in.faceCount();

        int[] boundarySideCount = new int[1];
        boolean[] sideEmitted = new boolean[in.edgeCount()];

        ArrayList<int[]> sides = new ArrayList<>();
        for (int fi = 0; fi < origFaceCount; fi++) {
            if (!selected[fi]) continue;
            int comp = componentId[fi];
            int fid = in.faceIdAt(fi);
            for (int k = 0; k < 4; k++) {
                int eid = in.faceEdgeAt(fid, k);
                if (!isBoundaryEdge(in, eid, selected)) continue;
                int seq = edgeIndex(in, eid);
                if (seq >= 0 && seq < sideEmitted.length && sideEmitted[seq]) continue;
                if (seq >= 0 && seq < sideEmitted.length) sideEmitted[seq] = true;
                int origA = in.faceVertexAt(fid, k);
                int origB = in.faceVertexAt(fid, (k + 1) % 4);
                int denseA = oldToDense.get(origA);
                int denseB = oldToDense.get(origB);
                int topA = topVidByVidComp.get(packVidComp(origA, comp));
                int topB = topVidByVidComp.get(packVidComp(origB, comp));
                sides.add(new int[] { denseA, denseB, topA, topB });
                boundarySideCount[0]++;
            }
        }

        ArrayList<Integer> faceIdxList = new ArrayList<>();
        ArrayList<Integer> faceVpfList = new ArrayList<>();
        ArrayList<Boolean> generatedList = new ArrayList<>();

        for (int fi = 0; fi < origFaceCount; fi++) {
            int fid = in.faceIdAt(fi);
            if (selected[fi]) {
                int[] tops = topVids[fi];
                faceIdxList.add(tops[0]); faceIdxList.add(tops[1]);
                faceIdxList.add(tops[2]); faceIdxList.add(tops[3]);
                faceVpfList.add(4);
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
        for (int[] se : sides) {
            faceIdxList.add(se[0]); faceIdxList.add(se[1]);
            faceIdxList.add(se[3]); faceIdxList.add(se[2]);
            faceVpfList.add(4);
            generatedList.add(false);
        }

        HalfEdgeMesh out = finalizeMixedMesh(positions, faceIdxList, faceVpfList);
        out.computeNormals();
        outHandles = CoonsHandleBuilder.flushDirectedHandles(out, dh);
        outGenerated = new boolean[generatedList.size()];
        for (int i = 0; i < generatedList.size(); i++) outGenerated[i] = generatedList.get(i);
        return out;
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

}
