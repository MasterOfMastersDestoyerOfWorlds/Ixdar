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
import ixdar.geometry.mesh.data.ops.MeshMergeByDistance;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;
import ixdar.geometry.mesh.nodes.patch.AssignBezierHandlesNode;
import ixdar.geometry.mesh.nodes.patch.CoonsHandleBuilder;

@MeshNodeAnnotation(id = "merge_by_distance")
public class MergeByDistanceNode implements MeshNode {
    public static final float NUM_0_001 = 0.001f;

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort DISTANCE = new InputPort("distance", PortType.FLOAT, 0.001f, 1e-6f, 1f);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, DISTANCE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Welds vertices closer than a threshold distance into single vertices, useful for cleaning seams after joins or mirrors.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input/output. Vertices within `distance` are collapsed into one at the centroid. Bezier handle slots are rebuilt against the welded mesh.",
                DISTANCE.name, "Weld threshold in world units (1e-6..1). Use 0.0001 after coons_patch to close its seam duplicates; use larger values to merge post-mirror seams."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput(GEOMETRY.name, Object.class));
        Object d = FieldBroadcast.getInputOrDefault(ctx, DISTANCE.name, DISTANCE.defaultValue);
        float dist = FieldBroadcast.floatScalarOrDefault(d, NUM_0_001);
        MeshTopology inMesh = base.mesh();
        var outMesh = MeshMergeByDistance.merge(inMesh, dist);
        GeometryBundle out = base.withMesh(outMesh);

        // If the input carried bezier handle slots, those arrays are indexed by
        // input edge IDs — welding invalidates them. Rebuild by mapping each
        // input edge to the corresponding output edge (matched by welded
        // endpoint positions) via the shared helper.
        if (CoonsHandleBuilder.hasHandles(base) && inMesh != null && outMesh != null) {
            float[] inHS = CoonsHandleBuilder.readHandleSlot(base, AssignBezierHandlesNode.SLOT_HANDLES_START, inMesh);
            float[] inHE = CoonsHandleBuilder.readHandleSlot(base, AssignBezierHandlesNode.SLOT_HANDLES_END, inMesh);
            float[][] handles = CoonsHandleBuilder.rebuildHandlesAfterWeld(inHS, inHE, inMesh, outMesh, dist);
            if (handles != null) {
                out = out.withSlot(AssignBezierHandlesNode.SLOT_HANDLES_START, handles[0])
                        .withSlot(AssignBezierHandlesNode.SLOT_HANDLES_END, handles[1]);
            }
        }
        ctx.setOutput(GEOMETRY.name, out);
    }
}
