package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.nodegraph.runtime.NodeGraphRuntime;

/**
 * Every closed primitive in {@code dsl/fixtures/primitives.dsl} winds counter-clockwise seen from
 * outside: its signed volume is positive and equals the volume of the polyhedron it inscribes.
 */
class PrimitiveWindingTest {

    private static final String FIXTURE = "dsl/fixtures/primitives.dsl";

    // Float vertex positions carry about seven digits; the faceted volumes below are exact.
    private static final double EXACT = 1e-5;

    private static final double FOUR_THIRDS_PI = 4.0 * Math.PI / 3.0;

    private static final int CYLINDER_SEGMENTS = 16;

    private static final String MESH = "mesh";

    private static final String GEOMETRY = "geometry";

    /**
     * One primitive's statement, the volume range its shape must reach, and whether every face is a
     * quad.
     */
    private record Shape(String id, String port, double low, double high, boolean allQuads) {

        static Shape exact(String id, String port, double volume, boolean allQuads) {
            return new Shape(id, port, volume * (1.0 - EXACT), volume * (1.0 + EXACT), allQuads);
        }
    }

    @TestFactory
    Stream<DynamicTest> everyPrimitiveWindsOutward() {
        NodeGraphRuntime graph = NodeGraphRuntime.fromResource(FIXTURE);
        double icosahedronEdge = 1.0 / Math.sin(2.0 * Math.PI / 5.0);
        List<Shape> shapes = List.of(
                Shape.exact("cube_shape", MESH, 1.0, true),
                Shape.exact("icosphere_shape", MESH,
                        5.0 * (3.0 + Math.sqrt(5.0)) / 12.0 * Math.pow(icosahedronEdge, 3), false),
                Shape.exact("cylinder_shape", MESH, polygonArea(CYLINDER_SEGMENTS, 1.0), false),
                Shape.exact("cone_shape", MESH, polygonArea(CYLINDER_SEGMENTS, 1.0) / 3.0, false),
                // Pappus over 24 straight wedges of a 12-gon section of radius 0.35 at radius 1.
                Shape.exact("torus_shape", MESH,
                        polygonArea(12, 0.35) * 24.0 * Math.sin(2.0 * Math.PI / 24.0), true),
                // Flat caps close an octagonal prism of radius 0.5 and height 2; a seven-sided one
                // closes each cap with one triangle.
                Shape.exact("quad_cylinder_shape", GEOMETRY, polygonArea(8, 0.5) * 2.0, true),
                Shape.exact("odd_quad_cylinder_shape", GEOMETRY, polygonArea(7, 0.5) * 2.0, false),
                // The faceted spheres lie inside the sphere through their vertices: a 32 x 16 UV
                // sphere loses under 5% of it, the 16 x 16 cube-projected one (radius 0.5) under 10%.
                new Shape("uv_sphere_shape", MESH, 0.95 * FOUR_THIRDS_PI, FOUR_THIRDS_PI, false),
                new Shape("sphere_shape", MESH, 0.9 * FOUR_THIRDS_PI / 8.0, FOUR_THIRDS_PI / 8.0, true));
        return shapes.stream().map(shape -> dynamicTest(shape.id(), () -> {
            MeshTopology mesh = graph.executeGraphToMesh(shape.id(), shape.port());
            double volume = mesh.signedVolume();
            assertTrue(volume >= shape.low() && volume <= shape.high(), shape.id() + " encloses "
                    + volume + ", outside [" + shape.low() + ", " + shape.high() + "]; a negative volume "
                    + "means its faces wind inward");
            int boundaryEdges = 0;
            for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
                int halfEdge = mesh.edgeHalfEdge(mesh.edgeIdAt(activeEdge));
                boundaryEdges += mesh.halfEdgeFace(halfEdge) < 0
                        || mesh.halfEdgeFace(mesh.halfEdgeTwin(halfEdge)) < 0 ? 1 : 0;
            }
            assertEquals(0, boundaryEdges, shape.id() + " is not one closed surface");
            if (shape.allQuads()) {
                assertEquals(2 * mesh.faceCount(), mesh.triangleCount(), shape.id() + " has a face "
                        + "that is not a quad");
            }
        }));
    }

    /**
     * The area of a regular polygon inscribed in a circle.
     *
     * @param sides  its corner count
     * @param radius the circle's radius
     * @return its area
     */
    private static double polygonArea(int sides, double radius) {
        return sides / 2.0 * radius * radius * Math.sin(2.0 * Math.PI / sides);
    }
}
