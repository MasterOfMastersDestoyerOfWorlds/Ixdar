package ixdar.geometry.mesh.nodes.modifier;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
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
    public static final String MESH = "mesh";
    public static final String GEOMETRY = "geometry";
    public static final String CUTS_2 = "cuts";
    public static final String AXIS_2 = "axis";
    public static final String Z = "Z";

    private static final InputPort MESH_IN = new InputPort(MESH, PortType.MESH, null);
    private static final InputPort GEOMETRY_IN = new InputPort(GEOMETRY, PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort CUTS = new InputPort(CUTS_2, PortType.INT, 1, 1f, 32f);
    private static final InputPort AXIS = new InputPort(AXIS_2, PortType.STRING, Z);
    private static final OutputPort MESH_OUT = new OutputPort(MESH, PortType.MESH);
    private static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY, PortType.GEOMETRY_BUNDLE);

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
                MESH, "Topology-only input (alternative to geometry). Used when no bundle slots need preservation.",
                GEOMETRY, "Geometry bundle input/output. If it carries bezier handles, de Casteljau subdivision is used and handles survive the cut.",
                CUTS_2, "Number of new edge loops to insert, 1..32. Each cut subdivides aligned edges.",
                AXIS_2, "World-space axis the new loops run PERPENDICULAR to — i.e. cuts=1, axis=X inserts one loop across the X direction. Accepted: X, Y, Z."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle bundle = null;
        MeshTopology mesh = null;
        Object geoObj = ctx.getInputValue(GEOMETRY);
        if (geoObj != null) {
            bundle = GeometryBundles.requireBundle(geoObj);
            mesh = bundle.mesh();
        }
        if (mesh == null) {
            mesh = ctx.getInput(MESH, MeshTopology.class);
        }
        if (mesh == null || mesh.vertexCount() == 0) {
            ctx.setOutput(MESH, null);
            ctx.setOutput(GEOMETRY, GeometryBundle.empty());
            return;
        }

        Object cutsObj = FieldBroadcast.getInputOrDefault(ctx, CUTS_2, CUTS.defaultValue());
        int cuts = FieldBroadcast.intScalarOrDefault(cutsObj, 1);

        String axisStr = String.valueOf(ctx.getInputValue(AXIS_2));
        if (axisStr == null || axisStr.isEmpty()) axisStr = Z;

        int axisIndex = switch (axisStr.toUpperCase()) {
            case "X" -> 0;
            case "Y" -> 1;
            default -> 2; // Z
        };

        ArrayMesh am = mesh instanceof ArrayMesh m ? m : ArrayMeshEngine.fromUniformMeshTopology(mesh);
        ArrayMesh result = ArrayMeshEngine.loopCutAxis(am, cuts, axisIndex);

        ctx.setOutput(MESH, result);
        ctx.setOutput(GEOMETRY, bundle != null ? bundle.withMesh(result) : GeometryBundle.ofMesh(result));
    }
}
