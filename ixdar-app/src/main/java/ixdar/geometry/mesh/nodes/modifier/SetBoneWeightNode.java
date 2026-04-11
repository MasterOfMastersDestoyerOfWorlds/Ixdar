package ixdar.geometry.mesh.nodes.modifier;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Assigns bone weights to vertices using standard face selections.
 * <p>
 * Reuses the same {@link PortType#BOOLEAN} selection socket as extrude, inset,
 * and crease nodes — no new tagging mechanism. For each face where selection is
 * true, all vertices of that face receive the specified weight for the named bone.
 * <p>
 * Weights are stored as {@code float[]} in the GeometryBundle slot
 * {@code _bone_weight_{boneName}}, indexed by vertex ID.
 * Multiple calls for the same bone name accumulate via max.
 */
@MeshNodeAnnotation(id = "set_bone_weight")
public class SetBoneWeightNode implements MeshNode {

    public static final String BONE_WEIGHT_PREFIX = "_bone_weight_";

    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort BONE_NAME = new InputPort("bone_name", PortType.STRING, "bone");
    private static final InputPort WEIGHT = new InputPort("weight", PortType.FLOAT, 1.0f);
    private static final InputPort SELECTION = new InputPort("selection", PortType.BOOLEAN, true);
    private static final OutputPort GEOMETRY_OUT = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, BONE_NAME, WEIGHT, SELECTION);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput("geometry", Object.class));
        MeshTopology mesh = base.mesh();
        if (mesh == null || mesh.vertexCount() == 0) {
            ctx.setOutput("geometry", base);
            return;
        }

        Object nameObj = FieldBroadcast.getInputOrDefault(ctx, "bone_name", BONE_NAME.defaultValue());
        String boneName = nameObj instanceof String s ? s : "bone";

        Object weightObj = FieldBroadcast.getInputOrDefault(ctx, "weight", WEIGHT.defaultValue());
        float weight = FieldBroadcast.floatScalarOrDefault(weightObj, 1.0f);

        Object selObj = FieldBroadcast.getInputOrDefault(ctx, "selection", SELECTION.defaultValue());

        String slotKey = BONE_WEIGHT_PREFIX + boneName;

        // Get or create per-vertex weight array
        int vertCount = mesh.vertexCount();
        float[] weights = new float[vertCount];

        // Copy existing weights for this bone if present
        Object existing = base.slots().get(slotKey);
        if (existing instanceof float[] prev) {
            System.arraycopy(prev, 0, weights, 0, Math.min(prev.length, weights.length));
        }

        // For each selected face, assign weight to all its vertices
        int faceCount = mesh.faceCount();
        for (int fi = 0; fi < faceCount; fi++) {
            boolean sel = FieldBroadcast.boolAt(selObj, fi, true);
            if (!sel) continue;
            int fid = mesh.faceIdAt(fi);
            int fvc = mesh.faceVertexCount(fid);
            for (int k = 0; k < fvc; k++) {
                int vid = mesh.faceVertexAt(fid, k);
                if (vid >= 0 && vid < weights.length) {
                    weights[vid] = Math.max(weights[vid], weight);
                }
            }
        }

        ctx.setOutput("geometry", base.withSlot(slotKey, weights));
    }
}
