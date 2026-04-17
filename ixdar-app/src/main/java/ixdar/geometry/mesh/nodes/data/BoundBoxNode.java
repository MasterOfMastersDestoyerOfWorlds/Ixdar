package ixdar.geometry.mesh.nodes.data;

import java.util.List;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.Vector3Value;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.MeshTopology;

@MeshNodeAnnotation(id = "bound_box")
public class BoundBoxNode implements MeshNode {

    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final OutputPort MIN = new OutputPort("min", PortType.VECTOR3);
    private static final OutputPort MAX = new OutputPort("max", PortType.VECTOR3);

    @Override
    public String description() {
        return "Computes the axis-aligned bounding box of a geometry, outputting its min and max corner vectors.";
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MIN, MAX);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        MeshTopology mesh = GeometryBundles.meshPart(ctx.getInput("geometry", Object.class));
        if (mesh == null || mesh.vertexCount() == 0) {
            ctx.setOutput("min", new Vector3Value(0f, 0f, 0f));
            ctx.setOutput("max", new Vector3Value(0f, 0f, 0f));
            return;
        }
        Vector3f min = mesh.boundsMin(new Vector3f());
        Vector3f max = mesh.boundsMax(new Vector3f());
        ctx.setOutput("min", new Vector3Value(min.x, min.y, min.z));
        ctx.setOutput("max", new Vector3Value(max.x, max.y, max.z));
    }
}
