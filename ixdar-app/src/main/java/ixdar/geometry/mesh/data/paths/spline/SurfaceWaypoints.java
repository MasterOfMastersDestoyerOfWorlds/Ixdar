package ixdar.geometry.mesh.data.paths.spline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.paths.query.NearestVertex;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * The authored form of a surface path: {@code "x,y,z; x,y,z; ..."} parsed to
 * packed coordinates, printed back byte-stably, and snapped to the mesh
 * vertices a walk runs through.
 */
public final class SurfaceWaypoints {

    public static final int COORDINATES_PER_WAYPOINT = 3;

    public static final int CLOSED_LOOP_MINIMUM = 3;

    public static final int OPEN_PATH_MINIMUM = 2;

    public static final String COORDINATE_FORMAT = "%.6f";

    public static final float[] FACE_STEPS = { 0.5f, 0.125f };

    private SurfaceWaypoints() {
    }

    /**
     * Reads an authored waypoint list.
     *
     * @param text waypoint list as {@code "x,y,z; x,y,z; ..."}, or null for none
     * @throws IllegalArgumentException when a waypoint does not carry three numbers
     * @return the waypoints, a growable buffer of as many as the text holds
     */
    public static PackedPoints parse(String text) {
        PackedPoints waypoints = new PackedPoints();
        for (String chunk : text == null ? new String[0] : text.split(";")) {
            String trimmed = chunk.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split(",");
            if (parts.length != COORDINATES_PER_WAYPOINT) {
                throw new IllegalArgumentException("waypoint '" + trimmed
                        + "' needs three comma-separated coordinates");
            }
            waypoints.append(Float.parseFloat(parts[0].trim()), Float.parseFloat(parts[1].trim()),
                    Float.parseFloat(parts[2].trim()));
        }
        return waypoints;
    }

    /**
     * Reads an authored single point or direction, such as a ring's base normal.
     *
     * @param text the point as {@code "x,y,z"}, or null for none
     * @throws IllegalArgumentException when a waypoint does not carry three numbers
     * @return the point, or {@code null} when the text holds no point or more than one
     */
    public static Vector3f point(String text) {
        PackedPoints waypoints = parse(text);
        return waypoints.size() == 1 ? waypoints.at(0) : null;
    }

    /**
     * Prints points back as the authored string, at fixed precision.
     *
     * @param points        the waypoints
     * @param waypointCount waypoints to print from the front of the list
     * @return the waypoint list {@code "x,y,z; x,y,z; ..."}
     */
    public static String format(List<Vector3f> points, int waypointCount) {
        StringBuilder text = new StringBuilder();
        for (int waypoint = 0; waypoint < waypointCount; waypoint++) {
            if (waypoint > 0) {
                text.append("; ");
            }
            text.append(format(points.get(waypoint)));
        }
        return text.toString();
    }

    /**
     * Prints points back as the authored string, at fixed precision; the form a saved ring's
     * anchors are printed in.
     *
     * @param waypoints     the waypoints
     * @param waypointCount waypoints to print from the front
     * @return the waypoint list {@code "x,y,z; x,y,z; ..."}
     */
    public static String format(PackedPoints waypoints, int waypointCount) {
        return format(points(waypoints), waypointCount);
    }

    /**
     * Prints one point as the authored {@code "x,y,z"}, at fixed precision.
     *
     * @param point the point
     * @return the point as {@code "x,y,z"}
     */
    public static String format(Vector3f point) {
        StringBuilder text = new StringBuilder();
        for (int axis = 0; axis < COORDINATES_PER_WAYPOINT; axis++) {
            if (axis > 0) {
                text.append(',');
            }
            text.append(String.format(Locale.ROOT, COORDINATE_FORMAT, point.get(axis)));

        }
        return text.toString();
    }

    /**
     * The points of a buffer as a list, one new vector each.
     *
     * @param packed the points
     * @return the points in order
     */
    public static List<Vector3f> points(PackedPoints packed) {
        List<Vector3f> points = new ArrayList<>(packed.size());
        for (int point = 0; point < packed.size(); point++) {
            points.add(packed.at(point));
        }
        return points;
    }

    /**
     * Snaps each waypoint to the mesh vertex nearest it, the geometric selection
     * rings are re-evaluated through on another scan. A point on coincident copies
     * whose faces overlap takes the copy {@link NearestVertex#byNeighbours} picks
     * from its neighbours.
     *
     * @param grid          vertex grid of the mesh the waypoints are snapped
     *                      against
     * @param waypoints     the waypoints
     * @param waypointCount waypoints to snap from the front
     * @throws IllegalStateException naming every waypoint the grid refuses, one per
     *                               line
     * @return mesh vertex ids in waypoint order
     */
    public static int[] snap(NearestVertex grid, PackedPoints waypoints, int waypointCount) {
        int[][] copiesByWaypoint = new int[waypointCount][];
        String[] refusal = new String[waypointCount];
        for (int waypoint = 0; waypoint < waypointCount; waypoint++) {
            try {
                copiesByWaypoint[waypoint] = grid.sheetCopies(waypoints.at(waypoint));
            } catch (IllegalStateException unresolved) {
                refusal[waypoint] = unresolved.getMessage();
            }
        }
        int[] vertexIds = new int[waypointCount];
        StringBuilder refused = new StringBuilder();
        for (int waypoint = 0; waypoint < waypointCount; waypoint++) {
            try {
                vertexIds[waypoint] = grid.byNeighbours(copiesByWaypoint, waypoint);
            } catch (IllegalStateException unresolved) {
                refusal[waypoint] = unresolved.getMessage() + "; move the point onto a face only"
                        + " one of them has, or along the ring off the shared spot";
            }
            if (refusal[waypoint] != null) {
                refused.append(refused.length() == 0 ? "" : "\n").append("point ")
                        .append(waypoint + 1).append(" of ").append(waypointCount).append(": ")
                        .append(refusal[waypoint]);
            }
        }
        if (refused.length() > 0) {
            throw new IllegalStateException(refused.toString());
        }
        return vertexIds;
    }

    /**
     * Points to write for a ring's held vertices, each resolving back to its own
     * vertex through {@link #snap} after printing: the vertex's position, or a
     * point inside one of its faces.
     *
     * @param grid        vertex grid of the mesh the vertices belong to
     * @param vertexIds   the held vertices, in writing order, a closed ring
     * @param vertexCount vertices to place from the front of the array
     * @throws IllegalArgumentException naming every vertex no written point
     *                                  resolves to
     * @return one point per vertex
     */
    public static PackedPoints resolvingPoints(NearestVertex grid, int[] vertexIds, int vertexCount) {
        MeshTopology mesh = grid.mesh;
        PackedPoints points = new PackedPoints(vertexCount);
        int[][] copiesByAnchor = new int[vertexCount][];
        Vector3f position = new Vector3f();
        Vector3f centroid = new Vector3f();
        Vector3f written = new Vector3f();
        for (int anchor = 0; anchor < vertexCount; anchor++) {
            int vertexId = vertexIds[anchor];
            mesh.vertexPosition(vertexId, position);
            boolean placed = false;
            int faceCount = mesh.vertexFaceCount(vertexId);
            for (int candidate = -1; !placed && candidate < FACE_STEPS.length * faceCount; candidate++) {
                centroid.set(position);
                if (candidate >= 0) {
                    int faceId = mesh.vertexFaceAt(vertexId, candidate % faceCount);
                    centroid.set(mesh.faceCentroid(faceId)).sub(position)
                            .mul(FACE_STEPS[candidate / faceCount]).add(position);
                }
                for (int axis = 0; axis < COORDINATES_PER_WAYPOINT; axis++) {
                    written.setComponent(axis, Float.parseFloat(String.format(Locale.ROOT,
                            COORDINATE_FORMAT, centroid.get(axis))));
                }
                int[] copies;
                try {
                    copies = grid.sheetCopies(written);
                } catch (IllegalStateException unresolved) {
                    continue;
                }
                boolean names = Arrays.stream(copies).anyMatch(copy -> copy == vertexId);
                if (names && (copies.length == 1 || copiesByAnchor[anchor] == null)) {
                    copiesByAnchor[anchor] = copies;
                    placed = copies.length == 1;
                    points.set(anchor, written);
                }
            }
        }
        StringBuilder unplaced = new StringBuilder();
        for (int anchor = 0; anchor < vertexCount; anchor++) {
            int resolved;
            try {
                resolved = grid.byNeighbours(copiesByAnchor, anchor);
            } catch (IllegalStateException unresolved) {
                resolved = -1;
            }
            if (resolved != vertexIds[anchor]) {
                mesh.vertexPosition(vertexIds[anchor], position);
                unplaced.append(unplaced.length() > 0 ? ", " : "").append("anchor ")
                        .append(anchor + 1).append(" (vertex ").append(vertexIds[anchor])
                        .append(" at ")
                        .append(format(position))
                        .append(')');
            }
        }
        if (unplaced.length() > 0) {
            throw new IllegalArgumentException("cannot save the ring at " + unplaced
                    + ": repair_mesh split the surface there into coincident vertices, and "
                    + "neither a point inside the held one's faces nor the walk from the "
                    + "neighbouring anchors tells it from the other copies. Move that anchor a "
                    + "little along the ring, off the split point, and save again");
        }
        return points;
    }
}
