package ixdar.geometry.mesh.data.ops;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.nodegraph.field.Vector3Field;
import ixdar.nodegraph.field.Vector3Value;

/**
 * Applies per-vertex offsets to a mesh, producing a new {@link HalfEdgeMesh}.
 */
public final class MeshVertexOffset {
    private MeshVertexOffset() {
    }

    /**
     * Add a per-vertex or uniform offset to every vertex of {@code mesh}, returning a new mesh with
     * the input's vertex and face order and normals recomputed.
     *
     * @param mesh source mesh; null or empty meshes pass through as an empty mesh
     * @param offsetObj a {@link Vector3Field} (length must equal vertex count), a {@link Vector3Value} for a uniform offset, or anything else for a zero offset
     * @throws IllegalArgumentException if {@code offsetObj} is a {@link Vector3Field} whose length does not match {@code mesh.vertexCount()}
     * @return new offset mesh
     */
    public static HalfEdgeMesh apply(MeshTopology mesh, Object offsetObj) {
        if (mesh == null || mesh.vertexCount() == 0) {
            return new HalfEdgeMesh();
        }
        int n = mesh.vertexCount();
        Vector3Field field = null;
        Vector3Value uniform = null;
        if (offsetObj instanceof Vector3Field vf) {
            if (vf.length() != n) {
                throw new IllegalArgumentException("offset field length " + vf.length() + " != vertex count " + n);
            }
            field = vf;
        } else if (offsetObj instanceof Vector3Value vv) {
            uniform = vv;
        } else {
            uniform = new Vector3Value(0f, 0f, 0f);
        }

        float[] positions = new float[n * 3];
        int[] activeVertexOfId = new int[mesh.vertexIdBound()];
        Vector3f position = new Vector3f();
        for (int activeVertex = 0; activeVertex < n; activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            activeVertexOfId[vertexId] = activeVertex;
            mesh.vertexPosition(vertexId, position);
            int offset = activeVertex * 3;
            if (field != null) {
                positions[offset] = position.x + field.getX(activeVertex);
                positions[offset + 1] = position.y + field.getY(activeVertex);
                positions[offset + 2] = position.z + field.getZ(activeVertex);
            } else {
                positions[offset] = position.x + uniform.x();
                positions[offset + 1] = position.y + uniform.y();
                positions[offset + 2] = position.z + uniform.z();
            }
        }
        int[] faceSizes = new int[mesh.faceCount()];
        int corners = 0;
        for (int activeFace = 0; activeFace < faceSizes.length; activeFace++) {
            faceSizes[activeFace] = mesh.faceVertexCount(mesh.faceIdAt(activeFace));
            corners += faceSizes[activeFace];
        }
        int[] faceCorners = new int[corners];
        int outCorner = 0;
        for (int activeFace = 0; activeFace < faceSizes.length; activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            for (int corner = 0; corner < faceSizes[activeFace]; corner++) {
                faceCorners[outCorner++] = activeVertexOfId[mesh.faceVertexAt(faceId, corner)];
            }
        }
        HalfEdgeMesh out = HalfEdgeMeshEngine.bulkAllocateMixed(positions, faceSizes, faceCorners);
        out.computeNormals();
        return out;
    }
}
