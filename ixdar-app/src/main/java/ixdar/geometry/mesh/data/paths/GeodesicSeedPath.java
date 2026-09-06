package ixdar.geometry.mesh.data.paths;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;

/**
 * Builds the rough edge path FlipOut starts from: shortest edge walks chained through a list of
 * surface waypoints, closed into a loop on request.
 */
public final class GeodesicSeedPath {

    private GeodesicSeedPath() {
    }

    /**
     * Chains shortest edge walks between consecutive waypoint vertices into one seed path.
     *
     * @param intrinsic         unflipped triangulation the seed is expressed on
     * @param waypointVertexIds mesh vertex ids to pass through, in order
     * @param closed            whether to walk back from the last waypoint to the first
     * @throws IllegalArgumentException when fewer than two waypoints are given, or a leg of the
     *                                  walk cannot be closed on the mesh
     * @return the seed as intrinsic half-edges in travel order
     */
    public static int[] throughVertices(IntrinsicTriangulation intrinsic, int[] waypointVertexIds,
            boolean closed) {
        if (waypointVertexIds.length < 2) {
            throw new IllegalArgumentException("a seed path needs at least two waypoints, got "
                    + waypointVertexIds.length);
        }
        MeshTopology mesh = intrinsic.sourceMesh;
        double[] edgeCost = edgeCosts(mesh);
        List<Integer> vertexWalk = new ArrayList<>();
        vertexWalk.add(waypointVertexIds[0]);
        int legs = closed ? waypointVertexIds.length : waypointVertexIds.length - 1;
        for (int leg = 0; leg < legs; leg++) {
            int fromVertexId = waypointVertexIds[leg];
            int toVertexId = waypointVertexIds[(leg + 1) % waypointVertexIds.length];
            ShortestPathForest forest =
                    Dijkstra.forest(mesh, new int[] { toVertexId }, edgeCost);
            int walk = fromVertexId;
            int guard = 0;
            while (walk != toVertexId) {
                int parent = forest.parent[walk];
                if (parent < 0 || guard++ > mesh.vertexCount()) {
                    throw new IllegalArgumentException("no edge walk from vertex " + fromVertexId
                            + " to vertex " + toVertexId);
                }
                vertexWalk.add(parent);
                walk = parent;
            }
        }
        if (closed && vertexWalk.size() > 1
                && vertexWalk.get(0).intValue() == vertexWalk.get(vertexWalk.size() - 1)) {
            vertexWalk.remove(vertexWalk.size() - 1);
        }
        return toHalfEdges(intrinsic, vertexWalk, closed);
    }

    /**
     * Euclidean length per edge id, the cost the seed walks are measured with.
     *
     * @param mesh mesh whose edges are measured
     * @return per-edge-id lengths, with dead ids left at zero
     */
    public static double[] edgeCosts(MeshTopology mesh) {
        int maxEdgeId = 0;
        for (int index = 0; index < mesh.edgeCount(); index++) {
            maxEdgeId = Math.max(maxEdgeId, mesh.edgeIdAt(index));
        }
        double[] cost = new double[maxEdgeId + 1];
        Vector3f tailPosition = new Vector3f();
        Vector3f headPosition = new Vector3f();
        for (int index = 0; index < mesh.edgeCount(); index++) {
            int edgeId = mesh.edgeIdAt(index);
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            mesh.vertexPosition(mesh.halfEdgeVertex(halfEdge), tailPosition);
            mesh.vertexPosition(mesh.halfEdgeEndVertex(halfEdge), headPosition);
            cost[edgeId] = tailPosition.distance(headPosition);
        }
        return cost;
    }

    private static int[] toHalfEdges(IntrinsicTriangulation intrinsic, List<Integer> vertexWalk,
            boolean closed) {
        int spans = closed ? vertexWalk.size() : vertexWalk.size() - 1;
        int[] halfEdges = new int[spans];
        for (int index = 0; index < spans; index++) {
            int fromVertex =
                    intrinsic.vertexIndexByVertexId[vertexWalk.get(index)];
            int toVertex = intrinsic.vertexIndexByVertexId[
                    vertexWalk.get((index + 1) % vertexWalk.size())];
            int halfEdge = intrinsic.halfEdgeBetween(fromVertex, toVertex);
            if (halfEdge < 0) {
                throw new IllegalArgumentException("seed walk step " + index
                        + " is not along a mesh edge");
            }
            halfEdges[index] = halfEdge;
        }
        return halfEdges;
    }
}
