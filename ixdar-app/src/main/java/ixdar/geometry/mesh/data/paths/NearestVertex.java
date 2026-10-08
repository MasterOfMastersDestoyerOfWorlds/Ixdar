package ixdar.geometry.mesh.data.paths;

import java.util.Arrays;

import org.joml.Intersectionf;
import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;

/**
 * Deterministic geometric pick of the vertex nearest an authored point. A near-tie is refused,
 * never broken by id; coincident copies go to the one whose faces the point lies on.
 */
public final class NearestVertex {

    public static final double RELATIVE_EPSILON = 1e-6;

    public static final double CELL_BOUND_SLACK = 1e-6;

    public static final double SHEET_TOLERANCE = 0.1;

    public static final double MINIMUM_SHEET_OFFSET = 0.02;

    /** The indexed mesh, whose faces tell its coincident vertices apart. */
    public MeshTopology mesh;

    /** Grid origin, the minimum corner of the vertices' bounding box. */
    public final float[] origin = new float[3];

    /** Cells along x, y and z. */
    public final int[] cellsPerAxis = new int[3];

    /** Edge length of one cubic cell. */
    public float cellSize;

    /** Offset of each cell's first entry in {@link #entryVertexId}, plus a final total. */
    public int[] cellStart;

    /** Vertex id per entry, grouped by cell. */
    public int[] entryVertexId;

    /** Packed xyz per entry, in the same order as {@link #entryVertexId}. */
    public float[] entryXyz;

    private NearestVertex() {
    }

    /**
     * Distance from a point to the nearest mesh vertex, which at a point inside a closed surface
     * is the radius of the sphere inscribed there.
     *
     * @param mesh mesh whose vertices are scanned
     * @param x    point x
     * @param y    point y
     * @param z    point z
     * @return the distance, or {@link Double#POSITIVE_INFINITY} when the mesh has no vertices
     */
    public static double distanceToNearest(MeshTopology mesh, float x, float y, float z) {
        double bestSquared = Double.POSITIVE_INFINITY;
        Vector3f position = new Vector3f();
        for (int index = 0; index < mesh.vertexCount(); index++) {
            mesh.vertexPosition(mesh.vertexIdAt(index), position);
            double dx = position.x - x;
            double dy = position.y - y;
            double dz = position.z - z;
            bestSquared = Math.min(bestSquared, dx * dx + dy * dy + dz * dz);
        }
        return Math.sqrt(bestSquared);
    }

    /**
     * Buckets every live vertex of a mesh into a uniform grid of about one cell per vertex over
     * the bounding box, for repeated {@link #find(float, float, float)} picks.
     *
     * @param mesh mesh whose vertices are indexed; later edits to it are not seen
     * @return the index
     */
    public static NearestVertex over(MeshTopology mesh) {
        NearestVertex index = new NearestVertex();
        index.mesh = mesh;
        int vertexCount = mesh.vertexCount();
        int[] vertexIds = new int[vertexCount];
        float[] xyz = new float[3 * vertexCount];
        Vector3f position = new Vector3f();
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            vertexIds[vertex] = mesh.vertexIdAt(vertex);
            mesh.vertexPosition(vertexIds[vertex], position);
            xyz[3 * vertex] = position.x;
            xyz[3 * vertex + 1] = position.y;
            xyz[3 * vertex + 2] = position.z;
        }
        float[] maximum = { Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY,
            Float.NEGATIVE_INFINITY };
        Arrays.fill(index.origin, Float.POSITIVE_INFINITY);
        for (int coordinate = 0; coordinate < xyz.length; coordinate++) {
            int axis = coordinate % 3;
            index.origin[axis] = Math.min(index.origin[axis], xyz[coordinate]);
            maximum[axis] = Math.max(maximum[axis], xyz[coordinate]);
        }
        double largestExtent = 0.0;
        for (int axis = 0; axis < 3 && vertexCount > 0; axis++) {
            largestExtent = Math.max(largestExtent, maximum[axis] - index.origin[axis]);
        }
        // A flat or empty box would give zero volume; floor every extent at a sliver of the
        // largest so the cube root stays finite and the grid stays near one cell per vertex.
        double volume = 1.0;
        for (int axis = 0; axis < 3; axis++) {
            volume *= vertexCount == 0 ? 1.0
                    : Math.max(maximum[axis] - index.origin[axis],
                            CELL_BOUND_SLACK * largestExtent + Float.MIN_NORMAL);
        }
        index.cellSize = (float) Math.cbrt(volume / Math.max(1, vertexCount));
        int cellCount = 1;
        for (int axis = 0; axis < 3; axis++) {
            index.cellsPerAxis[axis] = vertexCount == 0 ? 1
                    : 1 + (int) ((maximum[axis] - index.origin[axis]) / index.cellSize);
            cellCount *= index.cellsPerAxis[axis];
        }
        // Every indexed coordinate is at or above the origin, so truncation is the floor here.
        int[] cellOf = new int[vertexCount];
        for (int coordinate = 0; coordinate < xyz.length; coordinate++) {
            int axis = coordinate % 3;
            int cell = Math.min(index.cellsPerAxis[axis] - 1,
                    (int) ((xyz[coordinate] - index.origin[axis]) / index.cellSize));
            cellOf[coordinate / 3] = cellOf[coordinate / 3] * index.cellsPerAxis[axis] + cell;
        }
        index.cellStart = new int[cellCount + 1];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            index.cellStart[cellOf[vertex] + 1]++;
        }
        for (int cell = 0; cell < cellCount; cell++) {
            index.cellStart[cell + 1] += index.cellStart[cell];
        }
        int[] cursor = Arrays.copyOf(index.cellStart, cellCount);
        index.entryVertexId = new int[vertexCount];
        index.entryXyz = new float[3 * vertexCount];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int entry = cursor[cellOf[vertex]]++;
            index.entryVertexId[entry] = vertexIds[vertex];
            index.entryXyz[3 * entry] = xyz[3 * vertex];
            index.entryXyz[3 * entry + 1] = xyz[3 * vertex + 1];
            index.entryXyz[3 * entry + 2] = xyz[3 * vertex + 2];
        }
        return index;
    }

    /**
     * The indexed vertex nearest the given point by Euclidean distance, refusing a near-tie
     * between the two closest rather than picking one arbitrarily. Among coincident copies it is
     * the one with a face within {@link #SHEET_TOLERANCE} of the point's distance.
     *
     * @param x point x
     * @param y point y
     * @param z point z
     * @throws IllegalStateException when no vertex is indexed, the two nearest positions tie
     *                               within {@link #RELATIVE_EPSILON}, or the point sits on no
     *                               one coincident copy's faces at least
     *                               {@link #MINIMUM_SHEET_OFFSET} of the way to the next vertex
     * @return the nearest vertex's id
     */
    public int find(float x, float y, float z) {
        int centerX = cellCoordinate(x, 0);
        int centerY = cellCoordinate(y, 1);
        int centerZ = cellCoordinate(z, 2);
        int widestAxis = Math.max(cellsPerAxis[0], Math.max(cellsPerAxis[1], cellsPerAxis[2]));
        double bestSquared = Double.POSITIVE_INFINITY;
        double secondSquared = Double.POSITIVE_INFINITY;
        int bestEntry = -1;
        // Shell by shell outward: before shell r every unvisited vertex lies at least r - 1 whole
        // cells away, so the search ends once that bound passes the second-best distance.
        for (int radius = 0; radius <= widestAxis; radius++) {
            double unvisitedBound = ((radius - 1) * (double) cellSize) * (1.0 - CELL_BOUND_SLACK);
            if (radius > 1 && unvisitedBound * unvisitedBound > secondSquared) {
                break;
            }
            for (int cellX = Math.max(0, centerX - radius);
                    cellX <= Math.min(cellsPerAxis[0] - 1, centerX + radius); cellX++) {
                for (int cellY = Math.max(0, centerY - radius);
                        cellY <= Math.min(cellsPerAxis[1] - 1, centerY + radius); cellY++) {
                    for (int cellZ = Math.max(0, centerZ - radius);
                            cellZ <= Math.min(cellsPerAxis[2] - 1, centerZ + radius); cellZ++) {
                        if (Math.abs(cellX - centerX) != radius
                                && Math.abs(cellY - centerY) != radius
                                && Math.abs(cellZ - centerZ) != radius) {
                            continue;
                        }
                        int cell = (cellX * cellsPerAxis[1] + cellY) * cellsPerAxis[2] + cellZ;
                        for (int entry = cellStart[cell]; entry < cellStart[cell + 1]; entry++) {
                            double dx = entryXyz[3 * entry] - x;
                            double dy = entryXyz[3 * entry + 1] - y;
                            double dz = entryXyz[3 * entry + 2] - z;
                            double squared = dx * dx + dy * dy + dz * dz;
                            // A copy at the best entry's very position is the same pick, settled
                            // below by its faces, so it is not a second candidate.
                            if (bestEntry >= 0 && samePosition(entry, bestEntry)) {
                                continue;
                            }
                            if (squared < bestSquared) {
                                secondSquared = bestSquared;
                                bestSquared = squared;
                                bestEntry = entry;
                            } else if (squared < secondSquared) {
                                secondSquared = squared;
                            }
                        }
                    }
                }
            }
        }
        if (bestEntry < 0) {
            throw new IllegalStateException("nearest vertex to (" + x + ", " + y + ", " + z
                    + "): the mesh has no vertices");
        }
        double bestDistance = Math.sqrt(bestSquared);
        double secondDistance = Math.sqrt(secondSquared);
        if (secondSquared != Double.POSITIVE_INFINITY
                && secondDistance - bestDistance <= RELATIVE_EPSILON * secondDistance) {
            throw new IllegalStateException("ambiguous nearest vertex to (" + x + ", " + y
                    + ", " + z + "): distances " + bestDistance + " and " + secondDistance
                    + " tie, move the point");
        }
        int cell = (cellCoordinate(entryXyz[3 * bestEntry], 0) * cellsPerAxis[1]
                + cellCoordinate(entryXyz[3 * bestEntry + 1], 1)) * cellsPerAxis[2]
                + cellCoordinate(entryXyz[3 * bestEntry + 2], 2);
        int copyCount = 0;
        for (int entry = cellStart[cell]; entry < cellStart[cell + 1]; entry++) {
            copyCount += samePosition(entry, bestEntry) ? 1 : 0;
        }
        if (copyCount == 1) {
            return entryVertexId[bestEntry];
        }
        // Coincident copies, such as repair_mesh's split of a non-manifold vertex, share one
        // position: the point picks the copy whose own faces it lies on, and only when it lies
        // clearly off the shared position, near that one copy's faces and away from every other's.
        double sheetReach = SHEET_TOLERANCE * bestDistance;
        int sheetVertex = -1;
        int sheetCount = 0;
        Vector3f position = new Vector3f();
        Vector3f corner = new Vector3f();
        Vector3f nextCorner = new Vector3f();
        Vector3f closest = new Vector3f();
        Vector3f point = new Vector3f(x, y, z);
        for (int entry = cellStart[cell]; entry < cellStart[cell + 1]; entry++) {
            if (!samePosition(entry, bestEntry)) {
                continue;
            }
            int vertexId = entryVertexId[entry];
            double nearestFace = Double.POSITIVE_INFINITY;
            for (int adjacency = 0; adjacency < mesh.vertexFaceCount(vertexId); adjacency++) {
                int faceId = mesh.vertexFaceAt(vertexId, adjacency);
                mesh.vertexPosition(mesh.faceVertexAt(faceId, 0), position);
                for (int fan = 1; fan + 1 < mesh.faceVertexCount(faceId); fan++) {
                    mesh.vertexPosition(mesh.faceVertexAt(faceId, fan), corner);
                    mesh.vertexPosition(mesh.faceVertexAt(faceId, fan + 1), nextCorner);
                    Intersectionf.findClosestPointOnTriangle(position, corner, nextCorner,
                            point, closest);
                    nearestFace = Math.min(nearestFace, closest.distance(point));
                }
            }
            if (nearestFace <= sheetReach) {
                sheetVertex = vertexId;
                sheetCount++;
            }
        }
        if (sheetCount != 1 || bestDistance < MINIMUM_SHEET_OFFSET * secondDistance) {
            throw new IllegalStateException("ambiguous nearest vertex to (" + x + ", " + y
                    + ", " + z + "): " + copyCount + " coincident vertices at distance "
                    + bestDistance + " and the point lies on the faces of " + sheetCount
                    + " of them, move the point off the shared position onto one copy's faces");
        }
        return sheetVertex;
    }

    private boolean samePosition(int entry, int other) {
        return entryXyz[3 * entry] == entryXyz[3 * other]
                && entryXyz[3 * entry + 1] == entryXyz[3 * other + 1]
                && entryXyz[3 * entry + 2] == entryXyz[3 * other + 2];
    }

    private int cellCoordinate(float coordinate, int axis) {
        int cell = (int) Math.floor((coordinate - origin[axis]) / cellSize);
        return Math.max(0, Math.min(cellsPerAxis[axis] - 1, cell));
    }
}
