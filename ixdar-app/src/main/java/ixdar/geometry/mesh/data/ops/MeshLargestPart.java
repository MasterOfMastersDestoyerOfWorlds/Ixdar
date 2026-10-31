package ixdar.geometry.mesh.data.ops;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.numeric.graph.UnionFind;

/**
 * Keeps the edge-connected part of a mesh with the most faces, dropping the specks and bubbles an
 * extraction or a weld can leave beside it.
 */
public final class MeshLargestPart {

    private MeshLargestPart() {
    }

    /**
     * The part with the most faces, as a new mesh with its vertices and faces in the input's order;
     * ties keep the part met first.
     *
     * @param mesh source mesh, treated read-only
     * @return the largest part, normals computed; an empty mesh for an empty input
     */
    public static HalfEdgeMesh keep(MeshTopology mesh) {
        int vertexCount = mesh.vertexCount();
        int[] activeOf = new int[mesh.vertexIdBound()];
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            activeOf[mesh.vertexIdAt(activeVertex)] = activeVertex;
        }
        int[] parent = UnionFind.singletons(vertexCount);
        int[] rank = new int[vertexCount];
        for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
            int halfEdge = mesh.edgeHalfEdge(mesh.edgeIdAt(activeEdge));
            UnionFind.unionByRank(parent, rank, activeOf[mesh.halfEdgeVertex(halfEdge)],
                    activeOf[mesh.halfEdgeEndVertex(halfEdge)]);
        }
        int[] facesOfPart = new int[vertexCount];
        int largest = -1;
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            int root = UnionFind.find(parent, activeOf[mesh.faceVertexAt(mesh.faceIdAt(activeFace), 0)]);
            facesOfPart[root]++;
            largest = largest < 0 || facesOfPart[root] > facesOfPart[largest] ? root : largest;
        }
        int[] kept = new int[vertexCount];
        Arrays.fill(kept, -1);
        float[] positions = new float[vertexCount * 3];
        int[] faceSizes = new int[mesh.faceCount()];
        int[] corners = new int[mesh.faceCount() * 4];
        int keptVertices = 0;
        int keptFaces = 0;
        int keptCorners = 0;
        Vector3f position = new Vector3f();
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            if (UnionFind.find(parent, activeOf[mesh.faceVertexAt(faceId, 0)]) != largest) {
                continue;
            }
            int size = mesh.faceVertexCount(faceId);
            if (corners.length < keptCorners + size) {
                corners = Arrays.copyOf(corners, Math.max(corners.length * 2, keptCorners + size));
            }
            for (int corner = 0; corner < size; corner++) {
                int vertex = activeOf[mesh.faceVertexAt(faceId, corner)];
                if (kept[vertex] < 0) {
                    mesh.vertexPosition(mesh.vertexIdAt(vertex), position);
                    positions[keptVertices * 3] = position.x;
                    positions[keptVertices * 3 + 1] = position.y;
                    positions[keptVertices * 3 + 2] = position.z;
                    kept[vertex] = keptVertices++;
                }
                corners[keptCorners++] = kept[vertex];
            }
            faceSizes[keptFaces++] = size;
        }
        HalfEdgeMesh part = HalfEdgeMeshOps.bulkAllocateMixed(Arrays.copyOf(positions, keptVertices * 3),
                Arrays.copyOf(faceSizes, keptFaces), Arrays.copyOf(corners, keptCorners));
        part.computeNormals();
        return part;
    }
}
