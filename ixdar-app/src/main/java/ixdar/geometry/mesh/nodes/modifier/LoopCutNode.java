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

/**
 * Adds edge loops along a world-space axis. Splits each quad face's edges
 * aligned with the axis, producing {@code cuts+1} strip quads per face.
 * Faces with no aligned edges pass through unchanged.
 * <p>
 * Topology-only: straight midpoint cuts. If input carries bezier handle slots,
 * the new vertices land on the STRAIGHT EDGE MIDPOINT (not on the Bezier
 * curve) and existing handle slots are passed through unchanged — they become
 * stale for new edges. Use {@code coons_loop_cut} for curve-preserving cuts
 * on handled cages.
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
