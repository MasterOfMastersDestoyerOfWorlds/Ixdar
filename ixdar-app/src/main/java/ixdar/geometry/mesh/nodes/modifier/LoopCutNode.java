package ixdar.geometry.mesh.nodes.modifier;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.ArrayMeshEngine;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;
import ixdar.geometry.mesh.nodes.patch.CoonsHandleBuilder;
import ixdar.geometry.mesh.nodes.patch.CoonsLoopCutNode;

/**
 * Adds edge loops along a world-space axis. Splits each quad face's edges
 * aligned with the axis, producing {@code cuts+1} strip quads per face.
 * Faces with no aligned edges pass through unchanged.
 * <p>
 * If the input geometry bundle carries bezier handle slots
 * ({@code _bezier_handles_start} from {@code assign_bezier_handles}), the cut
 * is performed via exact de Casteljau subdivision so new vertices land on the
 * original bezier curves and handles survive for downstream {@code coons_patch}
 * consumption. Otherwise a straight midpoint cut is used.
 */
@MeshNodeAnnotation(id = "loop_cut")
public class LoopCutNode implements MeshNode {

    private static final InputPort MESH_IN = new InputPort("mesh", PortType.MESH, null);
    private static final InputPort GEOMETRY_IN = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort CUTS = new InputPort("cuts", PortType.INT, 1, 1f, 32f);
    private static final InputPort AXIS = new InputPort("axis", PortType.STRING, "Z");
    private static final OutputPort MESH_OUT = new OutputPort("mesh", PortType.MESH);
    private static final OutputPort GEOMETRY_OUT = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(MESH_IN, GEOMETRY_IN, CUTS, AXIS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH_OUT, GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Inserts edge loops along a world-space axis (X, Y, or Z), splitting aligned quad faces to add resolution where needed.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                "mesh", "Topology-only input (alternative to geometry). Used when no bundle slots need preservation.",
                "geometry", "Geometry bundle input/output. If it carries bezier handles, de Casteljau subdivision is used and handles survive the cut.",
                "cuts", "Number of new edge loops to insert, 1..32. Each cut subdivides aligned edges.",
                "axis", "World-space axis the new loops run PERPENDICULAR to — i.e. cuts=1, axis=X inserts one loop across the X direction. Accepted: X, Y, Z."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle bundle = null;
        MeshTopology mesh = null;
        Object geoObj = ctx.getInputValue("geometry");
        if (geoObj != null) {
            bundle = GeometryBundles.requireBundle(geoObj);
            mesh = bundle.mesh();
        }
        if (mesh == null) {
            mesh = ctx.getInput("mesh", MeshTopology.class);
        }
        if (mesh == null || mesh.vertexCount() == 0) {
            ctx.setOutput("mesh", null);
            ctx.setOutput("geometry", GeometryBundle.empty());
            return;
        }

        Object cutsObj = FieldBroadcast.getInputOrDefault(ctx, "cuts", CUTS.defaultValue());
        int cuts = FieldBroadcast.intScalarOrDefault(cutsObj, 1);

        String axisStr = String.valueOf(ctx.getInputValue("axis"));
        if (axisStr == null || axisStr.isEmpty()) axisStr = "Z";

        // Curve-preserving path: if the input bundle carries bezier handle slots,
        // use exact de Casteljau subdivision via CoonsLoopCutNode so new vertices
        // land on the original bezier curves and handles survive the cut.
        if (bundle != null && CoonsHandleBuilder.hasHandles(bundle)) {
            GeometryBundle out = CoonsLoopCutNode.loopCut(bundle, axisStr, cuts);
            ctx.setOutput("mesh", out.mesh());
            ctx.setOutput("geometry", out);
            return;
        }

        int axisIndex = switch (axisStr.toUpperCase()) {
            case "X" -> 0;
            case "Y" -> 1;
            default -> 2; // Z
        };

        ArrayMesh am = mesh instanceof ArrayMesh m ? m : ArrayMeshEngine.fromUniformMeshTopology(mesh);
        ArrayMesh result = ArrayMeshEngine.loopCutAxis(am, cuts, axisIndex);

        ctx.setOutput("mesh", result);
        ctx.setOutput("geometry", bundle != null ? bundle.withMesh(result) : GeometryBundle.ofMesh(result));
    }
}
