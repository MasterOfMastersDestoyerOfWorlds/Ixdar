package ixdar.geometry.mesh.data.paths;

import java.util.Locale;

import ixdar.geometry.mesh.data.MeshTopology;

/**
 * The authored form of a surface path: {@code "x,y,z; x,y,z; ..."} parsed to packed coordinates,
 * printed back byte-stably, and snapped to the mesh vertices a walk runs through.
 */
public final class SurfaceWaypoints {

    /** Coordinates per waypoint in the authored string. */
    public static final int COORDINATES_PER_WAYPOINT = 3;

    /** Waypoints a closed loop needs before its seed walk encloses anything. */
    public static final int CLOSED_LOOP_MINIMUM = 3;

    /** Waypoints an open path needs: its two ends. */
    public static final int OPEN_PATH_MINIMUM = 2;

    /** Fixed decimal places every written coordinate carries, so one ring is always one string. */
    public static final String COORDINATE_FORMAT = "%.6f";

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
     * @param mesh          mesh the waypoints are snapped against
     * @param packedXyz     packed xyz, three floats per waypoint
     * @param waypointCount waypoints to snap from the front of the array
     * @return mesh vertex ids in waypoint order
     */
    public static int[] snap(MeshTopology mesh, float[] packedXyz, int waypointCount) {
        int[] vertexIds = new int[waypointCount];
        for (int waypoint = 0; waypoint < waypointCount; waypoint++) {
            int base = COORDINATES_PER_WAYPOINT * waypoint;
            vertexIds[waypoint] = NearestVertex.find(mesh, packedXyz[base], packedXyz[base + 1],
                    packedXyz[base + 2]);
        }
        return vertexIds;
    }
}
