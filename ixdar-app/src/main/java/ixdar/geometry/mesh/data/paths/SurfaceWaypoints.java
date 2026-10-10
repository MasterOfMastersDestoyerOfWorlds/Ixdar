package ixdar.geometry.mesh.data.paths;

import java.util.Arrays;
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

    public static final float[] FACE_STEPS = { 0.5f, 0.125f };

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
     * re-evaluated through on another scan. A point on coincident copies whose faces overlap
     * takes the copy {@link NearestVertex#byNeighbours} picks from its neighbours.
     *
     * @param grid          vertex grid of the mesh the waypoints are snapped against
     * @param packedXyz     packed xyz, three floats per waypoint
     * @param waypointCount waypoints to snap from the front of the array
     * @throws IllegalStateException naming every waypoint the grid refuses, one per line
     * @return mesh vertex ids in waypoint order
     */
    public static int[] snap(NearestVertex grid, float[] packedXyz, int waypointCount) {
        int[][] copiesByWaypoint = new int[waypointCount][];
        String[] refusal = new String[waypointCount];
        for (int waypoint = 0; waypoint < waypointCount; waypoint++) {
            int base = COORDINATES_PER_WAYPOINT * waypoint;
            try {
                copiesByWaypoint[waypoint] = grid.sheetCopies(packedXyz[base],
                        packedXyz[base + 1], packedXyz[base + 2]);
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
     * Points to write for a ring's held vertices, each resolving back to its own vertex through
     * {@link #snap} after printing: the vertex's position, or a point inside one of its faces.
     *
     * @param grid        vertex grid of the mesh the vertices belong to
     * @param vertexIds   the held vertices, in writing order, a closed ring
     * @param vertexCount vertices to place from the front of the array
     * @throws IllegalArgumentException naming every vertex no written point resolves to
     * @return packed xyz, three floats per vertex
     */
    public static float[] resolvingPoints(NearestVertex grid, int[] vertexIds, int vertexCount) {
        MeshTopology mesh = grid.mesh;
        float[] packed = new float[COORDINATES_PER_WAYPOINT * vertexCount];
        int[][] copiesByAnchor = new int[vertexCount][];
        Vector3f position = new Vector3f();
        Vector3f centroid = new Vector3f();
        Vector3f corner = new Vector3f();
        float[] written = new float[COORDINATES_PER_WAYPOINT];
        for (int anchor = 0; anchor < vertexCount; anchor++) {
            int vertexId = vertexIds[anchor];
            mesh.vertexPosition(vertexId, position);
            boolean placed = false;
            // Candidate -1 is the vertex itself; candidate k moves it toward the centroid of its
            // k-th face. The first point naming this vertex alone wins; failing that, the first
            // naming it among overlapping copies is left for the neighbouring anchors to settle.
            // Each face is tried at every step, the shorter steps for faces so thin that halfway
            // in lands nearer another vertex.
            int faceCount = mesh.vertexFaceCount(vertexId);
            for (int candidate = -1; !placed && candidate < FACE_STEPS.length * faceCount;
                    candidate++) {
                centroid.set(position);
                if (candidate >= 0) {
                    int faceId = mesh.vertexFaceAt(vertexId, candidate % faceCount);
                    centroid.zero();
                    for (int at = 0; at < mesh.faceVertexCount(faceId); at++) {
                        centroid.add(mesh.vertexPosition(mesh.faceVertexAt(faceId, at), corner));
                    }
                    centroid.div(mesh.faceVertexCount(faceId)).sub(position)
                            .mul(FACE_STEPS[candidate / faceCount]).add(position);
                }
                for (int axis = 0; axis < COORDINATES_PER_WAYPOINT; axis++) {
                    written[axis] = Float.parseFloat(String.format(Locale.ROOT,
                            COORDINATE_FORMAT, centroid.get(axis)));
                }
                int[] copies;
                try {
                    copies = grid.sheetCopies(written[0], written[1], written[2]);
                } catch (IllegalStateException unresolved) {
                    continue;
                }
                boolean names = Arrays.stream(copies).anyMatch(copy -> copy == vertexId);
                if (names && (copies.length == 1 || copiesByAnchor[anchor] == null)) {
                    copiesByAnchor[anchor] = copies;
                    placed = copies.length == 1;
                    System.arraycopy(written, 0, packed, COORDINATES_PER_WAYPOINT * anchor,
                            COORDINATES_PER_WAYPOINT);
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
                        .append(format(new float[] { position.x, position.y, position.z }, 1))
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
        return packed;
    }
}
