package ixdar.entrypoint;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map.Entry;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshLoader;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.quadlayout.integergrid.SeamlessParameterization;
import ixdar.geometry.mesh.quadlayout.lyon2021.LyonMetrics;
import ixdar.geometry.mesh.quadlayout.lyon2021.QuadLayout;
import ixdar.geometry.mesh.quadlayout.lyon2021.QuadLayoutExtractor;
import ixdar.geometry.mesh.quadlayout.quantization.StripEquivalence;
import ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.tmesh.TMesh;
import ixdar.geometry.mesh.quadlayout.vectorfield.CombedField;
import ixdar.geometry.mesh.quadlayout.vectorfield.FaceRosyField;
import ixdar.geometry.mesh.quadlayout.vectorfield.Singularity;

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

    private BenchmarkRockerArmLyon() {
    }

    public static void main(String[] args) throws Exception {
        Path objPath = args.length > 0
                ? Paths.get(args[0])
                : Paths.get("ixdar-app/test/resources/quadlayout/baseline-rocker-arm/rocker-arm.obj");

        long t0 = System.currentTimeMillis();
        ArrayMesh arrayMesh = MeshLoader.load(objPath.toString());
        HalfEdgeMesh mesh = arrayMesh.toHalfEdgeMesh();
        long tLoad = System.currentTimeMillis() - t0;
        System.out.printf("[bench-lyon] mesh load=%dms F=%d V=%d%n",
                tLoad, mesh.faceCount(), mesh.vertexCount());
        Entry<QuadLayout, HalfEdgeMesh> result = QuadLayoutEngine.pipeline(mesh, 15f);
        long tLayout = System.currentTimeMillis() - t0;
        System.out.printf("[bench-lyon] layout=%dms \n", tLayout);
    }
}
