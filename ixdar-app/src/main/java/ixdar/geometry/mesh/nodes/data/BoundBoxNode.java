package ixdar.geometry.mesh.nodes.data;

import java.util.List;

import org.joml.Vector3f;

import java.util.Map;

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
    public static final String GEOMETRY_2 = "geometry";
    public static final String MIN_2 = "min";
    public static final String MAX_2 = "max";

    private static final InputPort GEOMETRY = new InputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE, null);
    private static final OutputPort MIN = new OutputPort(MIN_2, PortType.VECTOR3);
    private static final OutputPort MAX = new OutputPort(MAX_2, PortType.VECTOR3);

    @Override
    public String description() {
        return "Computes the axis-aligned bounding box of a geometry, outputting its min and max corner vectors.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY_2, "Input geometry bundle to measure.",
                MIN_2, "Lower corner: <min_x, min_y, min_z>.",
                MAX_2, "Upper corner: <max_x, max_y, max_z>. Extent = max - min."
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
        MeshTopology mesh = GeometryBundles.meshPart(ctx.getInput(GEOMETRY_2, Object.class));
        if (mesh == null || mesh.vertexCount() == 0) {
            ctx.setOutput(MIN_2, new Vector3Value(0f, 0f, 0f));
            ctx.setOutput(MAX_2, new Vector3Value(0f, 0f, 0f));
            return;
        }
        Vector3f min = mesh.boundsMin(new Vector3f());
        Vector3f max = mesh.boundsMax(new Vector3f());
        ctx.setOutput(MIN_2, new Vector3Value(min.x, min.y, min.z));
        ctx.setOutput(MAX_2, new Vector3Value(max.x, max.y, max.z));
    }
}
