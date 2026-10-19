package ixdar.geometry.mesh.data.ops;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.nodegraph.field.BoolField;
import ixdar.nodegraph.field.Vector3Value;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * Deletes selected vertices (where selection is true), producing a new {@link HalfEdgeMesh}.
 */
public final class MeshDeleteVertices {

    private MeshDeleteVertices() {
    }

    /**
     * Delete every vertex flagged in {@code selectionObj}, dropping faces that used any deleted vertex.
     * Survivors keep their order, so vertex and face ids are the input's with the deleted ones removed.
     *
     * @param mesh source mesh; treated read-only
     * @param selectionObj per-vertex {@link BoolField} whose length matches {@code mesh.vertexCount()},
     *                     or a {@link Boolean} ({@code true} = delete every vertex)
     * @throws IllegalArgumentException if {@code selectionObj} is a {@link BoolField} of mismatched length
     * @return new mesh — empty if everything was deleted, a copy of the input if nothing was selected
     */
    public static MeshTopology delete(MeshTopology mesh, Object selectionObj) {
        if (mesh == null || mesh.vertexCount() == 0) {
            return new HalfEdgeMesh();
        }
        int n = mesh.vertexCount();
        boolean deleteAll = false;
        boolean[] del = new boolean[n];
        if (selectionObj instanceof BoolField bf) {
            if (bf.length() != n) {
                throw new IllegalArgumentException("selection length " + bf.length() + " != vertex count " + n);
            }
            for (int i = 0; i < n; i++) {
                del[i] = bf.get(i);
            }
        } else if (selectionObj instanceof Boolean b) {
            deleteAll = b;
        }

        if (deleteAll) {
            return new HalfEdgeMesh();
        }

        boolean any = false;
        for (boolean b : del) {
            if (b) {
                any = true;
                break;
            }
        }
        if (!any) {
            return MeshVertexOffset.apply(mesh, new Vector3Value(0f, 0f, 0f));
        }

        float[] positions = new float[n * 3];
        int[] outVertexOfId = new int[mesh.vertexIdBound()];
        Vector3f position = new Vector3f();
        int survivors = 0;
        for (int activeVertex = 0; activeVertex < n; activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            if (del[activeVertex]) {
                outVertexOfId[vertexId] = -1;
                continue;
            }
            mesh.vertexPosition(vertexId, position);
            positions[survivors * 3] = position.x;
            positions[survivors * 3 + 1] = position.y;
            positions[survivors * 3 + 2] = position.z;
            outVertexOfId[vertexId] = survivors++;
        }
        int corners = 0;
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            corners += mesh.faceVertexCount(mesh.faceIdAt(activeFace));
        }
        int[] faceVertices = new int[corners];
        int[] faceSizes = new int[mesh.faceCount()];
        int keptFaces = 0;
        int keptCorners = 0;
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            int size = mesh.faceVertexCount(faceId);
            boolean keep = true;
            for (int corner = 0; corner < size && keep; corner++) {
                keep = outVertexOfId[mesh.faceVertexAt(faceId, corner)] >= 0;
            }
            if (keep) {
                for (int corner = 0; corner < size; corner++) {
                    faceVertices[keptCorners + corner] = outVertexOfId[mesh.faceVertexAt(faceId, corner)];
                }
                faceSizes[keptFaces++] = size;
                keptCorners += size;
            }
        }
        HalfEdgeMesh out = HalfEdgeMeshEngine.bulkAllocateMixed(Arrays.copyOf(positions, survivors * 3),
                Arrays.copyOf(faceSizes, keptFaces), Arrays.copyOf(faceVertices, keptCorners));
        out.computeNormals();
        return out;
    }
}
