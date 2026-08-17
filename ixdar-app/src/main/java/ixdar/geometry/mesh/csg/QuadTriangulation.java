package ixdar.geometry.mesh.csg;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;

/**
 * Splits a mesh into triangles along each quad's shorter diagonal, recording which face every
 * triangle came from so the boolean's output can be traced back to the original quads.
 *
 * <p>See also: NHE*19 Section 3.1
 */
public final class QuadTriangulation {

    /** Coordinates per vertex, and equally vertices per triangle. */
    public static final int THREE = 3;

    /** Vertices in a quad. */
    public static final int QUAD_VERTEX_COUNT = 4;

    /** Source mesh being triangulated. */
    public final MeshTopology mesh;

    /** Vertex positions, three floats per vertex, in the source mesh's active-vertex order. */
    public float[] positions;

    /** Triangle corners as indices into {@link #positions}, three per triangle. */
    public int[] triangles;

    /** Face id in {@link #mesh} that each triangle was split from, one per triangle. */
    public int[] triangleSourceFace;

    /** Active-vertex index per vertex id, or {@code -1} for ids the mesh does not hold. */
    private int[] activeVertexByVertexId;

    /**
     * Stores the mesh to triangulate.
     *
     * @param mesh source mesh, whose faces may be any mix of triangles, quads and larger polygons
     */
    public QuadTriangulation(MeshTopology mesh) {
        this.mesh = mesh;
    }

    /**
     * Triangulate, filling {@link #positions}, {@link #triangles} and {@link #triangleSourceFace}.
     *
     * @return this
     */
    public QuadTriangulation build() {
        int vertexCount = mesh.vertexCount();
        positions = new float[vertexCount * THREE];
        int maximumVertexId = -1;
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            maximumVertexId = Math.max(maximumVertexId, mesh.vertexIdAt(activeVertex));
        }
        activeVertexByVertexId = new int[maximumVertexId + 1];
        Arrays.fill(activeVertexByVertexId, -1);
        Vector3f position = new Vector3f();
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            activeVertexByVertexId[vertexId] = activeVertex;
            mesh.vertexPosition(vertexId, position);
            positions[activeVertex * THREE] = position.x;
            positions[activeVertex * THREE + 1] = position.y;
            positions[activeVertex * THREE + 2] = position.z;
        }

        int faceCount = mesh.faceCount();
        int triangleCount = 0;
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            triangleCount += mesh.faceVertexCount(mesh.faceIdAt(activeFace)) - 2;
        }
        triangles = new int[triangleCount * THREE];
        triangleSourceFace = new int[triangleCount];

        int corner = 0;
        int triangle = 0;
        int[] faceVertices = new int[QUAD_VERTEX_COUNT];
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            int faceVertexCount = mesh.faceVertexCount(faceId);
            if (faceVertexCount > faceVertices.length) {
                faceVertices = new int[faceVertexCount];
            }
            for (int index = 0; index < faceVertexCount; index++) {
                faceVertices[index] = activeVertexByVertexId[mesh.faceVertexAt(faceId, index)];
            }
            if (faceVertexCount == QUAD_VERTEX_COUNT && !firstDiagonalIsShorter(faceVertices)) {
                // Fan from corner 1 rather than 0, which is the same as cutting the other diagonal.
                for (int index = 1; index < faceVertexCount - 1; index++) {
                    triangles[corner++] = faceVertices[1];
                    triangles[corner++] = faceVertices[(index + 1) % faceVertexCount];
                    triangles[corner++] = faceVertices[(index + 2) % faceVertexCount];
                    triangleSourceFace[triangle++] = faceId;
                }
                continue;
            }
            for (int index = 1; index < faceVertexCount - 1; index++) {
                triangles[corner++] = faceVertices[0];
                triangles[corner++] = faceVertices[index];
                triangles[corner++] = faceVertices[index + 1];
                triangleSourceFace[triangle++] = faceId;
            }
        }
        return this;
    }

    /**
     * Whether a quad's 0–2 diagonal is no longer than its 1–3 diagonal, which is the one QuadMixer
     * cuts along so the two triangles stay as well-shaped as the quad allows.
     *
     * @param faceVertices the quad's four active-vertex indices, in order around the face
     * @return true when the 0–2 diagonal should be cut
     */
    private boolean firstDiagonalIsShorter(int[] faceVertices) {
        return squaredDistance(faceVertices[0], faceVertices[2])
                <= squaredDistance(faceVertices[1], faceVertices[THREE]);
    }

    /**
     * Squared distance between two vertices of {@link #positions}.
     *
     * @param activeVertexA first active-vertex index
     * @param activeVertexB second active-vertex index
     * @return the squared distance, which orders the same as the distance
     */
    private float squaredDistance(int activeVertexA, int activeVertexB) {
        float deltaX = positions[activeVertexA * THREE] - positions[activeVertexB * THREE];
        float deltaY = positions[activeVertexA * THREE + 1] - positions[activeVertexB * THREE + 1];
        float deltaZ = positions[activeVertexA * THREE + 2] - positions[activeVertexB * THREE + 2];
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
    }
}
