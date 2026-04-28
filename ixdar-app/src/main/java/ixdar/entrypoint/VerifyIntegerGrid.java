package ixdar.entrypoint;

import java.io.File;
import java.util.HashSet;
import java.util.List;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.MeshLoader;
import ixdar.geometry.mesh.quadlayout.boundary.BoundaryCapper;
import ixdar.geometry.mesh.quadlayout.integergrid.SeamlessParameterization;
import ixdar.geometry.mesh.quadlayout.vectorfield.CombedField;
import ixdar.geometry.mesh.quadlayout.vectorfield.FaceRosyField;
import ixdar.geometry.mesh.quadlayout.vectorfield.Singularity;

/**
 * CLI verifier for PATCH-48 — Bommes 2013 iterative rounding + injectivity
 * barrier on top of the aligned parametrization.
 *
 * <pre>
 *   mvn -pl ixdar-app exec:java -Dexec.mainClass=ixdar.entrypoint.VerifyIgm \
 *     -Dexec.args="/path/to/mesh.obj"
 * </pre>
 *
 * Loads an OBJ (or a built-in cube if no path given), runs the cross field +
 * combing + seamless parametrization, and prints injectivity status, the
 * integer-singularity check, the UV bounding box, and the number of pinned
 * corners.
 */
public final class VerifyIntegerGrid {

    private VerifyIntegerGrid() {}

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : null;
        ArrayMesh mesh;
        if (path == null) {
            System.out.println("[verify-integergrid] no path given; using built-in unit cube");
            mesh = makeCube();
        } else if (path.equals("--subdivided-cube")) {
            int n = args.length > 1 ? Integer.parseInt(args[1]) : 4;
            System.out.println("[verify-integergrid] subdivided cube n=" + n);
            mesh = makeSubdividedCube(n);
        } else {
            if (!new File(path).exists()) {
                System.err.println("[verify-integergrid] mesh not found: " + path);
                System.exit(2);
            }
            ArrayMesh raw = MeshLoader.load(path);
            BoundaryCapper.CapResult cap = BoundaryCapper.cap(raw);
            mesh = cap.closedMesh();
            System.out.println("Loaded " + path + "  V=" + mesh.vertexCount()
                    + "  F=" + mesh.faceCount());
        }

        long t0 = System.currentTimeMillis();
        FaceRosyField field = new FaceRosyField(mesh);
        field.solve();
        CombedField combed = CombedField.comb(field);
        List<Singularity> singularities = field.findSingularities();
        long tField = System.currentTimeMillis() - t0;
        System.out.println("Cross field + combing + singularities in " + tField + " ms"
                + "  singularities=" + singularities.size()
                + "  seam edges=" + combed.seamEdgeCount() + "/" + field.interiorEdgeCount());

        long t1 = System.currentTimeMillis();
        SeamlessParameterization param = new SeamlessParameterization(
                mesh, field, combed, singularities);
        long tParam = System.currentTimeMillis() - t1;
        System.out.println("Seamless parametrization in " + tParam + " ms"
                + "  iterations=" + param.iterationCount()
                + "  injective=" + param.injectiveOnAllTriangles());

        float[] bbox = param.uvBoundingBox();
        System.out.println("UV bbox: u=[" + bbox[0] + ", " + bbox[1] + "]"
                + "  v=[" + bbox[2] + ", " + bbox[3] + "]");

        int F = mesh.faceCount();
        int positive = 0, negative = 0, degenerate = 0;
        for (int f = 0; f < F; f++) {
            float a = param.uvSignedArea(f);
            if (Math.abs(a) < 1e-10f) degenerate++;
            else if (a > 0) positive++;
            else negative++;
        }
        System.out.println("Face orientation: " + positive + " +ve, " + negative
                + " -ve, " + degenerate + " degenerate (of " + F + ")");

        int[] pinned = param.pinnedCorners();
        System.out.println("Hard-pinned corners (both u and v): " + pinned.length
                + "/" + (F * 3));

        // Integer-singularity check: every singularity-vertex corner must
        // sit at integer (u, v).
        HashSet<Integer> singVerts = new HashSet<>();
        for (Singularity s : singularities) singVerts.add(s.vertexId());
        int badSingCorners = 0;
        for (int f = 0; f < F; f++) {
            for (int c = 0; c < 3; c++) {
                int vid = mesh.faceVertexAt(f, c);
                if (!singVerts.contains(vid)) continue;
                float u = param.u(f, c);
                float v = param.v(f, c);
                if (Math.abs(u - Math.round(u)) > 1e-3f
                        || Math.abs(v - Math.round(v)) > 1e-3f) {
                    badSingCorners++;
                }
            }
        }
        System.out.println("Singularity-corner integer check: "
                + (badSingCorners == 0 ? "PASS" : "FAIL (" + badSingCorners + " off-integer)"));

        if (Boolean.parseBoolean(System.getProperty("verifyIgm.dump", "false"))) {
            System.out.println("Per-face dump:");
            for (int f = 0; f < F; f++) {
                System.out.printf("  f=%d a=%+.4f  v=[%d,%d,%d]  uv=(%+.3f,%+.3f) (%+.3f,%+.3f) (%+.3f,%+.3f)%n",
                        f, param.uvSignedArea(f),
                        mesh.faceVertexAt(f, 0), mesh.faceVertexAt(f, 1), mesh.faceVertexAt(f, 2),
                        param.u(f, 0), param.v(f, 0),
                        param.u(f, 1), param.v(f, 1),
                        param.u(f, 2), param.v(f, 2));
            }
        }
    }

    private static ArrayMesh makeCube() {
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

    private static ArrayMesh makeSubdividedCube(int n) {
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
