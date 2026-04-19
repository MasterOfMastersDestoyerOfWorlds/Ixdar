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
import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.ArrayMeshEngine;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;
import ixdar.geometry.mesh.nodes.patch.AssignBezierHandlesNode;
import ixdar.geometry.mesh.nodes.patch.CoonsHandleBuilder;

/**
 * Insets selected faces by creating a smaller inner face connected to the
 * original boundary by side quads.
 * <p>
 * For each selected face, creates inner vertices by lerping each vertex
 * toward the face center. The original face is replaced by the inner face,
 * and side quads connect the original boundary to the inner boundary.
 * All-quad topology is preserved when input is all-quad.
 */
@MeshNodeAnnotation(id = "inset_faces")
public class InsetFacesNode implements MeshNode {

    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort INSET = new InputPort("inset", PortType.FLOAT, 0.1f, 0f, 1f);
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
        return "Insets selected faces by creating a smaller inner face connected to the original boundary by side quads, useful for preparing faces for extrusion.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                "geometry", "Input/output cage. Preserves bezier handle slots via rebuild when _bezier_handle_weight is set.",
                "inset", "Inset amount in [0, 1] — fraction of the way from each corner toward the face centroid. 0 = no inset; 0.5 = halfway.",
                "selection", "Per-face BOOLEAN mask. True = face gets inset (replaced by inner quad + 4 side quads).",
                "mesh", "Topology-only output.",
                "generated", "Per-output-face BOOLEAN: true for the newly-created inner face of each inset; false for pass-through and side quads. Thread into the selection of the next op to chain features."
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

        Object insetObj = FieldBroadcast.getInputOrDefault(ctx, "inset", INSET.defaultValue());
        float inset = FieldBroadcast.floatScalarOrDefault(insetObj, 0.1f);

        Object selObj = FieldBroadcast.getInputOrDefault(ctx, "selection", SELECTION.defaultValue());

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

        ctx.setOutput("mesh", out);
        ctx.setOutput("geometry", outBundle);
        ctx.setOutput("generated", new BoolField(genMask));
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
            int o = eid * 3;
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
     * Quad-only fast path that merges inner verts along cage edges shared by
     * two selected faces (MESH-46). Two adjacent selected faces produce a
     * topologically connected inset region: their inner quads become
     * edge-adjacent along the shared cage edge, replacing the two per-face
     * side quads that would otherwise sit across it.
     *
     * <p>Merge position: {@code P_v + (P_other - P_v) * inset} along the shared
     * cage edge from each 2-face-corner endpoint — a straight lerp, consistent
     * with this node's flat-lerp semantics.
     *
     * <p>Three-or-more cage-vertex corners (where 3+ selected faces meet) are
     * <em>not</em> merged in this pass; each face keeps its face-local
     * centroid-lerp inner vert at those corners. See MESH-47 follow-up.
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
            int fb = fi * 4;
            for (int k = 0; k < 4; k++) {
                int vid = srcFaces[fb + k];
                facesAtVertex.computeIfAbsent(vid, x -> new ArrayList<>()).add(new int[]{fi, k});
            }
        }

        // Only merge shared edges where BOTH endpoints are 2-face corners.
        // Partial merges (one endpoint 2-face, the other 3+) caused manifold
        // violations downstream: the side quad kept on the 3+ end had the
        // merged vert on one side and the face-local vert on the other, while
        // its neighbor's inner quad ran along the merged edge — leaving two
        // output faces claiming the same directed half-edge.
        Set<Integer> fullyMergeableEdges = new HashSet<>();
        for (int eid : sharedEdgeIds) {
            int he = topology.edgeHalfEdge(eid);
            int va = topology.halfEdgeVertex(he);
            int vb = topology.halfEdgeEndVertex(he);
            List<int[]> atA = facesAtVertex.get(va);
            List<int[]> atB = facesAtVertex.get(vb);
            if (atA != null && atA.size() == 2 && atB != null && atB.size() == 2) {
                fullyMergeableEdges.add(eid);
            }
        }

        int[][] innerVerts = new int[faceCount][];
        for (int fi = 0; fi < faceCount; fi++) {
            if (selected[fi]) innerVerts[fi] = new int[4];
        }

        // New inner-vert positions; we don't know the final count ahead of time
        // because merges reduce it below the 4*selectedCount upper bound.
        ArrayList<Float> extraPos = new ArrayList<>();
        int nextVid = vertCount;
        Set<Long> mergedEndpoint = new HashSet<>();

        // Per-face centroid cache — for face-local lerp at 1-face and 3+ corners.
        float[] centroids = new float[faceCount * 3];
        for (int fi = 0; fi < faceCount; fi++) {
            if (!selected[fi]) continue;
            int fb = fi * 4;
            float cx = 0f, cy = 0f, cz = 0f;
            for (int k = 0; k < 4; k++) {
                int vid = srcFaces[fb + k];
                cx += srcPos[vid * 3];
                cy += srcPos[vid * 3 + 1];
                cz += srcPos[vid * 3 + 2];
            }
            centroids[fi * 3] = cx * 0.25f;
            centroids[fi * 3 + 1] = cy * 0.25f;
            centroids[fi * 3 + 2] = cz * 0.25f;
        }

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
                int fidA = topology.faceIdAt(fiA);
                int fidB = topology.faceIdAt(fiB);
                int eAfwd = topology.faceEdgeAt(fidA, kA);
                int eAback = topology.faceEdgeAt(fidA, (kA + 3) % 4);
                int eBfwd = topology.faceEdgeAt(fidB, kB);
                int eBback = topology.faceEdgeAt(fidB, (kB + 3) % 4);

                int sharedEid = -1;
                for (int pass = 0; pass < 2 && sharedEid < 0; pass++) {
                    int candA = (pass == 0) ? eAfwd : eAback;
                    if (!fullyMergeableEdges.contains(candA)) continue;
                    if (candA == eBfwd || candA == eBback) {
                        sharedEid = candA;
                    }
                }

                if (sharedEid >= 0) {
                    // Merged position: lerp from this corner toward the other
                    // endpoint of the shared cage edge by fraction t.
                    int heS = topology.edgeHalfEdge(sharedEid);
                    int va = topology.halfEdgeVertex(heS);
                    int vb = topology.halfEdgeEndVertex(heS);
                    int otherVid = (va == denseVid) ? vb : va;
                    float px = srcPos[denseVid * 3]
                            + (srcPos[otherVid * 3] - srcPos[denseVid * 3]) * t;
                    float py = srcPos[denseVid * 3 + 1]
                            + (srcPos[otherVid * 3 + 1] - srcPos[denseVid * 3 + 1]) * t;
                    float pz = srcPos[denseVid * 3 + 2]
                            + (srcPos[otherVid * 3 + 2] - srcPos[denseVid * 3 + 2]) * t;
                    int newVid = nextVid++;
                    extraPos.add(px); extraPos.add(py); extraPos.add(pz);
                    innerVerts[fiA][kA] = newVid;
                    innerVerts[fiB][kB] = newVid;
                    mergedEndpoint.add(packEdgeVertex(sharedEid, denseVid));
                    merged = true;
                }
            }

            if (!merged) {
                // Face-local: lerp corner toward face centroid by t.
                for (int[] pair : atV) {
                    int fi = pair[0], k = pair[1];
                    float ox = srcPos[denseVid * 3];
                    float oy = srcPos[denseVid * 3 + 1];
                    float oz = srcPos[denseVid * 3 + 2];
                    float cx = centroids[fi * 3];
                    float cy = centroids[fi * 3 + 1];
                    float cz = centroids[fi * 3 + 2];
                    int newVid = nextVid++;
                    extraPos.add(ox + (cx - ox) * t);
                    extraPos.add(oy + (cy - oy) * t);
                    extraPos.add(oz + (cz - oz) * t);
                    innerVerts[fi][k] = newVid;
                }
            }
        }

        // Shared cage edges with BOTH endpoints merged → drop their 2 side quads.
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
        int droppedSideQuads = 2 * droppedSharedEdges.size();

        int selectedCount = 0;
        for (boolean s : selected) if (s) selectedCount++;
        int outV = vertCount + extraPos.size() / 3;
        int outF = faceCount + selectedCount * 4 - droppedSideQuads;
        float[] outPos = new float[outV * 3];
        int[] outFaces = new int[outF * 4];

        System.arraycopy(srcPos, 0, outPos, 0, vertCount * 3);
        for (int i = 0; i < extraPos.size(); i++) {
            outPos[vertCount * 3 + i] = extraPos.get(i);
        }

        // Replace each selected face's slot with its inner quad; keep unselected
        // face slots as pass-through.
        int fWrite = 0;
        for (int fi = 0; fi < faceCount; fi++) {
            int fo = fWrite * 4;
            if (selected[fi]) {
                int[] iv = innerVerts[fi];
                outFaces[fo] = iv[0];
                outFaces[fo + 1] = iv[1];
                outFaces[fo + 2] = iv[2];
                outFaces[fo + 3] = iv[3];
            } else {
                int fb = fi * 4;
                outFaces[fo] = srcFaces[fb];
                outFaces[fo + 1] = srcFaces[fb + 1];
                outFaces[fo + 2] = srcFaces[fb + 2];
                outFaces[fo + 3] = srcFaces[fb + 3];
            }
            fWrite++;
        }

        // Side quads: one per cage edge of each selected face, skipping shared
        // edges where both endpoints merged (inner quads are now edge-adjacent).
        for (int fi = 0; fi < faceCount; fi++) {
            if (!selected[fi]) continue;
            int fid = topology.faceIdAt(fi);
            int[] iv = innerVerts[fi];
            int fb = fi * 4;
            for (int k = 0; k < 4; k++) {
                int eid = topology.faceEdgeAt(fid, k);
                if (droppedSharedEdges.contains(eid)) continue;
                int next = (k + 1) & 3;
                int fo = fWrite * 4;
                outFaces[fo] = srcFaces[fb + k];
                outFaces[fo + 1] = srcFaces[fb + next];
                outFaces[fo + 2] = iv[next];
                outFaces[fo + 3] = iv[k];
                fWrite++;
            }
        }

        ArrayMesh out = new ArrayMesh(outPos, null, outFaces, 4);
        out.computeNormals();
        return out;
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

        if (selectedCount == 0 || inset <= 0f) {
            return new ArrayMesh(srcPos, null, srcFaces, vpf);
        }

        // Fast path: uniform quad input. Same cage-vertex-keyed merge scheme
        // as CoonsInsetFacesNode (MESH-45) but using straight-line lerps along
        // the shared cage edge rather than Coons surface evaluations, since
        // plain inset_faces is flat-lerp by design.
        if (vpf == 4) {
            return insetFacesQuadWithSharedEdgeMerge(topology, srcPos, srcFaces,
                    vertCount, faceCount, selected, Math.min(inset, 1f));
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
            out.addVertex(srcPos[vi * 3], srcPos[vi * 3 + 1], srcPos[vi * 3 + 2]);
        }

        Vector3f center = new Vector3f();
        int[][] faceInnerVerts = new int[faceCount][];

        for (int fi = 0; fi < faceCount; fi++) {
            if (!selected[fi]) continue;

            center.set(0f, 0f, 0f);
            for (int k = 0; k < vpf; k++) {
                int vid = srcFaces[fi * vpf + k];
                center.add(srcPos[vid * 3], srcPos[vid * 3 + 1], srcPos[vid * 3 + 2]);
            }
            center.div(vpf);

            int[] innerVerts = new int[vpf];
            for (int k = 0; k < vpf; k++) {
                int vid = srcFaces[fi * vpf + k];
                float ox = srcPos[vid * 3];
                float oy = srcPos[vid * 3 + 1];
                float oz = srcPos[vid * 3 + 2];
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
