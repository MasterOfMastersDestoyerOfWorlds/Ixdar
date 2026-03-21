package ixdar.geometry.mesh.nodes.modifier;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.HalfEdgeMesh;

/**
 * Blender-style subdivide mesh (stub: passes mesh through unchanged; iterate with real subdivision).
 */
@MeshNodeAnnotation(id = "subdivide_mesh")
public class SubdivideMeshNode implements MeshNode {

    private static final InputPort MESH = new InputPort("mesh", PortType.MESH, null);
    private static final InputPort LEVELS = new InputPort("levels", PortType.INT, 0);
    private static final OutputPort MESH_OUT = new OutputPort("mesh", PortType.MESH);
    private static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(MESH, LEVELS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH_OUT, GEOMETRY);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        HalfEdgeMesh mesh = ctx.getInput("mesh", HalfEdgeMesh.class);
        if (mesh == null) {
            ctx.setOutput("mesh", null);
            ctx.setOutput("geometry", GeometryBundle.empty());
            return;
        }
        ctx.setOutput("mesh", mesh);
        ctx.setOutput("geometry", GeometryBundle.ofMesh(mesh));
    }
}
