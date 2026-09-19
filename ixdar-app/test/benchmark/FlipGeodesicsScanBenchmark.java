package benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.ops.MeshMergeByDistance;
import ixdar.geometry.mesh.data.paths.FlipGeodesics;
import ixdar.geometry.mesh.data.paths.GeodesicSeedPath;
import ixdar.geometry.mesh.data.paths.GirdlingPlane;
import ixdar.geometry.mesh.data.paths.IntrinsicPathTracer;
import ixdar.geometry.mesh.data.paths.IntrinsicTriangulation;
import ixdar.geometry.mesh.data.paths.NearestVertex;
import ixdar.geometry.mesh.data.paths.SplineAnchorFit;
import ixdar.geometry.mesh.data.paths.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.SurfaceRing;
import ixdar.geometry.mesh.data.paths.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.SurfaceSplineTracer;
import ixdar.geometry.mesh.data.paths.TracedSurfacePath;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

/**
 * Times FlipOut, a ring row and the ring tool's hover frame on a full-resolution crawfish scan.
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

    /** Input-file entry point: the scan the loop is tightened on. */
    private static final String MESH_PROPERTY = "benchmark.geodesicMesh";

    /** Output-file entry point: where the manifold shell is written as OBJ, for the viewer. */
    private static final String SHELL_PROPERTY = "benchmark.shellObj";

    /** Scan used when the property is absent. */
    private static final String DEFAULT_MESH = "/home/acw/crawfish/IMG_4109.glb";

    /**
     * Three surface points spread around one walking leg at {@code z ~= 0.4}, at three different
     * heights along it so the seed walk spirals instead of following one cross-section.
     */
    private static final float[][] LEG_WAYPOINTS = {
        { 0.158970f, -0.111934f, 0.400764f },
        { 0.107179f, -0.108244f, 0.428180f },
        { 0.127260f, -0.110376f, 0.366955f },
    };

    /**
     * Three points around the abdomen at {@code z ~= -0.3}, a girth loop an order of magnitude
     * longer than the leg loop, kept as the scaling data point.
     */
    private static final float[][] BODY_WAYPOINTS = {
        { 0.142617f, 0.015641f, -0.297141f },
        { -0.074982f, 0.134666f, -0.283574f },
        { -0.054713f, -0.066467f, -0.319990f },
    };

    /** Timed rounds; the first ones are discarded as warm-up. */
    private static final int ROUNDS = 6;

    /** Rounds discarded before the warm numbers are collected. */
    private static final int WARMUP_ROUNDS = 2;

    /** Floats per packed xyz position. */
    private static final int POSITION_STRIDE = 3;

    /** Nanoseconds in a millisecond. */
    private static final double NANOS_PER_MILLI = 1e6;

    /** One half, the crossing fraction a cut point snaps to its edge's tail or head at. */
    private static final double HALF = 0.5;

    /**
     * Weld radius applied before anything else. The scan's glTF indices split vertices at texture
     * seams, which leaves 19255 index-connected components on IMG_4109; welding coincident
     * positions restores one surface (7 components, 460459 of 468350 vertices in the largest).
     */
    private static final float WELD_DISTANCE = 1e-6f;

    /**
     * Times the leg and body loops through their seed, FlipOut and trace stages.
     *
     * @throws IOException if the scan cannot be read
     */
    @Test
    public void tightenCrawfishLegLoop() throws IOException {
        HalfEdgeMesh mesh = crawfishShell();

        timeLoop("leg", mesh, LEG_WAYPOINTS);
        timeLoop("body", mesh, BODY_WAYPOINTS);
    }

    /**
     * The CRAW-23 ring row for one walking leg, on the shell rather than the raw scan: the scene
     * and {@code /mesh/rings/add} refuse IMG_4109 itself until CRAW-26 repairs its non-manifold
     * edges, so the row is produced here the way the timings above are.
     *
     * @throws IOException if the scan cannot be read
     */
    @Test
    public void ringRowOnTheCrawfishLeg() throws IOException {
        HalfEdgeMesh mesh = crawfishShell();
        String shellPath = System.getProperty(SHELL_PROPERTY, "");
        if (!shellPath.isEmpty()) {
            writeObj(mesh, Path.of(shellPath));
            System.out.printf("[ring] shell written to %s%n", shellPath);
        }
        float[] packed = new float[LEG_WAYPOINTS.length * POSITION_STRIDE];
        for (int waypoint = 0; waypoint < LEG_WAYPOINTS.length; waypoint++) {
            System.arraycopy(LEG_WAYPOINTS[waypoint], 0, packed, POSITION_STRIDE * waypoint,
                    POSITION_STRIDE);
        }

        SurfaceRing ring = SurfaceRing.through(mesh, packed, LEG_WAYPOINTS.length, true, 0,
                FlipGeodesics.UNBOUNDED_ITERATIONS);

        System.out.printf(
                "[ring] leg row: %d edges, length %.5f (seed %.5f), centroid %.5f, %.5f, %.5f, "
                        + "fingerprint %s%n",
                ring.markedEdgeCount, ring.length, ring.seedLength, ring.centroidX, ring.centroidY,
                ring.centroidZ, EdgeMarks.fingerprint(mesh, ring.markedByEdgeId));
    }

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
        SurfaceGeodesics geodesics = SurfaceGeodesics.over(mesh);
        System.out.printf(
                "[hover] prepare: intrinsic triangulation over %d faces in %.1f ms, mean edge %.5f%n",
                mesh.faceCount(), (System.nanoTime() - prepareStart) / NANOS_PER_MILLI,
                geodesics.meanEdgeLength);

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
        int hitVertexId = NearestVertex.find(mesh, point[0], point[1], point[2]);
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
            if (girdleVertexId.length < girdle.cut.stepCount) {
                girdleVertexId = new int[girdle.cut.stepCount];
            }
            int firstStep = 0;
            double nearest = Double.POSITIVE_INFINITY;
            for (int step = 0; step < girdle.cut.stepCount; step++) {
                int halfEdge = mesh.edgeHalfEdge(girdle.cut.edgeId[step]);
                girdleVertexId[step] = girdle.cut.crossingFraction[step] <= HALF
                        ? mesh.halfEdgeVertex(halfEdge)
                        : mesh.halfEdgeEndVertex(halfEdge);
                int base = POSITION_STRIDE * step;
                double dx = girdle.polyline[base] - hitPoint[0];
                double dy = girdle.polyline[base + 1] - hitPoint[1];
                double dz = girdle.polyline[base + 2] - hitPoint[2];
                if (dx * dx + dy * dy + dz * dz < nearest) {
                    nearest = dx * dx + dy * dy + dz * dz;
                    firstStep = step;
                }
            }
            SurfaceSplineTracer tracer = new SurfaceSplineTracer(geodesics);
            tracer.maximumDepth = SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH - 1;
            SplineAnchorFit fit = new SplineAnchorFit(tracer);
            boolean fitted =
                    fit.fit(girdle.polyline, girdle.cut.stepCount, girdleVertexId, firstStep);
            double millis = (System.nanoTime() - start) / NANOS_PER_MILLI;
            assertTrue(fitted, "the anchor fit failed at hover point " + name);

            SurfaceSpline spline = SurfaceSpline.of(tracer);
            String ring = String.format(Locale.ROOT,
                    "%d-edge cut, %d anchors, %d ring edges, length %.9f, deviation %.9f, "
                            + "fingerprint %s",
                    girdle.cut.stepCount, fit.anchorCount, spline.markedEdgeCount, spline.length,
                    fit.deviation, EdgeMarks.fingerprint(mesh, spline.markedByEdgeId));
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
        ArrayMesh loaded = MeshLoader.load(meshPath);
        System.out.printf("[flip-geodesics] loader: %d vertices, %d faces%n",
                loaded.vertexCount(), loaded.faceCount());
        ArrayMesh welded = MeshMergeByDistance.mergeToArrayMesh(loaded, WELD_DISTANCE);
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

    /**
     * Times one seed loop through {@code ROUNDS} rounds and prints the warm-best stage split.
     *
     * @param name      label printed with every line of this loop's timings
     * @param mesh      manifold scan surface the loop is tightened on
     * @param waypoints surface points the seed walk passes through
     */
    private static void timeLoop(String name, HalfEdgeMesh mesh, float[][] waypoints) {
        int[] waypointVertexIds = new int[waypoints.length];
        for (int index = 0; index < waypointVertexIds.length; index++) {
            float[] point = waypoints[index];
            waypointVertexIds[index] = NearestVertex.find(mesh, point[0], point[1], point[2]);
        }

        double bestSetupMillis = Double.POSITIVE_INFINITY;
        double bestSeedMillis = Double.POSITIVE_INFINITY;
        double bestFlipMillis = Double.POSITIVE_INFINITY;
        double bestTraceMillis = Double.POSITIVE_INFINITY;
        int seedEdges = 0;
        int tightEdges = 0;
        int tracePoints = 0;
        double seedLength = 0.0;
        double tightLength = 0.0;
        long flips = 0;
        for (int round = 0; round < ROUNDS; round++) {
            long setupStart = System.nanoTime();
            IntrinsicTriangulation intrinsic = IntrinsicTriangulation.over(mesh);
            long setupEnd = System.nanoTime();
            IntrinsicPathTracer tracer = IntrinsicPathTracer.snapshotOf(intrinsic);
            long seedStart = System.nanoTime();
            int[] seed = GeodesicSeedPath.throughVertices(intrinsic, waypointVertexIds, true);
            long seedEnd = System.nanoTime();
            FlipGeodesics flipper = new FlipGeodesics();
            double seedTotal = intrinsic.chainLength(seed, seed.length);
            long flipStart = System.nanoTime();
            int[] tightened = flipper.shorten(intrinsic, seed, true,
                    FlipGeodesics.UNBOUNDED_ITERATIONS);
            long flipEnd = System.nanoTime();
            TracedSurfacePath traced = tracer.trace(intrinsic, tightened, true);
            long traceEnd = System.nanoTime();

            System.out.printf(
                    "[flip-geodesics] %s round %d: setup %.1f ms, seed %.1f ms, flipout %.2f ms, "
                            + "trace %.2f ms (%d -> %d edges, %.5f -> %.5f, %d flips)%n",
                    name, round, (setupEnd - setupStart) / NANOS_PER_MILLI,
                    (seedEnd - seedStart) / NANOS_PER_MILLI,
                    (flipEnd - flipStart) / NANOS_PER_MILLI,
                    (traceEnd - flipEnd) / NANOS_PER_MILLI,
                    seed.length, tightened.length, seedTotal, flipper.pathLength(),
                    flipper.flipCount);
            if (round < WARMUP_ROUNDS) {
                continue;
            }
            bestSetupMillis = Math.min(bestSetupMillis, (setupEnd - setupStart) / NANOS_PER_MILLI);
            bestSeedMillis = Math.min(bestSeedMillis, (seedEnd - seedStart) / NANOS_PER_MILLI);
            bestFlipMillis = Math.min(bestFlipMillis, (flipEnd - flipStart) / NANOS_PER_MILLI);
            bestTraceMillis = Math.min(bestTraceMillis, (traceEnd - flipEnd) / NANOS_PER_MILLI);
            seedEdges = seed.length;
            tightEdges = tightened.length;
            tracePoints = traced.pointCount;
            seedLength = seedTotal;
            tightLength = flipper.pathLength();
            flips = flipper.flipCount;
        }

        System.out.printf(
                "[flip-geodesics] %s warm best: intrinsic setup %.1f ms, seed walk %.1f ms, "
                        + "FlipOut %.2f ms, trace %.2f ms%n",
                name, bestSetupMillis, bestSeedMillis, bestFlipMillis, bestTraceMillis);
        System.out.printf(
                "[flip-geodesics] %s loop: %d seed edges (%.5f) -> %d geodesic edges (%.5f), "
                        + "%d flips, %d traced points%n",
                name, seedEdges, seedLength, tightEdges, tightLength, flips, tracePoints);
    }

    /**
     * Writes a mesh as a plain OBJ so the viewer scene can load the shell the ring was measured
     * on, which the raw scan cannot be until CRAW-26 repairs its non-manifold edges.
     *
     * @param mesh mesh to write
     * @param out  file to write it to
     * @throws IOException if the file cannot be written
     */
    private static void writeObj(HalfEdgeMesh mesh, Path out) throws IOException {
        Vector3f position = new Vector3f();
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < mesh.vertexCount(); index++) {
            mesh.vertexPosition(mesh.vertexIdAt(index), position);
            text.append("v ").append(position.x).append(' ').append(position.y).append(' ')
                    .append(position.z).append('\n');
        }
        for (int index = 0; index < mesh.faceCount(); index++) {
            int faceId = mesh.faceIdAt(index);
            text.append('f');
            for (int corner = 0; corner < mesh.faceVertexCount(faceId); corner++) {
                text.append(' ').append(mesh.faceVertexAt(faceId, corner) + 1);
            }
            text.append('\n');
        }
        Files.write(out, text.toString().getBytes(StandardCharsets.UTF_8));
    }
}
