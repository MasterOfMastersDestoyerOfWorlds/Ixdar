package ixdar.geometry.mesh.data.sculpt;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * A stroke snapped onto a mesh: the vertices nearest its lifted points, joined into one chain of
 * edges by shortest edge paths.
 */
public final class StrokeChain {

    /** Vertex ids along the chain, no vertex twice in a row. */
    public int[] vertexIds = new int[0];

    /** Edge ids joining consecutive chain vertices, in chain order. */
    public int[] edgeIds = new int[0];

    /** Consecutive vertices no edge path joined, the chain jumping across instead. */
    public int unresolvedGaps;

    /** Search that joins consecutive snapped vertices. */
    public final EdgeDistances search = new EdgeDistances();

    /**
     * Snap packed lifted points to their nearest vertices and join them by shortest edge paths;
     * the chain's vertices and edges replace {@link #vertexIds} and {@link #edgeIds}.
     *
     * @param mesh       surface to snap to
     * @param pointXyz   lifted points, packed xyz, in stroke order
     * @param pointCount how many points {@code pointXyz} holds
     */
    public void snap(MeshTopology mesh, float[] pointXyz, int pointCount) {
        int[] vertices = new int[Math.max(1, pointCount)];
        int[] edges = new int[Math.max(1, pointCount)];
        int vertexCount = 0;
        int edgeCount = 0;
        unresolvedGaps = 0;
        Vector3f position = new Vector3f();
        int[] source = new int[1];
        for (int point = 0; point < pointCount; point++) {
            int nearestId = -1;
            float nearestDistance = Float.POSITIVE_INFINITY;
            for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
                int vertexId = mesh.vertexIdAt(activeVertex);
                mesh.vertexPosition(vertexId, position);
                float distance = position.distanceSquared(pointXyz[point * 3],
                        pointXyz[point * 3 + 1], pointXyz[point * 3 + 2]);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestId = vertexId;
                }
            }
            if (nearestId < 0 || vertexCount > 0 && vertices[vertexCount - 1] == nearestId) {
                continue;
            }
            if (vertexCount == 0) {
                vertices[vertexCount++] = nearestId;
                continue;
            }
            source[0] = vertices[vertexCount - 1];
            if (!search.expand(mesh, source, 1, Float.POSITIVE_INFINITY, nearestId, null)) {
                unresolvedGaps++;
                vertices = ensureCapacity(vertices, vertexCount + 1);
                vertices[vertexCount++] = nearestId;
                continue;
            }
            int pathLength = 0;
            for (int walk = nearestId; search.arrivalEdgeByVertexId[walk] >= 0;
                    walk = mesh.edgeOtherVertex(search.arrivalEdgeByVertexId[walk], walk)) {
                pathLength++;
            }
            vertices = ensureCapacity(vertices, vertexCount + pathLength);
            edges = ensureCapacity(edges, edgeCount + pathLength);
            int walk = nearestId;
            for (int step = pathLength - 1; step >= 0; step--) {
                int edgeId = search.arrivalEdgeByVertexId[walk];
                vertices[vertexCount + step] = walk;
                edges[edgeCount + step] = edgeId;
                walk = mesh.edgeOtherVertex(edgeId, walk);
            }
            vertexCount += pathLength;
            edgeCount += pathLength;
        }
        vertexIds = Arrays.copyOf(vertices, vertexCount);
        edgeIds = Arrays.copyOf(edges, edgeCount);
    }

    private static int[] ensureCapacity(int[] values, int needed) {
        return needed <= values.length ? values : Arrays.copyOf(values, Math.max(needed, values.length * 2));
    }
}
