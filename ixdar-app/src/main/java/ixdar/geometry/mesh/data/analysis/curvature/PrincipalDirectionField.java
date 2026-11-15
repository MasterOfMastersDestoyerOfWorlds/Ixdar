package ixdar.geometry.mesh.data.analysis.curvature;

import java.util.stream.IntStream;

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
    private final float[] dirMax;   // nv * 3, packed xyz
    private final float[] dirMin;   // nv * 3, packed xyz
    private final float[] vertexNormals;  // nv * 3, packed xyz

    private PrincipalDirectionField(float[] kappaMax, float[] kappaMin,
                                    float[] dirMax, float[] dirMin,
                                    float[] vertexNormals) {
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
        return PackedPoints.at(dirMax, vertexId * 3);
    }

    /**
     * Direction of minimum curvature at a vertex.
     *
     * @param vertexId vertex id in the mesh the field was computed on
     * @return the unit tangent direction of minimum curvature, as a new vector
     */
    public Vector3f dirMin(int vertexId) {
        return PackedPoints.at(dirMin, vertexId * 3);
    }

    /**
     * Unit vertex normal at a vertex.
     *
     * @param vertexId vertex id in the mesh the field was computed on
     * @return the area-weighted vertex normal, as a new vector
     */
    public Vector3f vertexNormal(int vertexId) {
        return PackedPoints.at(vertexNormals, vertexId * 3);
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
     * @param positions vertex positions by vertex id, three floats each
     * @param triangles fan triangle corners as vertex ids, three per triangle
     * @param faceN     unit normal per fan triangle, three floats each
     * @param ring      neighbour vertex ids per vertex id
     * @return populated principal-direction field for every vertex of {@code mesh}
     */
    public static PrincipalDirectionField compute(MeshTopology mesh, float[] positions,
            int[] triangles, float[] faceN, int[][] ring) {
        int vertexIdBound = mesh.vertexIdBound();

        float[] vertexNormals = SemanticPatchDecomposer.vertexNormals(triangles, faceN,
                vertexIdBound);

        float[] kappaMax = new float[vertexIdBound];
        float[] kappaMin = new float[vertexIdBound];
        float[] dirMax = new float[vertexIdBound * 3];
        float[] dirMin = new float[vertexIdBound * 3];

        int vertexCount = mesh.vertexCount();
        IntStream.range(0, (vertexCount + VERTICES_PER_TASK - 1) / VERTICES_PER_TASK).parallel()
                .forEach(task -> {
            for (int activeVertex = task * VERTICES_PER_TASK;
                    activeVertex < Math.min(vertexCount, (task + 1) * VERTICES_PER_TASK);
                    activeVertex++) {
                int vertexId = mesh.vertexIdAt(activeVertex);
                int[] neighbours = ring[vertexId];
                if (neighbours == null || neighbours.length < 2) continue;

                Vector3f normal = PackedPoints.at(vertexNormals, vertexId * 3);
                // First tangent: the normal's least dominant axis crossed with the normal.
                float ax = Math.abs(normal.x);
                float ay = Math.abs(normal.y);
                float az = Math.abs(normal.z);
                Vector3f t1 = (ax < ay && ax < az ? new Vector3f(1, 0, 0)
                        : ay < az ? new Vector3f(0, 1, 0) : new Vector3f(0, 0, 1)).cross(normal);
                float l1 = t1.length();
                if (l1 < ZERO_VECTOR_EPSILON) {
                    t1.set(1, 0, 0);
                } else {
                    t1.div(l1);
                }
                Vector3f t2 = normal.cross(t1, new Vector3f());
                Vector3f position = PackedPoints.at(positions, vertexId * 3);

                float m00 = 0, m01 = 0, m11 = 0;
                float wTotal = 0;
                for (int neighbourId : neighbours) {
                    Vector3f edge = PackedPoints.at(positions, neighbourId * 3).sub(position);
                    float elen = edge.length();
                    if (elen < MIN_WEIGHT_EPSILON) continue;
                    Vector3f along = new Vector3f(normal).mul(-edge.dot(normal)).add(edge);
                    if (along.length() < MIN_WEIGHT_EPSILON) continue;
                    along.normalize();

                    Vector3f normalChange = PackedPoints.at(vertexNormals, neighbourId * 3).sub(normal);
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

                PackedPoints.store(dirMax, vertexId * 3, new Vector3f(t1).mul(maxAx).fma(maxAy, t2));
                PackedPoints.store(dirMin, vertexId * 3, new Vector3f(t1).mul(minAx).fma(minAy, t2));
            }
        });

        return new PrincipalDirectionField(kappaMax, kappaMin, dirMax, dirMin, vertexNormals);
    }
}
