package ixdar.geometry.mesh.data.paths;

import java.util.Locale;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;

/**
 * The authored form of a surface path: {@code "x,y,z; x,y,z; ..."} parsed to packed coordinates,
 * printed back byte-stably, and snapped to the mesh vertices a walk runs through.
 */
public final class SurfaceWaypoints {

    public static final int COORDINATES_PER_WAYPOINT = 3;

    public static final int CLOSED_LOOP_MINIMUM = 3;

    public static final int OPEN_PATH_MINIMUM = 2;

    public static final String COORDINATE_FORMAT = "%.6f";

    public static final float FACE_STEP = 0.5f;

    private SurfaceWaypoints() {
    }

    /**
     * Reads an authored waypoint list into packed xyz coordinates.
     *
     * @param text waypoint list as {@code "x,y,z; x,y,z; ..."}, or null for none
     * @throws IllegalArgumentException when a waypoint does not carry three numbers
     * @return packed xyz, three floats per waypoint
     */
    public static float[] parse(String text) {
        if (text == null || text.isBlank()) {
            return new float[0];
        }
        String[] chunks = text.split(";");
        float[] packed = new float[COORDINATES_PER_WAYPOINT * chunks.length];
        int waypointCount = 0;
        for (String chunk : chunks) {
            String trimmed = chunk.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split(",");
            if (parts.length != COORDINATES_PER_WAYPOINT) {
                throw new IllegalArgumentException("waypoint '" + trimmed
                        + "' needs three comma-separated coordinates");
            }
            for (int axis = 0; axis < COORDINATES_PER_WAYPOINT; axis++) {
                packed[COORDINATES_PER_WAYPOINT * waypointCount + axis] =
                        Float.parseFloat(parts[axis].trim());
            }
            waypointCount++;
        }
        float[] exact = new float[COORDINATES_PER_WAYPOINT * waypointCount];
        System.arraycopy(packed, 0, exact, 0, exact.length);
        return exact;
    }

    /**
     * Prints packed coordinates back as the authored string, at fixed precision.
     *
     * @param packedXyz     packed xyz, three floats per waypoint
     * @param waypointCount waypoints to print from the front of the array
     * @return the waypoint list {@code "x,y,z; x,y,z; ..."}
     */
    public static String format(float[] packedXyz, int waypointCount) {
        StringBuilder text = new StringBuilder();
        for (int waypoint = 0; waypoint < waypointCount; waypoint++) {
            if (waypoint > 0) {
                text.append("; ");
            }
            for (int axis = 0; axis < COORDINATES_PER_WAYPOINT; axis++) {
                if (axis > 0) {
                    text.append(',');
                }
                text.append(String.format(Locale.ROOT, COORDINATE_FORMAT,
                        packedXyz[COORDINATES_PER_WAYPOINT * waypoint + axis]));
            }
        }
        return text.toString();
    }

    /**
     * Snaps each waypoint to the mesh vertex nearest it, the geometric selection rings are
     * re-evaluated through on another scan.
     *
     * @param grid          vertex grid of the mesh the waypoints are snapped against
     * @param packedXyz     packed xyz, three floats per waypoint
     * @param waypointCount waypoints to snap from the front of the array
     * @throws IllegalStateException naming every waypoint the grid refuses, one per line
     * @return mesh vertex ids in waypoint order
     */
    public static int[] snap(NearestVertex grid, float[] packedXyz, int waypointCount) {
        int[] vertexIds = new int[waypointCount];
        StringBuilder refused = new StringBuilder();
        for (int waypoint = 0; waypoint < waypointCount; waypoint++) {
            int base = COORDINATES_PER_WAYPOINT * waypoint;
            try {
                vertexIds[waypoint] =
                        grid.find(packedXyz[base], packedXyz[base + 1], packedXyz[base + 2]);
            } catch (IllegalStateException unresolved) {
                refused.append(refused.length() == 0 ? "" : "\n").append("point ")
                        .append(waypoint + 1).append(" of ").append(waypointCount).append(": ")
                        .append(unresolved.getMessage());
            }
        }
        if (refused.length() > 0) {
            throw new IllegalStateException(refused.toString());
        }
        return vertexIds;
    }

    /**
     * Points to write for held vertices, each resolving back to its own vertex through
     * {@link #snap} once printed by {@link #format} and parsed again. A vertex's own position is
     * used when it resolves; a coincident copy gets a point inside one of its own faces.
     *
     * @param grid        vertex grid of the mesh the vertices belong to
     * @param vertexIds   the held vertices, in writing order
     * @param vertexCount vertices to place from the front of the array
     * @throws IllegalArgumentException naming every vertex no written point resolves to
     * @return packed xyz, three floats per vertex
     */
    public static float[] resolvingPoints(NearestVertex grid, int[] vertexIds, int vertexCount) {
        MeshTopology mesh = grid.mesh;
        float[] packed = new float[COORDINATES_PER_WAYPOINT * vertexCount];
        StringBuilder unplaced = new StringBuilder();
        Vector3f position = new Vector3f();
        Vector3f centroid = new Vector3f();
        Vector3f corner = new Vector3f();
        float[] written = new float[COORDINATES_PER_WAYPOINT];
        for (int anchor = 0; anchor < vertexCount; anchor++) {
            int vertexId = vertexIds[anchor];
            mesh.vertexPosition(vertexId, position);
            boolean placed = false;
            // Candidate -1 is the vertex itself; candidate k moves it toward the centroid of its
            // k-th face, which only that copy's faces contain.
            for (int candidate = -1; !placed && candidate < mesh.vertexFaceCount(vertexId);
                    candidate++) {
                centroid.set(position);
                if (candidate >= 0) {
                    int faceId = mesh.vertexFaceAt(vertexId, candidate);
                    centroid.zero();
                    for (int at = 0; at < mesh.faceVertexCount(faceId); at++) {
                        centroid.add(mesh.vertexPosition(mesh.faceVertexAt(faceId, at), corner));
                    }
                    centroid.div(mesh.faceVertexCount(faceId)).sub(position)
                            .mul(FACE_STEP).add(position);
                }
                for (int axis = 0; axis < COORDINATES_PER_WAYPOINT; axis++) {
                    written[axis] = Float.parseFloat(String.format(Locale.ROOT,
                            COORDINATE_FORMAT, centroid.get(axis)));
                }
                try {
                    placed = grid.find(written[0], written[1], written[2]) == vertexId;
                } catch (IllegalStateException unresolved) {
                    placed = false;
                }
            }
            if (!placed) {
                if (unplaced.length() > 0) {
                    unplaced.append("; ");
                }
                unplaced.append("anchor ")
                        .append(anchor + 1).append(" (vertex ").append(vertexId).append(" at ")
                        .append(format(new float[] { position.x, position.y, position.z }, 1))
                        .append(')');
            }
            System.arraycopy(written, 0, packed, COORDINATES_PER_WAYPOINT * anchor,
                    COORDINATES_PER_WAYPOINT);
        }
        if (unplaced.length() > 0) {
            throw new IllegalArgumentException("no written point resolves back to "
                    + unplaced + "; the coincident vertices there share every face");
        }
        return packed;
    }
}
