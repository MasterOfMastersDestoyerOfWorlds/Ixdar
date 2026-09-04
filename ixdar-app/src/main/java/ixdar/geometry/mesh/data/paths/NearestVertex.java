package ixdar.geometry.mesh.data.paths;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;

/**
 * Deterministic geometric vertex pick: the one mesh vertex nearest an authored
 * point.
 *
 * <p>
 * A near-tie between the two closest vertices is an authoring error, never
 * broken by vertex id: ids tie authored data to a primitive's id-generation
 * order, which is the disease geometric selection replaces.
 */
public final class NearestVertex {

    /** Relative distance gap below which the two nearest vertices count as tied. */
    public static final double RELATIVE_EPSILON = 1e-6;

    private NearestVertex() {
    }

    /**
     * The vertex nearest the given point, by Euclidean distance over all live
     * vertices.
     *
     * @param mesh mesh whose vertices are scanned
     * @param x    point x
     * @param y    point y
     * @param z    point z
     * @throws IllegalStateException when the mesh has no vertices, or when the two
     *                               nearest vertices are within
     *                               {@link #RELATIVE_EPSILON} of each other, so the
     *                               pick would be arbitrary
     * @return the nearest vertex's id
     */
    public static int find(MeshTopology mesh, float x, float y, float z) {
        double bestSquared = Double.POSITIVE_INFINITY;
        double secondSquared = Double.POSITIVE_INFINITY;
        int bestVertex = -1;
        Vector3f position = new Vector3f();
        for (int index = 0; index < mesh.vertexCount(); index++) {
            int vertexId = mesh.vertexIdAt(index);
            mesh.vertexPosition(vertexId, position);
            double dx = position.x - x;
            double dy = position.y - y;
            double dz = position.z - z;
            double squared = dx * dx + dy * dy + dz * dz;
            if (squared < bestSquared) {
                secondSquared = bestSquared;
                bestSquared = squared;
                bestVertex = vertexId;
            } else if (squared < secondSquared) {
                secondSquared = squared;
            }
        }
        if (bestVertex < 0) {
            throw new IllegalStateException("nearest vertex to (" + x + ", " + y + ", " + z
                    + "): the mesh has no vertices");
        }
        if (secondSquared != Double.POSITIVE_INFINITY) {
            double bestDistance = Math.sqrt(bestSquared);
            double secondDistance = Math.sqrt(secondSquared);
            if (secondDistance - bestDistance <= RELATIVE_EPSILON * secondDistance) {
                throw new IllegalStateException("ambiguous nearest vertex to (" + x + ", " + y
                        + ", " + z + "): distances " + bestDistance + " and " + secondDistance
                        + " tie, move the point");
            }
        }
        return bestVertex;
    }
}
