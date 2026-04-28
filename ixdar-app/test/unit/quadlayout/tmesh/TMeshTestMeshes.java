package unit.quadlayout.tmesh;

import ixdar.geometry.mesh.data.ArrayMesh;

/** Shared test-mesh builders for the tmesh package tests. */
final class TMeshTestMeshes {

    private TMeshTestMeshes() {}

    static ArrayMesh makeCube() {
        float[] pos = {
                -0.5f, -0.5f, -0.5f,
                 0.5f, -0.5f, -0.5f,
                 0.5f,  0.5f, -0.5f,
                -0.5f,  0.5f, -0.5f,
                -0.5f, -0.5f,  0.5f,
                 0.5f, -0.5f,  0.5f,
                 0.5f,  0.5f,  0.5f,
                -0.5f,  0.5f,  0.5f,
        };
        int[] faces = {
                0, 2, 1,  0, 3, 2,
                4, 5, 6,  4, 6, 7,
                0, 1, 5,  0, 5, 4,
                3, 7, 6,  3, 6, 2,
                0, 4, 7,  0, 7, 3,
                1, 2, 6,  1, 6, 5,
        };
        return new ArrayMesh(pos, null, faces, 3);
    }

    static ArrayMesh makeSubdividedCube(int n) {
        java.util.HashMap<Long, Integer> vertexOf = new java.util.HashMap<>();
        java.util.ArrayList<Float> pos = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> faces = new java.util.ArrayList<>();
        for (int axis = 0; axis < 3; axis++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                int[][] grid = new int[n + 1][n + 1];
                for (int j = 0; j <= n; j++) {
                    for (int i = 0; i <= n; i++) {
                        float[] xyz = new float[3];
                        xyz[axis] = sign * 0.5f;
                        int u = (axis + 1) % 3;
                        int v = (axis + 2) % 3;
                        xyz[u] = -0.5f + (float) i / n;
                        xyz[v] = -0.5f + (float) j / n;
                        long key = Math.round(xyz[0] * 1_000_000.0) * 1_000_001L * 1_000_001L
                                + Math.round(xyz[1] * 1_000_000.0) * 1_000_001L
                                + Math.round(xyz[2] * 1_000_000.0);
                        Integer existing = vertexOf.get(key);
                        int idx;
                        if (existing != null) {
                            idx = existing;
                        } else {
                            idx = pos.size() / 3;
                            pos.add(xyz[0]);
                            pos.add(xyz[1]);
                            pos.add(xyz[2]);
                            vertexOf.put(key, idx);
                        }
                        grid[j][i] = idx;
                    }
                }
                for (int j = 0; j < n; j++) {
                    for (int i = 0; i < n; i++) {
                        int a = grid[j][i];
                        int b = grid[j][i + 1];
                        int c = grid[j + 1][i + 1];
                        int d = grid[j + 1][i];
                        boolean flip = (sign > 0) ^ ((axis + 1) % 2 == 1);
                        if (flip) {
                            faces.add(a); faces.add(b); faces.add(c);
                            faces.add(a); faces.add(c); faces.add(d);
                        } else {
                            faces.add(a); faces.add(c); faces.add(b);
                            faces.add(a); faces.add(d); faces.add(c);
                        }
                    }
                }
            }
        }
        float[] posArr = new float[pos.size()];
        for (int i = 0; i < posArr.length; i++) posArr[i] = pos.get(i);
        int[] faceArr = faces.stream().mapToInt(Integer::intValue).toArray();
        return new ArrayMesh(posArr, null, faceArr, 3);
    }
}
