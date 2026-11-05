package ixdar.nodegraph.nodes.modifier;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.bundle.SlotCarry;
import ixdar.geometry.mesh.data.ops.MeshProjection;
import ixdar.geometry.mesh.data.ops.MeshVertexOffset;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.field.FieldBroadcast;

@MeshNodeAnnotation(id = "shrinkwrap", category = NodeCategory.MODIFIERS)
public class ShrinkwrapMeshNode implements MeshNode {
    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort TARGET = new InputPort("target", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort OFFSET = new InputPort("offset", PortType.FLOAT, 0f, -1f, 1f);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, TARGET, OFFSET);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Moves every vertex along its normal onto the target surface, to the nearer hit forward or"
                + " backward; vertices whose normal misses the target stay where they are.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input/output. Each vertex is cast along its vertex normal both ways; topology is kept.",
                TARGET.name, "The surface projected onto, in the same coordinates as the geometry.",
                OFFSET.name, "Distance kept from the target along each vertex's normal (-1..1): positive stands"
                        + " outside it, 0 lands on it."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = Objects.requireNonNullElse(ctx.getInput(GEOMETRY.name, GeometryBundle.class),
                GeometryBundle.empty());
        GeometryBundle target = ctx.getInput(TARGET.name, GeometryBundle.class);
        MeshTopology mesh = base.mesh();
        if (mesh == null || target == null || target.mesh() == null || target.mesh().faceCount() == 0) {
            ctx.setOutput(GEOMETRY.name, base);
            return;
        }
        float keep = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, OFFSET.name, OFFSET.defaultValue), 0f);
        float[] xyz = mesh.packedPositions(null);
        float[] normalXyz = new float[xyz.length];
        Vector3f normal = new Vector3f();
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            mesh.vertexNormal(mesh.vertexIdAt(activeVertex), normal).normalize();
            normalXyz[activeVertex * 3] = normal.x;
            normalXyz[activeVertex * 3 + 1] = normal.y;
            normalXyz[activeVertex * 3 + 2] = normal.z;
        }
        MeshProjection hits = MeshProjection.along(xyz, normalXyz, target.mesh());
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            if (hits.hitFace[activeVertex] == MeshTopology.NONE) {
                continue;
            }
            float move = hits.offset[activeVertex] + keep;
            for (int axis = 0; axis < 3; axis++) {
                xyz[activeVertex * 3 + axis] += normalXyz[activeVertex * 3 + axis] * move;
            }
        }
        ctx.setOutput(GEOMETRY.name, SlotCarry.carry(List.of(base), GeometryBundle.ofMesh(
                MeshVertexOffset.atPositions(mesh, xyz))));
    }
}
