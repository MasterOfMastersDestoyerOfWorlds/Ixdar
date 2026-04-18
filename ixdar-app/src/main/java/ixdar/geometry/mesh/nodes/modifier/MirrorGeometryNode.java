package ixdar.geometry.mesh.nodes.modifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;

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
import ixdar.geometry.mesh.data.ops.MeshAppend;
import ixdar.geometry.mesh.data.ops.MeshMergeByDistance;
import ixdar.geometry.mesh.nodes.patch.AssignBezierHandlesNode;
import ixdar.geometry.mesh.nodes.patch.CoonsHandleBuilder;

/**
 * Mirrors geometry across a symmetry plane, reverses face winding on the
 * mirrored copy, and welds seam vertices. Halves work for bilaterally
 * symmetric shapes.
 */
@MeshNodeAnnotation(id = "mirror_geometry")
public class MirrorGeometryNode implements MeshNode {

    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort AXIS = new InputPort("axis", PortType.STRING, "X");
    private static final InputPort MERGE_DISTANCE = new InputPort("merge_distance", PortType.FLOAT, 0.0001f, 0f, 1f);
    private static final OutputPort GEOMETRY_OUT = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, AXIS, MERGE_DISTANCE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Mirrors geometry across a symmetry plane (X, Y, or Z axis), reverses face winding on the copy, and welds seam vertices for bilateral symmetry.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                "geometry", "Input/output. Output contains original + mirrored copy. Bezier handles are reflected across the mirror plane.",
                "axis", "Symmetry plane: X (mirror across YZ plane), Y (across XZ), Z (across XY). Default X.",
                "merge_distance", "Weld threshold for seam vertices on the symmetry plane. 0 = no weld (two disjoint halves); typical 0.0001."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput("geometry", Object.class));
        String axis = ctx.getInput("axis", String.class);
        if (axis == null) axis = "X";
        Number mdNum = ctx.getInput("merge_distance", Number.class);
        float md = mdNum == null ? 0.0001f : mdNum.floatValue();

        MeshTopology mesh = base.mesh();
        if (mesh == null || mesh.vertexCount() == 0) {
            ctx.setOutput("geometry", base);
            return;
        }

        // Build mirror transform: negate the chosen axis
        Matrix4f mirror = new Matrix4f();
        switch (axis.toUpperCase()) {
            case "Y" -> mirror.scaling(1, -1, 1);
            case "Z" -> mirror.scaling(1, 1, -1);
            default -> mirror.scaling(-1, 1, 1);
        }

        // Create mirrored copy with reversed winding
        HalfEdgeMesh mirrored = new HalfEdgeMesh();
        Vector3f p = new Vector3f();
        org.joml.Vector4f ph = new org.joml.Vector4f();
        int n = mesh.vertexCount();
        java.util.HashMap<Integer, Integer> idMap = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            int vid = mesh.vertexIdAt(i);
            mesh.vertexPosition(vid, p);
            ph.set(p.x, p.y, p.z, 1f);
            mirror.transform(ph);
            int nid = mirrored.addVertex(ph.x, ph.y, ph.z);
            idMap.put(vid, nid);
        }
        for (int fi = 0; fi < mesh.faceCount(); fi++) {
            int fid = mesh.faceIdAt(fi);
            int fc = mesh.faceVertexCount(fid);
            int[] nv = new int[fc];
            // Reverse winding to maintain consistent normals after mirror
            for (int k = 0; k < fc; k++) {
                int ov = mesh.faceVertexAt(fid, fc - 1 - k);
                nv[k] = idMap.get(ov);
            }
            mirrored.addFace(nv);
        }

        // Join original + mirrored
        HalfEdgeMesh combined = new HalfEdgeMesh();
        Matrix4f identity = new Matrix4f();
        MeshAppend.append(combined, mesh, identity);
        MeshAppend.append(combined, mirrored, identity);

        // Weld seam vertices
        MeshTopology result;
        if (md > 0f) {
            result = MeshMergeByDistance.merge(combined, md);
        } else {
            result = combined;
        }

        if (result instanceof HalfEdgeMesh hem) {
            hem.computeNormals();
        }

        // Preserve non-handle slots from input; rebuild handle slots if present.
        HashMap<String, Object> nextSlots = new HashMap<>(base.slots());
        nextSlots.remove(AssignBezierHandlesNode.SLOT_HANDLES_START);
        nextSlots.remove(AssignBezierHandlesNode.SLOT_HANDLES_END);

        if (CoonsHandleBuilder.hasHandles(base)) {
            float[][] rebuilt = rebuildMirroredHandles(base, mesh, mirrored, combined, result,
                    idMap, axis, md);
            if (rebuilt != null) {
                nextSlots.put(AssignBezierHandlesNode.SLOT_HANDLES_START, rebuilt[0]);
                nextSlots.put(AssignBezierHandlesNode.SLOT_HANDLES_END, rebuilt[1]);
            }
        }

        ctx.setOutput("geometry", new GeometryBundle(result, Map.copyOf(nextSlots)));
    }

    /**
     * Builds directed handles for the combined (pre-weld) mesh: the original
     * half uses the input's handles unchanged; the mirrored half reflects
     * handle vectors across the mirror plane AND swaps start↔end on each edge
     * because the face winding was reversed. Then flushes against the final
     * (possibly welded) output mesh via {@link CoonsHandleBuilder}.
     */
    private static float[][] rebuildMirroredHandles(
            GeometryBundle base, MeshTopology origMesh, HalfEdgeMesh mirroredMesh,
            HalfEdgeMesh combined, MeshTopology finalMesh,
            Map<Integer, Integer> origToMirroredVid, String axis, float mergeDist) {

        float[] origHS = CoonsHandleBuilder.readHandleSlot(base,
                AssignBezierHandlesNode.SLOT_HANDLES_START, origMesh);
        float[] origHE = CoonsHandleBuilder.readHandleSlot(base,
                AssignBezierHandlesNode.SLOT_HANDLES_END, origMesh);

        int axisIdx = switch (axis.toUpperCase()) {
            case "Y" -> 1;
            case "Z" -> 2;
            default -> 0;
        };

        // Temporary bundle for the combined mesh with assembled handles, so
        // we can delegate to rebuildHandlesAfterWeld for the optional weld step.
        Map<Long, float[]> dh = new HashMap<>();

        // Vertex-id translation helpers:
        //   combined's first-append IDs are sequential 0..origN-1 in the order
        //   origMesh enumerates them — which equals origToMirroredVid.get(origVid)
        //   because the mirrored mesh was constructed by the same enumeration.
        //   The second append shifts the mirrored IDs by origMesh.vertexCount().
        int origVertexOffset = origMesh.vertexCount();
        for (int ei = 0; ei < origMesh.edgeCount(); ei++) {
            int eid = origMesh.edgeIdAt(ei);
            int he = origMesh.edgeHalfEdge(eid);
            int va = origMesh.halfEdgeVertex(he);
            int vb = origMesh.halfEdgeEndVertex(he);
            int o = eid * 3;

            int origVaCombined = origToMirroredVid.get(va);
            int origVbCombined = origToMirroredVid.get(vb);
            int mirVaCombined = origVaCombined + origVertexOffset;
            int mirVbCombined = origVbCombined + origVertexOffset;

            // Original half: handles unchanged.
            dh.put(CoonsHandleBuilder.dirPack(origVaCombined, origVbCombined),
                    new float[]{origHS[o], origHS[o + 1], origHS[o + 2]});
            dh.put(CoonsHandleBuilder.dirPack(origVbCombined, origVaCombined),
                    new float[]{origHE[o], origHE[o + 1], origHE[o + 2]});

            // Mirrored half: reflect handle offset vectors across the mirror axis.
            float hsX = origHS[o], hsY = origHS[o + 1], hsZ = origHS[o + 2];
            float heX = origHE[o], heY = origHE[o + 1], heZ = origHE[o + 2];
            if (axisIdx == 0) { hsX = -hsX; heX = -heX; }
            else if (axisIdx == 1) { hsY = -hsY; heY = -heY; }
            else { hsZ = -hsZ; heZ = -heZ; }

            // Handles are keyed by directed-edge, not by winding. Register both
            // directions; the flush step picks the right side per output edge.
            dh.put(CoonsHandleBuilder.dirPack(mirVaCombined, mirVbCombined),
                    new float[]{hsX, hsY, hsZ});
            dh.put(CoonsHandleBuilder.dirPack(mirVbCombined, mirVaCombined),
                    new float[]{heX, heY, heZ});
        }

        // Flush onto the combined mesh first to get handle arrays keyed by
        // combined edge IDs. Then, if a weld was applied, rebuild against the
        // final welded mesh using the same helper merge_by_distance uses.
        float[][] combinedHandles = CoonsHandleBuilder.flushDirectedHandles(combined, dh);
        if (finalMesh == combined) {
            return combinedHandles;
        }
        return CoonsHandleBuilder.rebuildHandlesAfterWeld(
                combinedHandles[0], combinedHandles[1], combined, finalMesh, mergeDist);
    }
}
