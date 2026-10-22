package ixdar.geometry.mesh.csg;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.bundle.CornerUvField;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.TriangulationRule;

/**
 * Splits a mesh into triangles along each quad's shorter diagonal, recording which face every
 * triangle came from so the boolean's output can be traced back to the original quads.
 *
 * <p>See also: NHE*19 Section 3.1
 */
public final class QuadTriangulation {

    public static final int THREE = 3;

    /** Source mesh being triangulated. */
    public final MeshTopology mesh;

    /** Vertex positions, three floats per vertex, in the source mesh's active-vertex order. */
    public float[] positions;

    /** Triangle corners as indices into {@link #positions}, three per triangle. */
    public int[] triangles;

    /** Face id in {@link #mesh} that each triangle was split from, one per triangle. */
    public int[] triangleSourceFace;

    /**
     * {@code u} per triangle corner, three per triangle, or null when the operand carried no UVs or
     * they could not follow the split.
     */
    public double[] cornerU;

    /** {@code v} per triangle corner, the same length and order as {@link #cornerU}. */
    public double[] cornerV;

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

        triangleSourceFace = new int[mesh.triangleCount()];
        triangles = mesh.triangulate(TriangulationRule.SHORTER_DIAGONAL, triangleSourceFace);
        for (int corner = 0; corner < triangles.length; corner++) {
            triangles[corner] = activeVertexByVertexId[triangles[corner]];
        }
        return this;
    }

    /**
     * Triangulate and carry a per-corner UV field onto the triangles. A corner field is indexed
     * three per face, so one that does not cover exactly this mesh's triangles is left behind, the
     * same rule the textured draw applies.
     *
     * @param uv per-corner UVs over {@link #mesh}, or null for an operand without textures
     * @return this, with {@link #cornerU} null unless the field covered the mesh
     */
    public QuadTriangulation build(CornerUvField uv) {
        build();
        if (uv == null || uv.faceCount() != mesh.faceCount()
                || triangleSourceFace.length != mesh.faceCount()) {
            return this;
        }
        cornerU = uv.cornerU;
        cornerV = uv.cornerV;
        return this;
    }
}
