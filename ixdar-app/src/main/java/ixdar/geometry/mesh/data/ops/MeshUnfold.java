package ixdar.geometry.mesh.data.ops;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.PackedPoints;

/**
 * Unfolds the slivers {@link MeshFolds} finds turned over, the ones quad_layout refuses, by moving
 * their corners to their neighbours' centroid until none folds. Topology is unchanged.
 */
public final class MeshUnfold {

    public static final int MAX_UNFOLD_ROUNDS_BEFORE_GIVING_UP = 64;

    /** Faces folded before the unfold. */
    public int foldedFaceCount;

    /** Faces still folded after it. */
    public int stillFoldedFaceCount;

    /** Vertices it moved. */
    public int movedVertexCount;

    /** Ids of the faces still folded after it. */
    public int[] stillFoldedFaceIds = new int[0];

    /**
     * Unfold {@code mesh} in place.
     *
     * @param mesh the surface, moved in place; its normals are recomputed when anything moved
     * @return what the unfold found and did
     */
    public static MeshUnfold run(HalfEdgeMesh mesh) {
        MeshUnfold result = new MeshUnfold();
        int[] activeOf = new int[mesh.vertexIdBound()];
        PackedPoints xyz = mesh.packedPositions(activeOf);
        MeshFolds folds = new MeshFolds(mesh, activeOf, xyz);
        int[] folded = foldedFaces(mesh, folds.foldedAtStart);
        result.foldedFaceCount = folded.length;
        if (folded.length == 0) {
            return result;
        }
        int vertexCount = mesh.vertexCount();
        boolean[] moved = new boolean[vertexCount];
        int[] stamp = new int[vertexCount];
        int[] movers = new int[vertexCount];
        int[][] rings = mesh.oneRings();
        int round = 0;
        while (folded.length > 0 && round < MAX_UNFOLD_ROUNDS_BEFORE_GIVING_UP) {
            round++;
            int moverCount = 0;
            for (int faceId : folded) {
                for (int corner = 0; corner < mesh.faceVertexCount(faceId); corner++) {
                    int vertex = activeOf[mesh.faceVertexAt(faceId, corner)];
                    if (stamp[vertex] != round) {
                        stamp[vertex] = round;
                        movers[moverCount++] = vertex;
                    }
                }
            }
            PackedPoints centroids = new PackedPoints(moverCount);
            for (int index = 0; index < moverCount; index++) {
                Vector3f sum = new Vector3f();
                for (int neighbour : rings[movers[index]]) {
                    sum.add(xyz.at(neighbour));
                }
                centroids.set(index, sum.div(rings[movers[index]].length));
            }
            for (int index = 0; index < moverCount; index++) {
                xyz.set(movers[index], centroids.at(index));
                moved[movers[index]] = true;
            }
            folds.update(xyz, movers, moverCount);
            folded = foldedFaces(mesh, folds.failedByFaceId);
        }
        result.stillFoldedFaceCount = folded.length;
        result.stillFoldedFaceIds = folded;
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            if (moved[vertex]) {
                result.movedVertexCount++;
                mesh.setVertexPosition(mesh.vertexIdAt(vertex), xyz.get(vertex, 0), xyz.get(vertex, 1),
                        xyz.get(vertex, 2));
            }
        }
        mesh.computeNormals();
        return result;
    }

    /**
     * The live faces a flag array marks.
     *
     * @param mesh   the surface
     * @param marked flag by face id
     * @return the marked face ids, in the mesh's order
     */
    private static int[] foldedFaces(HalfEdgeMesh mesh, boolean[] marked) {
        int[] found = new int[mesh.faceCount()];
        int count = 0;
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            if (marked[faceId]) {
                found[count++] = faceId;
            }
        }
        return Arrays.copyOf(found, count);
    }
}
