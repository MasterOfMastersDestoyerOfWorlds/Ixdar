package benchmark;

import java.io.IOException;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.ops.MeshMergeByDistance;
import ixdar.geometry.mesh.data.paths.geodesic.TracedSurfacePath;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.BranchCrossSectionRing;
import ixdar.geometry.mesh.data.rings.RingCandidateExtractor;
import ixdar.geometry.mesh.data.rings.RingCandidates;

/**
 * Runs the ring proposal on a mesh file; outside the default test globs, so run it explicitly:
 *
 * <pre>
 * mvn test -pl ixdar-app -P test -Dtest=RingCandidatesScanBenchmark \
 *     -Dbenchmark.ringMesh=/home/acw/crawfish/IMG_4109.glb -Dbenchmark.ringResolution=192
 * </pre>
 *
 * <p>
 * A {@code .glb} scan is welded and cut to its largest manifold shell first.
 */
public final class RingCandidatesScanBenchmark {

    private static final String MESH_PROPERTY = "benchmark.ringMesh";

    private static final String RESOLUTION_PROPERTY = "benchmark.ringResolution";

    private static final String NECKNESS_PROPERTY = "benchmark.ringNeckness";

    private static final String BUDGET_PROPERTY = "benchmark.ringBudget";

    private static final String BRANCH_CUT_PROPERTY = "benchmark.ringAtBranch";

    private static final String DEFAULT_MESH =
            "test/resources/quadlayout/figure_8/fertility_in_tri.off";

    private static final float WELD_DISTANCE = 1e-6f;

    private static final double NANOS_PER_MILLI = 1e6;

    private static final int COORDINATES_PER_POINT = 3;

    /**
     * Proposes rings on the configured mesh, prints one row per ring, and checks a second run
     * reproduces the first.
     *
     * @throws IOException when the mesh file cannot be read
     */
    @Test
    public void proposeRings() throws IOException {
        String meshPath = System.getProperty(MESH_PROPERTY, DEFAULT_MESH);
        MeshTopology mesh = load(meshPath);
        System.out.printf("[rings] mesh %s: %d vertices, %d faces, %d edges%n", meshPath,
                mesh.vertexCount(), mesh.faceCount(), mesh.edgeCount());

        RingCandidateExtractor extractor = new RingCandidateExtractor();
        extractor.resolution = Integer.getInteger(RESOLUTION_PROPERTY, extractor.resolution);
        extractor.skeletonBranchBudget =
                Integer.getInteger(BUDGET_PROPERTY, extractor.skeletonBranchBudget);
        extractor.minimumNeckness = Float.parseFloat(System.getProperty(NECKNESS_PROPERTY,
                String.valueOf(RingCandidateExtractor.DEFAULT_MINIMUM_NECKNESS)));
        long start = System.nanoTime();
        RingCandidates rings = extractor.extract(mesh);
        double millis = (System.nanoTime() - start) / NANOS_PER_MILLI;

        System.out.printf("[rings] resolution %d, budget %d: %d skeleton branches, %d regions, "
                + "%d rims, %d rings in %.0f ms%n", extractor.resolution,
                extractor.skeletonBranchBudget, extractor.skeleton.branches().size(),
                extractor.regionCount, rings.boundariesExamined, rings.ringCount, millis);
        System.out.printf("[rings] rejected: %d grown, %d collapsed, %d merged, %d below %.2f, "
                + "%d unclosed, %d not girdling%n", rings.rejectedGrown, rings.rejectedCollapsed,
                rings.rejectedMerged, rings.rejectedBelowThreshold, extractor.minimumNeckness,
                rings.rejectedUnclosed, rings.rejectedNotGirdling);
        for (int ring = 0; ring < rings.ringCount; ring++) {
            System.out.println(row(mesh, rings, ring));
        }

        String branchCut = System.getProperty(BRANCH_CUT_PROPERTY, "");
        if (!branchCut.isBlank()) {
            String[] parts = branchCut.split(",");
            BranchCrossSectionRing cutter = new BranchCrossSectionRing();
            cutter.resolution = extractor.resolution;
            RingCandidates one = cutter.extract(mesh, new float[] {
                Float.parseFloat(parts[0]), Float.parseFloat(parts[1]),
                Float.parseFloat(parts[2]) }, Float.parseFloat(parts[COORDINATES_PER_POINT]));
            System.out.printf("[rings] branch cut at %s: branch %d, plane %.4f,%.4f,%.4f, "
                    + "radius %.5f, %d rings%n", branchCut, cutter.branch, cutter.planePoint[0],
                    cutter.planePoint[1], cutter.planePoint[2], cutter.localSkeletonRadius,
                    one.ringCount);
            for (int ring = 0; ring < one.ringCount; ring++) {
                System.out.println(row(mesh, one, ring));
            }
        }

        RingCandidateExtractor repeatRun = new RingCandidateExtractor();
        repeatRun.resolution = extractor.resolution;
        repeatRun.skeletonBranchBudget = extractor.skeletonBranchBudget;
        repeatRun.minimumNeckness = extractor.minimumNeckness;
        RingCandidates second = repeatRun.extract(mesh);
        StringBuilder first = new StringBuilder();
        StringBuilder repeat = new StringBuilder();
        for (int ring = 0; ring < rings.ringCount; ring++) {
            first.append(row(mesh, rings, ring)).append('\n');
        }
        for (int ring = 0; ring < second.ringCount; ring++) {
            repeat.append(row(mesh, second, ring)).append('\n');
        }
        System.out.println("[rings] second run identical: "
                + first.toString().equals(repeat.toString()));
    }

    /**
     * One printable ranked row: the fields {@code /mesh/rings/list} returns, then what a loop
     * needs to be a loop. Length over diameter runs near pi for a round girdle and near 2 for a
     * path that doubles back.
     *
     * @param mesh  surface the ring's edge ids belong to
     * @param rings candidates to read
     * @param ring  ring index
     * @return the formatted row
     */
    private static String row(MeshTopology mesh, RingCandidates rings, int ring) {
        int[] edgeIds = rings.marks[ring].markedEdgeIds;
        TracedSurfacePath path = rings.path[ring];
        double diameter = 0.0;
        double[] xyz = path.positions;
        for (int here = 0; here < path.pointCount; here++) {
            for (int there = here + 1; there < path.pointCount; there++) {
                double dx = xyz[COORDINATES_PER_POINT * there] - xyz[COORDINATES_PER_POINT * here];
                double dy = xyz[COORDINATES_PER_POINT * there + 1]
                        - xyz[COORDINATES_PER_POINT * here + 1];
                double dz = xyz[COORDINATES_PER_POINT * there + 2]
                        - xyz[COORDINATES_PER_POINT * here + 2];
                diameter = Math.max(diameter, Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
        }
        return String.format(Locale.ROOT,
                "[rings] %2d neckness=%.3f length=%.5f skeletonRadius=%.5f centroid=%.4f,%.4f,%.4f "
                        + "branches=%d|%d regions=%d|%d area=%.4f|%.4f edges=%d "
                        + "cycle=%b lengthOverDiameter=%.2f",
                ring, rings.neckness[ring], path.length, rings.skeletonRadius[ring],
                path.centroid[0], path.centroid[1], path.centroid[2],
                rings.branchOnOneSide[ring], rings.branchOnOtherSide[ring],
                rings.regionOnOneSide[ring], rings.regionOnOtherSide[ring],
                rings.areaOnOneSide[ring], rings.areaOnOtherSide[ring], edgeIds.length,
                RingCandidateExtractor.marksOneClosedCycle(mesh, edgeIds),
                path.length / Math.max(1e-9, diameter));
    }

    /**
     * Loads the mesh, welding a scan and reducing it to its largest manifold shell first.
     *
     * @param meshPath file to load
     * @throws IOException when the file cannot be read
     * @throws IllegalStateException when a file other than a scan loads without a half-edge form
     * @return a surface the intrinsic triangulation accepts
     */
    private static MeshTopology load(String meshPath) throws IOException {
        MeshTopology loaded = MeshLoader.load(meshPath);
        if (!meshPath.toLowerCase(Locale.ROOT).endsWith(".glb")) {
            if (!(loaded instanceof HalfEdgeMesh)) {
                throw new IllegalStateException(meshPath + " has no half-edge form; only a .glb scan is"
                        + " welded and reduced to its largest manifold shell");
            }
            return loaded;
        }
        MeshMergeByDistance welder = new MeshMergeByDistance();
        welder.weldArrays(loaded, WELD_DISTANCE);
        ArrayMesh welded = new ArrayMesh(welder.weldedPositions, null, welder.weldedFaceCorners,
                ManifoldShell.TRIANGLE_CORNERS);
        ManifoldShell shell = new ManifoldShell();
        HalfEdgeMesh mesh = shell.of(welded);
        System.out.printf("[rings] scan cleanup: %d loaded vertices -> %d welded, %d faces -> %d "
                + "manifold -> %d in the largest shell (%d vertices)%n", loaded.vertexCount(),
                welded.vertexCount(), shell.inputFaceCount, shell.manifoldFaceCount,
                shell.keptFaceCount, shell.keptVertexCount);
        return mesh;
    }
}
