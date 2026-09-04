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
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Assigns a bone weight to every vertex of each selected face, stored in the GeometryBundle slot
 * {@link #BONE_WEIGHT_PREFIX} + bone name, indexed by vertex id.
 *
 * <p>Selection uses the same {@link PortType#BOOLEAN} socket as extrude, inset and crease.
 * Repeated calls for one bone name accumulate by maximum, never by sum.
 */
@MeshNodeAnnotation(id = "set_bone_weight")
public class SetBoneWeightNode implements MeshNode {
    public static final String BONE = "bone";
    public static final String BONE_WEIGHT_PREFIX = "_bone_weight_";

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort BONE_NAME = new InputPort("bone_name", PortType.STRING, BONE);
    public static final InputPort WEIGHT = new InputPort("weight", PortType.FLOAT, 1.0f, 0f, 1f);
    public static final InputPort SELECTION = new InputPort("selection", PortType.BOOLEAN, true);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, BONE_NAME, WEIGHT, SELECTION);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Assigns a bone weight to vertices of selected faces for skeletal animation, stored per-vertex in the geometry bundle for use with apply_bone.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input/output. Per-vertex bone weights are written into a slot keyed by `bone_name`.",
                BONE_NAME.name, "Name of the bone whose weight is being written. Match this in a downstream apply_bone.",
                WEIGHT.name, "Weight value in [0, 1]. 0 = vertex unaffected; 1 = fully driven by the bone.",
                SELECTION.name, "Per-face BOOLEAN mask. All vertices of selected faces receive the weight; other vertices keep their prior weight for this bone."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = Objects.requireNonNullElse(ctx.getInput(GEOMETRY.name, GeometryBundle.class), GeometryBundle.empty());
        MeshTopology mesh = base.mesh();
        if (mesh == null || mesh.vertexCount() == 0) {
            ctx.setOutput(GEOMETRY.name, base);
            return;
        }

        Object nameObj = FieldBroadcast.getInputOrDefault(ctx, BONE_NAME.name, BONE_NAME.defaultValue);
        String boneName = nameObj instanceof String s ? s : BONE;

        Object weightObj = FieldBroadcast.getInputOrDefault(ctx, WEIGHT.name, WEIGHT.defaultValue);
        float weight = FieldBroadcast.floatScalarOrDefault(weightObj, 1.0f);

        Object selObj = FieldBroadcast.getInputOrDefault(ctx, SELECTION.name, SELECTION.defaultValue);

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

        ctx.setOutput(GEOMETRY.name, base.withSlot(slotKey, weights));
    }
}
