package ixdar.geometry.mesh.nodes.geometry;

import java.util.HashMap;
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
import ixdar.geometry.mesh.data.ops.MeshAppend;
import ixdar.geometry.mesh.data.ops.MeshMergeByDistance;
import ixdar.geometry.mesh.nodes.data.TagGeometryNode;

import ixdar.geometry.mesh.nodes.modifier.SetBoneWeightNode;

@MeshNodeAnnotation(id = "join_geometry")
public class JoinGeometryNode implements MeshNode {
    public static final String A_2 = "a";
    public static final String B_2 = "b";
    public static final String MERGE_DISTANCE_2 = "merge_distance";
    public static final String GEOMETRY_2 = "geometry";

    private static final InputPort A = new InputPort(A_2, PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort B = new InputPort(B_2, PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort MERGE_DISTANCE = new InputPort(MERGE_DISTANCE_2, PortType.FLOAT, 0f, 0f, 1f);
    private static final OutputPort GEOMETRY = new OutputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(A, B, MERGE_DISTANCE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public String description() {
        return "Combines two geometry inputs into one mesh, merging tags and bone weights. Optionally welds nearby vertices within merge_distance.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                A_2, "First geometry bundle. Tags/weights from both are preserved in the output.",
                B_2, "Second geometry bundle.",
                MERGE_DISTANCE_2, "Weld threshold for seam vertices. 0 = no weld (a and b remain disjoint); typical 0.001 for light seam cleanup.",
                GEOMETRY_2, "Combined bundle."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle ga = GeometryBundles.bundlePart(ctx.getInput(A_2, Object.class));
        GeometryBundle gb = GeometryBundles.bundlePart(ctx.getInput(B_2, Object.class));
        MeshTopology ma = ga == null ? null : ga.mesh();
        MeshTopology mb = gb == null ? null : gb.mesh();
        if (ma == null || ma.vertexCount() == 0) {
            if (gb != null) {
                ctx.setOutput(GEOMETRY_2, gb);
            } else {
                ctx.setOutput(GEOMETRY_2, GeometryBundle.empty());
            }
            return;
        }
        if (mb == null || mb.vertexCount() == 0) {
            ctx.setOutput(GEOMETRY_2, ga);
            return;
        }
        Number mergeDist = ctx.getInput(MERGE_DISTANCE_2, Number.class);
        float md = mergeDist == null ? 0f : mergeDist.floatValue();

        MeshTopology joined = MeshAppend.join(ma, mb);
        if (md > 0f) {
            joined = MeshMergeByDistance.merge(joined, md);
        }
        GeometryBundle result = GeometryBundle.ofMesh(joined);

        int countA = ma.vertexCount();
        int countB = mb.vertexCount();
        int total = joined.vertexCount();

        // Merge tags from both inputs — offset B's vertex indices by A's count
        Map<String, boolean[]> tagsA = TagGeometryNode.getTags(ga);
        Map<String, boolean[]> tagsB = TagGeometryNode.getTags(gb);
        if (tagsA != null || tagsB != null) {
            Map<String, boolean[]> merged = new HashMap<>();

            // Copy A's tags (first countA vertices)
            if (tagsA != null) {
                for (var entry : tagsA.entrySet()) {
                    boolean[] mask = new boolean[total];
                    boolean[] src = entry.getValue();
                    System.arraycopy(src, 0, mask, 0, Math.min(src.length, countA));
                    merged.put(entry.getKey(), mask);
                }
            }

            // Merge B's tags (offset by countA)
            if (tagsB != null) {
                for (var entry : tagsB.entrySet()) {
                    boolean[] mask = merged.get(entry.getKey());
                    if (mask == null) {
                        mask = new boolean[total];
                        merged.put(entry.getKey(), mask);
                    }
                    boolean[] src = entry.getValue();
                    for (int i = 0; i < Math.min(src.length, countB); i++) {
                        mask[countA + i] = src[i];
                    }
                }
            }

            result = result.withSlot(TagGeometryNode.TAGS_SLOT, merged);
        }

        // Merge bone weight slots (float[] indexed by vertex ID)
        String prefix = SetBoneWeightNode.BONE_WEIGHT_PREFIX;
        Map<String, float[]> boneSlots = new HashMap<>();
        for (var entry : ga.slots().entrySet()) {
            if (entry.getKey().startsWith(prefix) && entry.getValue() instanceof float[] src) {
                float[] arr = new float[total];
                System.arraycopy(src, 0, arr, 0, Math.min(src.length, countA));
                boneSlots.put(entry.getKey(), arr);
            }
        }
        for (var entry : gb.slots().entrySet()) {
            if (entry.getKey().startsWith(prefix) && entry.getValue() instanceof float[] src) {
                float[] arr = boneSlots.computeIfAbsent(entry.getKey(), k -> new float[total]);
                for (int i = 0; i < Math.min(src.length, countB); i++) {
                    arr[countA + i] = src[i];
                }
            }
        }
        for (var entry : boneSlots.entrySet()) {
            result = result.withSlot(entry.getKey(), entry.getValue());
        }

        ctx.setOutput(GEOMETRY_2, result);
    }
}
