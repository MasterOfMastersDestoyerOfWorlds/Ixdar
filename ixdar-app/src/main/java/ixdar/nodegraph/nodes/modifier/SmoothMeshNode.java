package ixdar.nodegraph.nodes.modifier;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.ops.MeshSmoothing;
import ixdar.geometry.mesh.data.ops.MeshVertexOffset;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.field.FieldBroadcast;
import ixdar.nodegraph.field.Vector3Field;

/** Taubin lambda/mu smoothing of a mesh over its own edge adjacency. */
@MeshNodeAnnotation(id = "smooth_mesh", category = NodeCategory.MODIFIERS)
public class SmoothMeshNode implements MeshNode {
    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort PASSES = new InputPort("passes", PortType.INT, 10, 0f, 200f);
    public static final InputPort LAMBDA = new InputPort("lambda", PortType.FLOAT, 0.5f, 0f, 1f);
    public static final InputPort MU = new InputPort("mu", PortType.FLOAT, -0.53f, -1f, 0f);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, PASSES, LAMBDA, MU);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Smooths a mesh by umbrella passes over its edge adjacency, alternating a shrinking lambda and an "
                + "inflating mu (Taubin), so slivers even out without the surface shrinking.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input/output; topology and slots are kept, only positions move.",
                PASSES.name, "Lambda and mu passes counted together.",
                LAMBDA.name, "Share of the way toward the neighbours' mean each odd pass moves.",
                MU.name, "Share each even pass moves, negative and slightly larger than lambda; 0 repeats lambda "
                        + "(plain umbrella smoothing, which shrinks).");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = Objects.requireNonNullElse(ctx.getInput(GEOMETRY.name, GeometryBundle.class),
                GeometryBundle.empty());
        if (base.mesh() == null || base.mesh().vertexCount() == 0) {
            ctx.setOutput(GEOMETRY_OUT.name, base);
            return;
        }
        int passes = FieldBroadcast.intScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, PASSES.name, PASSES.defaultValue), 10);
        float lambda = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, LAMBDA.name, LAMBDA.defaultValue), 0.5f);
        float mu = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, MU.name, MU.defaultValue), -0.53f);
        float[] displacement = MeshSmoothing.umbrella(base.mesh(), lambda, mu, passes, null);
        ctx.setOutput(GEOMETRY_OUT.name, base.withMesh(MeshVertexOffset.apply(base.mesh(),
                new Vector3Field(displacement))));
    }
}
