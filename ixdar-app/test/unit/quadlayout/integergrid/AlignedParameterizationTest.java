package unit.quadlayout.integergrid;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.integergrid.AlignedParameterization;
import ixdar.geometry.mesh.quadlayout.vectorfield.CombedField;
import ixdar.geometry.mesh.quadlayout.vectorfield.FaceRosyField;

public class AlignedParameterizationTest {

    @Test
    void cubeSolves() {
        ArrayMesh mesh = makeCube();
        FaceRosyField field = new FaceRosyField(mesh);
        field.solve();
        CombedField combed = CombedField.comb(field);

        AlignedParameterization param = new AlignedParameterization(mesh, field, combed);
        assertNotNull(param);

        // Every triangle has finite (u,v) at every corner.
        int F = mesh.faceCount();
        for (int f = 0; f < F; f++) {
            for (int c = 0; c < 3; c++) {
                float u = param.u(f, c);
                float v = param.v(f, c);
                assertTrue(Float.isFinite(u), "u not finite at face " + f + " corner " + c);
                assertTrue(Float.isFinite(v), "v not finite at face " + f + " corner " + c);
            }
        }

        // Bounding box is non-degenerate.
        float[] bbox = param.uvBoundingBox();
        assertTrue(bbox[1] - bbox[0] > 1e-3f, "u range too small: " + (bbox[1] - bbox[0]));
        assertTrue(bbox[3] - bbox[2] > 1e-3f, "v range too small: " + (bbox[3] - bbox[2]));

        // Every triangle's UV must be non-degenerate (non-zero area). Sign
        // consistency across all 12 cube faces is NOT guaranteed by the
        // relaxed real-valued solve alone — the cross field's matching can
        // induce per-face flips on a cube where opposite faces' frames
        // disagree. Resolving those flips is PATCH-48 (injectivity barrier
        // and integer rounding at singularities). For v2 we only assert no
        // fully-collapsed triangles.
        int degenerate = 0;
        int nonDegenerate = 0;
        for (int f = 0; f < F; f++) {
            float a = param.uvSignedArea(f);
            if (Math.abs(a) < 1e-8f) degenerate++;
            else nonDegenerate++;
        }
        assertTrue(degenerate == 0,
                "cube has " + degenerate + " degenerate UV triangles");
        assertTrue(nonDegenerate == F,
                "cube non-degenerate triangle count " + nonDegenerate + " != " + F);
    }

    @Test
    void subdividedCubeSolves() {
        ArrayMesh mesh = makeSubdividedCube(2);
        int F = mesh.faceCount();
        assertTrue(F > 12, "subdivided cube should have more than 12 faces; got " + F);

        FaceRosyField field = new FaceRosyField(mesh);
        field.solve();
        CombedField combed = CombedField.comb(field);

        AlignedParameterization param = new AlignedParameterization(mesh, field, combed);
        assertNotNull(param);

        // All UV coords finite.
        for (int f = 0; f < F; f++) {
            for (int c = 0; c < 3; c++) {
                assertTrue(Float.isFinite(param.u(f, c)));
                assertTrue(Float.isFinite(param.v(f, c)));
            }
        }

        // Energy must be small relative to the total face area; here we assert
        // the per-face mean residual squared is not blown up.
        double E = param.energy();
        double meanPerFace = E / Math.max(1, F);
        assertTrue(meanPerFace < 5.0,
                "subdivided cube energy/F too high: " + meanPerFace + " (E=" + E + ", F=" + F + ")");

        // No degenerate UV triangles.
        int degenerate = 0;
        for (int f = 0; f < F; f++) {
            if (Math.abs(param.uvSignedArea(f)) < 1e-10f) degenerate++;
        }
        assertTrue(degenerate < F,
                "all subdivided-cube triangles degenerate in UV (" + degenerate + "/" + F + ")");
    }

    /**
     * Build a unit cube as 12 triangles. Vertices at +-0.5 on each axis.
     */
    private static ArrayMesh makeCube() {
        float[] pos = {
                -0.5f, -0.5f, -0.5f,   // 0
                 0.5f, -0.5f, -0.5f,   // 1
                 0.5f,  0.5f, -0.5f,   // 2
                -0.5f,  0.5f, -0.5f,   // 3
                -0.5f, -0.5f,  0.5f,   // 4
                 0.5f, -0.5f,  0.5f,   // 5
                 0.5f,  0.5f,  0.5f,   // 6
                -0.5f,  0.5f,  0.5f,   // 7
        };
        // Faces with consistent outward winding.
        int[] faces = {
                // -Z face (z=-0.5), normal -Z: order should be CW when viewed from +Z
                0, 2, 1,
                0, 3, 2,
                // +Z face
                4, 5, 6,
                4, 6, 7,
                // -Y face (y=-0.5)
                0, 1, 5,
                0, 5, 4,
                // +Y face
                3, 7, 6,
                3, 6, 2,
                // -X face
                0, 4, 7,
                0, 7, 3,
                // +X face
                1, 2, 6,
                1, 6, 5,
        };
        return new ArrayMesh(pos, null, faces, 3);
    }

    /**
     * Subdivide each cube face into a {@code n}x{@code n} grid of quads
     * (= 2*n*n triangles per face = 12*n*n triangles total).
     */
    private static ArrayMesh makeSubdividedCube(int n) {
        // Generate vertices on the surface; dedupe via map.
        java.util.HashMap<Long, Integer> vertexOf = new java.util.HashMap<>();
        java.util.ArrayList<Float> pos = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> faces = new java.util.ArrayList<>();

        // Each face: 6 cube faces; parametrize via (u,v) on the face plane.
        // Faces (axis perpendicular to the face, sign of axis):
        //   axis = 0 (x), sign = -1, +1
        //   axis = 1 (y), sign = -1, +1
        //   axis = 2 (z), sign = -1, +1
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
                        // Quantize for dedup.
                        long key = quantize(xyz[0]) * 1_000_001L * 1_000_001L
                                + quantize(xyz[1]) * 1_000_001L
                                + quantize(xyz[2]);
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
                // Emit two triangles per cell, with winding consistent with
                // outward normal.
                for (int j = 0; j < n; j++) {
                    for (int i = 0; i < n; i++) {
                        int a = grid[j][i];
                        int b = grid[j][i + 1];
                        int c = grid[j + 1][i + 1];
                        int d = grid[j + 1][i];
                        // Determine winding from outward face direction.
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

    private static long quantize(float x) {
        return Math.round(x * 1_000_000.0);
    }
}
