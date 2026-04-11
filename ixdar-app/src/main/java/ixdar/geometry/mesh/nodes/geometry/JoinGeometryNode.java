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
import ixdar.geometry.mesh.nodes.data.TagGeometryNode;

@MeshNodeAnnotation(id = "join_geometry")
public class JoinGeometryNode implements MeshNode {

    private static final InputPort A = new InputPort("a", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort B = new InputPort("b", PortType.GEOMETRY_BUNDLE, null);
    private static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(A, B);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle ga = GeometryBundles.bundlePart(ctx.getInput("a", Object.class));
        GeometryBundle gb = GeometryBundles.bundlePart(ctx.getInput("b", Object.class));
        MeshTopology ma = ga == null ? null : ga.mesh();
        MeshTopology mb = gb == null ? null : gb.mesh();
        if (ma == null || ma.vertexCount() == 0) {
            if (gb != null) {
                ctx.setOutput("geometry", gb);
            } else {
                ctx.setOutput("geometry", GeometryBundle.empty());
            }
            return;
        }
        if (mb == null || mb.vertexCount() == 0) {
            ctx.setOutput("geometry", ga);
            return;
        }
        var joined = MeshAppend.join(ma, mb);
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
        String prefix = ixdar.geometry.mesh.nodes.modifier.SetBoneWeightNode.BONE_WEIGHT_PREFIX;
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

        ctx.setOutput("geometry", result);
    }
}
