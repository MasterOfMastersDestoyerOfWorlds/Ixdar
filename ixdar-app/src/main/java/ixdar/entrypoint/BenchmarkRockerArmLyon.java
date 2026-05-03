package ixdar.entrypoint;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshLoader;
import ixdar.geometry.mesh.quadlayout.CrossField;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;

/**
 * Lyon-Table-1 pipeline benchmark on ROCKERARM, end-to-end on our own pipeline.
 *
 * <p>
 * Loads only the OBJ mesh, then recomputes cross-field (BZK09 §4 + optional
 * CIE*16), parametrization (BZK09 §5 + PATCH-114 LocalStiffening), motorcycle
 * graph (Lyon §3), T-mesh, ILP quantization, layout, and metrics. No external
 * (metriko / libigl) bootstrap data is consumed.
 *
 * <pre>
 *   mvn -pl ixdar-app exec:java \
 *       -Dexec.mainClass=ixdar.entrypoint.BenchmarkRockerArmLyon
 * </pre>
 */
public final class BenchmarkRockerArmLyon {

    private static final long TIMEOUT_MS = 30_000L;

    private BenchmarkRockerArmLyon() {
    }

    public static void main(String[] args) throws Exception {
        AtomicBoolean finished = new AtomicBoolean(false);
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(TIMEOUT_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (!finished.get()) {
                System.err.printf("[bench-lyon] FAILED timeout=%dms%n", TIMEOUT_MS);
                CrossField.printLastDiagnosticsOnFailure();
                System.exit(124);
            }
        }, "bench-lyon-timeout");
        watchdog.setDaemon(true);
        watchdog.start();

        Path objPath = args.length > 0
                ? Paths.get(args[0])
                : Paths.get("ixdar-app/test/resources/quadlayout/baseline-rocker-arm/rocker-arm.obj");

        try {
            long t0 = System.currentTimeMillis();
            ArrayMesh arrayMesh = MeshLoader.load(objPath.toString());
            HalfEdgeMesh mesh = arrayMesh.toHalfEdgeMesh();
            long tLoad = System.currentTimeMillis() - t0;
            System.out.printf("[bench-lyon] mesh load=%dms F=%d V=%d%n",
                    tLoad, mesh.faceCount(), mesh.vertexCount());
            QuadLayoutEngine.pipeline(mesh, 15f);
            long tLayout = System.currentTimeMillis() - t0;
            System.out.printf("[bench-lyon] layout=%dms \n", tLayout);
        } finally {
            finished.set(true);
            watchdog.interrupt();
        }
    }
}
