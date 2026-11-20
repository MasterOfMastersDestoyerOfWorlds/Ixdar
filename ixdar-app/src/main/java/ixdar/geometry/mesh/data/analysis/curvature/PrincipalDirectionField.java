package ixdar.geometry.mesh.data.analysis.curvature;

import java.util.stream.IntStream;

import org.joml.Matrix3f;
import org.joml.Vector3f;

import ixdar.geometry.mesh.data.analysis.segmentation.SemanticPatchDecomposer;
import ixdar.geometry.mesh.data.analysis.segmentation.SemanticPatchDecomposer.EdgeDihedrals;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.data.representation.TriangleSoup;

/**
 * Per-vertex principal curvatures {@code κMax ≥ κMin} and their directions, estimated from the shape
 * operator over the 1-ring. The directions are unit tangent vectors, mutually orthogonal and
 * orthogonal to the vertex normal; a ridge runs along {@code dirMin}.
 *
 * <p>See also: Meyer 2003
 */
public final class PrincipalDirectionField {
    public static final float ZERO_VECTOR_EPSILON = 1e-20f;
    public static final float MIN_WEIGHT_EPSILON = 1e-12f;
    public static final int VERTICES_PER_TASK = 4096;

    private final float[] kappaMax;
    private final float[] kappaMin;
    private final PackedPoints dirMax;
    private final PackedPoints dirMin;
    private final PackedPoints vertexNormals;

    private PrincipalDirectionField(float[] kappaMax, float[] kappaMin,
                                    PackedPoints dirMax, PackedPoints dirMin,
                                    PackedPoints vertexNormals) {
        this.kappaMax = kappaMax;
        this.kappaMin = kappaMin;
        this.dirMax = dirMax;
        this.dirMin = dirMin;
        this.vertexNormals = vertexNormals;
    }

    /**
     * Maximum principal curvature at a vertex.
     *
     * @param vertexId vertex id in the mesh the field was computed on
     * @return κMax (signed, in 1/length units)
     */
    public float kappaMax(int vertexId) { return kappaMax[vertexId]; }
    /**
     * Minimum principal curvature at a vertex.
     *
     * @param vertexId vertex id in the mesh the field was computed on
     * @return κMin, with {@code κMin <= κMax}
     */
    public float kappaMin(int vertexId) { return kappaMin[vertexId]; }

    /**
     * Direction of maximum curvature at a vertex.
     *
     * @param vertexId vertex id in the mesh the field was computed on
     * @return the unit tangent direction of maximum curvature, as a new vector
     */
    public Vector3f dirMax(int vertexId) {
        return dirMax.at(vertexId);
    }

    /**
     * Direction of minimum curvature at a vertex.
     *
     * @param vertexId vertex id in the mesh the field was computed on
     * @return the unit tangent direction of minimum curvature, as a new vector
     */
    public Vector3f dirMin(int vertexId) {
        return dirMin.at(vertexId);
    }

    /**
     * Unit vertex normal at a vertex.
     *
     * @param vertexId vertex id in the mesh the field was computed on
     * @return the area-weighted vertex normal, as a new vector
     */
    public Vector3f vertexNormal(int vertexId) {
        return vertexNormals.at(vertexId);
    }

    /**
     * Length of the id-indexed arrays: the vertex id bound of the mesh the field was computed
     * on. Slots of ids the mesh does not hold read as zero.
     *
     * @return vertex id bound
     */
    public int vertexIdBound() { return kappaMax.length; }

    /**
     * Build the field for the given mesh, read in place and indexed by vertex id. Faces of any
     * size are fanned from their first corner, matching the fan triangles of {@code ed}.
     *
     * @param mesh source surface, of any polygon sizes
     * @param ed {@link SemanticPatchDecomposer#computeEdgeDihedrals} of {@code mesh}: one face
     *           normal per fan triangle in active-face order, and edge keys of vertex ids
     * @return populated principal-direction field for every vertex of {@code mesh}
     */
    public static PrincipalDirectionField compute(MeshTopology mesh, EdgeDihedrals ed) {
        return compute(mesh, ed.positions(), ed.triangles(), ed.faceNormals(),
                TriangleSoup.oneRings(ed.triangles(), mesh.vertexIdBound()));
    }

    /**
     * Build the field from the mesh's fan triangles and each vertex's 1-ring, the order of whose
     * neighbours the curvature sums follow; vertices are estimated in parallel.
     *
     * @param mesh      source surface, of any polygon sizes
     * @param points    vertex positions by vertex id
     * @param triangles fan triangle corners as vertex ids, three per triangle
     * @param faceN     unit normal per fan triangle
     * @param ring      neighbour vertex ids per vertex id
     * @return populated principal-direction field for every vertex of {@code mesh}
     */
    public static PrincipalDirectionField compute(MeshTopology mesh, PackedPoints points,
            int[] triangles, PackedPoints faceN, int[][] ring) {
        int vertexIdBound = mesh.vertexIdBound();

        PackedPoints vertexNormals = SemanticPatchDecomposer.vertexNormals(triangles, faceN, vertexIdBound);

        float[] kappaMax = new float[vertexIdBound];
        float[] kappaMin = new float[vertexIdBound];
        PackedPoints dirMax = new PackedPoints(vertexIdBound);
        PackedPoints dirMin = new PackedPoints(vertexIdBound);

        int vertexCount = mesh.vertexCount();
        IntStream.range(0, (vertexCount + VERTICES_PER_TASK - 1) / VERTICES_PER_TASK).parallel()
                .forEach(task -> {
            for (int activeVertex = task * VERTICES_PER_TASK;
                    activeVertex < Math.min(vertexCount, (task + 1) * VERTICES_PER_TASK);
                    activeVertex++) {
                int vertexId = mesh.vertexIdAt(activeVertex);
                int[] neighbours = ring[vertexId];
                if (neighbours == null || neighbours.length < 2) continue;

                Vector3f normal = vertexNormals.at(vertexId);
                if (normal.lengthSquared() == 0f) continue;
                Matrix3f frame = MeshTopology.tangentFrame(normal);
                Vector3f t1 = frame.getColumn(0, new Vector3f());
                Vector3f t2 = frame.getColumn(1, new Vector3f());
                Vector3f position = points.at(vertexId);

                float m00 = 0, m01 = 0, m11 = 0;
                float wTotal = 0;
                for (int neighbourId : neighbours) {
                    Vector3f edge = points.at(neighbourId).sub(position);
                    float elen = edge.length();
                    if (elen < MIN_WEIGHT_EPSILON) continue;
                    Vector3f along = new Vector3f(edge).fma(-edge.dot(normal), normal);
                    if (along.length() < MIN_WEIGHT_EPSILON) continue;
                    along.normalize();

                    Vector3f normalChange = vertexNormals.at(neighbourId).sub(normal);
                    float kEdge = -normalChange.dot(along) / elen;

                    float a = along.dot(t1);
                    float b = along.dot(t2);

                    float w = elen;
                    m00 += w * a * a * kEdge;
                    m01 += w * a * b * kEdge;
                    m11 += w * b * b * kEdge;
                    wTotal += w;
                }
                if (wTotal < MIN_WEIGHT_EPSILON) continue;
                m00 /= wTotal;
                m01 /= wTotal;
                m11 /= wTotal;

                float trace = m00 + m11;
                float diff = m00 - m11;
                float disc = (float) Math.sqrt(diff * diff + 4f * m01 * m01);
                float kA = 0.5f * (trace + disc);
                float kB = 0.5f * (trace - disc);
                float eaX, eaY;
                if (Math.abs(m01) > MIN_WEIGHT_EPSILON) {
                    eaX = m01;
                    eaY = kA - m00;
                } else {
                    eaX = kA >= m11 ? 1f : 0f;
                    eaY = kA >= m11 ? 0f : 1f;
                }
                float elen = (float) Math.sqrt(eaX * eaX + eaY * eaY);
                if (elen < ZERO_VECTOR_EPSILON) { eaX = 1; eaY = 0; elen = 1; }
                eaX /= elen; eaY /= elen;
                float ebX = -eaY;
                float ebY = eaX;

                float kmax, kmin, maxAx, maxAy, minAx, minAy;
                if (kA >= kB) {
                    kmax = kA; kmin = kB;
                    maxAx = eaX; maxAy = eaY;
                    minAx = ebX; minAy = ebY;
                } else {
                    kmax = kB; kmin = kA;
                    maxAx = ebX; maxAy = ebY;
                    minAx = eaX; minAy = eaY;
                }
                kappaMax[vertexId] = kmax;
                kappaMin[vertexId] = kmin;

                dirMax.set(vertexId, new Vector3f(t1).mul(maxAx).fma(maxAy, t2));
                dirMin.set(vertexId, new Vector3f(t1).mul(minAx).fma(minAy, t2));
            }
        });

        return new PrincipalDirectionField(kappaMax, kappaMin, dirMax, dirMin, vertexNormals);
    }
}
