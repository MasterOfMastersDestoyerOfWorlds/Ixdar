package ixdar.nodegraph.nodes.modifier;

import java.util.Objects;
import java.util.List;
import java.util.Map;

import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.ops.MeshMergeByDistance;
import ixdar.geometry.mesh.data.bundle.SlotCarry;
import ixdar.nodegraph.field.FieldBroadcast;

@MeshNodeAnnotation(id = "merge_by_distance", category = NodeCategory.MODIFIERS)
public class MergeByDistanceNode implements MeshNode {
    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort DISTANCE = new InputPort("distance", PortType.FLOAT, 0.001f, 1e-6f, 1f);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

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
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input/output. Vertices within `distance` are collapsed into one at the centroid. Bezier handle slots are rebuilt against the welded mesh.",
                DISTANCE.name, "Weld threshold in world units (1e-6..1). Use 0.0001 after coons_patch to close its seam duplicates; use larger values to merge post-mirror seams."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = Objects.requireNonNullElse(ctx.getInput(GEOMETRY.name, GeometryBundle.class), GeometryBundle.empty());
        Object d = FieldBroadcast.getInputOrDefault(ctx, DISTANCE.name, DISTANCE.defaultValue);
        float dist = FieldBroadcast.floatScalarOrDefault(d, 0.001f);
        MeshTopology welded = MeshMergeByDistance.merge(base.mesh(), dist);
        ctx.setOutput(GEOMETRY.name, SlotCarry.carry(List.of(base), GeometryBundle.ofMesh(welded)));
    }
}
