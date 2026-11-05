package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.analysis.curvature.ImplicitFit;
import ixdar.geometry.mesh.data.analysis.curvature.SurfaceRidges;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.ops.MeshVertexOffset;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.sculpt.MidpointSplit;
import ixdar.nodegraph.api.MapNodeContext;
import ixdar.nodegraph.field.Vector3Field;
import ixdar.nodegraph.nodes.modifier.MergeByDistanceNode;
import ixdar.nodegraph.nodes.primitives.QuadSphereMeshNode;

/**
 * OBS04 on shapes whose curvature is known: a sphere's fit has curvature 1 / R, eq. 2's derivative
 * matches a finite difference of the curvature along its line, and an ellipsoid's ridges and valleys
 * lie on its three principal sections.
 */
class SurfaceRidgesTest {

    private static final double[] AXES = { 3.0, 2.0, 1.2 };

    private static final int SAMPLES = 20000;

    private static final int LEVELS = 4;

    private static final double FIRST_CELL = 2.0;

    private static final double REGULARIZATION = 0.1;

    private static final double STEP = 0.005;

    /**
     * Points and normals of the ellipsoid x^2/a^2 + y^2/b^2 + z^2/c^2 = 1, the unit sphere's Fibonacci
     * lattice stretched.
     */
    static double[][] ellipsoid(double[] axes) {
        double[] xyz = new double[SAMPLES * 3];
        double[] normal = new double[SAMPLES * 3];
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int index = 0; index < SAMPLES; index++) {
            double z = 1.0 - 2.0 * (index + 0.5) / SAMPLES;
            double ring = Math.sqrt(1.0 - z * z);
            double[] unit = { ring * Math.cos(golden * index), ring * Math.sin(golden * index), z };
            double length = 0.0;
            for (int axis = 0; axis < 3; axis++) {
                xyz[index * 3 + axis] = axes[axis] * unit[axis];
                normal[index * 3 + axis] = unit[axis] / axes[axis];
                length += normal[index * 3 + axis] * normal[index * 3 + axis];
            }
            for (int axis = 0; axis < 3; axis++) {
                normal[index * 3 + axis] /= Math.sqrt(length);
            }
        }
        return new double[][] { xyz, normal };
    }

    @Test
    void jetMatchesFiniteDifferences() {
        double[][] shape = ellipsoid(AXES);
        ImplicitFit fit = new ImplicitFit(shape[0], shape[1], FIRST_CELL, LEVELS, REGULARIZATION);
        double[] jet = new double[ImplicitFit.JET_LENGTH];
        double[] plus = new double[ImplicitFit.JET_LENGTH];
        double[] minus = new double[ImplicitFit.JET_LENGTH];
        double step = 1e-4;
        for (double[] point : new double[][] { { 1.5, 1.2, 0.6 }, { -2.0, 0.7, 0.7 } }) {
            fit.evaluate(fit.levels.size(), point, 0, ImplicitFit.THIRD, jet);
            for (int axis = 0; axis < 3; axis++) {
                double[] ahead = point.clone();
                double[] back = point.clone();
                ahead[axis] += step;
                back[axis] -= step;
                fit.evaluate(fit.levels.size(), ahead, 0, ImplicitFit.THIRD, plus);
                fit.evaluate(fit.levels.size(), back, 0, ImplicitFit.THIRD, minus);
                // Each order's derivative along this axis against the next order's analytic entries.
                assertEquals((plus[0] - minus[0]) / (2 * step), jet[ImplicitFit.GRADIENT_START + axis], 1e-5,
                        "gradient " + axis);
                for (int a = 0; a < 3; a++) {
                    assertEquals((plus[ImplicitFit.GRADIENT_START + a] - minus[ImplicitFit.GRADIENT_START + a]) / (2 * step),
                            jet[ImplicitFit.HESSIAN_START + a * 3 + axis], 1e-4, "hessian " + a + axis);
                    for (int b = 0; b < 3; b++) {
                        assertEquals((plus[ImplicitFit.HESSIAN_START + a * 3 + b] - minus[ImplicitFit.HESSIAN_START + a * 3 + b])
                                / (2 * step), jet[ImplicitFit.THIRD_START + a * 9 + b * 3 + axis], 1e-3,
                                "third " + a + b + axis);
                    }
                }
            }
        }
    }

    @Test
    void sphereCurvatureIsOneOverRadius() {
        double radius = 2.0;
        double[][] sphere = ellipsoid(new double[] { radius, radius, radius });
        ImplicitFit fit = new ImplicitFit(sphere[0], sphere[1], FIRST_CELL, LEVELS, REGULARIZATION);
        double[] out = new double[ImplicitFit.CURVATURE_LENGTH];
        for (double[] point : new double[][] { { 1.9, 0.3, 0.2 }, { -0.5, 1.1, -1.5 }, { 0.2, -0.1, 2.1 } }) {
            assertTrue(fit.curvature(point, out));
            double distance = Math.sqrt(point[0] * point[0] + point[1] * point[1] + point[2] * point[2]);
            assertEquals(radius, distance, 0.02 * radius, "projection lands on the sphere");
            assertEquals(1.0 / radius, out[0], 0.1 / radius, "larger curvature");
            assertEquals(1.0 / radius, out[1], 0.1 / radius, "smaller curvature");
        }
    }

    @Test
    void derivativeMatchesFiniteDifferenceAlongTheLine() {
        double[][] shape = ellipsoid(AXES);
        ImplicitFit fit = new ImplicitFit(shape[0], shape[1], FIRST_CELL, LEVELS, REGULARIZATION);
        double[] here = new double[ImplicitFit.CURVATURE_LENGTH];
        double[] ahead = new double[ImplicitFit.CURVATURE_LENGTH];
        double[] behind = new double[ImplicitFit.CURVATURE_LENGTH];
        int compared = 0;
        for (double[] start : new double[][] { { 1.5, 1.2, 0.6 }, { -2.0, 0.7, 0.7 }, { 0.8, -1.4, -0.7 } }) {
            assertTrue(fit.curvature(start, here));
            for (int kind = 0; kind < 2; kind++) {
                double[] forward = start.clone();
                double[] backward = start.clone();
                for (int axis = 0; axis < 3; axis++) {
                    forward[axis] += STEP * here[ImplicitFit.DIRECTIONS_START + kind * 3 + axis];
                    backward[axis] -= STEP * here[ImplicitFit.DIRECTIONS_START + kind * 3 + axis];
                }
                assertTrue(fit.curvature(forward, ahead));
                assertTrue(fit.curvature(backward, behind));
                double travelled = 0.0;
                for (int axis = 0; axis < 3; axis++) {
                    travelled += (forward[axis] - backward[axis]) * (forward[axis] - backward[axis]);
                }
                double difference = (ahead[kind] - behind[kind]) / Math.sqrt(travelled);
                double analytic = here[ImplicitFit.DERIVATIVES_START + kind];
                assertEquals(difference, analytic, 0.05 * Math.abs(difference) + 0.01,
                        "derivative of curvature " + kind + " at " + start[0] + "," + start[1] + "," + start[2]);
                compared++;
            }
        }
        assertEquals(6, compared);
    }

    @Test
    void ellipsoidLinesLieOnItsPrincipalSections() {
        MapNodeContext ball = new MapNodeContext(new QuadSphereMeshNode())
                .with(QuadSphereMeshNode.SIZE, 2f).with(QuadSphereMeshNode.RESOLUTION, 96).eval();
        MeshTopology merged = new MapNodeContext(new MergeByDistanceNode())
                .with(MergeByDistanceNode.GEOMETRY, ball.output(QuadSphereMeshNode.MESH, GeometryBundle.class))
                .with(MergeByDistanceNode.DISTANCE, 1e-4f).eval()
                .output(MergeByDistanceNode.GEOMETRY_OUT, GeometryBundle.class).mesh();
        // Split once so the edges are short enough for every level asked; the stretch below puts the new
        // vertices on the ellipsoid too.
        boolean[] every = new boolean[merged.faceIdBound()];
        Arrays.fill(every, true);
        MeshTopology sphere = new MidpointSplit().split(merged, every);
        float[] offset = new float[sphere.vertexIdBound() * 3];
        Vector3f position = new Vector3f();
        for (int activeVertex = 0; activeVertex < sphere.vertexCount(); activeVertex++) {
            int vertexId = sphere.vertexIdAt(activeVertex);
            sphere.vertexPosition(vertexId, position).normalize();
            for (int axis = 0; axis < 3; axis++) {
                offset[vertexId * 3 + axis] = (float) (position.get(axis) * AXES[axis]) - sphere.vertexPosition(vertexId,
                        new Vector3f()).get(axis);
            }
        }
        MeshTopology shape = MeshVertexOffset.apply(sphere, Vector3Field.copyOf(offset));
        SurfaceRidges ridges = new SurfaceRidges();
        ridges.levels = LEVELS;
        ridges.detect(shape);
        assertEquals(LEVELS, ridges.levelsFitted, "the mesh is fine enough for every level asked");
        assertFalse(ridges.lines.isEmpty(), "an ellipsoid has ridges");
        int points = 0;
        int onSection = 0;
        for (float[] line : ridges.lines) {
            for (int point = 0; point * 3 < line.length; point++) {
                double nearest = Double.POSITIVE_INFINITY;
                for (int axis = 0; axis < 3; axis++) {
                    nearest = Math.min(nearest, Math.abs(line[point * 3 + axis]) / AXES[axis]);
                }
                onSection += nearest < 0.08 ? 1 : 0;
                points++;
            }
        }
        assertTrue(onSection > 0.9 * points, onSection + " of " + points + " line points on a principal section");
    }
}
