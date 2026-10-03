package ixdar.geometry.mesh.nodes.geometry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.IntField;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.ModeConstraint;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.csg.BooleanOperation;
import ixdar.geometry.mesh.csg.MeshBooleanResult;
import ixdar.geometry.mesh.csg.QuadTriangulation;
import ixdar.geometry.mesh.data.CornerUvField;
import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MaterialData;
import ixdar.geometry.mesh.data.MaterialSet;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;
import ixdar.platform.Platforms;

/**
 * Exact boolean (CSG) union, difference or intersect of two meshes.
 *
 * <p>The output carries per-face provenance slots, and a textured operand's UVs ride through the
 * kernel as interpolated vertex properties so each face keeps its source face's material.
 *
 * <p>See also: NHE*19 Section 3.1
 */
@MeshNodeAnnotation(id = "mesh_boolean", desktopOnly = true)
public class MeshBooleanNode implements MeshNode {
    public static final String DIFFERENCE = "DIFFERENCE";
    public static final String UNION = "UNION";
    public static final String INTERSECT = "INTERSECT";
    public static final String FACE_ORIGIN_SLOT = "_boolean_face_origin";

    public static final String FACE_SOURCE_OPERAND_SLOT = "_boolean_face_source_operand";

    public static final String FACE_SOURCE_QUAD_SLOT = "_boolean_face_source_quad";

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
                        + " _boolean_face_origin, _boolean_face_source_operand and"
                        + " _boolean_face_source_quad slots, plus interpolated UVs and a material"
                        + " per face when the operands carried textures."
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

        // Operand A's per-corner, per-face and per-edge slots index a mesh the boolean replaced.
        GeometryBundle output = bundleA.withMesh(result.mesh)
                .withoutSlot(EdgeMarks.SLOT)
                .withoutSlot(CornerUvField.SLOT)
                .withoutSlot(MaterialData.SLOT)
                .withoutSlot(MaterialSet.SLOT)
                .withoutSlot(MaterialSet.FACE_MATERIAL_SLOT)
                .withSlot(FACE_ORIGIN_SLOT, new IntField(result.faceOrigin))
                .withSlot(FACE_SOURCE_OPERAND_SLOT, new IntField(result.faceSourceOperand))
                .withSlot(FACE_SOURCE_QUAD_SLOT, new IntField(result.faceSourceQuad));
        if (result.cornerU != null) {
            output = output.withSlot(CornerUvField.SLOT,
                    new CornerUvField(result.cornerU, result.cornerV));
        }

        // Every output face takes the material of the input face it was copied or cut from, so a
        // seam between two differently textured operands keeps both materials. Indexed by
        // MeshBooleanResult.ORIGIN_A and ORIGIN_B, which are 0 and 1.
        MaterialSet[] operandSet = {MaterialSet.of(bundleA), MaterialSet.of(bundleB)};
        IntField[] operandFaceMaterial =
                {MaterialSet.faceMaterialOf(bundleA), MaterialSet.faceMaterialOf(bundleB)};
        MaterialData[] operandMaterial = {MaterialData.of(bundleA), MaterialData.of(bundleB)};

        List<MaterialData> materials = new ArrayList<>();
        int[] faceMaterial = new int[result.faceSourceOperand.length];
        for (int face = 0; face < faceMaterial.length; face++) {
            int operand = result.faceSourceOperand[face];
            if (operand < 0) {
                faceMaterial[face] = MaterialSet.NO_MATERIAL;
                continue;
            }
            IntField sourceIndex = operandFaceMaterial[operand];
            int sourceFace = result.faceSourceQuad[face];
            MaterialData material = operandMaterial[operand];
            if (operandSet[operand] != null && sourceIndex != null && sourceFace >= 0
                    && sourceFace < sourceIndex.length()) {
                material = operandSet[operand].get(sourceIndex.get(sourceFace));
            }
            int index = materials.indexOf(material);
            if (material != null && index < 0) {
                index = materials.size();
                materials.add(material);
            }
            faceMaterial[face] = material == null ? MaterialSet.NO_MATERIAL : index;
        }
        if (!materials.isEmpty()) {
            output = output
                    .withSlot(MaterialSet.SLOT,
                            new MaterialSet(materials.toArray(new MaterialData[0])))
                    .withSlot(MaterialSet.FACE_MATERIAL_SLOT, new IntField(faceMaterial));
        }
        ctx.setOutput(GEOMETRY.name, output);
    }
}
