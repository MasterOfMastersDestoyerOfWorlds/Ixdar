package ixdar.geometry.mesh.data.analysis.curvature;
import java.util.Map;
import java.util.List;

import java.util.ArrayList;

import ixdar.geometry.mesh.data.analysis.segmentation.SemanticPatchDecomposer;
import ixdar.geometry.mesh.data.analysis.segmentation.SemanticPatchDecomposer.EdgeDihedrals;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.EdgeKey;

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
    public static final int TYPICAL_VALENCE = 6;

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
     * Write the direction of maximum curvature at a vertex into {@code out} (indices 0..2).
     *
     * @param vertexId vertex id in the mesh the field was computed on
     * @param out length-3 array to receive the unit tangent direction of maximum curvature
     */
    public void dirMax(int vertexId, float[] out) {
        out[0] = dirMax[vertexId * 3];
        out[1] = dirMax[vertexId * 3 + 1];
        out[2] = dirMax[vertexId * 3 + 2];
    }

    /**
     * Write the direction of minimum curvature at a vertex into {@code out} (indices 0..2).
     *
     * @param vertexId vertex id in the mesh the field was computed on
     * @param out length-3 array to receive the unit tangent direction of minimum curvature
     */
    public void dirMin(int vertexId, float[] out) {
        out[0] = dirMin[vertexId * 3];
        out[1] = dirMin[vertexId * 3 + 1];
        out[2] = dirMin[vertexId * 3 + 2];
    }

    /**
     * Write the unit vertex normal at a vertex into {@code out} (indices 0..2).
     *
     * @param vertexId vertex id in the mesh the field was computed on
     * @param out length-3 array to receive the area-weighted vertex normal
     */
    public void vertexNormal(int vertexId, float[] out) {
        out[0] = vertexNormals[vertexId * 3];
        out[1] = vertexNormals[vertexId * 3 + 1];
        out[2] = vertexNormals[vertexId * 3 + 2];
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
        int vertexIdBound = mesh.vertexIdBound();
        float[] positions = ed.positions();

        // Per-vertex normal: average of the incident fan triangles' normals, triangle by triangle.
        float[] vertexNormals = new float[vertexIdBound * 3];
        float[] faceN = ed.faceNormals();
        int[] triangles = ed.triangles();
        for (int corner = 0; corner < triangles.length; corner++) {
            int vertexId = triangles[corner];
            int triangle = corner / 3;
            vertexNormals[vertexId * 3]     += faceN[triangle * 3];
            vertexNormals[vertexId * 3 + 1] += faceN[triangle * 3 + 1];
            vertexNormals[vertexId * 3 + 2] += faceN[triangle * 3 + 2];
        }
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            float nx = vertexNormals[vertexId * 3];
            float ny = vertexNormals[vertexId * 3 + 1];
            float nz = vertexNormals[vertexId * 3 + 2];
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > ZERO_VECTOR_EPSILON) {
                vertexNormals[vertexId * 3]     = nx / len;
                vertexNormals[vertexId * 3 + 1] = ny / len;
                vertexNormals[vertexId * 3 + 2] = nz / len;
            }
        }

        // Build 1-ring vertex neighbourhoods from edge map.
        int[][] ring = oneRings(ed, vertexIdBound);

        float[] kappaMax = new float[vertexIdBound];
        float[] kappaMin = new float[vertexIdBound];
        float[] dirMax = new float[vertexIdBound * 3];
        float[] dirMin = new float[vertexIdBound * 3];

        // Temporary arrays reused across vertices.
        float[] t1 = new float[3];
        float[] t2 = new float[3];
        float[] nv_ = new float[3];

        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            int[] neighbours = ring[vertexId];
            if (neighbours == null || neighbours.length < 2) continue;

            nv_[0] = vertexNormals[vertexId * 3];
            nv_[1] = vertexNormals[vertexId * 3 + 1];
            nv_[2] = vertexNormals[vertexId * 3 + 2];
            buildTangentBasis(nv_, t1, t2);

            // Accumulate the 2x2 tangent-plane shape operator:
            //   II(u, u) ≈ (n_u - n_v) · edge_dir / |edge_dir|
            // Build a weighted sum over the 1-ring of II(edge_dir, edge_dir)
            // projected into (t1, t2). Gives a 2x2 symmetric matrix.
            float m00 = 0, m01 = 0, m11 = 0;
            float wTotal = 0;
            for (int neighbourId : neighbours) {
                float ex = positions[neighbourId * 3]     - positions[vertexId * 3];
                float ey = positions[neighbourId * 3 + 1] - positions[vertexId * 3 + 1];
                float ez = positions[neighbourId * 3 + 2] - positions[vertexId * 3 + 2];
                float elen = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
                if (elen < MIN_WEIGHT_EPSILON) continue;
                // Tangential part of the edge (remove normal component).
                float edn = ex * nv_[0] + ey * nv_[1] + ez * nv_[2];
                float tx = ex - edn * nv_[0];
                float ty = ey - edn * nv_[1];
                float tz = ez - edn * nv_[2];
                float tlen = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
                if (tlen < MIN_WEIGHT_EPSILON) continue;
                tx /= tlen; ty /= tlen; tz /= tlen;

                float nux = vertexNormals[neighbourId * 3]     - nv_[0];
                float nuy = vertexNormals[neighbourId * 3 + 1] - nv_[1];
                float nuz = vertexNormals[neighbourId * 3 + 2] - nv_[2];
                // Normal curvature along this edge ≈ -(Δn · t) / elen.
                float kEdge = -(nux * tx + nuy * ty + nuz * tz) / elen;

                // Project t into (t1, t2) basis.
                float a = tx * t1[0] + ty * t1[1] + tz * t1[2];
                float b = tx * t2[0] + ty * t2[1] + tz * t2[2];

                // Accumulate the outer product a[a; b] * kEdge weighted by
                // edge length (longer edges sample the surface more).
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

            // Closed-form 2x2 symmetric eigendecomposition.
            float trace = m00 + m11;
            float diff = m00 - m11;
            float disc = (float) Math.sqrt(diff * diff + 4f * m01 * m01);
            float kA = 0.5f * (trace + disc);
            float kB = 0.5f * (trace - disc);
            // Eigenvector of λ=kA: proportional to (m01, kA - m00) unless m01 ≈ 0.
            float eaX, eaY;
            if (Math.abs(m01) > MIN_WEIGHT_EPSILON) {
                eaX = m01;
                eaY = kA - m00;
            } else {
                // Diagonal; pick axis-aligned eigenvector.
                eaX = kA >= m11 ? 1f : 0f;
                eaY = kA >= m11 ? 0f : 1f;
            }
            float elen = (float) Math.sqrt(eaX * eaX + eaY * eaY);
            if (elen < ZERO_VECTOR_EPSILON) { eaX = 1; eaY = 0; elen = 1; }
            eaX /= elen; eaY /= elen;
            // Orthogonal eigenvector in 2D.
            float ebX = -eaY;
            float ebY = eaX;

            // κMax ≥ κMin; swap if needed.
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

            // Lift eigenvectors from (t1, t2) basis back to 3D.
            dirMax[vertexId * 3]     = maxAx * t1[0] + maxAy * t2[0];
            dirMax[vertexId * 3 + 1] = maxAx * t1[1] + maxAy * t2[1];
            dirMax[vertexId * 3 + 2] = maxAx * t1[2] + maxAy * t2[2];
            dirMin[vertexId * 3]     = minAx * t1[0] + minAy * t2[0];
            dirMin[vertexId * 3 + 1] = minAx * t1[1] + minAy * t2[1];
            dirMin[vertexId * 3 + 2] = minAx * t1[2] + minAy * t2[2];
        }

        return new PrincipalDirectionField(kappaMax, kappaMin, dirMax, dirMin, vertexNormals);
    }

    /**
     * Builds an orthonormal 2D tangent basis (t1, t2) perpendicular to n.
     *
     * @param n unit normal (length-3)
     * @param t1 length-3 output for the first tangent axis
     * @param t2 length-3 output for the second tangent axis ({@code n x t1})
     */
    private static void buildTangentBasis(float[] n, float[] t1, float[] t2) {
        float ax = Math.abs(n[0]);
        float ay = Math.abs(n[1]);
        float az = Math.abs(n[2]);
        float helperX, helperY, helperZ;
        if (ax < ay && ax < az) { helperX = 1; helperY = 0; helperZ = 0; }
        else if (ay < az)        { helperX = 0; helperY = 1; helperZ = 0; }
        else                     { helperX = 0; helperY = 0; helperZ = 1; }
        // t1 = normalize(helper × n)
        t1[0] = helperY * n[2] - helperZ * n[1];
        t1[1] = helperZ * n[0] - helperX * n[2];
        t1[2] = helperX * n[1] - helperY * n[0];
        float l1 = (float) Math.sqrt(t1[0] * t1[0] + t1[1] * t1[1] + t1[2] * t1[2]);
        if (l1 < ZERO_VECTOR_EPSILON) { t1[0] = 1; t1[1] = 0; t1[2] = 0; l1 = 1; }
        t1[0] /= l1; t1[1] /= l1; t1[2] /= l1;
        // t2 = n × t1 (already unit)
        t2[0] = n[1] * t1[2] - n[2] * t1[1];
        t2[1] = n[2] * t1[0] - n[0] * t1[2];
        t2[2] = n[0] * t1[1] - n[1] * t1[0];
    }

    /**
     * Each vertex's 1-ring neighbours, in the iteration order of {@code ed}'s edge map, which the
     * curvature sums and the crest and saddle walks depend on.
     *
     * @param ed edge map whose keys join vertex ids
     * @param vertexIdBound the mesh's vertex id bound, the length of the result
     * @return neighbour vertex ids per vertex id; empty for ids the mesh does not hold
     */
    public static int[][] oneRings(EdgeDihedrals ed, int vertexIdBound) {
        List<List<Integer>> neighbours = new ArrayList<>(vertexIdBound);
        for (int vertexId = 0; vertexId < vertexIdBound; vertexId++) {
            neighbours.add(new ArrayList<>(TYPICAL_VALENCE));
        }
        for (Map.Entry<Long, int[]> entry : ed.edgeFaces().entrySet()) {
            long key = entry.getKey();
            int lowerId = EdgeKey.minVertex(key);
            int upperId = EdgeKey.maxVertex(key);
            neighbours.get(lowerId).add(upperId);
            neighbours.get(upperId).add(lowerId);
        }
        int[][] out = new int[vertexIdBound][];
        for (int vertexId = 0; vertexId < vertexIdBound; vertexId++) {
            List<Integer> list = neighbours.get(vertexId);
            int[] arr = new int[list.size()];
            for (int slot = 0; slot < list.size(); slot++) arr[slot] = list.get(slot);
            out[vertexId] = arr;
        }
        return out;
    }
}
