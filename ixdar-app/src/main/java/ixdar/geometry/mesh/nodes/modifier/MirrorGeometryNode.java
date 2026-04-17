package ixdar.geometry.mesh.nodes.modifier;

import java.util.List;

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

        ctx.setOutput("geometry", GeometryBundle.ofMesh(result));
    }
}
