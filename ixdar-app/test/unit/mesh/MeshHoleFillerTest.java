package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.ops.MeshHoleFiller;
import ixdar.geometry.mesh.data.representation.EdgeKey;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.data.representation.TriangleGeometry;

/**
 * {@link MeshHoleFiller} on hand-made loops: collinear T-junction cracks fill without flat
 * triangles, and refinement and fairing leave no needle fan or cone (CRAW-146).
 */
class MeshHoleFillerTest {

    private static final float[] SQUARE_WITH_EDGE_MIDPOINTS = {0, 0, 0, 1, 0, 0, 2, 0, 0, 2, 1, 0, 2, 2, 0, 1, 2, 0,
        0, 2, 0, 0, 1, 0};

    private static final float[] CRACK = {1, 1, 0, 0, 0, 0, 1, 0, 0, 2, 0, 0};

    private static final float[] SCAN_CRACK = {-0.16543801f, -0.021835968f, -0.05094105f, -0.16547173f,
        -0.021172523f, -0.050615758f, -0.16422088f, -0.020702522f, -0.051000662f, -0.16259676f, -0.01903084f,
        -0.051313795f, -0.16166958f, -0.017917126f, -0.051538393f, -0.16214979f, -0.017278403f, -0.05126901f,
        -0.1631356f, -0.016512398f, -0.051183052f, -0.16444862f, -0.017050087f, -0.049984932f, -0.16452652f,
        -0.017404318f, -0.049744904f, -0.16437364f, -0.017958462f, -0.049432218f, -0.16556549f, -0.018191006f,
        -0.049821272f, -0.16639778f, -0.019048989f, -0.050020337f, -0.16643833f, -0.02029854f, -0.0504826f,
        -0.16549292f, -0.020909756f, -0.05038452f, -0.16545054f, -0.02143529f, -0.050846994f};

    private static final double[] SCAN_CRACK_SIGMA = {0.0014597489708008666, 0.0010651176559840245,
        0.0020015679675174673, 0.0019327332350151094, 0.0017505095177718026, 0.0018907027935355363,
        0.0016938154826875071, 0.0011115269643921612, 8.508595281288161E-4, 9.647587068242707E-4,
        0.0014137443365229497, 0.001243580230790747, 0.0011409428782195462, 0.0010799798988764021,
        8.036614302398087E-4};

    private static final int[][] SCAN_CRACK_FORBIDDEN = {{0, 2}, {8, 10}};

    private static final double COARSE_SIGMA = 10.0;

    private static final double FINE_SIGMA = 0.25;

    private static final int POLYGON_SIDES = 24;

    private static final double CAP_POLAR_DEGREES = 40;

    private static final String MINTS = "refinement mints vertices";

    private static final String FILLS = "the loop fills";

    /**
     * A square loop with a midpoint on every side, filled bare and with refinement: every
     * triangle keeps a positive area.
     */
    @Test
    void squareWithEdgeMidpointsFillsWithoutFlatTriangles() {
        assertNoFlatTriangle(fill(SQUARE_WITH_EDGE_MIDPOINTS, COARSE_SIGMA));
        assertNoFlatTriangle(fill(SQUARE_WITH_EDGE_MIDPOINTS, FINE_SIGMA));
    }

    /**
     * A four-vertex crack whose last three vertices are collinear, where the flat triangle ties
     * for the least area, fills with the only two triangles that have area.
     */
    @Test
    void crackFillsAroundItsStraightCorner() {
        MeshHoleFiller filler = fill(CRACK, COARSE_SIGMA);
        assertEquals(2, filler.triangleCount);
        assertNoFlatTriangle(filler);
    }

    /**
     * A hole the IMG_4109 repair opened, at its own density: loop vertex 1 sits on the segment from
     * loop vertex 13 to 14, and the relaxation's flips must not make the flat triangle (13, 14, 1).
     */
    @Test
    void scanCrackRefinesWithoutFlatTriangles() {
        long[] forbidden = Arrays.stream(SCAN_CRACK_FORBIDDEN)
                .mapToLong(chord -> EdgeKey.undirected(chord[0], chord[1])).sorted().toArray();
        assertNoFlatTriangle(fill(SCAN_CRACK, SCAN_CRACK_SIGMA.clone(), forbidden));
    }

    /**
     * A flat regular polygon, where every triangulation has the same area so the bare fill is a fan
     * of needles: refined at the loop's own spacing (L03 section 5), no vertex keeps such a fan, the
     * thinnest corner widens, and no minted vertex is a cone.
     */
    @Test
    void flatPolygonRefinesItsNeedleFanAway() {
        double step = 2 * Math.PI / POLYGON_SIDES;
        MeshHoleFiller bare = banded(latitude(Math.PI / 2, 1), latitude(Math.PI / 2, 1 + step), COARSE_SIGMA);
        MeshHoleFiller refined = banded(latitude(Math.PI / 2, 1), latitude(Math.PI / 2, 1 + step), step);
        assertTrue(refined.vertexCount > 2 * POLYGON_SIDES, MINTS);
        assertTrue(widestFan(refined) < widestFan(bare), "refinement breaks the needle fan up");
        assertTrue(thinnestCorner(refined) > thinnestCorner(bare), "and widens the thinnest corner");
        assertNoCone(refined);
    }

    /**
     * A cap cut out of the unit sphere with the sphere's next ring held fixed: the L03 section 6
     * fairing bulges the refined patch out of the loop's plane toward the sphere, and no minted
     * vertex is a cone sharper or more crumpled than a half turn.
     */
    @Test
    void sphereCapFairsIntoABulgeWithoutCones() {
        double loopPolar = Math.toRadians(CAP_POLAR_DEGREES);
        double step = 2 * Math.PI * Math.sin(loopPolar) / POLYGON_SIDES;
        MeshHoleFiller filler = banded(latitude(loopPolar, 1), latitude(loopPolar + step, 1), step);
        assertTrue(filler.vertexCount > 2 * POLYGON_SIDES, MINTS);
        double loopHeight = Math.cos(loopPolar);
        for (int vertex = 2 * POLYGON_SIDES; vertex < filler.vertexCount; vertex++) {
            assertTrue(filler.vertexPositions.get(vertex, 2) > loopHeight,
                    "minted vertex " + vertex + " bulges above the loop's plane");
        }
        assertNoCone(filler);
    }

    /**
     * Fills a loop whose surroundings are one band of triangles out to a parallel ring, every loop
     * vertex at one density.
     */
    private static MeshHoleFiller banded(float[] loop, float[] ring, double sigma) {
        MeshHoleFiller filler = new MeshHoleFiller();
        filler.loopPositions = PackedPoints.view(loop, 0, PackedPoints.XYZ);
        filler.loopLength = POLYGON_SIDES;
        filler.loopSigma = new double[POLYGON_SIDES];
        Arrays.fill(filler.loopSigma, sigma);
        filler.ringPositions = PackedPoints.view(ring, 0, PackedPoints.XYZ);
        filler.ringCount = POLYGON_SIDES;
        filler.surfaceTriangles = IntStream.range(0, POLYGON_SIDES).flatMap(index -> {
            int next = (index + 1) % POLYGON_SIDES;
            return IntStream.of(index, POLYGON_SIDES + next, POLYGON_SIDES + index, index, next, POLYGON_SIDES + next);
        }).toArray();
        filler.build();
        assertTrue(filler.filled, FILLS);
        return filler;
    }

    private static float[] latitude(double polar, double radius) {
        float[] points = new float[POLYGON_SIDES * PackedPoints.XYZ];
        for (int index = 0; index < POLYGON_SIDES; index++) {
            double azimuth = 2 * Math.PI * index / POLYGON_SIDES;
            points[index * PackedPoints.XYZ] = (float) (radius * Math.sin(polar) * Math.cos(azimuth));
            points[index * PackedPoints.XYZ + 1] = (float) (radius * Math.sin(polar) * Math.sin(azimuth));
            points[index * PackedPoints.XYZ + 2] = (float) (radius * Math.cos(polar));
        }
        return points;
    }

    private static int widestFan(MeshHoleFiller filler) {
        int[] fan = new int[filler.vertexCount];
        for (int slot = 0; slot < filler.triangleCount * HalfEdgeMesh.TRIANGLE_CORNERS; slot++) {
            fan[filler.triangles[slot]]++;
        }
        return Arrays.stream(fan).max().orElse(0);
    }

    private static double thinnestCorner(MeshHoleFiller filler) {
        return Arrays.stream(cornerAngles(filler)).min().orElse(0);
    }

    private static double[] cornerAngles(MeshHoleFiller filler) {
        return IntStream.range(0, filler.triangleCount * HalfEdgeMesh.TRIANGLE_CORNERS).mapToDouble(slot -> {
            int first = slot - slot % HalfEdgeMesh.TRIANGLE_CORNERS;
            return TriangleGeometry.cornerAngle(filler.vertexPositions, filler.triangles[slot],
                    filler.triangles[first + (slot + 1) % HalfEdgeMesh.TRIANGLE_CORNERS],
                    filler.triangles[first + (slot + 2) % HalfEdgeMesh.TRIANGLE_CORNERS]);
        }).toArray();
    }

    private static void assertNoCone(MeshHoleFiller filler) {
        double[] angles = cornerAngles(filler);
        double[] sums = new double[filler.vertexCount];
        for (int slot = 0; slot < angles.length; slot++) {
            sums[filler.triangles[slot]] += angles[slot];
        }
        for (int vertex = filler.loopLength + filler.ringCount; vertex < filler.vertexCount; vertex++) {
            assertTrue(Math.abs(2 * Math.PI - sums[vertex]) < Math.PI,
                    "minted vertex " + vertex + " has angle sum " + Math.toDegrees(sums[vertex]));
        }
    }

    private static MeshHoleFiller fill(float[] loop, double sigma) {
        double[] loopSigma = new double[loop.length / PackedPoints.XYZ];
        Arrays.fill(loopSigma, sigma);
        return fill(loop, loopSigma, new long[0]);
    }

    private static MeshHoleFiller fill(float[] loop, double[] loopSigma, long[] forbiddenChords) {
        MeshHoleFiller filler = new MeshHoleFiller();
        filler.loopPositions = PackedPoints.view(loop.clone(), 0, PackedPoints.XYZ);
        filler.loopLength = filler.loopPositions.size();
        filler.loopSigma = loopSigma;
        filler.forbiddenChords = forbiddenChords;
        filler.build();
        assertTrue(filler.filled, FILLS);
        return filler;
    }

    private static void assertNoFlatTriangle(MeshHoleFiller filler) {
        int[] corners = Arrays.copyOf(filler.triangles, filler.triangleCount * HalfEdgeMesh.TRIANGLE_CORNERS);
        double[] areas = TriangleGeometry.areas(filler.vertexPositions, corners);
        List<Integer> flat = IntStream.range(0, areas.length).filter(triangle -> areas[triangle] == 0).boxed().toList();
        assertEquals(List.of(), flat, "fill triangles of zero area");
    }
}
