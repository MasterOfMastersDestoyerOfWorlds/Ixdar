package ixdar.geometry.mesh.nodes.data;

import java.util.List;

import org.joml.Vector3f;

import java.util.Map;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.nodes.api.Vector3Value;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.MeshTopology;

@MeshNodeAnnotation(id = "bound_box")
public class BoundBoxNode implements MeshNode {
    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final OutputPort MIN = new OutputPort("min", PortType.VECTOR3);
    public static final OutputPort MAX = new OutputPort("max", PortType.VECTOR3);

    @Override
    public String description() {
        return "Computes the axis-aligned bounding box of a geometry, outputting its min and max corner vectors.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input geometry bundle to measure.",
                MIN.name, "Lower corner: <min_x, min_y, min_z>.",
                MAX.name, "Upper corner: <max_x, max_y, max_z>. Extent = max - min."
        );
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
        MeshTopology mesh = GeometryBundles.meshPart(ctx.getInput(GEOMETRY.name, GeometryBundle.class));
        if (mesh == null || mesh.vertexCount() == 0) {
            ctx.setOutput(MIN.name, new Vector3Value(0f, 0f, 0f));
            ctx.setOutput(MAX.name, new Vector3Value(0f, 0f, 0f));
            return;
        }
        Vector3f min = mesh.boundsMin(new Vector3f());
        Vector3f max = mesh.boundsMax(new Vector3f());
        ctx.setOutput(MIN.name, new Vector3Value(min.x, min.y, min.z));
        ctx.setOutput(MAX.name, new Vector3Value(max.x, max.y, max.z));
    }
}
