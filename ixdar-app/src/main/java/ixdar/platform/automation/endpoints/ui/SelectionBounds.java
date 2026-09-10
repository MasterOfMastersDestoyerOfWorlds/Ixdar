package ixdar.platform.automation.endpoints.ui;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.Patch;
import ixdar.geometry.mesh.nodes.data.TagGeometryNode;
import ixdar.scenes.mesh.MeshNodeViewerScene;

/**
 * Axis-aligned bounds of one named part of what the mesh viewer is showing, so a camera can be
 * pointed at it without guessing orbit angles. A selection reads {@code kind:name}, and a bare
 * name is tried as a tag, then an edge-mark label, then a patch id.
 */
public class SelectionBounds {
    public static final String MESH = "mesh";
    public static final String OVERLAY = "overlay";
    public static final String TAG = "tag";
    public static final String EDGE_MARK = "edge-mark";
    public static final String PATCH = "patch";
    public static final String NO_MESH = "no mesh is loaded";
    public static final String NONE = "(none)";

    public final Vector3f minimum = new Vector3f();
    public final Vector3f maximum = new Vector3f();
    public String kind = "";
    public String name = "";
    public int matchedVertices;
    public String error = "";

    /**
     * Resolve a selection against the viewer and fill in the bounds it covers.
     *
     * @param scene the mesh viewer holding the mesh, its geometry bundle, and any overlay
     * @param selection the selection expression, or blank for the whole mesh
     * @return true when the selection resolved and the bounds are usable
     */
    public boolean resolve(MeshNodeViewerScene scene, String selection) {
        String expression = selection == null ? "" : selection.trim();
        int separator = expression.indexOf(':');
        kind = separator < 0 ? expression : expression.substring(0, separator).trim();
        name = separator < 0 ? "" : expression.substring(separator + 1).trim();
        if (kind.isEmpty()) {
            kind = MESH;
        }
        if (MESH.equals(kind)) {
            return wholeMesh(scene.getMesh(), NO_MESH);
        }
        if (OVERLAY.equals(kind)) {
            return wholeMesh(scene.getOverlayMesh(), "no overlay is loaded");
        }
        if (TAG.equals(kind)) {
            return tag(scene, name);
        }
        if (EDGE_MARK.equals(kind)) {
            return edgeMark(scene, name);
        }
        if (PATCH.equals(kind)) {
            return patch(scene, name);
        }
        String bare = kind;
        if (tag(scene, bare) || edgeMark(scene, bare) || patch(scene, bare)) {
            name = bare;
            return true;
        }
        kind = "";
        error = "no tag, edge-mark label or patch id named '" + bare + "'; "
                + "known tags: " + tagNames(scene) + ", edge marks: " + edgeMarkNames(scene);
        return false;
    }

    /**
     * Take the bounds straight from a mesh's own axis-aligned box.
     *
     * @param target the mesh to measure, or null when nothing is loaded
     * @param absentMessage what to report when the mesh is absent
     * @return true when the mesh was present
     */
    private boolean wholeMesh(MeshTopology target, String absentMessage) {
        if (target == null) {
            error = absentMessage;
            return false;
        }
        target.boundsMin(minimum);
        target.boundsMax(maximum);
        matchedVertices = target.vertexCount();
        return true;
    }

    /**
     * Grow the bounds over every vertex a tag's per-vertex mask marks.
     *
     * @param scene the mesh viewer
     * @param tagName the tag label to look up in the geometry bundle
     * @return true when the tag exists and marks at least one vertex
     */
    private boolean tag(MeshNodeViewerScene scene, String tagName) {
        MeshTopology target = scene.getMesh();
        Map<String, boolean[]> tags = TagGeometryNode.getTags(scene.getGeometryBundle());
        if (target == null || tags == null || !tags.containsKey(tagName)) {
            error = "no tag named '" + tagName + "'; known tags: " + tagNames(scene);
            return false;
        }
        boolean[] mask = tags.get(tagName);
        Vector3f position = new Vector3f();
        for (int index = 0; index < target.vertexCount() && index < mask.length; index++) {
            if (mask[index]) {
                target.vertexPosition(target.vertexIdAt(index), position);
                accumulate(position);
            }
        }
        if (matchedVertices == 0) {
            error = "tag '" + tagName + "' marks no vertices";
            return false;
        }
        kind = TAG;
        return true;
    }

    /**
     * Grow the bounds over both endpoints of every edge an edge-mark label flags.
     *
     * @param scene the mesh viewer
     * @param label the edge-mark label to look up in the geometry bundle
     * @return true when the label exists and flags at least one edge
     */
    private boolean edgeMark(MeshNodeViewerScene scene, String label) {
        MeshTopology target = scene.getMesh();
        GeometryBundle bundle = scene.getGeometryBundle();
        boolean[] flags = null;
        if (target != null && bundle != null) {
            Object marks = bundle.slots().get(EdgeMarks.SLOT);
            if (marks instanceof Map<?, ?> byLabel && byLabel.get(label) instanceof boolean[] labelled) {
                flags = labelled;
            }
        }
        if (flags == null) {
            error = "no edge-mark label '" + label + "'; known edge marks: " + edgeMarkNames(scene);
            return false;
        }
        Vector3f position = new Vector3f();
        for (int index = 0; index < target.edgeCount(); index++) {
            int edgeId = target.edgeIdAt(index);
            if (edgeId >= flags.length || !flags[edgeId]) {
                continue;
            }
            int halfEdge = target.edgeHalfEdge(edgeId);
            target.vertexPosition(target.halfEdgeVertex(halfEdge), position);
            accumulate(position);
            target.vertexPosition(target.halfEdgeEndVertex(halfEdge), position);
            accumulate(position);
        }
        if (matchedVertices == 0) {
            error = "edge-mark label '" + label + "' flags no edges";
            return false;
        }
        kind = EDGE_MARK;
        return true;
    }

    /**
     * Grow the bounds over the vertices of one decomposer patch, running the decomposition first
     * when the patch overlay has not already computed it.
     *
     * @param scene the mesh viewer
     * @param patchId the patch id, as decimal text
     * @return true when a patch with that id exists
     */
    private boolean patch(MeshNodeViewerScene scene, String patchId) {
        MeshTopology target = scene.getMesh();
        if (target == null) {
            error = NO_MESH;
            return false;
        }
        int wanted;
        try {
            wanted = Integer.parseInt(patchId);
        } catch (NumberFormatException notANumber) {
            error = "patch id '" + patchId + "' is not a number";
            return false;
        }
        List<Patch> patches = scene.decomposePatches();
        Vector3f position = new Vector3f();
        for (Patch candidate : patches) {
            if (candidate.id() != wanted) {
                continue;
            }
            for (int denseVertexIndex : candidate.vertexIndices()) {
                target.vertexPosition(target.vertexIdAt(denseVertexIndex), position);
                accumulate(position);
            }
        }
        if (matchedVertices == 0) {
            error = "no patch with id " + wanted + "; the decomposition holds " + patches.size() + " patches";
            return false;
        }
        kind = PATCH;
        return true;
    }

    /**
     * Widen the accumulating box to include one point.
     *
     * @param position the point to include
     */
    private void accumulate(Vector3f position) {
        if (matchedVertices == 0) {
            minimum.set(position);
            maximum.set(position);
        } else {
            minimum.min(position);
            maximum.max(position);
        }
        matchedVertices++;
    }

    /**
     * The tag labels the current geometry bundle carries, for an error message that says what the
     * caller could have asked for.
     *
     * @param scene the mesh viewer
     * @return comma-separated tag names, or {@code (none)}
     */
    private String tagNames(MeshNodeViewerScene scene) {
        Map<String, boolean[]> tags = TagGeometryNode.getTags(scene.getGeometryBundle());
        return joinNames(tags == null ? null : tags.keySet());
    }

    /**
     * The edge-mark labels the current geometry bundle carries.
     *
     * @param scene the mesh viewer
     * @return comma-separated edge-mark labels, or {@code (none)}
     */
    private String edgeMarkNames(MeshNodeViewerScene scene) {
        GeometryBundle bundle = scene.getGeometryBundle();
        if (bundle == null || !(bundle.slots().get(EdgeMarks.SLOT) instanceof Map<?, ?> marks)) {
            return NONE;
        }
        return joinNames(marks.keySet());
    }

    /**
     * Render a set of label names for an error message.
     *
     * @param names the labels to list, possibly null or empty
     * @return the names comma-separated, or {@code (none)}
     */
    private String joinNames(Collection<?> names) {
        if (names == null || names.isEmpty()) {
            return NONE;
        }
        StringBuilder joined = new StringBuilder();
        for (Object name : names) {
            if (joined.length() > 0) {
                joined.append(",");
            }
            joined.append(name);
        }
        return joined.toString();
    }
}
