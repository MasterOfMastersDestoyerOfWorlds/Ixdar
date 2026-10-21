package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ixdar.automation.endpoints.ring.Add;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceMetric;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.RingRegions;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.nodegraph.syntax.PythonParser;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.scenes.ring.RingScene;
import ixdar.tools.ring.RingTool;
import unit.GlStandIn;

/**
 * A ring through authored surface points, the route {@code rings add} takes: confirmed into the
 * ring tool, walling two regions, saved as a statement that reloads the same edges, and refused
 * with a reason when the points decide no ring.
 */
class AuthoredPointsRingTest {

    private static final int COORDINATES = 3;

    private static final float TUBE_RADIUS = 0.4f;

    private static final int TUBE_SIDES = 64;

    private static final int TUBE_RINGS = 65;

    private static final float TUBE_HALF_LENGTH = 2f;

    private static final int MIDDLE_ROW = 32;

    // Seven points round the middle of the tube as an agent gathers them from several views: out
    // of ring order, unevenly spaced and a little off the surface.
    private static final int[] SIDES_GIVEN = { 0, 30, 9, 52, 21, 41, 60 };

    private static final float OFF_SURFACE = 1.01f;

    private static final String CARRIER = "carrier = load_mesh(path=\"tube.off\")\n";

    private static final String SPLINE_RING = "spline_ring";

    private Platform suitePlatform;

    private GL suiteGl;

    /** Pair the suite's platform with a GL that does nothing, as the scene's tools need one. */
    @BeforeEach
    void installGlStandIn() {
        suitePlatform = Platforms.get();
        suiteGl = Platforms.gl();
        Platforms.init(suitePlatform, GlStandIn.create());
    }

    /** Put the suite's GL back. */
    @AfterEach
    void restoreSuiteGl() {
        Platforms.init(suitePlatform, suiteGl);
    }

    @Test
    void pointsRoundTheTubeConfirmARingThatWallsTwoRegionsAndSavesAndReloads(@TempDir Path folder)
            throws IOException {
        MeshTopology tube = tube();
        Path dsl = folder.resolve("tube.dsl");
        Files.write(dsl, CARRIER.getBytes(StandardCharsets.UTF_8));
        RingTool tool = toolOn(tube, dsl);
        float[] points = new float[COORDINATES * SIDES_GIVEN.length];
        for (int point = 0; point < SIDES_GIVEN.length; point++) {
            double angle = 2.0 * Math.PI * SIDES_GIVEN[point] / TUBE_SIDES;
            points[COORDINATES * point] = 0f;
            points[COORDINATES * point + 1] = (float) (OFF_SURFACE * TUBE_RADIUS * Math.cos(angle));
            points[COORDINATES * point + 2] = (float) (OFF_SURFACE * TUBE_RADIUS * Math.sin(angle));
        }

        int ring = tool.confirmPoints(points, SIDES_GIVEN.length, Add.AUTHORED_POINTS);

        assertTrue(ring >= 0, tool.lastError);
        assertTrue(tool.confirmedRingUnsaved.get(ring), "the ring was confirmed as saved");
        assertEquals(1, tool.unsavedRingCount(), "Ctrl+S would find nothing to save");
        int[] authored = tool.confirmedAuthoredVertexId.get(ring);
        assertEquals(SIDES_GIVEN.length, authored.length, "a point was dropped");
        for (int side : SIDES_GIVEN) {
            assertTrue(Arrays.stream(authored).anyMatch(held -> held == vertex(MIDDLE_ROW, side)),
                    "the ring does not run through the point on side " + side);
        }
        boolean[] drawn = tool.confirmedRings.get(ring).marks.markedByEdgeId;
        Map<String, boolean[]> live = tool.liveRingMarks();
        RingRegions regions = new RingRegions(tube, live.keySet().toArray(new String[0]),
                live.values().toArray(new boolean[0][])).build();
        assertEquals(2, regions.regionCount, "the ring does not cut the tube in two: "
                + regions.problems);

        assertTrue(tool.saveRings(), tool.lastError);
        String saved = new String(Files.readAllBytes(dsl), StandardCharsets.UTF_8);
        List<PythonParser.ParsedNode> statements = PythonParser.parse(saved);
        PythonParser.ParsedNode written = statements.get(statements.size() - 1);
        assertEquals(SPLINE_RING, written.type, saved);
        assertEquals(0, tool.unsavedRingCount(), "the save left the ring unsaved");

        RingTool reloaded = toolOn(tube, dsl);
        String id = tool.confirmedStatementIds.get(ring);
        reloaded.scene.ringMarksByLabel.put(id, drawn);
        reloaded.scene.graph = NodeGraphRuntime.fromSource(saved);
        reloaded.adoptGraphRings(tube);
        int adopted = reloaded.confirmedSourceLabel.indexOf(id);
        assertTrue(adopted >= 0, "the saved ring was not adopted: " + reloaded.lastError);
        assertArrayEquals(drawn, reloaded.confirmedRings.get(adopted).marks.markedByEdgeId,
                "the saved statement reloads other edges than the confirmed ring");
    }

    @Test
    void pointsThatDecideNoRingAreRefusedWithAReason() {
        RingTool tool = toolOn(tube(), null);
        float[] two = { 0f, TUBE_RADIUS, 0f, 0f, -TUBE_RADIUS, 0f };
        assertEquals(-1, tool.confirmPoints(two, 2, Add.AUTHORED_POINTS));
        assertTrue(tool.lastError.contains("needs 3"), tool.lastError);

        float[] oneSpot = { 0f, TUBE_RADIUS, 0f, 0f, TUBE_RADIUS, 1e-4f, 1e-4f, TUBE_RADIUS, 0f };
        assertEquals(-1, tool.confirmPoints(oneSpot, COORDINATES, Add.AUTHORED_POINTS));
        assertTrue(tool.lastError.contains("snap to 1"), tool.lastError);
        assertTrue(tool.confirmedRings.isEmpty(), "a refused call confirmed a ring");
        assertFalse(tool.lastError.isEmpty());
    }

    /**
     * The ring tool on a scene showing the tube, its geodesics ready and the tool active.
     *
     * @param tube the shown surface
     * @param dsl  working .dsl a save writes, or {@code null} for none
     * @return the scene's ring tool
     */
    private static RingTool toolOn(MeshTopology tube, Path dsl) {
        RingScene scene = new RingScene() {
            @Override
            public MeshTopology getMesh() {
                return tube;
            }

            @Override
            public MeshTopology halfEdgeSurface() {
                return tube;
            }

            @Override
            public String workingDslPath() {
                return dsl == null ? null : dsl.toString();
            }
        };
        scene.initPoints();
        scene.setTools();
        scene.switchTool(scene.defaultTool);
        RingTool tool = scene.ringTool;
        tool.geodesics = SurfaceGeodesics.over(SurfaceMetric.of(tube));
        tool.active = true;
        tool.graphRingsPending = false;
        return tool;
    }

    private static int vertex(int row, int side) {
        return TUBE_SIDES * row + side;
    }

    /**
     * A straight open tube along x.
     *
     * @return the tube, {@link #TUBE_SIDES} vertices to a row
     */
    private static MeshTopology tube() {
        float[] positions = new float[COORDINATES * TUBE_SIDES * TUBE_RINGS];
        for (int row = 0; row < TUBE_RINGS; row++) {
            float x = -TUBE_HALF_LENGTH + 2f * TUBE_HALF_LENGTH * row / (TUBE_RINGS - 1);
            for (int side = 0; side < TUBE_SIDES; side++) {
                double angle = 2.0 * Math.PI * side / TUBE_SIDES;
                int base = COORDINATES * vertex(row, side);
                positions[base] = x;
                positions[base + 1] = (float) (TUBE_RADIUS * Math.cos(angle));
                positions[base + 2] = (float) (TUBE_RADIUS * Math.sin(angle));
            }
        }
        int[] faces = new int[2 * COORDINATES * TUBE_SIDES * (TUBE_RINGS - 1)];
        int corner = 0;
        for (int row = 0; row + 1 < TUBE_RINGS; row++) {
            for (int side = 0; side < TUBE_SIDES; side++) {
                int next = (side + 1) % TUBE_SIDES;
                faces[corner++] = vertex(row, side);
                faces[corner++] = vertex(row + 1, side);
                faces[corner++] = vertex(row + 1, next);
                faces[corner++] = vertex(row, side);
                faces[corner++] = vertex(row + 1, next);
                faces[corner++] = vertex(row, next);
            }
        }
        return HalfEdgeMeshOps.buildFromIndexedMesh(positions, faces);
    }
}
