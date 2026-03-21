package ixdar.geometry.mesh.nodes.modifier;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.RotationValue;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.MeshTopology;

@MeshNodeAnnotation(id = "instance_on_points")
public class InstanceOnPointsNode implements MeshNode {

    private static final InputPort POINTS = new InputPort("points", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort INSTANCE = new InputPort("instance", PortType.MESH, null);
    private static final InputPort ROTATION = new InputPort("rotation", PortType.ROTATION, new RotationValue(0f, 0f, 0f, 1f));
    private static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(POINTS, INSTANCE, ROTATION);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle pts = GeometryBundles.bundlePart(ctx.getInput("points", Object.class));
        MeshTopology inst = ctx.getInput("instance", MeshTopology.class);
        if (pts == null) {
            ctx.setOutput("geometry", GeometryBundle.empty());
            return;
        }
        ctx.setOutput("geometry", pts.withSlot("instance_mesh", inst));
    }
}
