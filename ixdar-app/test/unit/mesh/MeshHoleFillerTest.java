package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.ops.MeshHoleFiller;
import ixdar.geometry.mesh.data.representation.EdgeKey;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.data.representation.TriangleGeometry;

/**
 * {@link MeshHoleFiller} on loops whose consecutive vertices are collinear, the T-junction cracks
 * a Trellis2 scan leaves: no fill triangle may have zero area.
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
        assertTrue(filler.filled, "the loop fills");
        return filler;
    }

    private static void assertNoFlatTriangle(MeshHoleFiller filler) {
        int[] corners = Arrays.copyOf(filler.triangles, filler.triangleCount * MeshHoleFiller.TRIANGLE_CORNERS);
        double[] areas = TriangleGeometry.areas(filler.vertexPositions, corners);
        List<Integer> flat = IntStream.range(0, areas.length).filter(triangle -> areas[triangle] == 0).boxed().toList();
        assertEquals(List.of(), flat, "fill triangles of zero area");
    }
}
