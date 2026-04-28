package ixdar.entrypoint;

import java.io.File;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.MeshLoader;
import ixdar.geometry.mesh.quadlayout.boundary.BoundaryCapper;
import ixdar.geometry.mesh.quadlayout.integergrid.AlignedParameterization;
import ixdar.geometry.mesh.quadlayout.vectorfield.CombedField;
import ixdar.geometry.mesh.quadlayout.vectorfield.FaceRosyField;

/**
 * CLI verifier for PATCH-40 v2 — Campen 2014 Eq. 6.1 aligned parametrization.
 *
 * <pre>
 *   mvn -pl ixdar-app exec:java -Dexec.mainClass=ixdar.entrypoint.VerifyAlignedParam \
 *     -Dexec.args="/path/to/mesh.obj"
 * </pre>
 *
 * Loads an OBJ, optionally caps open boundaries, runs cross field + combing,
 * solves the aligned parametrization, and prints UV bounding box, energy, a
 * few sample corner UVs, and signed-area orientation statistics.
 */
public final class VerifyAlignedParam {

    private VerifyAlignedParam() {}

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : null;
        ArrayMesh mesh;
        if (path == null) {
            System.out.println("[verify-aligned-param] no path given; using built-in unit cube");
            mesh = makeCube();
        } else {
            if (!new File(path).exists()) {
                System.err.println("[verify-aligned-param] mesh not found: " + path);
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
        long tField = System.currentTimeMillis() - t0;
        System.out.println("Cross field solved in " + tField + " ms"
                + "  smoothness=" + field.smoothnessEnergy());

        long t1 = System.currentTimeMillis();
        CombedField combed = CombedField.comb(field);
        long tComb = System.currentTimeMillis() - t1;
        System.out.println("Combed in " + tComb + " ms  seam edges="
                + combed.seamEdgeCount() + "/" + field.interiorEdgeCount());

        long t2 = System.currentTimeMillis();
        AlignedParameterization param = new AlignedParameterization(mesh, field, combed);
        long tParam = System.currentTimeMillis() - t2;
        System.out.println("Aligned parametrization solved in " + tParam + " ms"
                + "  energy=" + param.energy());

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

        int sampleCount = Math.min(10, F);
        System.out.println("Sample corners (face, u0,v0  u1,v1  u2,v2):");
        for (int f = 0; f < sampleCount; f++) {
            System.out.printf("  f=%-4d  (%+.4f, %+.4f)  (%+.4f, %+.4f)  (%+.4f, %+.4f)%n",
                    f,
                    param.u(f, 0), param.v(f, 0),
                    param.u(f, 1), param.v(f, 1),
                    param.u(f, 2), param.v(f, 2));
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
}
