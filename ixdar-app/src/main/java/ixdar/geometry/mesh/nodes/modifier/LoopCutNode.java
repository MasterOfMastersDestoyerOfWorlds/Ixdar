package ixdar.geometry.mesh.nodes.modifier;

import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.ArrayMeshEngine;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Adds edge loops along a world-space axis, splitting each quad's axis-aligned edges; faces with
 * no aligned edge pass through unchanged.
 *
 * <p>Cuts land on straight edge midpoints, so on a cage carrying Bezier handle slots the new
 * vertices sit off the curve and the handles go stale — use {@code coons_loop_cut} there.
 */
@MeshNodeAnnotation(id = "loop_cut")
public class LoopCutNode implements MeshNode {
    public static final String Z = "Z";

    public static final InputPort MESH_IN = new InputPort("mesh", PortType.MESH, null);
    public static final InputPort GEOMETRY_IN = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort CUTS = new InputPort("cuts", PortType.INT, 1, 1f, 32f);
    public static final InputPort AXIS = new InputPort("axis", PortType.STRING, Z);
    public static final OutputPort MESH_OUT = new OutputPort(MESH_IN.name, PortType.MESH);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY_IN.name, PortType.GEOMETRY_BUNDLE);

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
                MESH_IN.name, "Topology-only input (alternative to geometry). Used when no bundle slots need preservation.",
                GEOMETRY_IN.name, "Geometry bundle input/output. If it carries bezier handles, de Casteljau subdivision is used and handles survive the cut.",
                CUTS.name, "Number of new edge loops to insert, 1..32. Each cut subdivides aligned edges.",
                AXIS.name, "World-space axis the new loops run PERPENDICULAR to — i.e. cuts=1, axis=X inserts one loop across the X direction. Accepted: X, Y, Z."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle bundle = null;
        MeshTopology mesh = null;
        Object geoObj = ctx.getInputValue(GEOMETRY_IN.name);
        if (geoObj != null) {
            bundle = GeometryBundles.requireBundle(geoObj);
            mesh = bundle.mesh();
        }
        if (mesh == null) {
            mesh = ctx.getInput(MESH_IN.name, MeshTopology.class);
        }
        if (mesh == null || mesh.vertexCount() == 0) {
            ctx.setOutput(MESH_IN.name, null);
            ctx.setOutput(GEOMETRY_IN.name, GeometryBundle.empty());
            return;
        }

        Object cutsObj = FieldBroadcast.getInputOrDefault(ctx, CUTS.name, CUTS.defaultValue);
        int cuts = FieldBroadcast.intScalarOrDefault(cutsObj, 1);

        String axisStr = String.valueOf(ctx.getInputValue(AXIS.name));
        if (axisStr == null || axisStr.isEmpty()) axisStr = Z;

        int axisIndex = switch (axisStr.toUpperCase()) {
            case "X" -> 0;
            case "Y" -> 1;
            default -> 2; // Z
        };

        ArrayMesh am = mesh instanceof ArrayMesh m ? m : ArrayMeshEngine.fromUniformMeshTopology(mesh);
        ArrayMesh result = ArrayMeshEngine.loopCutAxis(am, cuts, axisIndex);

        ctx.setOutput(MESH_IN.name, result);
        ctx.setOutput(GEOMETRY_IN.name, bundle != null ? bundle.withMesh(result) : GeometryBundle.ofMesh(result));
    }
}
