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

    /** Euclidean length per edge id, measured on the first {@link #byNeighbours} walk. */
    public double[] edgeLengthById;

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
     * @throws IllegalStateException when {@link #sheetCopies} refuses the point or leaves more
     *                               than one copy, whose faces then lie on top of each other
     * @return the nearest vertex's id
     */
    public int find(float x, float y, float z) {
        int[] copies = sheetCopies(x, y, z);
        if (copies.length > 1) {
            throw new IllegalStateException("ambiguous nearest vertex to (" + x + ", " + y + ", "
                    + z + "): " + copies.length + " coincident vertices there have faces lying on "
                    + "top of each other under the point, so the point alone cannot tell them "
                    + "apart");
        }
        return copies[0];
    }

    /**
     * The vertices a point may name: the one nearest it, or, among coincident copies there,
     * every copy with a face within {@link #SHEET_TOLERANCE} of the point's distance.
     *
     * @param x point x
     * @param y point y
     * @param z point z
     * @throws IllegalStateException when no vertex is indexed, the two nearest positions tie
     *                               within {@link #RELATIVE_EPSILON}, or the point sits on no
     *                               coincident copy's faces at least
     *                               {@link #MINIMUM_SHEET_OFFSET} of the way to the next vertex
     * @return vertex ids in index order, one unless copies' faces overlap under the point
     */
    public int[] sheetCopies(float x, float y, float z) {
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
            return new int[] { entryVertexId[bestEntry] };
        }
        // Coincident copies, such as repair_mesh's split of a non-manifold vertex, share one
        // position: the point names the copies whose own faces it lies on, and only when it lies
        // clearly off the shared position.
        double sheetReach = SHEET_TOLERANCE * bestDistance;
        int[] sheetVertex = new int[copyCount];
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
                    // A face with two corners at one position, such as a hole fill joining two
                    // copies, is the segment between its distinct corners; JOML's triangle test
                    // divides by its zero-length edge and returns NaN, which no copy passes.
                    Vector3f far = corner.equals(position) ? nextCorner : corner;
                    if (far.equals(position)) {
                        closest.set(position);
                    } else if (corner.equals(position) || corner.equals(nextCorner)
                            || nextCorner.equals(position)) {
                        Intersectionf.findClosestPointOnLineSegment(position.x, position.y,
                                position.z, far.x, far.y, far.z, x, y, z, closest);
                    } else {
                        Intersectionf.findClosestPointOnTriangle(position, corner, nextCorner,
                                point, closest);
                    }
                    nearestFace = Math.min(nearestFace, closest.distance(point));
                }
            }
            if (nearestFace <= sheetReach) {
                sheetVertex[sheetCount++] = vertexId;
            }
        }
        if (sheetCount == 0 || bestDistance < MINIMUM_SHEET_OFFSET * secondDistance) {
            throw new IllegalStateException("ambiguous nearest vertex to (" + x + ", " + y
                    + ", " + z + "): " + copyCount + " coincident vertices at distance "
                    + bestDistance + " and the point lies on the faces of " + sheetCount
                    + " of them, move the point off the shared position onto one copy's faces");
        }
        return Arrays.copyOf(sheetVertex, sheetCount);
    }

    /**
     * The vertex a ring's waypoint names: its point's one vertex, or among overlapping copies the
     * one a surface walk from the neighbouring waypoints' vertices reaches first along its own
     * edges, then the one whose faces point most toward them.
     *
     * @param copiesByWaypoint {@link #sheetCopies} per waypoint, null where it refused; the
     *                         first and last waypoints neighbour each other
     * @param waypoint         waypoint to resolve
     * @throws IllegalStateException when no neighbour reaches a copy, or two tie on both counts
     *                               within {@link #RELATIVE_EPSILON}
     * @return the vertex id, or -1 when the waypoint was refused
     */
    public int byNeighbours(int[][] copiesByWaypoint, int waypoint) {
        int[] copies = copiesByWaypoint[waypoint];
        if (copies == null || copies.length == 1) {
            return copies == null ? -1 : copies[0];
        }
        int count = copiesByWaypoint.length;
        int[] previous = copiesByWaypoint[Math.floorMod(waypoint - 1, count)];
        int[] next = copiesByWaypoint[(waypoint + 1) % count];
        previous = previous == null || previous == copies ? new int[0] : previous;
        next = next == null || next == copies || next == previous ? new int[0] : next;
        int[] sources = Arrays.copyOf(previous, previous.length + next.length);
        System.arraycopy(next, 0, sources, previous.length, next.length);
        if (edgeLengthById == null) {
            int edgeBound = 0;
            for (int index = 0; index < mesh.edgeCount(); index++) {
                edgeBound = Math.max(edgeBound, mesh.edgeIdAt(index) + 1);
            }
            edgeLengthById = new double[edgeBound];
            Vector3f tail = new Vector3f();
            Vector3f head = new Vector3f();
            for (int index = 0; index < mesh.edgeCount(); index++) {
                int edgeId = mesh.edgeIdAt(index);
                int halfEdge = mesh.edgeHalfEdge(edgeId);
                mesh.vertexPosition(mesh.halfEdgeVertex(halfEdge), tail);
                mesh.vertexPosition(mesh.halfEdgeEndVertex(halfEdge), head);
                edgeLengthById[edgeId] = tail.distance(head);
            }
        }
        double[] distance = Dijkstra.forest(mesh, sources, edgeLengthById, copies).distance;
        double nearest = Double.POSITIVE_INFINITY;
        for (int copy : copies) {
            nearest = Math.min(nearest, distance[copy]);
        }
        Vector3f position = mesh.vertexPosition(copies[0], new Vector3f());
        if (nearest == Double.POSITIVE_INFINITY) {
            throw new IllegalStateException(copies.length + " coincident vertices at ("
                    + position.x + ", " + position.y + ", " + position.z + ") have faces lying "
                    + "on top of each other, and the surface from the neighbouring points "
                    + "reaches none of them");
        }
        // Copies reached equally soon, through vertices they share, go to the one whose faces
        // point most toward the sources: the sum over source positions of its best face's cosine.
        Vector3f toSource = new Vector3f();
        Vector3f toFace = new Vector3f();
        Vector3f corner = new Vector3f();
        int first = -1;
        int tied = 0;
        double firstFacing = Double.NEGATIVE_INFINITY;
        double secondFacing = Double.NEGATIVE_INFINITY;
        for (int copy : copies) {
            if (distance[copy] == Double.POSITIVE_INFINITY
                    || distance[copy] - nearest > RELATIVE_EPSILON * distance[copy]) {
                continue;
            }
            tied++;
            double facing = 0.0;
            for (int source = 0; source < sources.length; source++) {
                mesh.vertexPosition(sources[source], toSource).sub(position);
                boolean repeated = toSource.lengthSquared() == 0f;
                for (int earlier = 0; earlier < source && !repeated; earlier++) {
                    repeated = mesh.vertexPosition(sources[earlier], corner).sub(position)
                            .equals(toSource);
                }
                if (repeated) {
                    continue;
                }
                toSource.normalize();
                double best = -1.0;
                for (int adjacency = 0; adjacency < mesh.vertexFaceCount(copy); adjacency++) {
                    int faceId = mesh.vertexFaceAt(copy, adjacency);
                    toFace.zero();
                    for (int at = 0; at < mesh.faceVertexCount(faceId); at++) {
                        toFace.add(mesh.vertexPosition(mesh.faceVertexAt(faceId, at), corner));
                    }
                    toFace.div(mesh.faceVertexCount(faceId)).sub(position);
                    if (toFace.lengthSquared() > 0f) {
                        best = Math.max(best, toFace.normalize().dot(toSource));
                    }
                }
                facing += best;
            }
            if (facing > firstFacing) {
                secondFacing = firstFacing;
                firstFacing = facing;
                first = copy;
            } else if (facing > secondFacing) {
                secondFacing = facing;
            }
        }
        if (tied > 1 && firstFacing - secondFacing <= RELATIVE_EPSILON) {
            throw new IllegalStateException(copies.length + " coincident vertices at ("
                    + position.x + ", " + position.y + ", " + position.z + ") have faces lying "
                    + "on top of each other, and the surface from the neighbouring points reaches "
                    + "two of them equally soon, facing the same way");
        }
        return first;
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
