package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.paths.SurfaceMetric;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.quadlayout.crossfield.NDirectionField;
import ixdar.geometry.mesh.quadlayout.crossfield.SectionIntegrals;
import ixdar.geometry.mesh.quadlayout.solver.matrix.NormalMatrix;

/**
 * The flat-array cross-field assembly must reproduce, entry for entry, the keyed-map assembly it
 * replaced (kept here as the reference) on a closed torus and on a disk with boundary.
 */
class CrossFieldAssemblyTest {

    private static final String TORUS_SOURCE =
            "carrier = torus(major_radius=1.0, minor_radius=0.35, major_segments=12,"
                    + " minor_segments=8, triangulate=true)";

    private static final String DISK_SOURCE =
            "carrier = mesh_disk(rings=4, angular_segments=12, radius=4.0, triangulate=true)";

    private static final double CURVATURE_BIAS = -1.0;

    private static final int KEY_SHIFT = 32;

    private static final long KEY_MASK = 0xFFFFFFFFL;

    private static final int TRIANGLE_CORNERS = 3;

    @Test
    void torusAssemblyMatchesTheMapReference() throws Exception {
        assertAssemblyMatchesReference(TORUS_SOURCE);
    }

    @Test
    void diskAssemblyMatchesTheMapReference() throws Exception {
        assertAssemblyMatchesReference(DISK_SOURCE);
    }

    /**
     * Builds the field on the primitive the source makes and compares its energy and mass
     * matrices with the keyed-map reference assembly, entry for entry and bit for bit.
     *
     * @param source DSL statement producing the carrier mesh
     * @throws Exception when the primitive graph fails
     */
    private static void assertAssemblyMatchesReference(String source) throws Exception {
        NodeGraphRuntime runtime = NodeGraphRuntime.fromSource(source);
        GeometryBundle bundle = (GeometryBundle) runtime.executeGraphResult(runtime.statements,
                "carrier", "geometry");
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.fromMeshTopology(bundle.mesh());
        NDirectionField field = new NDirectionField();
        field.curvatureBias = CURVATURE_BIAS;
        field.build(mesh, SurfaceMetric.of(mesh));

        int vertexCount = mesh.vertexCount();
        double[] diag = new double[vertexCount];
        Map<Long, Double> upRe = new HashMap<>();
        Map<Long, Double> upIm = new HashMap<>();
        double[] diagMass = new double[vertexCount];
        Map<Long, Double> massUpRe = new HashMap<>();
        Map<Long, Double> massUpIm = new HashMap<>();
        for (int f = 0; f < mesh.faceCount(); f++) {
            int fId = mesh.faceIdAt(f);
            int[] halfEdge = new int[TRIANGLE_CORNERS];
            int[] vertexId = new int[TRIANGLE_CORNERS];
            int[] active = new int[TRIANGLE_CORNERS];
            Vector3f[] position = new Vector3f[TRIANGLE_CORNERS];
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                halfEdge[corner] = mesh.faceHalfEdgeAt(fId, corner);
                vertexId[corner] = mesh.halfEdgeVertex(halfEdge[corner]);
                active[corner] = field.activeOfVertexId[vertexId[corner]];
                position[corner] = mesh.vertexPosition(vertexId[corner]);
            }
            double[] transportAngle = new double[TRIANGLE_CORNERS];
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                int twin = mesh.halfEdgeTwin(halfEdge[corner]);
                transportAngle[corner] = field.n
                        * (angle(field, mesh, vertexId[(corner + 1) % TRIANGLE_CORNERS], twin)
                                - angle(field, mesh, vertexId[corner], halfEdge[corner]));
            }
            double holonomy = (transportAngle[0] + transportAngle[1] + transportAngle[2])
                    % (2.0 * Math.PI);
            if (holonomy <= -Math.PI) {
                holonomy += 2.0 * Math.PI;
            }
            if (holonomy > Math.PI) {
                holonomy -= 2.0 * Math.PI;
            }
            Vector3f edge0 = new Vector3f(position[1]).sub(position[0]);
            double area = 0.5 * new Vector3f(edge0)
                    .cross(new Vector3f(position[2]).sub(position[0])).length();
            if (area < NDirectionField.EPS) {
                continue;
            }
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                Vector3f toNext = new Vector3f(position[(corner + 1) % TRIANGLE_CORNERS])
                        .sub(position[corner]);
                Vector3f toPrev = new Vector3f(position[(corner + 2) % TRIANGLE_CORNERS])
                        .sub(position[corner]);
                double diagStiff = SectionIntegrals.stiffnessDiagonal(holonomy,
                        toNext.lengthSquared(), toNext.dot(toPrev), toPrev.lengthSquared());
                diag[active[corner]] += diagStiff / area - CURVATURE_BIAS * holonomy / 6.0;
                diagMass[active[corner]] += area / 6.0;
            }
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                int from = active[corner];
                int to = active[(corner + 1) % TRIANGLE_CORNERS];
                int opposite = (corner + 2) % TRIANGLE_CORNERS;
                Vector3f oppToFrom = new Vector3f(position[corner]).sub(position[opposite]);
                Vector3f oppToTo = new Vector3f(position[(corner + 1) % TRIANGLE_CORNERS])
                        .sub(position[opposite]);
                double[] stiff = SectionIntegrals.stiffnessOffDiagonal(holonomy,
                        oppToFrom.lengthSquared(), oppToFrom.dot(oppToTo), oppToTo.lengthSquared());
                double[] massOff = SectionIntegrals.massOffDiagonal(holonomy);
                double entryRe = stiff[0] / area - CURVATURE_BIAS * holonomy * massOff[0];
                double entryIm = stiff[1] / area - CURVATURE_BIAS * (holonomy * massOff[1] - 0.5);
                double cosRho = Math.cos(transportAngle[corner]);
                double sinRho = Math.sin(transportAngle[corner]);
                double re = entryRe * cosRho + entryIm * sinRho;
                double im = entryIm * cosRho - entryRe * sinRho;
                int low = Math.min(from, to);
                int high = Math.max(from, to);
                accumulate(upRe, upIm, low, high, re, low == from ? im : -im);
                double massRe0 = area * massOff[0];
                double massIm0 = area * massOff[1];
                double massRe = massRe0 * cosRho + massIm0 * sinRho;
                double massIm = massIm0 * cosRho - massRe0 * sinRho;
                accumulate(massUpRe, massUpIm, low, high, massRe, low == from ? massIm : -massIm);
            }
        }
        for (int v = 0; v < vertexCount; v++) {
            diag[v] += NDirectionField.DEFAULT_SHIFT * diagMass[v];
        }
        assertSameEntries(realify(diag, upRe, upIm), field.energyMatrix, "energy");
        assertSameEntries(realify(diagMass, massUpRe, massUpIm), field.massSystemMatrix, "mass");
    }

    private static double angle(NDirectionField field, HalfEdgeMesh mesh, int vertexId,
            int halfEdge) {
        double value = field.angleInFrame[halfEdge];
        return mesh.halfEdgeVertex(halfEdge) == vertexId && !Double.isNaN(value) ? value : 0.0;
    }

    private static void accumulate(Map<Long, Double> real, Map<Long, Double> imaginary, int row,
            int column, double realValue, double imaginaryValue) {
        long key = ((long) row << KEY_SHIFT) | (column & KEY_MASK);
        real.merge(key, realValue, Double::sum);
        imaginary.merge(key, imaginaryValue, Double::sum);
    }

    /**
     * The reference realify: each upper complex entry a + ib becomes four upper real entries.
     *
     * @param complexDiagonal real diagonal per vertex
     * @param upRe            real parts keyed by (low, high)
     * @param upIm            imaginary parts keyed by (low, high)
     * @return the realified matrix built through the keyed-map constructor
     */
    private static NormalMatrix realify(double[] complexDiagonal, Map<Long, Double> upRe,
            Map<Long, Double> upIm) {
        int dofCount = 2 * complexDiagonal.length;
        double[] realDiagonal = new double[dofCount];
        for (int v = 0; v < complexDiagonal.length; v++) {
            realDiagonal[2 * v] = complexDiagonal[v];
            realDiagonal[2 * v + 1] = complexDiagonal[v];
        }
        Map<Long, Double> upper = new HashMap<>();
        for (Map.Entry<Long, Double> entry : upRe.entrySet()) {
            long key = entry.getKey();
            int i = (int) (key >>> KEY_SHIFT);
            int j = (int) (key & KEY_MASK);
            double a = entry.getValue();
            double b = upIm.getOrDefault(key, 0.0);
            upper.merge(pack(2 * i, 2 * j), a, Double::sum);
            upper.merge(pack(2 * i + 1, 2 * j + 1), a, Double::sum);
            upper.merge(pack(2 * i, 2 * j + 1), -b, Double::sum);
            upper.merge(pack(2 * i + 1, 2 * j), b, Double::sum);
        }
        return new NormalMatrix(realDiagonal, upper, new double[dofCount]);
    }

    private static long pack(int row, int column) {
        return ((long) row << KEY_SHIFT) | (column & KEY_MASK);
    }

    /**
     * Asserts both matrices hold exactly the same value at every (row, column), diagonal
     * included, with duplicate storage summed.
     *
     * @param expected reference matrix
     * @param actual   matrix under test
     * @param label    which matrix, for the failure message
     */
    private static void assertSameEntries(NormalMatrix expected, NormalMatrix actual,
            String label) {
        assertEquals(expected.size(), actual.size(), label + " dimension");
        Map<Long, Double> expectedEntries = entries(expected);
        Map<Long, Double> actualEntries = entries(actual);
        Set<Long> keys = new HashSet<>(expectedEntries.keySet());
        keys.addAll(actualEntries.keySet());
        assertTrue(expectedEntries.size() > expected.size(), label + " has off-diagonal entries");
        for (long key : keys) {
            assertEquals(expectedEntries.getOrDefault(key, 0.0), actualEntries.getOrDefault(key, 0.0),
                    0.0, label + " entry (" + (key >>> KEY_SHIFT) + ", " + (key & KEY_MASK) + ")");
        }
    }

    private static Map<Long, Double> entries(NormalMatrix matrix) {
        Map<Long, Double> entries = new HashMap<>();
        for (int row = 0; row < matrix.size(); row++) {
            entries.merge(pack(row, row), matrix.diag(row), Double::sum);
            for (int cursor = matrix.rowStart(row); cursor < matrix.rowEnd(row); cursor++) {
                entries.merge(pack(row, matrix.column(cursor)), matrix.value(cursor), Double::sum);
            }
        }
        return entries;
    }
}
