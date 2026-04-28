package ixdar.entrypoint;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.MeshLoader;
import ixdar.geometry.mesh.quadlayout.vectorfield.CombedField;
import ixdar.geometry.mesh.quadlayout.vectorfield.FaceRosyField;

/**
 * PATCH-54 benchmark on Lyon 2021's rocker-arm-20k reference mesh. Times each
 * stage of the per-vertex IGM end-to-end so we can compare against the paper's
 * 10s/100k claim:
 *
 * <ol>
 *   <li>Mesh load + cross-field solve (BZK12)</li>
 *   <li>Combing</li>
 *   <li>IGM Hessian build + relaxed solve (ojAlgo SparseLu)</li>
 * </ol>
 *
 * <p>Skips the iterative-rounding loop — that's a separate timing concern
 * and PATCH-48's cap is configurable. The relaxed solve is the key bandwidth
 * test; if it's under a second on rocker-arm-20k the whole pipeline becomes
 * tractable.
 *
 * <pre>
 *   mvn -pl ixdar-app exec:java \
 *       -Dexec.mainClass=ixdar.entrypoint.BenchmarkRockerArm
 * </pre>
 */
public final class BenchmarkRockerArm {

    private BenchmarkRockerArm() {}

    public static void main(String[] args) throws Exception {
        Path obj = args.length > 0
                ? Paths.get(args[0])
                : Paths.get("ixdar-app/test/resources/quadlayout/rocker-arm/rocker-arm.obj");

        long t0 = System.currentTimeMillis();
        ArrayMesh mesh = MeshLoader.load(obj.toString());
        long tLoad = System.currentTimeMillis() - t0;
        System.out.printf("[bench] load=%dms F=%d V=%d%n",
                tLoad, mesh.faceCount(), mesh.vertexCount());

        long t1 = System.currentTimeMillis();
        FaceRosyField field = new FaceRosyField(mesh);
        field.solve();
        long tField = System.currentTimeMillis() - t1;
        System.out.printf("[bench] cross-field=%dms Ei=%d%n",
                tField, field.interiorEdgeCount());

        long t2 = System.currentTimeMillis();
        CombedField combed = CombedField.comb(field);
        long tComb = System.currentTimeMillis() - t2;
        System.out.printf("[bench] combing=%dms seam=%d%n",
                tComb, combed.seamEdgeCount());

        Class<?> hessianClass = Class.forName(
                "ixdar.geometry.mesh.quadlayout.integergrid.IgmHessian");
        Constructor<?> ctor = hessianClass.getDeclaredConstructor(
                ArrayMesh.class, FaceRosyField.class, CombedField.class,
                double.class);
        ctor.setAccessible(true);

        long t3 = System.currentTimeMillis();
        Object H = ctor.newInstance(mesh, field, combed, 1.0);
        long tBuild = System.currentTimeMillis() - t3;
        Field nField = hessianClass.getDeclaredField("N");
        nField.setAccessible(true);
        int N = nField.getInt(H);
        Field chartField = hessianClass.getDeclaredField("chart");
        chartField.setAccessible(true);
        Object chart = chartField.get(H);
        Field cvCountField = chart.getClass().getDeclaredField("chartVertexCount");
        cvCountField.setAccessible(true);
        Field chartCountField = chart.getClass().getDeclaredField("chartCount");
        chartCountField.setAccessible(true);
        int numCV = cvCountField.getInt(chart);
        int chartCount = chartCountField.getInt(chart);
        System.out.printf("[bench] hessian-build=%dms N=%d numCV=%d chartCount=%d%n",
                tBuild, N, numCV, chartCount);

        Method solve = hessianClass.getDeclaredMethod(
                "solveWithPins", int[].class, double[].class,
                int[].class, double[].class);
        solve.setAccessible(true);

        long t4 = System.currentTimeMillis();
        double[] x;
        try {
            x = (double[]) solve.invoke(H, null, null, null, null);
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            System.out.printf("[bench] SOLVE FAILED: %s: %s%n",
                    cause.getClass().getSimpleName(), cause.getMessage());
            return;
        }
        long tSolve = System.currentTimeMillis() - t4;

        double normSq = 0;
        for (double v : x) normSq += v * v;
        long total = System.currentTimeMillis() - t0;
        System.out.printf("[bench] relaxed-solve=%dms ||x||=%.4g%n",
                tSolve, Math.sqrt(normSq));
        System.out.printf("[bench] TOTAL=%dms (load+field+comb+build+solve)%n", total);
    }
}
