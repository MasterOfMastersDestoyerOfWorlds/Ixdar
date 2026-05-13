package ixdar.entrypoint;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;

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
    public static final int NUM_124 = 124;
    public static final float NUM_15 = 15f;

    private static final long TIMEOUT_MS = 300_000L;

    private BenchmarkRockerArmLyon() {
    }

    /**
     * CLI entry: load the rocker-arm OBJ (or {@code args[0]}), run the full
     * quad-layout pipeline, and report load/layout timings; a daemon watchdog
     * exits with code {@value #NUM_124} after {@link #TIMEOUT_MS} ms.
     *
     * @param args optional path to the input OBJ at index 0
     * @throws Exception propagated from mesh loading or pipeline execution
     */
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
                System.err.println(CrossField.lastDiagnostics);
                System.exit(NUM_124);
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
            TopologyStats beforeTopology = TopologyStats.capture(mesh);
            long tLoad = System.currentTimeMillis() - t0;
            System.out.printf("[bench-lyon] mesh load=%dms %s%n",
                    tLoad, beforeTopology);
            QuadLayoutEngine.pipeline(mesh, NUM_15);
            TopologyStats afterTopology = TopologyStats.capture(mesh);
            if (!beforeTopology.equals(afterTopology)) {
                System.err.printf("[bench-lyon] topology changed before=%s after=%s%n",
                        beforeTopology, afterTopology);
            }
            long tLayout = System.currentTimeMillis() - t0;
            System.out.printf("[bench-lyon] layout=%dms \n", tLayout);
        } finally {
            finished.set(true);
            watchdog.interrupt();
        }
    }

    private record TopologyStats(int faces, int edges, int vertices, int halfEdges, int boundaryEdges) {
        static TopologyStats capture(HalfEdgeMesh mesh) {
            int boundaryEdges = 0;
            for (int eAi = 0; eAi < mesh.edgeCount(); eAi++) {
                if (mesh.isBoundaryEdge(mesh.edgeIdAt(eAi))) {
                    boundaryEdges++;
                }
            }
            return new TopologyStats(mesh.faceCount(), mesh.edgeCount(), mesh.vertexCount(),
                    mesh.halfEdgeCount(), boundaryEdges);
        }

        /**
         * Compact human-readable summary of face/edge/vertex/half-edge/boundary counts.
         *
         * @return string of the form {@code "F=.. E=.. V=.. HE=.. boundaryE=.."}
         */
        @Override
        public String toString() {
            return String.format("F=%d E=%d V=%d HE=%d boundaryE=%d",
                    faces, edges, vertices, halfEdges, boundaryEdges);
        }
    }

}
