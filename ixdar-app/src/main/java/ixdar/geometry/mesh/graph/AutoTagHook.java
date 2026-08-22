package ixdar.geometry.mesh.graph;

import java.util.HashMap;
import java.util.Map;

import ixdar.annotations.meshnode.BoolField;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.nodes.data.TagGeometryNode;

/**
 * Auto-tags the faces a node just generated with the DSL left-hand-side variable
 * name. Called by {@link NodeGraphRuntime} after each {@code node.evaluate(ctx)}.
 *
 * <p>Applies to any node exposing a {@link PortType#BOOLEAN} output
 * {@code "generated"} beside a {@link PortType#GEOMETRY_BUNDLE} output
 * {@code "geometry"}; the merged per-vertex mask is written to
 * {@link TagGeometryNode#TAGS_SLOT}.
 */
public final class AutoTagHook {
    public static final String GENERATED = "generated";
    public static final String GEOMETRY = "geometry";

    private AutoTagHook() {}

    /**
     * Projects the node's per-face {@code generated} mask to a per-vertex mask
     * and merges it under tag name {@code lhs} into the geometry bundle's tags.
     * No-op when the node lacks that output pair, when the mask is empty or
     * all-false, or when the output mesh is empty.
     *
     * @param node node that just finished evaluating
     * @param ctx its evaluation context (read outputs / write merged geometry)
     * @param lhs DSL left-hand-side variable name to tag with; empty/null skips
     */
    public static void applyIfApplicable(MeshNode node, NodeContext ctx, String lhs) {
        if (lhs == null || lhs.isEmpty()) {
            return;
        }
        if (!hasGeneratedAndGeometryOutputs(node)) {
            return;
        }

        BoolField generated = ctx.getOutput(GENERATED, BoolField.class);
        if (generated == null || generated.length() == 0) {
            return;
        }
        GeometryBundle outBundle = ctx.getOutput(GEOMETRY, GeometryBundle.class);
        if (outBundle == null) {
            return;
        }
        MeshTopology mesh = outBundle.mesh();
        if (mesh == null || mesh.vertexCount() == 0 || mesh.faceCount() == 0) {
            return;
        }

        boolean any = false;
        for (int i = 0; i < generated.length(); i++) {
            if (generated.get(i)) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }

        boolean[] vertexMask = faceToVertexMask(mesh, generated);
        Map<String, boolean[]> mergedTags = mergeTag(outBundle, lhs, vertexMask);
        ctx.setOutput(GEOMETRY, outBundle.withSlot(TagGeometryNode.TAGS_SLOT, mergedTags));
    }

    private static boolean hasGeneratedAndGeometryOutputs(MeshNode node) {
        boolean hasGenerated = false;
        boolean hasGeometry = false;
        for (OutputPort p : node.outputs()) {
            if (p.type == PortType.BOOLEAN && GENERATED.equals(p.name)) {
                hasGenerated = true;
            } else if (p.type == PortType.GEOMETRY_BUNDLE && GEOMETRY.equals(p.name)) {
                hasGeometry = true;
            }
        }
        return hasGenerated && hasGeometry;
    }

    private static boolean[] faceToVertexMask(MeshTopology mesh, BoolField faceMask) {
        int vCount = mesh.vertexCount();
        int fCount = mesh.faceCount();
        boolean[] vMask = new boolean[vCount];
        int limit = Math.min(fCount, faceMask.length());
        Map<Integer, Integer> vidToDense = buildVidToDense(mesh);
        for (int fi = 0; fi < limit; fi++) {
            if (!faceMask.get(fi)) {
                continue;
            }
            int fid = mesh.faceIdAt(fi);
            int fvc = mesh.faceVertexCount(fid);
            for (int k = 0; k < fvc; k++) {
                int vid = mesh.faceVertexAt(fid, k);
                Integer dense = vidToDense.get(vid);
                if (dense != null && dense < vCount) {
                    vMask[dense] = true;
                }
            }
        }
        return vMask;
    }

    private static Map<Integer, Integer> buildVidToDense(MeshTopology mesh) {
        Map<Integer, Integer> m = new HashMap<>();
        for (int i = 0; i < mesh.vertexCount(); i++) {
            m.put(mesh.vertexIdAt(i), i);
        }
        return m;
    }

    private static Map<String, boolean[]> mergeTag(
            GeometryBundle bundle, String tagName, boolean[] newVertexMask) {
        Map<String, boolean[]> prev = TagGeometryNode.getTags(bundle);
        Map<String, boolean[]> out = new HashMap<>();
        int vCount = newVertexMask.length;

        if (prev != null) {
            for (Map.Entry<String, boolean[]> e : prev.entrySet()) {
                boolean[] resized = resizeMask(e.getValue(), vCount);
                out.put(e.getKey(), resized);
            }
        }

        boolean[] existing = out.get(tagName);
        if (existing == null) {
            out.put(tagName, newVertexMask.clone());
        } else {
            for (int i = 0; i < vCount; i++) {
                if (newVertexMask[i]) {
                    existing[i] = true;
                }
            }
        }
        return out;
    }

    private static boolean[] resizeMask(boolean[] src, int vCount) {
        if (src.length == vCount) {
            return src.clone();
        }
        boolean[] dst = new boolean[vCount];
        int copy = Math.min(src.length, vCount);
        System.arraycopy(src, 0, dst, 0, copy);
        return dst;
    }
}
