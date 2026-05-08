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
 * Auto-tag the faces a node just generated with the DSL author's left-hand-side
 * variable name. Invoked by {@link NodeGraphRuntime} immediately after each
 * {@code node.evaluate(ctx)} call.
 *
 * <p>Applicability is decided by port signature, not by node class — any node
 * that exposes both a {@link PortType#BOOLEAN} output named {@code "generated"}
 * and a {@link PortType#GEOMETRY_BUNDLE} output named {@code "geometry"} gets
 * auto-tagging transparently. Today that covers {@code coons_inset_faces},
 * {@code coons_extrude_mesh}, {@code inset_faces}, and {@code extrude_mesh};
 * future feature-creating nodes pick it up without a code change.
 *
 * <p>The resulting per-vertex mask is stored in the geometry bundle's
 * {@link TagGeometryNode#TAGS_SLOT} slot under the LHS name, merged with any
 * pre-existing tags so chained features accumulate.
 */
public final class AutoTagHook {
    public static final String GENERATED = "generated";
    public static final String GEOMETRY = "geometry";

    private AutoTagHook() {}

    /**
     * TODO: document {@code applyIfApplicable}.
     *
     * @param node TODO: describe
     * @param ctx TODO: describe
     * @param lhs TODO: describe
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
            if (p.type() == PortType.BOOLEAN && GENERATED.equals(p.name())) {
                hasGenerated = true;
            } else if (p.type() == PortType.GEOMETRY_BUNDLE && GEOMETRY.equals(p.name())) {
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
