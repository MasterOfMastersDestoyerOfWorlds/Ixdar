package unit.quadlayout.solver;

import ixdar.geometry.mesh.data.ArrayMesh;

/**
 * Helper for tests: builds a triangulated unit cube ([0,1]^3) with the six
 * faces subdivided into an N&times;N grid each. Vertices on shared edges are
 * deduplicated by index lookup, so STVD adjacency wraps faces correctly.
 */
public final class CubeBuilder {
    private final int n;
    public final float[] positions;
    public final int[] indices;
    private final int[][][] vertId; // [face][i][j]

    public CubeBuilder(int n) {
        this.n = n;
        int verticesPerFace = (n + 1) * (n + 1);
        // Worst case 6 * verticesPerFace; we'll dedup edges/corners.
        java.util.Map<Long, Integer> idMap = new java.util.HashMap<>();
        java.util.List<float[]> posList = new java.util.ArrayList<>();
        vertId = new int[6][n + 1][n + 1];
        for (int f = 0; f < 6; f++) {
            for (int i = 0; i <= n; i++) {
                for (int j = 0; j <= n; j++) {
                    float[] p = facePoint(f, i, j);
                    long key = quantize(p);
                    Integer existing = idMap.get(key);
                    int id;
                    if (existing == null) {
                        id = posList.size();
                        posList.add(p);
                        idMap.put(key, id);
                    } else {
                        id = existing;
                    }
                    vertId[f][i][j] = id;
                }
            }
        }
        this.positions = new float[posList.size() * 3];
        for (int k = 0; k < posList.size(); k++) {
            positions[3 * k] = posList.get(k)[0];
            positions[3 * k + 1] = posList.get(k)[1];
            positions[3 * k + 2] = posList.get(k)[2];
        }
        java.util.List<Integer> tris = new java.util.ArrayList<>();
        for (int f = 0; f < 6; f++) {
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    int v00 = vertId[f][i][j];
                    int v10 = vertId[f][i + 1][j];
                    int v01 = vertId[f][i][j + 1];
                    int v11 = vertId[f][i + 1][j + 1];
                    tris.add(v00); tris.add(v10); tris.add(v11);
                    tris.add(v00); tris.add(v11); tris.add(v01);
                }
            }
        }
        this.indices = new int[tris.size()];
        for (int k = 0; k < tris.size(); k++) indices[k] = tris.get(k);
    }

    public ArrayMesh build() {
        return new ArrayMesh(positions, null, indices, 3);
    }

    public int cornerVertex(boolean x, boolean y, boolean z) {
        int xi = x ? n : 0;
        int yi = y ? n : 0;
        // Use face 0 (z=0) when z=false, face 1 (z=1) when z=true.
        int f = z ? 1 : 0;
        return vertId[f][xi][yi];
    }

    private float[] facePoint(int f, int i, int j) {
        float u = (float) i / n;
        float v = (float) j / n;
        switch (f) {
            case 0: return new float[]{ u, v, 0 };       // z=0
            case 1: return new float[]{ u, v, 1 };       // z=1
            case 2: return new float[]{ u, 0, v };       // y=0
            case 3: return new float[]{ u, 1, v };       // y=1
            case 4: return new float[]{ 0, u, v };       // x=0
            case 5: return new float[]{ 1, u, v };       // x=1
            default: throw new IllegalArgumentException();
        }
    }

    private static long quantize(float[] p) {
        // 1e6 quantization is safe for unit cube + integer subdivisions.
        long x = Math.round(p[0] * 1_000_000.0);
        long y = Math.round(p[1] * 1_000_000.0);
        long z = Math.round(p[2] * 1_000_000.0);
        return (x * 1_000_003L + y) * 1_000_003L + z;
    }
}
