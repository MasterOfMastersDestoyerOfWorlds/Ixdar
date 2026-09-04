package ixdar.geometry.mesh.nodes.modifier;

import java.util.Objects;
import java.util.List;

import ixdar.geometry.mesh.nodes.api.InputPort;

import java.util.Map;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.ArrayMeshEngine;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

@MeshNodeAnnotation(id = "solidify_mesh")
public class SolidifyMeshNode implements MeshNode {
    public static final float NUM_0_01 = 0.01f;

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
        float t = FieldBroadcast.floatScalarOrDefault(to, NUM_0_01);
        ArrayMesh am = in instanceof ArrayMesh m ? m : ArrayMeshEngine.fromUniformMeshTopology(in);
        if (!ArrayMeshEngine.isUniformQuads(am)) {
            throw new IllegalStateException("solidify_mesh requires uniform quad meshes");
        }
        ArrayMesh out = ArrayMeshEngine.solidifyUniformQuads(am, t);
        ctx.setOutput(GEOMETRY.name, base.withMesh(out));
    }
}
