package ixdar.geometry.mesh.nodes.patch;

import java.util.ArrayList;
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
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Curve-preserving extrude for bezier Coons cages. Unlike {@code extrude_mesh}
 * (which offsets extruded vertices along the flat cage face normal), this node
 * offsets each top-face corner along the <em>Coons surface normal</em> computed
 * at that corner's parameter on the originating face. Bezier handles on the
 * parallel top-face edges are copied from the bottom edges (a translation
 * preserves tangent directions). Vertical side-wall edges get zero handles
 * (straight radial extrusion).
 *
 * <p>Input geometry <strong>must</strong> carry bezier handle slots produced
 * by {@code assign_bezier_handles}. Input without handles is passed through
 * unchanged with a console warning; use plain {@code extrude_mesh} for
 * topology-only extrudes on unhandled meshes.
 *
 * <p>Companion to {@link CoonsInsetFacesNode}. Typical workflow:
 * {@code cube → loop_cut → assign_bezier_handles → coons_inset_faces →
 * coons_extrude_mesh(selection=inset.generated, offset=-0.1) → coons_patch →
 * merge_by_distance}.
 */
@MeshNodeAnnotation(id = "coons_extrude_mesh")
public class CoonsExtrudeMeshNode implements MeshNode {

    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort OFFSET = new InputPort("offset", PortType.FLOAT, 0.1f, -10f, 10f);
    private static final InputPort SELECTION = new InputPort("selection", PortType.BOOLEAN, true);
    private static final InputPort REGION = new InputPort("region", PortType.BOOLEAN, false);
    private static final OutputPort GEOMETRY_OUT = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);
    private static final OutputPort MESH_OUT = new OutputPort("mesh", PortType.MESH);
    private static final OutputPort GENERATED_OUT = new OutputPort("generated", PortType.BOOLEAN);

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
                "geometry", "Input/output bundle; MUST carry bezier handle slots from assign_bezier_handles upstream. Unhandled input passes through unchanged with a warning.",
                "offset", "Signed distance to push extruded corners along the Coons surface normal. Positive = outward protrusion; negative = inward depression (e.g. eye socket).",
                "selection", "Per-face BOOLEAN mask. Selected QUADS (non-quads pass through) get extruded.",
                "region", "If true, adjacent selected faces share extruded vertices and average their surface normals at shared corners — single connected extrusion region. If false (default), each face extrudes independently, producing a separate protrusion per face.",
                "mesh", "Topology-only output.",
                "generated", "Per-output-face BOOLEAN: true for the newly-created top face of each extrusion. Thread into the next op's selection to chain features (e.g. another coons_extrude inward for deeper recesses)."
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
            System.err.println("[coons_extrude_mesh] WARNING: input lacks bezier handles; passing through unchanged. Use assign_bezier_handles upstream, or use plain extrude_mesh for topology-only extrudes.");
            ctx.setOutput("mesh", in);
            ctx.setOutput("geometry", base);
            ctx.setOutput("generated", new BoolField(new boolean[in.faceCount()]));
            return;
        }

        Object offObj = FieldBroadcast.getInputOrDefault(ctx, "offset", OFFSET.defaultValue());
        float offset = FieldBroadcast.floatScalarOrDefault(offObj, 0.1f);
        Object selObj = FieldBroadcast.getInputOrDefault(ctx, "selection", SELECTION.defaultValue());
        Object regObj = FieldBroadcast.getInputOrDefault(ctx, "region", REGION.defaultValue());
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

        ctx.setOutput("mesh", r.outMesh);
        ctx.setOutput("geometry", outBundle);
        ctx.setOutput("generated", new BoolField(r.generated));
    }

    private record ExtrudeResult(HalfEdgeMesh outMesh, float[][] handles, boolean[] generated) {}

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
            origPos[i * 3] = tmp.x;
            origPos[i * 3 + 1] = tmp.y;
            origPos[i * 3 + 2] = tmp.z;
        }

        // Seed the directed-handle map with every original edge's handles.
        // Edges NOT touched by a selected face keep them unchanged. Edges on
        // the boundary between selected and unselected are reused as side-quad
        // bottom edges.
        Map<Long, float[]> dh = new HashMap<>();
        seedOriginalEdgeHandles(in, hStart, hEnd, oldToDense, dh);

        int newVertsPerFace = 4;
        int newVertTotal = selectedCount * newVertsPerFace;
        ArrayList<Float> extraPos = new ArrayList<>(newVertTotal * 3);
        // Per face, new vertex dense indices in face-winding order (or null if unselected).
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
                    in.vertexPosition(v0, new Vector3f()),
                    in.vertexPosition(v1, new Vector3f()),
                    in.vertexPosition(v2, new Vector3f()),
                    in.vertexPosition(v3, new Vector3f()),
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

        // Plumb top-face edge handles: copy from bottom-face edges.
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
            // Vertical side-wall edges: zero handles.
            for (int k = 0; k < 4; k++) {
                int origDense = oldToDense.get(origIds[k]);
                int topVid = tops[k];
                dh.put(CoonsHandleBuilder.dirPack(origDense, topVid), new float[3]);
                dh.put(CoonsHandleBuilder.dirPack(topVid, origDense), new float[3]);
            }
        }

        return buildOutput(in, origVertCount, origPos, extraPos, selected, topVids, dh, oldToDense);
    }

    // ----------------------------------------------------------------------
    // REGION mode: shared cage corners across adjacent selected faces map to
    // one shared top vertex whose offset direction is the average of the
    // incident faces' surface normals at that corner. Side walls only appear
    // on edges where a selected face borders an unselected face (or mesh
    // boundary).
    // ----------------------------------------------------------------------

    private static ExtrudeResult doExtrudeRegion(
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
            origPos[i * 3] = tmp.x;
            origPos[i * 3 + 1] = tmp.y;
            origPos[i * 3 + 2] = tmp.z;
        }

        // Per original vertex id: list of (selected face id, corner index within face)
        // so we can average surface normals at shared corners.
        Map<Integer, List<int[]>> vertIncidence = new HashMap<>();
        for (int fi = 0; fi < origFaceCount; fi++) {
            if (!selected[fi]) continue;
            int fid = in.faceIdAt(fi);
            for (int k = 0; k < 4; k++) {
                int vid = in.faceVertexAt(fid, k);
                vertIncidence.computeIfAbsent(vid, x -> new ArrayList<>()).add(new int[]{fi, k});
            }
        }

        // Allocate one top vertex per unique original vid that's incident to a selected face.
        Map<Integer, Integer> origVidToTopDense = new HashMap<>();
        ArrayList<Float> extraPos = new ArrayList<>();
        int nextVid = origVertCount;

        for (Map.Entry<Integer, List<int[]>> e : vertIncidence.entrySet()) {
            int origVid = e.getKey();
            List<int[]> uses = e.getValue();
            // Average surface normal across all incident selected faces.
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
            if (len > 1e-8f) avgN.mul(1f / len);

            Vector3f p = in.vertexPosition(origVid, new Vector3f());
            extraPos.add(p.x + avgN.x * offset);
            extraPos.add(p.y + avgN.y * offset);
            extraPos.add(p.z + avgN.z * offset);
            origVidToTopDense.put(origVid, nextVid++);
        }

        // Seed original edge handles.
        Map<Long, float[]> dh = new HashMap<>();
        seedOriginalEdgeHandles(in, hStart, hEnd, oldToDense, dh);

        // For each selected face: add handles for its 4 top-face edges (copied
        // from bottom). Track faces for output assembly below.
        int[][] topVids = new int[origFaceCount][];
        for (int fi = 0; fi < origFaceCount; fi++) {
            if (!selected[fi]) continue;
            int fid = in.faceIdAt(fi);
            int[] tops = new int[4];
            for (int k = 0; k < 4; k++) {
                int vid = in.faceVertexAt(fid, k);
                tops[k] = origVidToTopDense.get(vid);
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

        // Boundary edges between selected and unselected: get side quads + zero
        // handles on the two new vertical edges.
        for (int fi = 0; fi < origFaceCount; fi++) {
            if (!selected[fi]) continue;
            int fid = in.faceIdAt(fi);
            for (int k = 0; k < 4; k++) {
                int origA = in.faceVertexAt(fid, k);
                int origB = in.faceVertexAt(fid, (k + 1) % 4);
                int eid = in.faceEdgeAt(fid, k);
                if (isBoundaryEdge(in, eid, selected)) {
                    int denseA = oldToDense.get(origA);
                    int denseB = oldToDense.get(origB);
                    int topA = origVidToTopDense.get(origA);
                    int topB = origVidToTopDense.get(origB);
                    dh.put(CoonsHandleBuilder.dirPack(denseA, topA), new float[3]);
                    dh.put(CoonsHandleBuilder.dirPack(topA, denseA), new float[3]);
                    dh.put(CoonsHandleBuilder.dirPack(denseB, topB), new float[3]);
                    dh.put(CoonsHandleBuilder.dirPack(topB, denseB), new float[3]);
                }
            }
        }

        return buildOutputRegion(in, origVertCount, origPos, extraPos, selected, topVids,
                origVidToTopDense, oldToDense, dh);
    }

    // ----------------------------------------------------------------------
    // Shared helpers
    // ----------------------------------------------------------------------

    /** Passes topology through unchanged. Used for selection=empty / offset=0. */
    private static ExtrudeResult passThrough(MeshTopology in, float[] hStart, float[] hEnd) {
        int vn = in.vertexCount();
        float[] positions = new float[vn * 3];
        Vector3f tmp = new Vector3f();
        Map<Integer, Integer> oldToDense = new HashMap<>();
        for (int i = 0; i < vn; i++) {
            int vid = in.vertexIdAt(i);
            oldToDense.put(vid, i);
            in.vertexPosition(vid, tmp);
            positions[i * 3] = tmp.x;
            positions[i * 3 + 1] = tmp.y;
            positions[i * 3 + 2] = tmp.z;
        }
        int fn = in.faceCount();
        int vpf = fn == 0 ? 4 : in.faceVertexCount(in.faceIdAt(0));
        int[] faceIdx = new int[fn * vpf];
        int w = 0;
        for (int fi = 0; fi < fn; fi++) {
            int fid = in.faceIdAt(fi);
            int fvc = in.faceVertexCount(fid);
            for (int k = 0; k < vpf; k++) {
                faceIdx[w++] = k < fvc ? oldToDense.get(in.faceVertexAt(fid, k)) : 0;
            }
        }
        HalfEdgeMesh out = HalfEdgeMesh.bulkAllocate(positions, faceIdx, vpf);
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
            int o = eid * 3;
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
        int o = eid * 3;
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
        int totalVerts = origVertCount + extraPos.size() / 3;
        float[] positions = new float[totalVerts * 3];
        System.arraycopy(origPos, 0, positions, 0, origPos.length);
        for (int i = 0; i < extraPos.size(); i++) {
            positions[origPos.length + i] = extraPos.get(i);
        }

        int origFaceCount = in.faceCount();
        // Each unselected face: 1 face. Each selected face: 1 top + 4 side walls = 5.
        int outFaceCount = 0;
        for (int fi = 0; fi < origFaceCount; fi++) {
            outFaceCount += selected[fi] ? 5 : 1;
        }
        int[] faceIdx = new int[outFaceCount * 4];
        boolean[] generated = new boolean[outFaceCount];
        int w = 0, faceW = 0;

        for (int fi = 0; fi < origFaceCount; fi++) {
            int fid = in.faceIdAt(fi);
            if (!selected[fi]) {
                int fvc = in.faceVertexCount(fid);
                for (int k = 0; k < 4; k++) {
                    faceIdx[w++] = k < fvc ? oldToDense.get(in.faceVertexAt(fid, k)) : 0;
                }
                generated[faceW++] = false;
                continue;
            }
            int[] tops = topVids[fi];
            int[] origDenseIds = {
                    oldToDense.get(in.faceVertexAt(fid, 0)),
                    oldToDense.get(in.faceVertexAt(fid, 1)),
                    oldToDense.get(in.faceVertexAt(fid, 2)),
                    oldToDense.get(in.faceVertexAt(fid, 3)),
            };
            // Top face (matches original winding — new faces go into the same slot as the original).
            faceIdx[w++] = tops[0]; faceIdx[w++] = tops[1]; faceIdx[w++] = tops[2]; faceIdx[w++] = tops[3];
            generated[faceW++] = true;
            // 4 side walls: each is (origA, origB, topB, topA) for k in 0..3.
            for (int k = 0; k < 4; k++) {
                int origA = origDenseIds[k];
                int origB = origDenseIds[(k + 1) % 4];
                int topA = tops[k];
                int topB = tops[(k + 1) % 4];
                faceIdx[w++] = origA; faceIdx[w++] = origB; faceIdx[w++] = topB; faceIdx[w++] = topA;
                generated[faceW++] = false;
            }
        }

        HalfEdgeMesh out = HalfEdgeMesh.bulkAllocate(positions, faceIdx, 4);
        out.computeNormals();
        float[][] handles = CoonsHandleBuilder.flushDirectedHandles(out, dh);
        return new ExtrudeResult(out, handles, generated);
    }

    /** Builds the output mesh for REGION mode: side walls only on boundary edges. */
    private static ExtrudeResult buildOutputRegion(
            MeshTopology in, int origVertCount, float[] origPos, ArrayList<Float> extraPos,
            boolean[] selected, int[][] topVids,
            Map<Integer, Integer> origVidToTopDense, Map<Integer, Integer> oldToDense,
            Map<Long, float[]> dh) {
        int totalVerts = origVertCount + extraPos.size() / 3;
        float[] positions = new float[totalVerts * 3];
        System.arraycopy(origPos, 0, positions, 0, origPos.length);
        for (int i = 0; i < extraPos.size(); i++) {
            positions[origPos.length + i] = extraPos.get(i);
        }

        int origFaceCount = in.faceCount();

        // Count boundary edges (each selected-face edge that borders an unselected
        // face or a mesh boundary) — each becomes one side quad, deduplicating
        // per-edge (not per-face).
        // We track edges already visited to avoid emitting the same side quad
        // twice when it borders two selected faces (shouldn't happen for true
        // boundary edges, but guard anyway).
        int[] boundarySideCount = new int[1];
        boolean[] sideEmitted = new boolean[in.edgeCount()];

        // Gather boundary edges per face with the winding from that selected face.
        record SideEdge(int origA, int origB, int topA, int topB) {}
        ArrayList<SideEdge> sides = new ArrayList<>();
        for (int fi = 0; fi < origFaceCount; fi++) {
            if (!selected[fi]) continue;
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
                int topA = origVidToTopDense.get(origA);
                int topB = origVidToTopDense.get(origB);
                sides.add(new SideEdge(denseA, denseB, topA, topB));
                boundarySideCount[0]++;
            }
        }

        // Face count: selected faces → 1 top each (replacing the original slot).
        //             unselected faces → 1 pass-through.
        //             boundary edges → 1 side quad each.
        int outFaceCount = origFaceCount + boundarySideCount[0];
        int[] faceIdx = new int[outFaceCount * 4];
        boolean[] generated = new boolean[outFaceCount];
        int w = 0, faceW = 0;

        for (int fi = 0; fi < origFaceCount; fi++) {
            int fid = in.faceIdAt(fi);
            if (selected[fi]) {
                int[] tops = topVids[fi];
                faceIdx[w++] = tops[0]; faceIdx[w++] = tops[1]; faceIdx[w++] = tops[2]; faceIdx[w++] = tops[3];
                generated[faceW++] = true;
            } else {
                int fvc = in.faceVertexCount(fid);
                for (int k = 0; k < 4; k++) {
                    faceIdx[w++] = k < fvc ? oldToDense.get(in.faceVertexAt(fid, k)) : 0;
                }
                generated[faceW++] = false;
            }
        }
        for (SideEdge se : sides) {
            faceIdx[w++] = se.origA; faceIdx[w++] = se.origB; faceIdx[w++] = se.topB; faceIdx[w++] = se.topA;
            generated[faceW++] = false;
        }

        HalfEdgeMesh out = HalfEdgeMesh.bulkAllocate(positions, faceIdx, 4);
        out.computeNormals();
        float[][] handles = CoonsHandleBuilder.flushDirectedHandles(out, dh);
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
        return !bothSelected(f1, f2, in, selected);
    }

    private static boolean bothSelected(int fid1, int fid2, MeshTopology in, boolean[] selected) {
        return isFaceSelected(fid1, in, selected) && isFaceSelected(fid2, in, selected);
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
