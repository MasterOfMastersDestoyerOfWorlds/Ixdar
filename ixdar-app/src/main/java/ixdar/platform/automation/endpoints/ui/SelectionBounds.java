package ixdar.platform.automation.endpoints.ui;

import java.util.Collection;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.RingRegions;
import ixdar.geometry.mesh.nodes.data.TagGeometryNode;
import ixdar.platform.input.OrbitMouseTrap;
import ixdar.scenes.mesh.MeshNodeViewerScene;
import ixdar.scenes.ring.RingScene;

/**
 * Axis-aligned bounds of one named part of what the mesh viewer is showing, so a camera can be
 * pointed at it without guessing orbit angles. A selection reads {@code kind:name}, and a bare
 * name is tried as a tag, then an edge-mark label, then a ring label.
 */
public class SelectionBounds {
    public static final String MESH = "mesh";
    public static final String OVERLAY = "overlay";
    public static final String TAG = "tag";
    public static final String EDGE_MARK = "edge-mark";
    public static final String REGION = "region";
    public static final String RING = "ring";
    public static final String POINT = "point";
    public static final String BOUNDS = "bounds";
    public static final String SELECTED = "selected";
    public static final String NO_MESH = "no mesh is loaded";
    public static final String NONE = "(none)";
    public static final String NUMBER_SEPARATOR = "\\s*,\\s*";
    public static final int POINT_FIELD_COUNT = 4;
    public static final int BOUNDS_FIELD_COUNT = 6;
    public static final int MAX_X_INDEX = 3;
    public static final int MAX_Y_INDEX = 4;
    public static final int MAX_Z_INDEX = 5;

    public final Vector3f minimum = new Vector3f();
    public final Vector3f maximum = new Vector3f();
    public String kind = "";
    public String name = "";
    public int matchedVertices;
    public String error = "";

    /** Smallest radius {@link #sphere} reports: a point selection's radius, else a tiny floor. */
    public float minimumRadius = OrbitMouseTrap.MINIMUM_FRAME_RADIUS;

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
        if (RING.equals(kind)) {
            return ring(scene, name);
        }
        if (REGION.equals(kind)) {
            // Comma-separated region numbers as the regions command reports them, or the region
            // tool's selection.
            RingRegions regions = scene instanceof RingScene ringScene
                    && ringScene.regionLayer.regions != null
                    && ringScene.regionLayer.regions.mesh == ringScene.halfEdgeSurface()
                            ? ringScene.regionLayer.regions : null;
            if (regions == null) {
                error = "no regions yet: open the region tool (Ctrl+T) or switch region colours "
                        + "on (G) in the ring-tool scene";
                return false;
            }
            boolean[] wanted = new boolean[regions.regionCount];
            if (SELECTED.equals(name)) {
                boolean[] selected = ((RingScene) scene).regionTool.selectedRegions;
                System.arraycopy(selected, 0, wanted, 0, Math.min(selected.length, wanted.length));
            }
            for (String field : SELECTED.equals(name) ? new String[0] : name.split(NUMBER_SEPARATOR)) {
                int region;
                try {
                    region = Integer.parseInt(field.trim());
                } catch (NumberFormatException notANumber) {
                    region = -1;
                }
                if (region < 0 || region >= regions.regionCount) {
                    error = "no region '" + field + "'; there are " + regions.regionCount
                            + " regions, numbered from 0, or say '" + SELECTED + "'";
                    return false;
                }
                wanted[region] = true;
            }
            if (!regionFaces(regions, wanted)) {
                error = "region selection '" + name + "' holds no faces";
                return false;
            }
            return true;
        }
        if (POINT.equals(kind) || BOUNDS.equals(kind)) {
            // A point and radius x,y,z,r, or a box minX,minY,minZ,maxX,maxY,maxZ.
            boolean point = POINT.equals(kind);
            int expected = point ? POINT_FIELD_COUNT : BOUNDS_FIELD_COUNT;
            String[] parts = name.split(NUMBER_SEPARATOR);
            float[] values = new float[expected];
            try {
                for (int index = 0; index < expected && parts.length == expected; index++) {
                    values[index] = Float.parseFloat(parts[index]);
                }
            } catch (NumberFormatException notANumber) {
                parts = new String[0];
            }
            if (parts.length != expected) {
                error = point ? "a point needs x,y,z and a radius"
                        : "bounds needs six numbers: minX,minY,minZ,maxX,maxY,maxZ";
                return false;
            }
            minimum.set(values[0], values[1], values[2]);
            if (point) {
                maximum.set(minimum);
                minimumRadius = Math.max(OrbitMouseTrap.MINIMUM_FRAME_RADIUS, values[MAX_X_INDEX]);
            } else {
                maximum.set(values[MAX_X_INDEX], values[MAX_Y_INDEX], values[MAX_Z_INDEX]);
            }
            return true;
        }
        String bare = kind;
        if (tag(scene, bare) || edgeMark(scene, bare) || ring(scene, bare)) {
            name = bare;
            return true;
        }
        kind = "";
        error = "no tag, edge-mark label or ring named '" + bare + "'; "
                + "known tags: " + tagNames(scene) + ", edge marks: " + edgeMarkNames(scene);
        return false;
    }

    /**
     * The sphere a camera frames for these bounds: the box's centre and half its diagonal, never
     * below {@link #minimumRadius}.
     *
     * @param center receives the sphere's centre
     * @return the sphere's radius
     */
    public float sphere(Vector3f center) {
        center.set(minimum).add(maximum).mul(0.5f);
        return Math.max(minimumRadius, maximum.distance(minimum) * 0.5f);
    }

    /**
     * Grow the bounds over both endpoints of every edge a mask flags, the shape every ring and
     * edge label takes.
     *
     * @param mesh  the surface the mask indexes by edge id
     * @param flags edge-id-indexed mask
     * @return true when the mask flags at least one edge
     */
    public boolean markedEdges(MeshTopology mesh, boolean[] flags) {
        Vector3f position = new Vector3f();
        for (int index = 0; index < mesh.edgeCount(); index++) {
            int edgeId = mesh.edgeIdAt(index);
            if (edgeId >= flags.length || !flags[edgeId]) {
                continue;
            }
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            mesh.vertexPosition(mesh.halfEdgeVertex(halfEdge), position);
            accumulate(position);
            mesh.vertexPosition(mesh.halfEdgeEndVertex(halfEdge), position);
            accumulate(position);
        }
        return matchedVertices > 0;
    }

    /**
     * Grow the bounds over every corner of every face in the wanted ring regions.
     *
     * @param regions the regions the rings cut the surface into
     * @param wanted  region-indexed choice of regions to include
     * @return true when the wanted regions hold at least one face
     */
    public boolean regionFaces(RingRegions regions, boolean[] wanted) {
        MeshTopology mesh = regions.mesh;
        Vector3f position = new Vector3f();
        for (int activeFace = 0; activeFace < regions.regionByActiveFace.length; activeFace++) {
            int region = regions.regionByActiveFace[activeFace];
            if (region < 0 || region >= wanted.length || !wanted[region]) {
                continue;
            }
            int faceId = mesh.faceIdAt(activeFace);
            for (int corner = 0; corner < mesh.faceVertexCount(faceId); corner++) {
                mesh.vertexPosition(mesh.faceVertexAt(faceId, corner), position);
                accumulate(position);
            }
        }
        return matchedVertices > 0;
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
        if (!markedEdges(target, flags)) {
            error = "edge-mark label '" + label + "' flags no edges";
            return false;
        }
        kind = EDGE_MARK;
        return true;
    }

    /**
     * Grow the bounds over one of the editing scene's live rings, a graph ring or one drawn in
     * the ring tool, by the label the {@code regions} report and the ring numbers use.
     *
     * @param scene the mesh viewer, which must be the ring-tool editing scene
     * @param label the ring's label
     * @return true when the ring exists and has edges
     */
    private boolean ring(MeshNodeViewerScene scene, String label) {
        if (!(scene instanceof RingScene ringScene) || ringScene.halfEdgeSurface() == null) {
            error = "rings are only held by the ring-tool editing scene with a mesh loaded";
            return false;
        }
        Map<String, boolean[]> rings = ringScene.ringTool.liveRingMarks();
        boolean[] flags = rings.get(label);
        if (flags == null) {
            error = "no ring labelled '" + label + "'; known rings: " + joinNames(rings.keySet());
            return false;
        }
        if (!markedEdges(ringScene.halfEdgeSurface(), flags)) {
            error = "ring '" + label + "' has no edges on the shown surface";
            return false;
        }
        kind = RING;
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
