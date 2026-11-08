package benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Locale;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.ops.MeshMergeByDistance;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceMetric;
import ixdar.geometry.mesh.data.paths.geodesic.TracedSurfacePath;
import ixdar.geometry.mesh.data.paths.query.GirdlingPlane;
import ixdar.geometry.mesh.data.paths.query.NearestVertex;
import ixdar.geometry.mesh.data.paths.spline.SplineAnchorFit;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSplineTracer;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * Times the ring tool's hover frame on a full-resolution crawfish scan.
 *
 * <pre>
 * mvn test -pl ixdar-app -P test -Dtest=FlipGeodesicsScanBenchmark \
 *     -Dbenchmark.geodesicMesh=/home/acw/crawfish/IMG_4109.glb
 * </pre>
 *
 * <p>
 * Stages are timed apart so the warm cost is not hidden inside the one-off triangulation setup.
 */
public final class FlipGeodesicsScanBenchmark {

    private static final String MESH_PROPERTY = "benchmark.geodesicMesh";

    private static final String DEFAULT_MESH = "/home/acw/crawfish/IMG_4109.glb";

    // Around one walking leg at z ~= 0.4, at three heights so the seed walk spirals instead of
    // following one cross-section.
    private static final float[][] LEG_WAYPOINTS = {
        { 0.158970f, -0.111934f, 0.400764f },
        { 0.107179f, -0.108244f, 0.428180f },
        { 0.127260f, -0.110376f, 0.366955f },
    };

    // Around the abdomen at z ~= -0.3, a girth loop an order of magnitude longer than the leg
    // loop, kept as the scaling data point.
    private static final float[][] BODY_WAYPOINTS = {
        { 0.142617f, 0.015641f, -0.297141f },
        { -0.074982f, 0.134666f, -0.283574f },
        { -0.054713f, -0.066467f, -0.319990f },
    };

    private static final int ROUNDS = 6;

    private static final int WARMUP_ROUNDS = 2;

    private static final int POSITION_STRIDE = 3;

    private static final double NANOS_PER_MILLI = 1e6;

    private static final double HALF = 0.5;

    // The scan's glTF indices split vertices at texture seams (19255 components on IMG_4109);
    // welding coincident positions restores one surface (7 components, 460459 of 468350 vertices).
    private static final float WELD_DISTANCE = 1e-6f;


    /**
     * Times the ring tool's hover frame — the girdling-plane search and the spline anchor fit that
     * {@code RingTool.perFrame} runs between the GPU pick and the overlay — at six surface points,
     * and asserts every round of a point traces the same ring.
     *
     * @throws IOException if the scan cannot be read
     */
    @Test
    public void hoverPreviewOnTheCrawfish() throws IOException {
        HalfEdgeMesh mesh = crawfishShell();
        long prepareStart = System.nanoTime();
        SurfaceGeodesics geodesics = SurfaceGeodesics.over(SurfaceMetric.of(mesh));
        System.out.printf(
                "[hover] prepare: intrinsic triangulation over %d faces in %.1f ms, mean edge %.5f%n",
                mesh.faceCount(), (System.nanoTime() - prepareStart) / NANOS_PER_MILLI,
                geodesics.metric.meanEdgeLength);

        for (int waypoint = 0; waypoint < LEG_WAYPOINTS.length; waypoint++) {
            timeHover("leg " + waypoint, mesh, geodesics, LEG_WAYPOINTS[waypoint]);
        }
        for (int waypoint = 0; waypoint < BODY_WAYPOINTS.length; waypoint++) {
            timeHover("body " + waypoint, mesh, geodesics, BODY_WAYPOINTS[waypoint]);
        }
    }

    /**
     * Runs one hover point through {@link #ROUNDS} frames, printing the warm best and worst and
     * failing when a later frame traces a different ring than the first.
     *
     * @param name      label printed with this point's timings
     * @param mesh      surface the cursor is over
     * @param geodesics engine holding the triangulation every trace runs on
     * @param point     surface point under the cursor
     */
    private static void timeHover(String name, HalfEdgeMesh mesh, SurfaceGeodesics geodesics,
            float[] point) {
        Vector3f position = new Vector3f();
        int hitVertexId = NearestVertex.over(mesh).find(point[0], point[1], point[2]);
        mesh.vertexPosition(hitVertexId, position);
        float[] hitPoint = { position.x, position.y, position.z };
        int faceId = mesh.vertexFaceAt(hitVertexId, 0);

        GirdlingPlane girdle = new GirdlingPlane();
        int[] girdleVertexId = new int[0];
        String firstRing = null;
        double bestMillis = Double.POSITIVE_INFINITY;
        double worstMillis = 0.0;
        long geodesicsPerFrame = 0;
        for (int round = 0; round < ROUNDS; round++) {
            long geodesicsBefore = geodesics.geodesicCount;
            long start = System.nanoTime();
            if (!girdle.find(mesh, faceId, hitPoint, null)) {
                System.out.printf("[hover] %s: no closed cut, %.2f ms%n", name,
                        (System.nanoTime() - start) / NANOS_PER_MILLI);
                return;
            }
            double cutMillis = (System.nanoTime() - start) / NANOS_PER_MILLI;
            TracedSurfacePath cut = girdle.cut.path;
            if (girdleVertexId.length < cut.pointCount) {
                girdleVertexId = new int[cut.pointCount];
            }
            int firstStep = 0;
            double nearest = Double.POSITIVE_INFINITY;
            for (int step = 0; step < cut.pointCount; step++) {
                int halfEdge = mesh.edgeHalfEdge(cut.edgeId[step]);
                girdleVertexId[step] = cut.fraction[step] <= HALF
                        ? mesh.halfEdgeVertex(halfEdge)
                        : mesh.halfEdgeEndVertex(halfEdge);
                int base = POSITION_STRIDE * step;
                double dx = cut.positions[base] - hitPoint[0];
                double dy = cut.positions[base + 1] - hitPoint[1];
                double dz = cut.positions[base + 2] - hitPoint[2];
                if (dx * dx + dy * dy + dz * dz < nearest) {
                    nearest = dx * dx + dy * dy + dz * dz;
                    firstStep = step;
                }
            }
            SurfaceSplineTracer tracer = new SurfaceSplineTracer(geodesics);
            tracer.maximumDepth = SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH - 1;
            SplineAnchorFit fit = new SplineAnchorFit(tracer);
            boolean fitted =
                    fit.fit(cut.positions, cut.pointCount, girdleVertexId, firstStep);
            double millis = (System.nanoTime() - start) / NANOS_PER_MILLI;
            assertTrue(fitted, "the anchor fit failed at hover point " + name);

            SurfaceSpline spline = SurfaceSpline.of(tracer);
            String ring = String.format(Locale.ROOT,
                    "%d-edge cut, %d anchors, %d ring edges, length %.9f, deviation %.9f, "
                            + "fingerprint %s",
                    cut.pointCount, fit.anchorCount, spline.marks.markedEdgeCount,
                    spline.path.length, fit.deviation,
                    EdgeMarks.fingerprint(mesh, spline.marks.markedByEdgeId));
            System.out.printf("[hover] %s round %d: %.2f ms (cut %.2f, fit %.2f), %d geodesics, "
                    + "%s%n", name, round, millis, cutMillis, millis - cutMillis,
                    geodesics.geodesicCount - geodesicsBefore, ring);
            if (firstRing == null) {
                firstRing = ring;
            }
            assertEquals(firstRing, ring, "hover point " + name + " traced a different ring in "
                    + "round " + round);
            if (round < WARMUP_ROUNDS) {
                continue;
            }
            bestMillis = Math.min(bestMillis, millis);
            worstMillis = Math.max(worstMillis, millis);
            geodesicsPerFrame = geodesics.geodesicCount - geodesicsBefore;
        }
        System.out.printf("[hover] %s warm: best %.2f ms, worst %.2f ms, %d geodesics per frame%n",
                name, bestMillis, worstMillis, geodesicsPerFrame);
    }

    /**
     * The scan's largest manifold shell: loaded, welded at {@link #WELD_DISTANCE}, stripped of the
     * faces that make an edge non-manifold, and reduced to the biggest surviving component.
     *
     * @throws IOException if the scan cannot be read
     * @return the shell as a half-edge mesh
     */
    private static HalfEdgeMesh crawfishShell() throws IOException {
        String meshPath = System.getProperty(MESH_PROPERTY, DEFAULT_MESH);
        long loadStart = System.nanoTime();
        MeshTopology loaded = MeshLoader.load(meshPath);
        System.out.printf("[flip-geodesics] loader: %d vertices, %d faces%n",
                loaded.vertexCount(), loaded.faceCount());
        MeshMergeByDistance welder = new MeshMergeByDistance();
        welder.weldArrays(loaded, WELD_DISTANCE);
        ArrayMesh welded = new ArrayMesh(welder.weldedPositions, null, welder.weldedFaceCorners,
                ManifoldShell.TRIANGLE_CORNERS);
        System.out.printf("[flip-geodesics] weld: %d vertices, %d faces%n",
                welded.vertexCount(), welded.faceCount());
        ManifoldShell shell = new ManifoldShell();
        HalfEdgeMesh mesh = shell.of(welded);
        System.out.printf("[flip-geodesics] cleanup: %d faces -> %d manifold -> %d in the largest "
                + "component (%d vertices)%n", shell.inputFaceCount, shell.manifoldFaceCount,
                shell.keptFaceCount, shell.keptVertexCount);
        double loadMillis = (System.nanoTime() - loadStart) / NANOS_PER_MILLI;
        System.out.printf(
                "[flip-geodesics] mesh %s: %d vertices, %d faces, %d edges, load+weld %.1f ms%n",
                meshPath, mesh.vertexCount(), mesh.faceCount(), mesh.edgeCount(), loadMillis);
        return mesh;
    }

}
