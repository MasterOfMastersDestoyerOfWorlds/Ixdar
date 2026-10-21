package ixdar.nodegraph.nodes.geometry;

import java.util.List;
import java.util.Map;

import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.nodegraph.api.ModeConstraint;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.geometry.mesh.csg.BooleanOperation;
import ixdar.geometry.mesh.csg.MeshBooleanResult;
import ixdar.geometry.mesh.csg.QuadTriangulation;
import ixdar.geometry.mesh.data.bundle.CornerUvField;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.bundle.SlotCarry;
import ixdar.nodegraph.field.FieldBroadcast;
import ixdar.platform.Platforms;

/**
 * Exact boolean (CSG) union, difference or intersect of two meshes.
 *
 * <p>The output mesh carries per-face provenance layers, and a textured operand's UVs ride through the
 * kernel as interpolated vertex properties so each face keeps its source face's material.
 *
 * <p>See also: NHE*19 Section 3.1
 */
@MeshNodeAnnotation(id = "mesh_boolean", desktopOnly = true, category = NodeCategory.GEOMETRY_OPERATIONS)
public class MeshBooleanNode implements MeshNode {
    public static final String DIFFERENCE = "DIFFERENCE";
    public static final String UNION = "UNION";
    public static final String INTERSECT = "INTERSECT";

    public static final InputPort MESH_A = new InputPort("mesh_a", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort MESH_B = new InputPort("mesh_b", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort OPERATION = new InputPort("operation", PortType.STRING, DIFFERENCE,
            new ModeConstraint(DIFFERENCE, List.of(UNION, DIFFERENCE, INTERSECT), Map.of()));
    public static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(MESH_A, MESH_B, OPERATION);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public String description() {
        return "Exact CSG boolean (union, difference, or intersect) of two meshes, splitting faces"
                + " at the intersection curve and recording where each output face came from.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                MESH_A.name, "First operand (typically the base mesh).",
                MESH_B.name, "Second operand (typically the tool mesh).",
                OPERATION.name, "CSG op: UNION (A ∪ B), DIFFERENCE (A − B), INTERSECT (A ∩ B).",
                GEOMETRY.name, "Result as a geometry bundle whose mesh carries per-face provenance"
                        + " layers (source_operand, source_face, _boolean_face_origin), plus"
                        + " interpolated UVs and a material per face when the operands carried"
                        + " textures."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle bundleA = ctx.getInput(MESH_A.name, GeometryBundle.class);
        GeometryBundle bundleB = ctx.getInput(MESH_B.name, GeometryBundle.class);

        if (bundleA == null || bundleA.mesh() == null || bundleA.mesh().vertexCount() == 0) {
            ctx.setOutput(GEOMETRY.name, bundleB != null ? bundleB : GeometryBundle.empty());
            return;
        }
        if (bundleB == null || bundleB.mesh() == null || bundleB.mesh().vertexCount() == 0) {
            ctx.setOutput(GEOMETRY.name, bundleA);
            return;
        }

        Object modeInput = FieldBroadcast.getInputOrDefault(ctx, OPERATION.name, OPERATION.defaultValue);
        String mode = modeInput instanceof String text ? text.toUpperCase() : DIFFERENCE;
        BooleanOperation operation = switch (mode) {
            case UNION -> BooleanOperation.UNION;
            case INTERSECT -> BooleanOperation.INTERSECTION;
            default -> BooleanOperation.DIFFERENCE;
        };

        MeshBooleanResult result = Platforms.get().meshBooleanBackend().compute(
                new QuadTriangulation(bundleA.mesh()).build(CornerUvField.of(bundleA)),
                new QuadTriangulation(bundleB.mesh()).build(CornerUvField.of(bundleB)),
                operation);

        // The kernel interpolates the UVs across cut faces itself; every other slot follows the
        // face provenance the backend recorded on the mesh, so each face keeps its own material.
        GeometryBundle output = GeometryBundle.ofMesh(result.mesh);
        if (result.cornerU != null) {
            output = output.withSlot(CornerUvField.SLOT,
                    new CornerUvField(result.cornerU, result.cornerV));
        }
        ctx.setOutput(GEOMETRY.name, SlotCarry.carry(List.of(bundleA, bundleB), output));
    }
}
