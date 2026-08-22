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
 * Tags all vertices with one or more comma-separated labels, stored in {@link #TAGS_SLOT} as one
 * per-vertex membership mask per tag and merged with any tags already present upstream.
 *
 * <p>Apply tags only once the vertex count is final: any later node that adds or removes vertices
 * invalidates the masks.
 */
@MeshNodeAnnotation(id = "tag_geometry")
public class TagGeometryNode implements MeshNode {
    public static final String TAGS_SLOT = "_tags";

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort TAGS = new InputPort("tags", PortType.STRING, "");
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public String description() {
        return "Annotates all vertices in a geometry with one or more comma-separated tag labels, stored as per-vertex boolean masks.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input/output bundle. Every vertex is labeled with each tag; slots are keyed by tag name.",
                TAGS.name, "Comma-separated tag labels (e.g. 'skull,cranium,face'). Downstream consumers read per-tag boolean masks from geometry slots."
        );
    }

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
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput(GEOMETRY.name, Object.class));
        String tagsStr = ctx.getInput(TAGS.name, String.class);

        if (tagsStr == null || tagsStr.isBlank()) {
            ctx.setOutput(GEOMETRY.name, base);
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

        ctx.setOutput(GEOMETRY.name, base.withSlot(TAGS_SLOT, merged));
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
     *
     * @param bundle bundle to inspect; may be {@code null}
     * @return tag-name to per-vertex boolean mask map, or {@code null} if the
     *         bundle is null or has no {@code _tags} slot of the expected type
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
