package ixdar.geometry.mesh.nodes.data;

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

/**
 * Tags all vertices in a geometry with one or more comma-separated labels.
 * <p>
 * Tags are stored in a {@code "__tags"} slot as a {@code Map<String, boolean[]>}
 * where each key is a tag name and the value is a per-vertex membership mask.
 * A vertex can belong to multiple tags simultaneously (e.g., "arm", "hand", "thumb").
 * <p>
 * If the incoming geometry already has tags from upstream, new tags are merged
 * (existing tags are preserved, new ones are added).
 * <p>
 * Tags are best applied as a final annotation step after geometry is finalized,
 * since nodes that change vertex count (boolean, subdivide) will invalidate
 * tag arrays.
 *
 * <pre>
 * tagged = tag_geometry(geometry=thumb.geometry, tags="arm,hand,thumb")
 * </pre>
 */
@MeshNodeAnnotation(id = "tag_geometry")
public class TagGeometryNode implements MeshNode {

    public static final String TAGS_SLOT = "__tags";

    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort TAGS = new InputPort("tags", PortType.STRING, "");
    private static final OutputPort GEOMETRY_OUT = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, TAGS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput("geometry", Object.class));
        String tagsStr = ctx.getInput("tags", String.class);

        if (tagsStr == null || tagsStr.isBlank()) {
            ctx.setOutput("geometry", base);
            return;
        }

        int vertexCount = base.mesh() == null ? 0 : base.mesh().vertexCount();

        // Retrieve existing tags or start fresh
        @SuppressWarnings("unchecked")
        Map<String, boolean[]> existingTags = (Map<String, boolean[]>) base.slots().get(TAGS_SLOT);
        Map<String, boolean[]> merged = existingTags != null ? new HashMap<>(existingTags) : new HashMap<>();

        // Parse comma-separated tag names and create/merge masks
        for (String raw : tagsStr.split(",")) {
            String tag = raw.strip();
            if (tag.isEmpty()) {
                continue;
            }
            if (merged.containsKey(tag)) {
                // Tag already exists — ensure all current vertices are marked true
                boolean[] existing = merged.get(tag);
                if (existing.length == vertexCount) {
                    // Same vertex count — mark all true (this geometry belongs to the tag)
                    for (int i = 0; i < vertexCount; i++) {
                        existing[i] = true;
                    }
                } else {
                    // Vertex count changed — replace with full mask
                    merged.put(tag, allTrue(vertexCount));
                }
            } else {
                merged.put(tag, allTrue(vertexCount));
            }
        }

        ctx.setOutput("geometry", base.withSlot(TAGS_SLOT, merged));
    }

    private static boolean[] allTrue(int length) {
        boolean[] mask = new boolean[length];
        for (int i = 0; i < length; i++) {
            mask[i] = true;
        }
        return mask;
    }

    /**
     * Extract the tags map from a GeometryBundle, or null if absent.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, boolean[]> getTags(GeometryBundle bundle) {
        if (bundle == null) {
            return null;
        }
        Object slot = bundle.slots().get(TAGS_SLOT);
        return slot instanceof Map ? (Map<String, boolean[]>) slot : null;
    }
}
