package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.NearestVertex;
import ixdar.geometry.mesh.data.paths.SurfaceMetric;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.nodes.data.SurfaceMetricNode;
import ixdar.geometry.mesh.nodes.primitives.GridMeshNode;
import ixdar.geometry.mesh.nodes.primitives.IcosphereMeshNode;
import ixdar.geometry.mesh.nodes.selection.RingDslWriter;
import ixdar.geometry.mesh.nodes.selection.SplineRingNode;

/**
 * Spline rings chained on one surface_metric mark what they mark each on a metric of its own, the
 * ring tool's save wires a graph that way, a ring without a metric is refused with the fix, and
 * the metric's vertex grid picks the expected vertex and refuses a tie.
 */
class SplineRingChainTest {

    private static final int SPHERE_SUBDIVISIONS = 4;

    private static final int GRID_TILES = 4;

    // Scaled points reach past the unit sphere's bounding box, so clamped grid cells are exercised.
    private static final float SAMPLE_REACH = 1.6f;

    private static final String EQUATOR_POINT = "0.97,0.02,0.23";

    private static final String EQUATOR_NORMAL = "0,1,0";

    private static final String MERIDIAN_POINT = "0.05,0.93,0.36";

    private static final String MERIDIAN_NORMAL = "1,0,0";

    private static final String FIRST_LABEL = "ring_00";

    private static final String SECOND_LABEL = "ring_01";

    private static final String UNWIRED_GRAPH = "sphere = icosphere(radius=1.0, subdivisions=4)\n"
            + FIRST_LABEL + " = spline_ring(geometry=sphere.mesh, points=\"" + EQUATOR_POINT
            + "\", normal=\"" + EQUATOR_NORMAL + "\", label=\"" + FIRST_LABEL + "\")\n"
            + SECOND_LABEL + " = spline_ring(geometry=" + FIRST_LABEL + ".geometry, points=\""
            + MERIDIAN_POINT + "\", normal=\"" + MERIDIAN_NORMAL + "\", label=\"" + SECOND_LABEL
            + "\")\n";

    private static final String WIRED_GRAPH = "sphere = icosphere(radius=1.0, subdivisions=4)\n"
            + "surface = surface_metric(geometry=sphere.mesh)\n"
            + FIRST_LABEL + " = spline_ring(geometry=surface.geometry, metric=surface.metric, "
            + "points=\"" + EQUATOR_POINT + "\", normal=\"" + EQUATOR_NORMAL + "\", label=\""
            + FIRST_LABEL + "\")\n"
            + SECOND_LABEL + " = spline_ring(geometry=" + FIRST_LABEL + ".geometry, "
            + "metric=surface.metric, points=\"" + MERIDIAN_POINT + "\", normal=\""
            + MERIDIAN_NORMAL + "\", label=\"" + SECOND_LABEL + "\")\n";

    @Test
    void ringsChainedOnOneMetricMarkWhatTheyMarkEachOnAMetricOfItsOwn() {
        SurfaceMetricNode measuring = new SurfaceMetricNode();
        MapNodeContext measured = new MapNodeContext(measuring);
        measured.setInput(SurfaceMetricNode.GEOMETRY.name, icosphere());
        measuring.evaluate(measured);
        GeometryBundle surface =
                measured.getOutput(SurfaceMetricNode.GEOMETRY_OUT.name, GeometryBundle.class);
        SurfaceMetric metric = measured.getOutput(SurfaceMetricNode.METRIC.name,
                SurfaceMetric.class);

        GeometryBundle first = ring(surface, metric, EQUATOR_POINT, EQUATOR_NORMAL, FIRST_LABEL);
        GeometryBundle chained = ring(first, metric, MERIDIAN_POINT, MERIDIAN_NORMAL,
                SECOND_LABEL);
        GeometryBundle unshared = ring(ring(surface, SurfaceMetric.of(surface.mesh()),
                EQUATOR_POINT, EQUATOR_NORMAL, FIRST_LABEL), SurfaceMetric.of(surface.mesh()),
                MERIDIAN_POINT, MERIDIAN_NORMAL, SECOND_LABEL);

        assertSame(metric.sourceMesh, chained.mesh(), "the chain left the measured mesh");
        for (String label : new String[] { FIRST_LABEL, SECOND_LABEL }) {
            boolean[] marks = EdgeMarks.bools(chained, label);
            int markedCount = 0;
            for (boolean marked : marks) {
                markedCount += marked ? 1 : 0;
            }
            assertTrue(markedCount > 0, label + " marked no edges");
            assertArrayEquals(EdgeMarks.bools(unshared, label), marks,
                    label + " marked other edges on the shared metric");
        }
        GeometryBundle elsewhere = icosphere();
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> ring(elsewhere, metric, EQUATOR_POINT, EQUATOR_NORMAL, FIRST_LABEL));
        assertTrue(refused.getMessage().contains("another mesh"), refused.getMessage());
    }

    @Test
    void theSaveWiresOneMetricIntoEveryRingWhichAnUnwiredGraphLacks() throws Exception {
        String wired = RingDslWriter.wireSurfaceMetric(UNWIRED_GRAPH);
        assertEquals(WIRED_GRAPH, wired);
        assertEquals(wired, RingDslWriter.wireSurfaceMetric(wired), "wiring twice changed it");

        GeometryBundle wiredRun = run(wired);
        for (String label : new String[] { FIRST_LABEL, SECOND_LABEL }) {
            assertTrue(EdgeMarks.bools(wiredRun, label) != null, label + " left no marks");
        }
        IllegalArgumentException unwired = assertThrows(IllegalArgumentException.class,
                () -> run(UNWIRED_GRAPH), "a spline_ring without a metric ran");
        assertTrue(unwired.getMessage().contains(FIRST_LABEL + " has no metric")
                && unwired.getMessage().contains("surface_metric(geometry=")
                && unwired.getMessage().contains("metric=surface.metric"), unwired.getMessage());
    }

    @Test
    void aRingAfterANodeThatRebuildsTheMeshGetsAMetricOfItsOwn() {
        String rebuilt = "sphere = icosphere(radius=1.0, subdivisions=4)\n"
                + FIRST_LABEL + " = spline_ring(geometry=sphere.mesh, points=\"" + EQUATOR_POINT
                + "\", label=\"" + FIRST_LABEL + "\")\n"
                + "rings = ring_candidates(geometry=" + FIRST_LABEL + ".geometry)\n"
                + SECOND_LABEL + " = spline_ring(geometry=rings.geometry, points=\""
                + MERIDIAN_POINT + "\", label=\"" + SECOND_LABEL + "\")\n";
        assertEquals("sphere = icosphere(radius=1.0, subdivisions=4)\n"
                + "surface = surface_metric(geometry=sphere.mesh)\n"
                + FIRST_LABEL + " = spline_ring(geometry=surface.geometry, metric=surface.metric, "
                + "points=\"" + EQUATOR_POINT + "\", label=\"" + FIRST_LABEL + "\")\n"
                + "rings = ring_candidates(geometry=" + FIRST_LABEL + ".geometry)\n"
                + "surface_2 = surface_metric(geometry=rings.geometry)\n"
                + SECOND_LABEL + " = spline_ring(geometry=surface_2.geometry, "
                + "metric=surface_2.metric, points=\"" + MERIDIAN_POINT + "\", label=\""
                + SECOND_LABEL + "\")\n", RingDslWriter.wireSurfaceMetric(rebuilt));
    }

    @Test
    void theGriddedPickFindsTheSphereVertexEachPointWasScaledFrom() {
        MeshTopology sphere = icosphere().mesh();
        NearestVertex grid = NearestVertex.over(sphere);
        Vector3f position = new Vector3f();
        // On a sphere centred at the origin, a vertex scaled radially stays nearest its own
        // vertex, so every scale names the expected pick.
        for (int index = 0; index < sphere.vertexCount(); index++) {
            int vertexId = sphere.vertexIdAt(index);
            sphere.vertexPosition(vertexId, position);
            for (float scale : new float[] { 0.5f, 1f, SAMPLE_REACH }) {
                assertEquals(vertexId, grid.find(scale * position.x, scale * position.y,
                        scale * position.z), "the grid missed vertex " + vertexId + " at scale "
                                + scale);
            }
        }
    }

    @Test
    void theGriddedPickOnAFlatGridFindsTheNearestCornerAndRefusesTies() {
        GridMeshNode gridNode = new GridMeshNode();
        MapNodeContext gridContext = new MapNodeContext(gridNode);
        gridContext.setInput("u_tiles", GRID_TILES);
        gridContext.setInput("v_tiles", GRID_TILES);
        gridContext.setInput("triangulate", true);
        gridNode.evaluate(gridContext);
        MeshTopology flat = gridContext.getOutput("mesh", GeometryBundle.class).mesh();
        NearestVertex grid = NearestVertex.over(flat);
        Vector3f picked = flat.vertexPosition(grid.find(1.2f, 0f, -0.7f), new Vector3f());
        assertEquals(new Vector3f(1f, 0f, -1f), picked,
                "the grid picked another vertex than the unit-spaced corner nearest the point");
        IllegalStateException tie = assertThrows(IllegalStateException.class,
                () -> grid.find(0.5f, 0f, 0f), "a point midway between two vertices has no pick");
        assertTrue(tie.getMessage().contains("move the point"), tie.getMessage());
    }

    private static GeometryBundle run(String source) throws Exception {
        NodeGraphRuntime runtime = NodeGraphRuntime.fromSource(source);
        Object result = runtime.executeGraphResult(runtime.statements, SECOND_LABEL,
                RingDslWriter.DEFAULT_UPSTREAM_PORT);
        assertTrue(result instanceof GeometryBundle, "the graph left no bundle");
        return (GeometryBundle) result;
    }

    private static GeometryBundle ring(GeometryBundle input, SurfaceMetric metric, String points,
            String normal, String label) {
        SplineRingNode node = new SplineRingNode();
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput(SplineRingNode.GEOMETRY.name, input);
        ctx.setInput(SplineRingNode.POINTS.name, points);
        ctx.setInput(SplineRingNode.NORMAL.name, normal);
        ctx.setInput(SplineRingNode.LABEL.name, label);
        ctx.setInput(SplineRingNode.METRIC.name, metric);
        node.evaluate(ctx);
        return ctx.getOutput(SplineRingNode.GEOMETRY_OUT.name, GeometryBundle.class);
    }

    private static GeometryBundle icosphere() {
        IcosphereMeshNode node = new IcosphereMeshNode();
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput(IcosphereMeshNode.RADIUS.name, 1f);
        ctx.setInput(IcosphereMeshNode.SUBDIVISIONS.name, SPHERE_SUBDIVISIONS);
        node.evaluate(ctx);
        return ctx.getOutput(IcosphereMeshNode.MESH.name, GeometryBundle.class);
    }
}
