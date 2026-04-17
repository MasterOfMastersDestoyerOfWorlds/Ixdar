package ixdar.geometry.mesh.nodes.modifier;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.ops.MeshMergeByDistance;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

@MeshNodeAnnotation(id = "merge_by_distance")
public class MergeByDistanceNode implements MeshNode {

    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort DISTANCE = new InputPort("distance", PortType.FLOAT, 0.001f, 1e-6f, 1f);
    private static final OutputPort GEOMETRY_OUT = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, DISTANCE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Welds vertices closer than a threshold distance into single vertices, useful for cleaning seams after joins or mirrors.";
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput("geometry", Object.class));
        Object d = FieldBroadcast.getInputOrDefault(ctx, "distance", DISTANCE.defaultValue());
        float dist = FieldBroadcast.floatScalarOrDefault(d, 0.001f);
        var outMesh = MeshMergeByDistance.merge(base.mesh(), dist);
        ctx.setOutput("geometry", base.withMesh(outMesh));
    }
}
