package benchmark;

import java.io.IOException;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.BranchCrossSectionRing;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.RingCandidateExtractor;
import ixdar.geometry.mesh.data.RingCandidates;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.ops.MeshMergeByDistance;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;

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

    /** Input-file entry point: the mesh rings are proposed on. */
    private static final String MESH_PROPERTY = "benchmark.ringMesh";

    /** Entry point for the skeleton's voxel resolution. */
    private static final String RESOLUTION_PROPERTY = "benchmark.ringResolution";

    /** Entry point for the neckness threshold. */
    private static final String NECKNESS_PROPERTY = "benchmark.ringNeckness";

    /** Entry point for the skeleton's branch-extraction budget. */
    private static final String BUDGET_PROPERTY = "benchmark.ringBudget";

    /** Entry point for a branch cut to report as well: {@code x,y,z,t}. */
    private static final String BRANCH_CUT_PROPERTY = "benchmark.ringAtBranch";

    /** Mesh used when the property is absent. */
    private static final String DEFAULT_MESH =
            "test/resources/quadlayout/figure_8/fertility_in_tri.off";

    /** Weld radius applied to a scan before anything else. */
    private static final float WELD_DISTANCE = 1e-6f;

    /** Nanoseconds in a millisecond. */
    private static final double NANOS_PER_MILLI = 1e6;

    /** Coordinates per point in a packed position array. */
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
        int[] edgeIds = rings.edgeIdsOf(ring);
        return String.format(Locale.ROOT,
                "[rings] %2d neckness=%.3f length=%.5f skeletonRadius=%.5f centroid=%.4f,%.4f,%.4f "
                        + "branches=%d|%d regions=%d|%d area=%.4f|%.4f edges=%d closingSpan=%.5f "
                        + "cycle=%b parts=%d lengthOverDiameter=%.2f",
                ring, rings.neckness[ring], rings.length[ring], rings.skeletonRadius[ring],
                rings.centroid[COORDINATES_PER_POINT * ring],
                rings.centroid[COORDINATES_PER_POINT * ring + 1],
                rings.centroid[COORDINATES_PER_POINT * ring + 2],
                rings.branchOnOneSide[ring], rings.branchOnOtherSide[ring],
                rings.regionOnOneSide[ring], rings.regionOnOtherSide[ring],
                rings.areaOnOneSide[ring], rings.areaOnOtherSide[ring], edgeIds.length,
                closingSpan(rings, ring), RingCandidates.marksOneClosedCycle(mesh, edgeIds),
                RingCandidates.markedEdgeComponents(mesh, edgeIds),
                rings.length[ring] / Math.max(1e-9, polylineDiameter(rings, ring)));
    }

    /**
     * Distance from a ring's last polyline point back to its first, zero when the stored loop
     * already repeats its first point at the end.
     *
     * @param rings candidates to read
     * @param ring  ring index
     * @return the length of the span left open between the polyline's two ends
     */
    private static double closingSpan(RingCandidates rings, int ring) {
        int points = rings.ringPointCount(ring);
        if (points < 2) {
            return 0.0;
        }
        int first = COORDINATES_PER_POINT * rings.polylineOffset[ring];
        int last = COORDINATES_PER_POINT * (rings.polylineOffset[ring] + points - 1);
        double dx = rings.polylinePoint[last] - rings.polylinePoint[first];
        double dy = rings.polylinePoint[last + 1] - rings.polylinePoint[first + 1];
        double dz = rings.polylinePoint[last + 2] - rings.polylinePoint[first + 2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Largest straight-line distance between two points of a ring's polyline.
     *
     * @param rings candidates to read
     * @param ring  ring index
     * @return the diameter of the point set, in world units
     */
    private static double polylineDiameter(RingCandidates rings, int ring) {
        int from = rings.polylineOffset[ring];
        int to = rings.polylineOffset[ring + 1];
        double widest = 0.0;
        for (int here = from; here < to; here++) {
            for (int there = here + 1; there < to; there++) {
                double dx = rings.polylinePoint[COORDINATES_PER_POINT * there]
                        - rings.polylinePoint[COORDINATES_PER_POINT * here];
                double dy = rings.polylinePoint[COORDINATES_PER_POINT * there + 1]
                        - rings.polylinePoint[COORDINATES_PER_POINT * here + 1];
                double dz = rings.polylinePoint[COORDINATES_PER_POINT * there + 2]
                        - rings.polylinePoint[COORDINATES_PER_POINT * here + 2];
                widest = Math.max(widest, Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
        }
        return widest;
    }

    /**
     * Loads the mesh, welding a scan and reducing it to its largest manifold shell first.
     *
     * @param meshPath file to load
     * @throws IOException when the file cannot be read
     * @return a surface the intrinsic triangulation accepts
     */
    private static MeshTopology load(String meshPath) throws IOException {
        ArrayMesh loaded = MeshLoader.load(meshPath);
        if (!meshPath.toLowerCase(Locale.ROOT).endsWith(".glb")) {
            return HalfEdgeMeshEngine.fromMeshTopology(loaded);
        }
        ArrayMesh welded = MeshMergeByDistance.mergeToArrayMesh(loaded, WELD_DISTANCE);
        ManifoldShell shell = new ManifoldShell();
        HalfEdgeMesh mesh = shell.of(welded);
        System.out.printf("[rings] scan cleanup: %d loaded vertices -> %d welded, %d faces -> %d "
                + "manifold -> %d in the largest shell (%d vertices)%n", loaded.vertexCount(),
                welded.vertexCount(), shell.inputFaceCount, shell.manifoldFaceCount,
                shell.keptFaceCount, shell.keptVertexCount);
        return mesh;
    }
}
