package ixdar.geometry.mesh.data.rings;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * Where a bisector meets the wall it ends on: the vertex of the wall's edges nearest a stored
 * point, so a bisector end follows its wall when the wall is edited.
 */
public final class RingAttachment {

    private RingAttachment() {
    }

    /**
     * The vertex of a wall's edges nearest a point, the vertex a bisector end attaches to.
     *
     * @param mesh          surface the wall lies on
     * @param marksByEdgeId the wall's edges, edge-id-indexed
     * @param x             point x
     * @param y             point y
     * @param z             point z
     * @return the vertex id, or -1 when the wall marks no edge of the mesh
     */
    public static int attach(MeshTopology mesh, boolean[] marksByEdgeId, float x, float y,
            float z) {
        Vector3f position = new Vector3f();
        double nearest = Double.POSITIVE_INFINITY;
        int attached = -1;
        for (int edgeId = 0; edgeId < marksByEdgeId.length; edgeId++) {
            if (!marksByEdgeId[edgeId] || !mesh.hasEdge(edgeId)) {
                continue;
            }
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            for (int vertexId : new int[] { mesh.halfEdgeVertex(halfEdge),
                mesh.halfEdgeEndVertex(halfEdge) }) {
                mesh.vertexPosition(vertexId, position);
                double distance = position.distanceSquared(x, y, z);
                if (distance < nearest) {
                    nearest = distance;
                    attached = vertexId;
                }
            }
        }
        return attached;
    }

    /**
     * Whether a path through a wall vertex crosses the wall there rather than touching it: round
     * the vertex, exactly one of the wall's two edges lies between the path's two.
     *
     * @param mesh         surface the path and the wall lie on
     * @param vertexId     the vertex they share
     * @param firstEdgeId  the path's edge into the vertex
     * @param secondEdgeId the path's edge out of it
     * @param wallMarks    the wall's edges, edge-id-indexed
     * @return true for a crossing; false for a touch, a run along the wall, a wall junction or a
     *         vertex on the mesh boundary
     */
    public static boolean crossesAt(MeshTopology mesh, int vertexId, int firstEdgeId,
            int secondEdgeId, boolean[] wallMarks) {
        int start = mesh.vertexOutgoingHalfEdge(vertexId);
        int halfEdge = start;
        int turn = 0;
        int firstTurn = -1;
        int secondTurn = -1;
        int wallEdges = 0;
        int wallTurn = -1;
        int otherWallTurn = -1;
        do {
            int edgeId = mesh.halfEdgeEdge(halfEdge);
            if (edgeId == firstEdgeId) {
                firstTurn = turn;
            } else if (edgeId == secondEdgeId) {
                secondTurn = turn;
            } else if (edgeId < wallMarks.length && wallMarks[edgeId]) {
                otherWallTurn = wallEdges == 0 ? otherWallTurn : turn;
                wallTurn = wallEdges == 0 ? turn : wallTurn;
                wallEdges++;
            }
            turn++;
            halfEdge = mesh.halfEdgeTwin(mesh.halfEdgePrev(halfEdge));
        } while (halfEdge != MeshTopology.NONE && halfEdge != start);
        if (halfEdge != start || wallEdges != 2 || firstTurn < 0 || secondTurn < 0) {
            return false;
        }
        int low = Math.min(firstTurn, secondTurn);
        int high = Math.max(firstTurn, secondTurn);
        return (wallTurn > low && wallTurn < high) != (otherWallTurn > low && otherWallTurn < high);
    }
}
