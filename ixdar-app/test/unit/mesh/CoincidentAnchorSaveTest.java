package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.AuthoredSplineRing;
import ixdar.geometry.mesh.data.paths.NearestVertex;
import ixdar.geometry.mesh.data.paths.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.SurfaceMetric;
import ixdar.geometry.mesh.data.paths.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.SurfaceSplineTracer;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.nodes.selection.RingDslWriter;

/**
 * Spheres stacked pole to pole, which repair_mesh welds and splits into coincident copies: a ring
 * held on those copies reloads identically, and shared-position points are all refused at once.
 */
class CoincidentAnchorSaveTest {

    private static final String SPHERES = "middle = icosphere(radius=1.0, subdivisions=2)\n"
            + "upper_base = icosphere(radius=1.0, subdivisions=2)\n"
            + "upper = transform_geometry(geometry=upper_base.mesh, translation=<0.0, 2.0, 0.0>)\n"
            + "lower_base = icosphere(radius=1.0, subdivisions=2)\n"
            + "lower = transform_geometry(geometry=lower_base.mesh, translation=<0.0, -2.0, 0.0>)\n"
            + "pair = join_geometry(a=middle.mesh, b=upper.geometry)\n"
            + "stack = join_geometry(a=pair.geometry, b=lower.geometry)\n"
            + "repaired = repair_mesh(geometry=stack.geometry, weld_epsilon=0.0001, "
            + "max_hole_edges=0, min_shell_faces=1)\n"
            + "surface = surface_metric(geometry=repaired.geometry)\n";

    private static final String RING = "ring_00";

    private static final String OTHER_RING = "ring_01";

    private static final float[] MERIDIAN_NORMAL = { 0f, 0f, 1f };

    // Two poles the neighbours share, then two equator points clear of every tie.
    private static final float[] POLES = { 0f, 1f, 0f, 0f, -1f, 0f };

    private static final float[] EQUATOR = { 0.98f, 0.01f, 0.03f, -0.98f, 0.01f, 0.03f };

    private static final int ANCHORS = 4;

    private static final float WELD = 1e-4f;

    @Test
    void aRingHeldOnSplitPolesSavesToPointsThatReloadTheSameRing() throws Exception {
        NodeGraphRuntime measuring = NodeGraphRuntime.fromSource(SPHERES);
        SurfaceMetric metric = (SurfaceMetric) measuring.executeGraphResult(measuring.statements,
                "surface", "metric");
        MeshTopology mesh = metric.sourceMesh;
        NearestVertex grid = metric.nearestVertex;
        int[] held = {
            middleCopy(mesh, POLES[0], POLES[1], POLES[2]),
            grid.find(EQUATOR[0], EQUATOR[1], EQUATOR[2]),
            middleCopy(mesh, POLES[3], POLES[4], POLES[5]),
            grid.find(EQUATOR[3], EQUATOR[4], EQUATOR[5]),
        };
        IllegalStateException shared = assertThrows(IllegalStateException.class,
                () -> grid.find(POLES[0], POLES[1], POLES[2]),
                "a point on the shared pole picked one copy");
        assertTrue(shared.getMessage().contains("2 coincident vertices"), shared.getMessage());

        AuthoredSplineRing ring = new AuthoredSplineRing(SurfaceGeodesics.over(metric));
        assertTrue(ring.trace(held, ANCHORS, MERIDIAN_NORMAL,
                SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH), ring.failure);
        String heldFingerprint =
                EdgeMarks.fingerprint(mesh, SurfaceSpline.of(ring.tracer).markedByEdgeId);

        float[] written = SurfaceWaypoints.resolvingPoints(grid, held, ANCHORS);
        String saved = RingDslWriter.wireRingInputs(SPHERES + RingDslWriter.splineStatement(
                RING, "surface.geometry", written, ANCHORS, MERIDIAN_NORMAL) + "\n");
        NodeGraphRuntime reloading = NodeGraphRuntime.fromSource(saved);
        GeometryBundle reloaded = (GeometryBundle) reloading.executeGraphResult(
                reloading.statements, RING, RingDslWriter.DEFAULT_UPSTREAM_PORT);

        String points = String.valueOf(reloading.statements.get(reloading.statements.size() - 1)
                .arguments.get("points"));
        assertArrayEquals(held, SurfaceWaypoints.snap(NearestVertex.over(reloaded.mesh()),
                SurfaceWaypoints.parse(points), ANCHORS), "the saved points snapped elsewhere");
        assertEquals(heldFingerprint,
                EdgeMarks.fingerprint(reloaded.mesh(), EdgeMarks.bools(reloaded, RING)),
                "the reloaded ring marks other edges than the held one");
    }

    @Test
    void pointsOnTheSharedPolesAreRefusedEveryPointOfEveryRingAtOnce() {
        String onPoles = SurfaceWaypoints.format(new float[] {
            POLES[0], POLES[1], POLES[2], EQUATOR[0], EQUATOR[1], EQUATOR[2],
            POLES[3], POLES[4], POLES[5], EQUATOR[3], EQUATOR[4], EQUATOR[5],
        }, ANCHORS);
        String normal = SurfaceWaypoints.format(MERIDIAN_NORMAL, 1);
        String source = SPHERES
                + RING + " = spline_ring(geometry=surface.geometry, metric=surface.metric, points=\""
                + onPoles + "\", normal=\"" + normal + "\", label=\"" + RING + "\")\n"
                + OTHER_RING + " = spline_ring(geometry=" + RING + ".geometry, "
                + "metric=surface.metric, points=\"" + onPoles + "\", normal=\"" + normal
                + "\", label=\"" + OTHER_RING + "\")\n";
        NodeGraphRuntime runtime = NodeGraphRuntime.fromSource(source);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> runtime.executeGraphResult(runtime.statements, OTHER_RING,
                        RingDslWriter.DEFAULT_UPSTREAM_PORT));

        String report = refused.getMessage();
        assertTrue(report.startsWith("2 failure(s)"), report);
        for (String label : new String[] { RING, OTHER_RING }) {
            String section = report.substring(report.indexOf("spline_ring " + label));
            assertTrue(section.contains("point 1 of 4") && section.contains("point 3 of 4"),
                    label + " does not name both of its pole points: " + report);
        }
        assertEquals(-1, report.indexOf("point 2 of 4"), "an equator point was refused");
    }

    /**
     * The copy of a shared position whose faces lie on the middle sphere, the one a ring traced
     * around that sphere holds.
     *
     * @param mesh the repaired stack
     * @param x    shared position x
     * @param y    shared position y
     * @param z    shared position z
     * @return the vertex id
     */
    private static int middleCopy(MeshTopology mesh, float x, float y, float z) {
        Vector3f position = new Vector3f();
        Vector3f corner = new Vector3f();
        int found = -1;
        int copies = 0;
        for (int index = 0; index < mesh.vertexCount(); index++) {
            int vertexId = mesh.vertexIdAt(index);
            if (mesh.vertexPosition(vertexId, position).distance(x, y, z) > WELD) {
                continue;
            }
            copies++;
            int faceId = mesh.vertexFaceAt(vertexId, 0);
            float centroidY = 0f;
            for (int at = 0; at < mesh.faceVertexCount(faceId); at++) {
                centroidY += mesh.vertexPosition(mesh.faceVertexAt(faceId, at), corner).y;
            }
            if (Math.abs(centroidY / mesh.faceVertexCount(faceId)) < Math.abs(y)) {
                found = vertexId;
            }
        }
        assertEquals(2, copies, "repair_mesh left the pole at " + y + " unsplit");
        return found;
    }
}
