package ixdar.geometry.mesh.nodes.modifier;
import java.util.Arrays;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import java.util.HashSet;
import java.util.Map;

import org.joml.Vector3f;

import java.util.Set;

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
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;
import ixdar.geometry.mesh.nodes.patch.AssignBezierHandlesNode;
import ixdar.geometry.mesh.nodes.patch.CoonsHandleBuilder;

/**
 * Extrudes selected faces of a mesh along their normals.
 * <p>
 * Supports two modes:
 * <ul>
 *   <li><b>INDIVIDUAL</b> (default, region=false): each face extrudes independently</li>
 *   <li><b>REGION</b> (region=true): adjacent selected faces share extruded vertices;
 *       side walls are only created on boundary edges between selected and unselected faces</li>
 * </ul>
 */
@MeshNodeAnnotation(id = "extrude_mesh")
public class ExtrudeMeshNode implements MeshNode {
    public static final String GEOMETRY_2 = "geometry";
    public static final String OFFSET_2 = "offset";
    public static final String SELECTION_2 = "selection";
    public static final String REGION_2 = "region";
    public static final String MESH = "mesh";
    public static final String GENERATED = "generated";
    public static final float NUM_0_1 = 0.1f;
    public static final int NUM_3 = 3;
    public static final float NUM_0 = 0f;
    public static final int NUM_4 = 4;
    public static final float NUM_1e_8 = 1e-8f;

    private static final InputPort GEOMETRY = new InputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort OFFSET = new InputPort(OFFSET_2, PortType.FLOAT, 0.1f, -10f, 10f);
    private static final InputPort SELECTION = new InputPort(SELECTION_2, PortType.BOOLEAN, true);
    private static final InputPort REGION = new InputPort(REGION_2, PortType.BOOLEAN, false);
    private static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE);
    private static final OutputPort MESH_OUT = new OutputPort(MESH, PortType.MESH);
    private static final OutputPort GENERATED_OUT = new OutputPort(GENERATED, PortType.BOOLEAN);

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
        return "Extrudes selected faces along their normals by an offset distance, with individual or region mode for controlling whether adjacent faces share extruded vertices.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY_2, "Input/output cage. Preserves bezier handle slots via rebuild when _bezier_handle_weight is set.",
                OFFSET_2, "Distance to push extruded faces along the (averaged) face normal. Positive = outward, negative = inward.",
                SELECTION_2, "Per-face BOOLEAN mask (or scalar). True = face gets extruded.",
                REGION_2, "If true, adjacent selected faces share extruded vertices — single extruded region. If false (default), each face extrudes independently (cheese-grater).",
                MESH, "Topology-only output.",
                GENERATED, "Per-output-face BOOLEAN: true for the newly-created top face of each extrusion; false for pass-through and side walls. Thread into the selection of the next op to chain features."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput(GEOMETRY_2, Object.class));
        MeshTopology in = base.mesh();
        if (in == null || in.vertexCount() == 0) {
            ctx.setOutput(MESH, null);
            ctx.setOutput(GEOMETRY_2, GeometryBundle.empty());
            ctx.setOutput(GENERATED, new BoolField(new boolean[0]));
            return;
        }

        Object offObj = FieldBroadcast.getInputOrDefault(ctx, OFFSET_2, OFFSET.defaultValue());
        float offset = FieldBroadcast.floatScalarOrDefault(offObj, NUM_0_1);

        Object selObj = FieldBroadcast.getInputOrDefault(ctx, SELECTION_2, SELECTION.defaultValue());
        Object regObj = FieldBroadcast.getInputOrDefault(ctx, REGION_2, REGION.defaultValue());
        boolean region = FieldBroadcast.boolAt(regObj, 0, false);

        ArrayMesh am = in instanceof ArrayMesh m ? m : ArrayMeshEngine.fromUniformMeshTopology(in);
        am.computeNormals();

        // ExtrusionResult carries the output mesh, the new→original vertex-id
        // map (for handle preservation and 'generated' classification), and the
        // per-output-face selection mask of which faces were replaced (= top
        // faces, = "generated" faces). 'selected' has length am.faceCount()
        // since top faces occupy those indices; side walls follow after.
        ExtrusionResult er = region
                ? extrudeRegion(am, offset, selObj)
                : extrudeFacesIndividual(am, offset, selObj);
        MeshTopology out = er.mesh;

        // Build 'generated' per-output-face mask: selected faces at indices
        // 0..faceCount-1 (now the top faces) are generated; everything beyond
        // is side walls (not generated).
        int outFaceCount = out == null ? 0 : out.faceCount();
        boolean[] genMask = new boolean[outFaceCount];
        int origFaceCount = am.faceCount();
        for (int fi = 0; fi < Math.min(origFaceCount, outFaceCount); fi++) {
            genMask[fi] = er.selected[fi];
        }

        GeometryBundle outBundle = base.withMesh(out);

        // Handle preservation — two paths:
        //   1. If the input carries the _bezier_handle_weight slot (stored by
        //      assign_bezier_handles), rebuild handles for every output edge
        //      via AssignBezierHandlesNode.computeHandles. This gives globally
        //      consistent handles: adjacent cage faces share an edge with
        //      identical handle data, so coons_patch produces a seamless
        //      surface across chained inset/extrude operations.
        //   2. Legacy fallback: copy handles edge-by-edge. Only used if the
        //      input has handles but no weight slot (e.g. handles written by
        //      hand or by a not-yet-updated upstream node).
        if (out != null && CoonsHandleBuilder.hasHandles(base)) {
            Object w = base.slots().get(AssignBezierHandlesNode.SLOT_WEIGHT);
            if (w instanceof Number num) {
                outBundle = AssignBezierHandlesNode.computeHandles(outBundle, num.floatValue());
            } else if (er.newToOrig != null) {
                outBundle = preserveHandles(base, in, out, er.newToOrig, outBundle);
            }
        }

        ctx.setOutput(MESH, out);
        ctx.setOutput(GEOMETRY_2, outBundle);
        ctx.setOutput(GENERATED, new BoolField(genMask));
    }

    /**
     * For each output edge, classify it and stash the appropriate directed
     * handle entry. Flush to output handle arrays.
     */
    private static GeometryBundle preserveHandles(GeometryBundle base, MeshTopology inMesh,
            MeshTopology outMesh, int[] newToOrig, GeometryBundle outBundle) {
        float[] inHS = CoonsHandleBuilder.readHandleSlot(base,
                AssignBezierHandlesNode.SLOT_HANDLES_START, inMesh);
        float[] inHE = CoonsHandleBuilder.readHandleSlot(base,
                AssignBezierHandlesNode.SLOT_HANDLES_END, inMesh);

        // Index input edges by directed vertex pair → directed handle offset.
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

            // Classify endpoints.
            boolean aNew = a >= origVc;
            boolean bNew = b >= origVc;
            int origA = aNew ? newToOrig[a - origVc] : a;
            int origB = bNew ? newToOrig[b - origVc] : b;
            if (origA < 0 || origB < 0 || origA == origB) {
                continue; // vertical wall or degenerate — leave zero handles
            }
            if (aNew != bNew) {
                continue; // mixed: vertical wall, zero handles
            }

            // Look up the input edge corresponding to this pair.
            long keyAB = CoonsHandleBuilder.dirPack(origA, origB);
            long keyBA = CoonsHandleBuilder.dirPack(origB, origA);
            float[] startAB = inEdgeToStart.get(keyAB);
            float[] endAB = inEdgeToEnd.get(keyAB);
            if (startAB == null) {
                // Try reverse canonical direction.
                startAB = inEdgeToEnd.get(keyBA);
                endAB = inEdgeToStart.get(keyBA);
            }
            if (startAB == null) {
                continue; // no ancestor edge — shouldn't happen for quads
            }
            // The output edge goes a→b; its start-handle is at endpoint a
            // (corresponding to origA) and its end-handle at b (origB).
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

    // ── Individual mode (original behavior) ──────────────────────────────

    private static ExtrusionResult extrudeFacesIndividual(ArrayMesh mesh, float offset, Object selection) {
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

        if (selectedCount == 0 || offset == NUM_0) {
            return new ExtrusionResult(new ArrayMesh(srcPos, null, srcFaces, vpf), selected, new int[0]);
        }

        int newVertCount = selectedCount * vpf;
        int sideFaceCount = selectedCount * vpf;

        // newToOrig[newVid - vertCount] = origVid that this new vertex was extruded from
        int[] newToOrig = new int[newVertCount];

        // Fast path: quads only — output ArrayMesh with primitive arrays, no boxing.
        if (vpf == NUM_4) {
            int outV = vertCount + newVertCount;
            int outF = faceCount + sideFaceCount;
            float[] outPos = new float[outV * NUM_3];
            int[] outFaces = new int[outF * NUM_4];

            System.arraycopy(srcPos, 0, outPos, 0, vertCount * NUM_3);

            Vector3f faceNormal = new Vector3f();
            int[][] faceNewVerts = new int[faceCount][];
            int nextVert = vertCount;
            for (int fi = 0; fi < faceCount; fi++) {
                if (!selected[fi]) {
                    continue;
                }
                mesh.faceNormal(fi, faceNormal);
                int[] newVerts = new int[NUM_4];
                int fb = fi * NUM_4;
                for (int k = 0; k < NUM_4; k++) {
                    int origVid = srcFaces[fb + k];
                    outPos[nextVert * NUM_3] = srcPos[origVid * NUM_3] - faceNormal.x * offset;
                    outPos[nextVert * NUM_3 + 1] = srcPos[origVid * NUM_3 + 1] - faceNormal.y * offset;
                    outPos[nextVert * NUM_3 + 2] = srcPos[origVid * NUM_3 + 2] - faceNormal.z * offset;
                    newToOrig[nextVert - vertCount] = origVid;
                    newVerts[k] = nextVert++;
                }
                faceNewVerts[fi] = newVerts;
            }

            int fWrite = 0;
            for (int fi = 0; fi < faceCount; fi++) {
                int fo = fWrite * NUM_4;
                if (selected[fi]) {
                    int[] nv = faceNewVerts[fi];
                    outFaces[fo] = nv[0];
                    outFaces[fo + 1] = nv[1];
                    outFaces[fo + 2] = nv[2];
                    outFaces[fo + NUM_3] = nv[NUM_3];
                } else {
                    int fb = fi * NUM_4;
                    outFaces[fo] = srcFaces[fb];
                    outFaces[fo + 1] = srcFaces[fb + 1];
                    outFaces[fo + 2] = srcFaces[fb + 2];
                    outFaces[fo + NUM_3] = srcFaces[fb + NUM_3];
                }
                fWrite++;
            }

            for (int fi = 0; fi < faceCount; fi++) {
                if (!selected[fi]) {
                    continue;
                }
                int[] nv = faceNewVerts[fi];
                int fb = fi * NUM_4;
                for (int k = 0; k < NUM_4; k++) {
                    int next = (k + 1) & NUM_3;
                    int fo = fWrite * NUM_4;
                    outFaces[fo] = srcFaces[fb + k];
                    outFaces[fo + 1] = srcFaces[fb + next];
                    outFaces[fo + 2] = nv[next];
                    outFaces[fo + NUM_3] = nv[k];
                    fWrite++;
                }
            }

            ArrayMesh out = new ArrayMesh(outPos, null, outFaces, NUM_4);
            out.computeNormals();
            return new ExtrusionResult(out, selected, newToOrig);
        }

        // Fallback for non-quad input (sides would be quads breaking uniformity)
        HalfEdgeMesh out = new HalfEdgeMesh(
                vertCount + newVertCount, 0,
                faceCount + sideFaceCount,
                (faceCount + sideFaceCount) * vpf * 2);

        for (int vi = 0; vi < vertCount; vi++) {
            out.addVertex(srcPos[vi * NUM_3], srcPos[vi * NUM_3 + 1], srcPos[vi * NUM_3 + 2]);
        }

        Vector3f faceNormal = new Vector3f();
        int[][] faceNewVerts = new int[faceCount][];

        for (int fi = 0; fi < faceCount; fi++) {
            if (!selected[fi]) continue;
            mesh.faceNormal(fi, faceNormal);
            int[] newVerts = new int[vpf];
            for (int k = 0; k < vpf; k++) {
                int origVid = srcFaces[fi * vpf + k];
                float nx = srcPos[origVid * NUM_3] - faceNormal.x * offset;
                float ny = srcPos[origVid * NUM_3 + 1] - faceNormal.y * offset;
                float nz = srcPos[origVid * NUM_3 + 2] - faceNormal.z * offset;
                int newVid = out.addVertex(nx, ny, nz);
                newVerts[k] = newVid;
                if (newVid - vertCount < newToOrig.length) {
                    newToOrig[newVid - vertCount] = origVid;
                }
            }
            faceNewVerts[fi] = newVerts;
        }

        for (int fi = 0; fi < faceCount; fi++) {
            if (selected[fi]) {
                out.addFace(faceNewVerts[fi]);
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
            int[] newVerts = faceNewVerts[fi];
            for (int k = 0; k < vpf; k++) {
                int next = (k + 1) % vpf;
                int origA = srcFaces[fi * vpf + k];
                int origB = srcFaces[fi * vpf + next];
                int newA = newVerts[k];
                int newB = newVerts[next];
                out.addFace(origA, origB, newB, newA);
            }
        }

        out.computeNormals();
        return new ExtrusionResult(out, selected, newToOrig);
    }

    // ── Region mode ──────────────────────────────────────────────────────
    // Adjacent selected faces share extruded vertices. Side walls are only
    // created on edges where a selected face borders an unselected face
    // (or a mesh boundary).

    private static ExtrusionResult extrudeRegion(ArrayMesh mesh, float offset, Object selection) {
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

        if (selectedCount == 0 || offset == NUM_0) {
            return new ExtrusionResult(new ArrayMesh(srcPos, null, srcFaces, vpf), selected, new int[0]);
        }

        // Identify which original vertices are used by selected faces
        boolean[] vertUsedBySelected = new boolean[vertCount];
        for (int fi = 0; fi < faceCount; fi++) {
            if (!selected[fi]) continue;
            for (int k = 0; k < vpf; k++) {
                vertUsedBySelected[srcFaces[fi * vpf + k]] = true;
            }
        }

        // Compute average normal of selected faces sharing each vertex
        // for smooth offset direction
        Vector3f faceNormal = new Vector3f();
        float[] vertNormals = new float[vertCount * NUM_3]; // accumulated
        int[] vertNormalCount = new int[vertCount];

        for (int fi = 0; fi < faceCount; fi++) {
            if (!selected[fi]) continue;
            mesh.faceNormal(fi, faceNormal);
            for (int k = 0; k < vpf; k++) {
                int vi = srcFaces[fi * vpf + k];
                vertNormals[vi * NUM_3] += faceNormal.x;
                vertNormals[vi * NUM_3 + 1] += faceNormal.y;
                vertNormals[vi * NUM_3 + 2] += faceNormal.z;
                vertNormalCount[vi]++;
            }
        }

        // Normalize vertex normals
        for (int vi = 0; vi < vertCount; vi++) {
            if (vertNormalCount[vi] == 0) continue;
            float nx = vertNormals[vi * NUM_3];
            float ny = vertNormals[vi * NUM_3 + 1];
            float nz = vertNormals[vi * NUM_3 + 2];
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > NUM_1e_8) {
                vertNormals[vi * NUM_3] = nx / len;
                vertNormals[vi * NUM_3 + 1] = ny / len;
                vertNormals[vi * NUM_3 + 2] = nz / len;
            }
        }

        // Create new vertex IDs: one new vertex for each original vertex used by selected faces
        int[] vertNewId = new int[vertCount];
        Arrays.fill(vertNewId, -1);
        int newVertTotal = 0;
        for (int vi = 0; vi < vertCount; vi++) {
            if (vertUsedBySelected[vi]) {
                vertNewId[vi] = vertCount + newVertTotal;
                newVertTotal++;
            }
        }
        // newToOrig[newVid - vertCount] = origVid that this extruded vertex came from
        int[] newToOrig = new int[newVertTotal];
        for (int vi = 0; vi < vertCount; vi++) {
            if (vertNewId[vi] >= 0) {
                newToOrig[vertNewId[vi] - vertCount] = vi;
            }
        }

        // Collect boundary edges of the selection region.
        // A boundary edge is one where a selected face meets an unselected face or the mesh boundary.
        // We store the edge as (va, vb) in the winding order of the SELECTED face.
        record BoundaryEdge(int va, int vb) {}
        List<BoundaryEdge> boundaryEdges = new ArrayList<>();
        Set<Long> edgeSeen = new HashSet<>();

        // Build edge-to-faces map for adjacency lookup
        Map<Long, List<Integer>> edgeFaces = new HashMap<>();
        for (int fi = 0; fi < faceCount; fi++) {
            for (int k = 0; k < vpf; k++) {
                int va = srcFaces[fi * vpf + k];
                int vb = srcFaces[fi * vpf + ((k + 1) % vpf)];
                long key = edgeKey(va, vb, vertCount);
                edgeFaces.computeIfAbsent(key, x -> new ArrayList<>()).add(fi);
            }
        }

        // Find boundary edges from the perspective of selected faces
        for (int fi = 0; fi < faceCount; fi++) {
            if (!selected[fi]) continue;
            for (int k = 0; k < vpf; k++) {
                int va = srcFaces[fi * vpf + k];
                int vb = srcFaces[fi * vpf + ((k + 1) % vpf)];
                long key = edgeKey(va, vb, vertCount);
                if (edgeSeen.contains(key)) continue;

                List<Integer> faces = edgeFaces.get(key);
                boolean allSelected = true;
                for (int f : faces) {
                    if (!selected[f]) { allSelected = false; break; }
                }
                // Boundary: not all adjacent faces are selected, or mesh boundary (1 face)
                if (!allSelected || faces.size() == 1) {
                    edgeSeen.add(key);
                    // Store with winding from the selected face
                    boundaryEdges.add(new BoundaryEdge(va, vb));
                }
            }
        }

        int sideQuadCount = boundaryEdges.size();
        int totalFaces = faceCount + sideQuadCount;

        HalfEdgeMesh out = new HalfEdgeMesh(
                vertCount + newVertTotal, 0,
                totalFaces, totalFaces * vpf * 2);

        // Copy all original vertices
        for (int vi = 0; vi < vertCount; vi++) {
            out.addVertex(srcPos[vi * NUM_3], srcPos[vi * NUM_3 + 1], srcPos[vi * NUM_3 + 2]);
        }

        // Add new (extruded) vertices — offset along averaged normal
        // Negate normal for Ixdar's inward-facing normal convention
        for (int vi = 0; vi < vertCount; vi++) {
            if (!vertUsedBySelected[vi]) continue;
            float nx = srcPos[vi * NUM_3] - vertNormals[vi * NUM_3] * offset;
            float ny = srcPos[vi * NUM_3 + 1] - vertNormals[vi * NUM_3 + 1] * offset;
            float nz = srcPos[vi * NUM_3 + 2] - vertNormals[vi * NUM_3 + 2] * offset;
            out.addVertex(nx, ny, nz);
        }

        // Add faces: selected faces use new vertex IDs, unselected use original
        for (int fi = 0; fi < faceCount; fi++) {
            int[] vids = new int[vpf];
            for (int k = 0; k < vpf; k++) {
                int origV = srcFaces[fi * vpf + k];
                vids[k] = selected[fi] ? vertNewId[origV] : origV;
            }
            out.addFace(vids);
        }

        // Add side quads on boundary edges of the selection region.
        // The selected face originally had edge va→vb but now uses newA→newB.
        // The unselected neighbor (or mesh boundary) still has edge vb→va.
        // Side quad winding: va → vb → newB → newA
        // This creates half-edges:
        //   va→vb (twin of unselected face's vb→va)
        //   vb→newB (new wall edge)
        //   newB→newA (twin of selected face's newA→newB)
        //   newA→va (new wall edge)
        for (BoundaryEdge be : boundaryEdges) {
            int newA = vertNewId[be.va];
            int newB = vertNewId[be.vb];
            if (newA < 0 || newB < 0) continue;
            out.addFace(be.va, be.vb, newB, newA);
        }

        out.computeNormals();
        return new ExtrusionResult(out, selected, newToOrig);
    }

    private static long edgeKey(int va, int vb, int vertCount) {
        int a = Math.min(va, vb);
        int b = Math.max(va, vb);
        return (long) a * vertCount + b;
    }

    /** Result of an extrusion: mesh + metadata used for handle preservation and generated output. */
    private record ExtrusionResult(MeshTopology mesh, boolean[] selected, int[] newToOrig) {}
}
