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

        // No-op / zero inset path.
        if (selectedCount == 0 || t <= 0f) {
            HalfEdgeMesh passThrough = copyMesh(in);
            float[][] passHandles = copyHandles(in, passThrough, hStart, hEnd);
            return new InsetResult(passThrough, passHandles, new boolean[passThrough.faceCount()]);
        }

        // Assemble new mesh: original verts, then per-selected-face 4 inner verts.
        float[] origPos = new float[origVertCount * 3];
        Vector3f tmp = new Vector3f();
        for (int i = 0; i < origVertCount; i++) {
            int vid = in.vertexIdAt(i);
            in.vertexPosition(vid, tmp);
            origPos[i * 3] = tmp.x;
            origPos[i * 3 + 1] = tmp.y;
            origPos[i * 3 + 2] = tmp.z;
        }
        // Old vid → dense index so new vids are consistent even if input had id gaps.
        Map<Integer, Integer> oldToDense = new HashMap<>();
        for (int i = 0; i < origVertCount; i++) {
            oldToDense.put(in.vertexIdAt(i), i);
        }

        ArrayList<Float> extraPos = new ArrayList<>(selectedCount * 4 * 3);
        // Per-face: the 4 new inner vertex dense indices in face-winding order.
        int[][] innerVids = new int[origFaceCount][];

        // Directed handle map shared by all faces.
        Map<Long, float[]> dh = new HashMap<>();

        // First, seed the directed-handle map with ALL original edge handles
        // (covers every edge that's kept unchanged — outer boundary of side
        // quads, and all pass-through-face edges).
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

        int nextVid = origVertCount;

        // Pass 1: for each selected face, compute 4 inner vertex positions on
        // the Coons surface and record directional handles for new edges.
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

            int d0 = oldToDense.get(v0);
            int d1 = oldToDense.get(v1);
            int d2 = oldToDense.get(v2);
            int d3 = oldToDense.get(v3);

            // Inner vertices at parameters (t, t), (1-t, t), (1-t, 1-t), (t, 1-t)
            Vector3f n0 = CoonsHandleBuilder.evalCoonsSurface(in, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3, t, t);
            Vector3f n1 = CoonsHandleBuilder.evalCoonsSurface(in, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3, 1f - t, t);
            Vector3f n2 = CoonsHandleBuilder.evalCoonsSurface(in, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3, 1f - t, 1f - t);
            Vector3f n3 = CoonsHandleBuilder.evalCoonsSurface(in, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3, t, 1f - t);

            int nd0 = nextVid++; extraPos.add(n0.x); extraPos.add(n0.y); extraPos.add(n0.z);
            int nd1 = nextVid++; extraPos.add(n1.x); extraPos.add(n1.y); extraPos.add(n1.z);
            int nd2 = nextVid++; extraPos.add(n2.x); extraPos.add(n2.y); extraPos.add(n2.z);
            int nd3 = nextVid++; extraPos.add(n3.x); extraPos.add(n3.y); extraPos.add(n3.z);
            innerVids[fi] = new int[]{nd0, nd1, nd2, nd3};

            // Inner-boundary edge handles via sub-curve approximation.
            // Each inner-boundary edge is an iso-curve segment in (u, v) space
            // with one parameter fixed. We approximate by sampling 4 points on
            // the Coons surface and using the start/end tangent direction.
            //
            // n0 → n1: v fixed at t, u goes t → (1-t). Tangent direction =
            //   S(t+ε, t) − S(t, t). Sub-curve length ≈ (1 − 2t)/3.
            //
            // n1 → n2: u fixed at (1-t), v goes t → (1-t).
            // n2 → n3: v fixed at (1-t), u goes (1-t) → t.
            // n3 → n0: u fixed at t, v goes (1-t) → t.
            addIsoCurveHandles(in, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3,
                    t, t, 1f - t, t, n0, n1, nd0, nd1, dh);   // n0→n1 along u, v=t
            addIsoCurveHandles(in, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3,
                    1f - t, t, 1f - t, 1f - t, n1, n2, nd1, nd2, dh); // n1→n2 along v, u=1-t
            addIsoCurveHandles(in, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3,
                    1f - t, 1f - t, t, 1f - t, n2, n3, nd2, nd3, dh); // n2→n3 along u reversed
            addIsoCurveHandles(in, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3,
                    t, 1f - t, t, t, n3, n0, nd3, nd0, dh);     // n3→n0 along v reversed

            // Radial edges (outer corner → inner corner): cubic bezier fit
            // using tangent samples at both ends. The path in (u, v) space is
            // a diagonal from the corner to the inner-corner parameter, so
            // tangents come from finite differences along that diagonal.
            // v0 at (0, 0) → n0 at (t, t)
            addRadialHandles(in, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3,
                    0f, 0f, t, t, d0, nd0, dh);
            // v1 at (1, 0) → n1 at (1-t, t)
            addRadialHandles(in, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3,
                    1f, 0f, 1f - t, t, d1, nd1, dh);
            // v2 at (1, 1) → n2 at (1-t, 1-t)
            addRadialHandles(in, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3,
                    1f, 1f, 1f - t, 1f - t, d2, nd2, dh);
            // v3 at (0, 1) → n3 at (t, 1-t)
            addRadialHandles(in, hStart, hEnd, v0, v1, v3, e0, e1, e2, e3,
                    0f, 1f, t, 1f - t, d3, nd3, dh);
        }

        // Pass 2: assemble output mesh topology.
        int totalVerts = origVertCount + extraPos.size() / 3;
        float[] positions = new float[totalVerts * 3];
        System.arraycopy(origPos, 0, positions, 0, origPos.length);
        for (int i = 0; i < extraPos.size(); i++) {
            positions[origVertCount * 3 + i] = extraPos.get(i);
        }

        // Face count: each unselected face contributes 1 face; each selected
        // face contributes 5 (inner quad + 4 side quads).
        int outFaceCount = 0;
        for (int fi = 0; fi < origFaceCount; fi++) {
            outFaceCount += selected[fi] ? 5 : 1;
        }
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
                    // Non-quad fallback: skip; caller shouldn't feed coons_inset
                    // non-quads anyway. Fill with degenerate so array lines up.
                    for (int k = 0; k < 4; k++) faceIdx[w++] = 0;
                    generated[faceWriteIdx++] = false;
                }
                continue;
            }
            int v0 = in.faceVertexAt(fid, 0);
            int v1 = in.faceVertexAt(fid, 1);
            int v2 = in.faceVertexAt(fid, 2);
            int v3 = in.faceVertexAt(fid, 3);
            int d0 = oldToDense.get(v0);
            int d1 = oldToDense.get(v1);
            int d2 = oldToDense.get(v2);
            int d3 = oldToDense.get(v3);
            int[] ni = innerVids[fi];
            int nd0 = ni[0], nd1 = ni[1], nd2 = ni[2], nd3 = ni[3];

            // Inner face (generated — threaded into follow-up selections)
            faceIdx[w++] = nd0; faceIdx[w++] = nd1; faceIdx[w++] = nd2; faceIdx[w++] = nd3;
            generated[faceWriteIdx++] = true;

            // Side quads: each bridges an outer edge to its shrunk inner parallel.
            // v0→v1 / nd1→nd0  becomes  v0, v1, nd1, nd0  (CCW in face winding)
            faceIdx[w++] = d0; faceIdx[w++] = d1; faceIdx[w++] = nd1; faceIdx[w++] = nd0;
            generated[faceWriteIdx++] = false;
            faceIdx[w++] = d1; faceIdx[w++] = d2; faceIdx[w++] = nd2; faceIdx[w++] = nd1;
            generated[faceWriteIdx++] = false;
            faceIdx[w++] = d2; faceIdx[w++] = d3; faceIdx[w++] = nd3; faceIdx[w++] = nd2;
            generated[faceWriteIdx++] = false;
            faceIdx[w++] = d3; faceIdx[w++] = d0; faceIdx[w++] = nd0; faceIdx[w++] = nd3;
            generated[faceWriteIdx++] = false;
        }

        HalfEdgeMesh outMesh = HalfEdgeMesh.bulkAllocate(positions, faceIdx, 4);
        outMesh.computeNormals();

        float[][] handles = CoonsHandleBuilder.flushDirectedHandles(outMesh, dh);
        return new InsetResult(outMesh, handles, generated);
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
