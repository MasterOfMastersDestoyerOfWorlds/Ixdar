package ixdar.geometry.mesh.nodes.geometry;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.ModeConstraint;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.csg.BooleanOperation;
import ixdar.geometry.mesh.csg.MeshBooleanResult;
import ixdar.geometry.mesh.csg.QuadTriangulation;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;
import ixdar.platform.Platforms;

/**
 * Exact boolean (CSG) union, difference or intersect of two meshes.
 *
 * <p>Quads are split along their shorter diagonal before the solve, and the output bundle carries
 * per-face provenance so a later stage can keep the untouched quads of both inputs.
 *
 * <p>See also: NHE*19 Section 3.1
 */
@MeshNodeAnnotation(id = "mesh_boolean", desktopOnly = true)
public class MeshBooleanNode implements MeshNode {
    public static final String DIFFERENCE = "DIFFERENCE";
    public static final String UNION = "UNION";
    public static final String INTERSECT = "INTERSECT";
    /** Bundle slot holding the operand each output face came from, one entry per face. */
    public static final String FACE_ORIGIN_SLOT = "boolean_face_origin";

    /** Bundle slot holding the source face id per output face, {@code -1} where the face is new. */
    public static final String FACE_SOURCE_QUAD_SLOT = "boolean_face_source_quad";

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
                GEOMETRY.name, "Result as a geometry bundle, with per-face provenance in the"
                        + " boolean_face_origin and boolean_face_source_quad slots."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle bundleA = GeometryBundles.bundlePart(ctx.getInput(MESH_A.name, Object.class));
        GeometryBundle bundleB = GeometryBundles.bundlePart(ctx.getInput(MESH_B.name, Object.class));

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
                new QuadTriangulation(bundleA.mesh()).build(),
                new QuadTriangulation(bundleB.mesh()).build(),
                operation);

        ctx.setOutput(GEOMETRY.name, bundleA.withMesh(result.mesh)
                .withSlot(FACE_ORIGIN_SLOT, result.faceOrigin)
                .withSlot(FACE_SOURCE_QUAD_SLOT, result.faceSourceQuad));
    }
}
