package ixdar.geometry.mesh.nodes.modifier;

import java.util.Objects;
import java.util.List;

import ixdar.nodegraph.api.InputPort;

import java.util.Map;
import ixdar.nodegraph.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.ArrayMeshEngine;
import ixdar.nodegraph.field.FieldBroadcast;

@MeshNodeAnnotation(id = "solidify_mesh", category = NodeCategory.MODIFIERS)
public class SolidifyMeshNode implements MeshNode {
    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort THICKNESS = new InputPort("thickness", PortType.FLOAT, 0.01f, 0.001f, 10f);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, THICKNESS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Gives thickness to a flat uniform-quad mesh by duplicating and offsetting it along normals, creating a watertight shell.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input/output. Flat (or near-flat) quad surface becomes a closed shell wrapped in a GeometryBundle.",
                THICKNESS.name, "Offset distance along the averaged vertex normal. 0 = no thickness; positive = outward shell."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = Objects.requireNonNullElse(ctx.getInput(GEOMETRY.name, GeometryBundle.class), GeometryBundle.empty());
        MeshTopology in = base.mesh();
        if (in == null || in.vertexCount() == 0) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            return;
        }
        Object to = FieldBroadcast.getInputOrDefault(ctx, THICKNESS.name, THICKNESS.defaultValue);
        float t = FieldBroadcast.floatScalarOrDefault(to, 0.01f);
        ArrayMesh am = in instanceof ArrayMesh m ? m : ArrayMeshEngine.fromUniformMeshTopology(in);
        if (!ArrayMeshEngine.isUniformQuads(am)) {
            throw new IllegalStateException("solidify_mesh requires uniform quad meshes");
        }
        ArrayMesh out = ArrayMeshEngine.solidifyUniformQuads(am, t);
        ctx.setOutput(GEOMETRY.name, base.withMesh(out));
    }
}
