package ixdar.geometry.mesh.data;
import java.util.Map;
import java.util.List;

import java.util.ArrayList;

import ixdar.geometry.mesh.data.SemanticPatchDecomposer.EdgeDihedrals;
import ixdar.geometry.mesh.data.representation.ArrayMesh;

/**
 * Per-vertex principal curvatures {@code κMax ≥ κMin} and their directions, estimated from the shape
 * operator over the 1-ring. The directions are unit tangent vectors, mutually orthogonal and
 * orthogonal to the vertex normal; a ridge runs along {@code dirMin}.
 *
 * <p>See also: Meyer 2003
 */
public final class PrincipalDirectionField {
    public static final int NUM_3 = 3;
    public static final float NUM_1e_20 = 1e-20f;
    public static final float NUM_1e_12 = 1e-12f;
    public static final float NUM_4 = 4f;
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_1 = 1f;
    public static final float NUM_0 = 0f;
    public static final int NUM_6 = 6;

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
     * Maximum principal curvature at vertex {@code v}.
     *
     * @param v vertex index
     * @return κMax (signed, in 1/length units)
     */
    public float kappaMax(int v) { return kappaMax[v]; }
    /**
     * Minimum principal curvature at vertex {@code v}.
     *
     * @param v vertex index
     * @return κMin, with {@code κMin <= κMax}
     */
    public float kappaMin(int v) { return kappaMin[v]; }

    /**
     * Write {@code dirMax(v)} into {@code out} (indices 0..2).
     *
     * @param v vertex index
     * @param out length-3 array to receive the unit tangent direction of maximum curvature
     */
    public void dirMax(int v, float[] out) {
        out[0] = dirMax[v * NUM_3];
        out[1] = dirMax[v * NUM_3 + 1];
        out[2] = dirMax[v * NUM_3 + 2];
    }

    /**
     * Write {@code dirMin(v)} into {@code out} (indices 0..2).
     *
     * @param v vertex index
     * @param out length-3 array to receive the unit tangent direction of minimum curvature
     */
    public void dirMin(int v, float[] out) {
        out[0] = dirMin[v * NUM_3];
        out[1] = dirMin[v * NUM_3 + 1];
        out[2] = dirMin[v * NUM_3 + 2];
    }

    /**
     * Write the unit vertex normal at {@code v} into {@code out} (indices 0..2).
     *
     * @param v vertex index
     * @param out length-3 array to receive the area-weighted vertex normal
     */
    public void vertexNormal(int v, float[] out) {
        out[0] = vertexNormals[v * NUM_3];
        out[1] = vertexNormals[v * NUM_3 + 1];
        out[2] = vertexNormals[v * NUM_3 + 2];
    }

    /**
     * Number of vertices for which curvatures and directions are stored.
     *
     * @return vertex count
     */
    public int vertexCount() { return kappaMax.length; }

    /**
     * Build the field for the given mesh. Reuses face normals from the
     * already-computed {@link EdgeDihedrals}.
     *
     * @param mesh source surface
     * @param ed precomputed edge / face dihedral data; supplies face normals and the edge map for 1-rings
     * @return populated principal-direction field for every vertex of {@code mesh}
     */
    public static PrincipalDirectionField compute(ArrayMesh mesh, EdgeDihedrals ed) {
        int nv = mesh.vertexCount();
        int[] faceIdx = mesh.copyFaceIndices();
        float[] positions = mesh.copyPositions();
        int faceCount = faceIdx.length / NUM_3;

        // Per-vertex normal: area-weighted average of incident face normals.
        float[] vertexNormals = new float[nv * NUM_3];
        float[] faceN = ed.faceNormals();
        for (int f = 0; f < faceCount; f++) {
            for (int k = 0; k < NUM_3; k++) {
                int v = faceIdx[f * NUM_3 + k];
                vertexNormals[v * NUM_3]     += faceN[f * NUM_3];
                vertexNormals[v * NUM_3 + 1] += faceN[f * NUM_3 + 1];
                vertexNormals[v * NUM_3 + 2] += faceN[f * NUM_3 + 2];
            }
        }
        for (int v = 0; v < nv; v++) {
            float nx = vertexNormals[v * NUM_3];
            float ny = vertexNormals[v * NUM_3 + 1];
            float nz = vertexNormals[v * NUM_3 + 2];
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > NUM_1e_20) {
                vertexNormals[v * NUM_3]     = nx / len;
                vertexNormals[v * NUM_3 + 1] = ny / len;
                vertexNormals[v * NUM_3 + 2] = nz / len;
            }
        }

        // Build 1-ring vertex neighbourhoods from edge map.
        int[][] ring = buildOneRing(mesh, ed, nv);

        float[] kappaMax = new float[nv];
        float[] kappaMin = new float[nv];
        float[] dirMax = new float[nv * NUM_3];
        float[] dirMin = new float[nv * NUM_3];

        // Temporary arrays reused across vertices.
        float[] t1 = new float[NUM_3];
        float[] t2 = new float[NUM_3];
        float[] nv_ = new float[NUM_3];

        for (int v = 0; v < nv; v++) {
            int[] neighbours = ring[v];
            if (neighbours == null || neighbours.length < 2) continue;

            nv_[0] = vertexNormals[v * NUM_3];
            nv_[1] = vertexNormals[v * NUM_3 + 1];
            nv_[2] = vertexNormals[v * NUM_3 + 2];
            buildTangentBasis(nv_, t1, t2);

            // Accumulate the 2x2 tangent-plane shape operator:
            //   II(u, u) ≈ (n_u - n_v) · edge_dir / |edge_dir|
            // Build a weighted sum over the 1-ring of II(edge_dir, edge_dir)
            // projected into (t1, t2). Gives a 2x2 symmetric matrix.
            float m00 = 0, m01 = 0, m11 = 0;
            float wTotal = 0;
            for (int u : neighbours) {
                float ex = positions[u * NUM_3]     - positions[v * NUM_3];
                float ey = positions[u * NUM_3 + 1] - positions[v * NUM_3 + 1];
                float ez = positions[u * NUM_3 + 2] - positions[v * NUM_3 + 2];
                float elen = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
                if (elen < NUM_1e_12) continue;
                // Tangential part of the edge (remove normal component).
                float edn = ex * nv_[0] + ey * nv_[1] + ez * nv_[2];
                float tx = ex - edn * nv_[0];
                float ty = ey - edn * nv_[1];
                float tz = ez - edn * nv_[2];
                float tlen = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
                if (tlen < NUM_1e_12) continue;
                tx /= tlen; ty /= tlen; tz /= tlen;

                float nux = vertexNormals[u * NUM_3]     - nv_[0];
                float nuy = vertexNormals[u * NUM_3 + 1] - nv_[1];
                float nuz = vertexNormals[u * NUM_3 + 2] - nv_[2];
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
            if (wTotal < NUM_1e_12) continue;
            m00 /= wTotal;
            m01 /= wTotal;
            m11 /= wTotal;

            // Closed-form 2x2 symmetric eigendecomposition.
            float trace = m00 + m11;
            float diff = m00 - m11;
            float disc = (float) Math.sqrt(diff * diff + NUM_4 * m01 * m01);
            float kA = NUM_0_5 * (trace + disc);
            float kB = NUM_0_5 * (trace - disc);
            // Eigenvector of λ=kA: proportional to (m01, kA - m00) unless m01 ≈ 0.
            float eaX, eaY;
            if (Math.abs(m01) > NUM_1e_12) {
                eaX = m01;
                eaY = kA - m00;
            } else {
                // Diagonal; pick axis-aligned eigenvector.
                eaX = kA >= m11 ? NUM_1 : NUM_0;
                eaY = kA >= m11 ? NUM_0 : NUM_1;
            }
            float elen = (float) Math.sqrt(eaX * eaX + eaY * eaY);
            if (elen < NUM_1e_20) { eaX = 1; eaY = 0; elen = 1; }
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
            kappaMax[v] = kmax;
            kappaMin[v] = kmin;

            // Lift eigenvectors from (t1, t2) basis back to 3D.
            dirMax[v * NUM_3]     = maxAx * t1[0] + maxAy * t2[0];
            dirMax[v * NUM_3 + 1] = maxAx * t1[1] + maxAy * t2[1];
            dirMax[v * NUM_3 + 2] = maxAx * t1[2] + maxAy * t2[2];
            dirMin[v * NUM_3]     = minAx * t1[0] + minAy * t2[0];
            dirMin[v * NUM_3 + 1] = minAx * t1[1] + minAy * t2[1];
            dirMin[v * NUM_3 + 2] = minAx * t1[2] + minAy * t2[2];
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
        if (l1 < NUM_1e_20) { t1[0] = 1; t1[1] = 0; t1[2] = 0; l1 = 1; }
        t1[0] /= l1; t1[1] /= l1; t1[2] /= l1;
        // t2 = n × t1 (already unit)
        t2[0] = n[1] * t1[2] - n[2] * t1[1];
        t2[1] = n[2] * t1[0] - n[0] * t1[2];
        t2[2] = n[0] * t1[1] - n[1] * t1[0];
    }

    private static int[][] buildOneRing(ArrayMesh mesh, EdgeDihedrals ed, int nv) {
        List<List<Integer>> tmp = new ArrayList<>(nv);
        for (int i = 0; i < nv; i++) tmp.add(new ArrayList<>(NUM_6));
        for (Map.Entry<Long, int[]> e : ed.edgeFaces().entrySet()) {
            long key = e.getKey();
            int u = EdgeKey.minVertex(key);
            int v = EdgeKey.maxVertex(key);
            tmp.get(u).add(v);
            tmp.get(v).add(u);
        }
        int[][] out = new int[nv][];
        for (int i = 0; i < nv; i++) {
            List<Integer> list = tmp.get(i);
            int[] arr = new int[list.size()];
            for (int j = 0; j < list.size(); j++) arr[j] = list.get(j);
            out[i] = arr;
        }
        return out;
    }
}
