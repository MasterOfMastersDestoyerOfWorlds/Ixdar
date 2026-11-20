package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.joml.Vector3f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.analysis.curvature.ImplicitFit;
import ixdar.geometry.mesh.data.analysis.curvature.SurfaceRidges;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.ops.MeshVertexOffset;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.PackedPoints;
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

    private static final int SPHERE_SAMPLES = 4000;

    private static final int LEVELS = 4;

    private static final double FIRST_CELL = 2.0;

    private static final double REGULARIZATION = 0.1;

    private static final double STEP = 0.005;

    private static final ImplicitFit ELLIPSOID_FIT;

    static {
        PackedPoints[] shape = ellipsoid(AXES, SAMPLES);
        ELLIPSOID_FIT = new ImplicitFit(shape[0], shape[1], FIRST_CELL, LEVELS, REGULARIZATION);
    }

    /**
     * Points and normals of the ellipsoid x^2/a^2 + y^2/b^2 + z^2/c^2 = 1, the unit sphere's Fibonacci
     * lattice stretched.
     */
    private static PackedPoints[] ellipsoid(double[] axes, int samples) {
        PackedPoints xyz = new PackedPoints(samples);
        PackedPoints normals = new PackedPoints(samples);
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int index = 0; index < samples; index++) {
            double z = 1.0 - 2.0 * (index + 0.5) / samples;
            double ring = Math.sqrt(1.0 - z * z);
            double[] unit = { ring * Math.cos(golden * index), ring * Math.sin(golden * index), z };
            double[] normal = new double[3];
            double length = 0.0;
            for (int axis = 0; axis < 3; axis++) {
                normal[axis] = unit[axis] / axes[axis];
                length += normal[axis] * normal[axis];
            }
            xyz.set(index, (float) (axes[0] * unit[0]), (float) (axes[1] * unit[1]), (float) (axes[2] * unit[2]));
            normals.set(index, (float) (normal[0] / Math.sqrt(length)), (float) (normal[1] / Math.sqrt(length)),
                    (float) (normal[2] / Math.sqrt(length)));
        }
        return new PackedPoints[] { xyz, normals };
    }

    @Test
    void jetMatchesFiniteDifferences() {
        ImplicitFit fit = ELLIPSOID_FIT;
        double[] jet = new double[ImplicitFit.JET_LENGTH];
        double[] plus = new double[ImplicitFit.JET_LENGTH];
        double[] minus = new double[ImplicitFit.JET_LENGTH];
        for (Vector3f point : new Vector3f[] { new Vector3f(1.5f, 1.2f, 0.6f), new Vector3f(-2.0f, 0.7f, 0.7f) }) {
            fit.evaluate(fit.levels.size(), point, ImplicitFit.THIRD, jet);
            for (int axis = 0; axis < 3; axis++) {
                Vector3f ahead = new Vector3f(point).setComponent(axis, point.get(axis) + 1e-4f);
                Vector3f back = new Vector3f(point).setComponent(axis, point.get(axis) - 1e-4f);
                // Half the float step actually taken, so rounding the moved point to float is not counted as error.
                double step = ((double) ahead.get(axis) - back.get(axis)) / 2.0;
                fit.evaluate(fit.levels.size(), ahead, ImplicitFit.THIRD, plus);
                fit.evaluate(fit.levels.size(), back, ImplicitFit.THIRD, minus);
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
        PackedPoints[] sphere = ellipsoid(new double[] { radius, radius, radius }, SPHERE_SAMPLES);
        ImplicitFit fit = new ImplicitFit(sphere[0], sphere[1], FIRST_CELL, LEVELS, REGULARIZATION);
        double[] out = new double[ImplicitFit.CURVATURE_LENGTH];
        for (Vector3f point : new Vector3f[] { new Vector3f(1.9f, 0.3f, 0.2f), new Vector3f(-0.5f, 1.1f, -1.5f),
            new Vector3f(0.2f, -0.1f, 2.1f) }) {
            assertTrue(fit.curvature(point, out));
            double distance = point.length();
            assertEquals(radius, distance, 0.02 * radius, "projection lands on the sphere");
            assertEquals(1.0 / radius, out[0], 0.1 / radius, "larger curvature");
            assertEquals(1.0 / radius, out[1], 0.1 / radius, "smaller curvature");
        }
    }

    @Test
    void derivativeMatchesFiniteDifferenceAlongTheLine() {
        ImplicitFit fit = ELLIPSOID_FIT;
        double[] here = new double[ImplicitFit.CURVATURE_LENGTH];
        double[] ahead = new double[ImplicitFit.CURVATURE_LENGTH];
        double[] behind = new double[ImplicitFit.CURVATURE_LENGTH];
        int compared = 0;
        for (Vector3f start : new Vector3f[] { new Vector3f(1.5f, 1.2f, 0.6f), new Vector3f(-2.0f, 0.7f, 0.7f),
            new Vector3f(0.8f, -1.4f, -0.7f) }) {
            assertTrue(fit.curvature(start, here));
            for (int kind = 0; kind < 2; kind++) {
                int direction = ImplicitFit.DIRECTIONS_START + kind * 3;
                Vector3f along = new Vector3f((float) (STEP * here[direction]), (float) (STEP * here[direction + 1]),
                        (float) (STEP * here[direction + 2]));
                Vector3f forward = new Vector3f(start).add(along);
                Vector3f backward = new Vector3f(start).sub(along);
                assertTrue(fit.curvature(forward, ahead));
                assertTrue(fit.curvature(backward, behind));
                double travelled = forward.distance(backward);
                double difference = (ahead[kind] - behind[kind]) / travelled;
                double analytic = here[ImplicitFit.DERIVATIVES_START + kind];
                assertEquals(difference, analytic, 0.05 * Math.abs(difference) + 0.01,
                        "derivative of curvature " + kind + " at " + start);
                compared++;
            }
        }
        assertEquals(6, compared);
    }

    @Test
    @Tag("heavy")
    void ellipsoidLinesLieOnItsPrincipalSections() {
        MapNodeContext ball = new MapNodeContext(new QuadSphereMeshNode())
                .with(QuadSphereMeshNode.SIZE, 2f).with(QuadSphereMeshNode.RESOLUTION, 96).eval();
        MeshTopology merged = new MapNodeContext(new MergeByDistanceNode())
                .with(MergeByDistanceNode.GEOMETRY, ball.output(QuadSphereMeshNode.MESH, GeometryBundle.class))
                .with(MergeByDistanceNode.DISTANCE, 1e-4f).eval()
                .output(MergeByDistanceNode.GEOMETRY_OUT, GeometryBundle.class).mesh();
        boolean[] every = new boolean[merged.faceIdBound()];
        Arrays.fill(every, true);
        MeshTopology sphere = new MidpointSplit().split(merged, every);
        PackedPoints offset = new PackedPoints(sphere.vertexIdBound());
        Vector3f position = new Vector3f();
        for (int activeVertex = 0; activeVertex < sphere.vertexCount(); activeVertex++) {
            int vertexId = sphere.vertexIdAt(activeVertex);
            sphere.vertexPosition(vertexId, position).normalize();
            Vector3f original = sphere.vertexPosition(vertexId, new Vector3f());
            offset.set(vertexId, (float) (position.x * AXES[0]) - original.x,
                    (float) (position.y * AXES[1]) - original.y, (float) (position.z * AXES[2]) - original.z);
        }
        MeshTopology shape = MeshVertexOffset.apply(sphere, Vector3Field.copyOf(offset));
        SurfaceRidges ridges = new SurfaceRidges();
        ridges.levels = LEVELS;
        ridges.detect(shape);
        assertEquals(LEVELS, ridges.levelsFitted, "the mesh is fine enough for every level asked");
        assertFalse(ridges.lines.isEmpty(), "an ellipsoid has ridges");
        int points = 0;
        int onSection = 0;
        for (PackedPoints line : ridges.lines) {
            for (int point = 0; point < line.size(); point++) {
                double nearest = Double.POSITIVE_INFINITY;
                for (int axis = 0; axis < 3; axis++) {
                    nearest = Math.min(nearest, Math.abs(line.get(point, axis)) / AXES[axis]);
                }
                onSection += nearest < 0.08 ? 1 : 0;
                points++;
            }
        }
        assertTrue(onSection > 0.9 * points, onSection + " of " + points + " line points on a principal section");
    }
}
