package ixdar.geometry.mesh.nodes.modifier;

import java.util.Arrays;
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
 * Marks edges with a crease weight for semi-sharp Catmull-Clark subdivision.
 * <p>
 * Crease weights control sharpness: 0.0 = fully smooth (default),
 * 1.0+ = sharp for that many subdivision levels (weight decrements by 1
 * each level). Weight 10.0 = effectively infinitely sharp.
 * <p>
 * Stores weights as a {@code float[]} in the GeometryBundle slot
 * {@code _crease_weights}, indexed by edge ID.
 */
@MeshNodeAnnotation(id = "mark_crease")
public class MarkCreaseNode implements MeshNode {

    public static final String CREASE_WEIGHTS_SLOT = "_crease_weights";

    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort SELECTION = new InputPort("selection", PortType.BOOLEAN, true);
    private static final InputPort WEIGHT = new InputPort("weight", PortType.FLOAT, 1.0f);
    private static final InputPort FACE_BOUNDARY = new InputPort("face_boundary", PortType.BOOLEAN, false);
    private static final OutputPort GEOMETRY_OUT = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, SELECTION, WEIGHT, FACE_BOUNDARY);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput("geometry", Object.class));
        MeshTopology mesh = base.mesh();
        if (mesh == null || mesh.edgeCount() == 0) {
            ctx.setOutput("geometry", base);
            return;
        }

        Object selObj = FieldBroadcast.getInputOrDefault(ctx, "selection", SELECTION.defaultValue());
        Object weightObj = FieldBroadcast.getInputOrDefault(ctx, "weight", WEIGHT.defaultValue());
        float weight = FieldBroadcast.floatScalarOrDefault(weightObj, 1.0f);

        Object fbObj = FieldBroadcast.getInputOrDefault(ctx, "face_boundary", FACE_BOUNDARY.defaultValue());
        boolean faceBoundary = fbObj instanceof Boolean b && b;

        // Get or create crease weights array
        int maxEdgeId = 0;
        for (int i = 0; i < mesh.edgeCount(); i++) {
            maxEdgeId = Math.max(maxEdgeId, mesh.edgeIdAt(i));
        }
        float[] weights = new float[maxEdgeId + 1];

        // Copy existing crease weights if present
        Object existing = base.slots().get(CREASE_WEIGHTS_SLOT);
        if (existing instanceof float[] prev) {
            System.arraycopy(prev, 0, weights, 0, Math.min(prev.length, weights.length));
        }

        if (faceBoundary) {
            // Mark all edges of selected faces
            int faceCount = mesh.faceCount();
            for (int fi = 0; fi < faceCount; fi++) {
                boolean sel = FieldBroadcast.boolAt(selObj, fi, true);
                if (!sel) continue;
                int fid = mesh.faceIdAt(fi);
                int edgeCount = mesh.faceEdgeCount(fid);
                for (int k = 0; k < edgeCount; k++) {
                    int eid = mesh.faceEdgeAt(fid, k);
                    if (eid >= 0 && eid < weights.length) {
                        weights[eid] = Math.max(weights[eid], weight);
                    }
                }
            }
        } else {
            // Mark edges directly by edge index selection
            int edgeCount = mesh.edgeCount();
            for (int ei = 0; ei < edgeCount; ei++) {
                boolean sel = FieldBroadcast.boolAt(selObj, ei, true);
                if (!sel) continue;
                int eid = mesh.edgeIdAt(ei);
                if (eid >= 0 && eid < weights.length) {
                    weights[eid] = Math.max(weights[eid], weight);
                }
            }
        }

        ctx.setOutput("geometry", base.withSlot(CREASE_WEIGHTS_SLOT, weights));
    }
}
