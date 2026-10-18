package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.RingRegions;
import ixdar.geometry.mesh.data.rings.AuthoredSplineRing;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceMetric;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSplineTracer;
import ixdar.geometry.mesh.data.paths.spline.SurfaceWaypoints;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.nodegraph.api.MapNodeContext;
import ixdar.nodegraph.nodes.selection.SplineCutNode;
import ixdar.nodegraph.syntax.PythonParser;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.input.Keys;
import ixdar.scenes.ring.RingScene;
import ixdar.tools.ControlHint;
import ixdar.tools.ring.RingDslWriter;
import ixdar.tools.ring.RingTool;
import unit.GlStandIn;

/**
 * Bisectors in the ring tool on a straight tube: open geodesic splines whose ends snap onto rings
 * or other bisectors, steered by anchors, crossing walls, saved and reloaded with unsaved walls.
 */
class BisectorTest {

    private static final int COORDINATES = 3;

    private static final float TUBE_RADIUS = 0.4f;

    private static final int TUBE_SIDES = 64;

    private static final int TUBE_RINGS = 65;

    private static final float TUBE_HALF_LENGTH = 2f;

    // About two thirds of a row apart per pixel at the reach, so a click a row off a ring snaps.
    private static final double WORLD_PER_PIXEL = 0.002;

    private static final int FIRST_RING_ROW = 16;

    private static final int SECOND_RING_ROW = 48;

    private static final int MIDDLE_ROW = 32;

    private static final int TOP_SIDE = 16;

    private static final int PATCH_HALF_ROWS = 6;

    private static final int PATCH_HALF_SIDES = 8;

    private static final int FIRST_PATCH_ROW = 24;

    private static final int SECOND_PATCH_ROW = 40;

    // The smallest true region holds dozens of faces, a sliver a handful.
    private static final int FEWEST_FACES_OF_A_REGION = 40;

    private static final String CARRIER = "carrier = load_mesh(path=\"tube.off\")\n";

    private static final String FIRST_WALL = "ring_a";

    private static final String SECOND_WALL = "ring_b";

    private static final String CLICKED_ORDER = "ring_c";

    private Platform suitePlatform;

    private GL suiteGl;

    /**
     * Pair the suite's platform with a GL that does nothing, since the scene's graph tool builds
     * its shapes on it as the scene hosts its tools.
     */
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
    void xDeletesTheSelectedPointThenTheLastUntilNoneIsLeft() {
        MeshTopology tube = tube();
        RingTool tool = toolOn(tube, null);
        ring(tool, FIRST_RING_ROW);
        ring(tool, SECOND_RING_ROW);
        assertTrue(tool.startBisector(), tool.lastError);
        assertTrue(click(tool, FIRST_RING_ROW, TOP_SIDE), tool.lastError);
        assertTrue(click(tool, SECOND_RING_ROW, TOP_SIDE), tool.lastError);
        int steer = vertex(MIDDLE_ROW, TOP_SIDE + PATCH_HALF_SIDES);
        assertTrue(tool.addAuthoredAnchor(steer), tool.lastError);
        assertEquals(COORDINATES, tool.draftAuthoredVertexId.length);
        assertEquals(steer, tool.selectedAnchorVertexId, "the new anchor is not selected");

        press(tool, Keys.X);
        assertEquals(2, tool.draftAuthoredVertexId.length, "X kept the selected anchor");
        assertTrue(tool.bisecting, "X left the bisector instead of deleting a point");
        press(tool, Keys.X);
        assertNull(tool.draft, "X did not delete the second end");
        assertTrue(tool.bisectorStartVertexId >= 0, "the first end went with the second");
        press(tool, Keys.X);
        assertEquals(-1, tool.bisectorStartVertexId, "X did not delete the first end");
        assertTrue(tool.bisecting);
        assertTrue(tool.escapePressed());
        assertFalse(tool.bisecting, "Esc did not leave the bisector");
    }

    @Test
    void anUnsavedRingIsAWallAndTheBisectorSavesAndReloadsWithIt(@TempDir Path folder)
            throws IOException {
        MeshTopology tube = tube();
        Path dsl = folder.resolve("tube.dsl");
        Files.write(dsl, CARRIER.getBytes(StandardCharsets.UTF_8));
        RingTool tool = toolOn(tube, dsl);
        int first = ring(tool, FIRST_RING_ROW);
        int second = ring(tool, SECOND_RING_ROW);
        assertTrue(tool.confirmedRingUnsaved.get(first) && tool.confirmedRingUnsaved.get(second));
        int bisector = bisect(tool, vertex(FIRST_RING_ROW, TOP_SIDE),
                vertex(SECOND_RING_ROW, TOP_SIDE), vertex(MIDDLE_ROW, TOP_SIDE + 2));
        boolean[] drawn = tool.confirmedRings.get(bisector).markedByEdgeId;
        int[] serials = { tool.confirmedRingSerial.get(first), tool.confirmedRingSerial.get(second),
            tool.confirmedRingSerial.get(bisector) };

        assertTrue(tool.saveRings(), tool.lastError);
        String saved = new String(Files.readAllBytes(dsl), StandardCharsets.UTF_8);
        List<PythonParser.ParsedNode> statements = PythonParser.parse(saved);
        String firstId = tool.confirmedStatementIds.get(first);
        String secondId = tool.confirmedStatementIds.get(second);
        String bisectorId = tool.confirmedStatementIds.get(bisector);
        PythonParser.ParsedNode written = statements.get(statements.size() - 1);
        assertEquals(SplineCutNode.NODE_ID, written.type, saved);
        assertEquals(bisectorId, written.id, saved);
        assertEquals(firstId + SplineCutNode.END_SEPARATOR + " " + secondId,
                written.arguments.get(SplineCutNode.ENDS.name), saved);
        assertTrue(written.arguments.get(SplineCutNode.METRIC.name)
                instanceof PythonParser.NodeReference, "the save left the bisector unwired:\n"
                        + saved);

        // The statement alone, on a bundle carrying both walls, gives back the same edges.
        GeometryBundle bundle = GeometryBundle.ofMesh(tube);
        bundle = EdgeMarks.with(bundle, firstId, tool.confirmedRings.get(first).markedByEdgeId);
        bundle = EdgeMarks.with(bundle, secondId, tool.confirmedRings.get(second).markedByEdgeId);
        SplineCutNode node = new SplineCutNode();
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput(SplineCutNode.GEOMETRY.name, bundle);
        ctx.setInput(SplineCutNode.POINTS.name, written.arguments.get(SplineCutNode.POINTS.name));
        ctx.setInput(SplineCutNode.ENDS.name, written.arguments.get(SplineCutNode.ENDS.name));
        ctx.setInput(SplineCutNode.LABEL.name, bisectorId);
        ctx.setInput(SplineCutNode.METRIC.name, tool.geodesics.metric);
        node.evaluate(ctx);
        assertArrayEquals(drawn, EdgeMarks.bools(
                ctx.getOutput(SplineCutNode.GEOMETRY.name, GeometryBundle.class), bisectorId),
                "the spline_cut statement reloads other edges than the bisector drawn");

        // The re-run graph's rings are adopted again, the bisector naming its walls by serial.
        tool.scene.ringMarksByLabel.put(firstId, tool.confirmedRings.get(first).markedByEdgeId);
        tool.scene.ringMarksByLabel.put(secondId, tool.confirmedRings.get(second).markedByEdgeId);
        tool.scene.ringMarksByLabel.put(bisectorId, drawn);
        tool.scene.graph = NodeGraphRuntime.fromSource(saved);
        tool.graphRingsChanged();
        tool.adoptGraphRings(tube);
        int reloaded = tool.confirmedSourceLabel.indexOf(bisectorId);
        assertTrue(reloaded >= 0, "the bisector was not adopted: " + tool.lastError);
        assertArrayEquals(drawn, tool.confirmedRings.get(reloaded).markedByEdgeId);
        assertArrayEquals(new int[] { serials[0], serials[1] },
                tool.confirmedBisectorEnds.get(reloaded), "the reloaded bisector lost its walls");
        assertEquals(serials[2], tool.confirmedRingSerial.get(reloaded));
    }

    @Test
    void clickOrderDoesNotMatterNorWhichSideOfARingTheFirstClickLands() {
        MeshTopology tube = tube();
        int nearFirst = vertex(MIDDLE_ROW - PATCH_HALF_ROWS, TOP_SIDE + 2);
        int nearSecond = vertex(MIDDLE_ROW + PATCH_HALF_ROWS, TOP_SIDE - 2);
        boolean[] reference = null;
        int[][] clicks = {
            { vertex(FIRST_RING_ROW - 1, TOP_SIDE), vertex(SECOND_RING_ROW, TOP_SIDE), nearFirst,
                nearSecond },
            { vertex(FIRST_RING_ROW + 1, TOP_SIDE), vertex(SECOND_RING_ROW, TOP_SIDE), nearSecond,
                nearFirst },
            { vertex(SECOND_RING_ROW + 1, TOP_SIDE), vertex(FIRST_RING_ROW + 1, TOP_SIDE),
                nearFirst, nearSecond },
            { vertex(SECOND_RING_ROW - 1, TOP_SIDE), vertex(FIRST_RING_ROW - 1, TOP_SIDE),
                nearSecond, nearFirst } };
        for (int[] order : clicks) {
            RingTool tool = toolOn(tube, null);
            ring(tool, FIRST_RING_ROW);
            ring(tool, SECOND_RING_ROW);
            int bisector = bisect(tool, order[0], order[1], order[2], order[3]);
            boolean[] marks = tool.confirmedRings.get(bisector).markedByEdgeId;
            if (reference == null) {
                reference = marks;
            }
            assertArrayEquals(reference, marks, "the click order changed the bisector");
        }
    }

    @Test
    void oneBisectorCrossesTwoPatchesAndSplitsBoth() {
        MeshTopology tube = tube();
        RingTool tool = toolOn(tube, null);
        patch(tool, FIRST_PATCH_ROW);
        patch(tool, SECOND_PATCH_ROW);
        assertEquals(COORDINATES, regions(tool).regionCount, "two patches on the tube");
        // From the far edge of one patch, across both, to the far edge of the other: four walls
        // crossed or touched, two clicks.
        int bisector = bisect(tool, vertex(FIRST_PATCH_ROW - PATCH_HALF_ROWS, TOP_SIDE),
                vertex(SECOND_PATCH_ROW + PATCH_HALF_ROWS, TOP_SIDE));
        RingRegions regions = regions(tool);
        assertTrue(regions.ringIsWall[bisector], regions.problems.toString());
        // Each patch falls in two; the tube outside only opens between the patches.
        assertEquals(COORDINATES + 2, regions.regionCount,
                String.join(RingDslWriter.LINE_BREAK, regions.reportLines()));
        assertNoSliverAndNoBisectorProblem(regions, tool.liveLabel(bisector));
    }

    @Test
    void bisectorsJoinedToEachOtherAndToARingCloseRegions() {
        MeshTopology tube = tube();
        RingTool tool = toolOn(tube, null);
        int ring = ring(tool, FIRST_RING_ROW);
        int apex = vertex(MIDDLE_ROW, TOP_SIDE);
        // A bite off the ring, steered out to the apex, closes a pocket with the ring.
        int bite = bisect(tool, vertex(FIRST_RING_ROW, TOP_SIDE - PATCH_HALF_SIDES),
                vertex(FIRST_RING_ROW, TOP_SIDE + PATCH_HALF_SIDES), apex);
        assertEquals(COORDINATES, regions(tool).regionCount, "the bite closes no pocket");
        // A second bisector from the bite's apex back to the ring halves the pocket.
        int join = bisect(tool, apex, vertex(FIRST_RING_ROW, TOP_SIDE));
        int[] ends = tool.confirmedBisectorEnds.get(join).clone();
        Arrays.sort(ends);
        assertArrayEquals(new int[] { tool.confirmedRingSerial.get(ring),
            tool.confirmedRingSerial.get(bite) }, ends,
                "the join does not end on the bite and the ring");
        RingRegions regions = regions(tool);
        assertEquals(COORDINATES + 1, regions.regionCount,
                String.join(RingDslWriter.LINE_BREAK, regions.reportLines()));
        assertNoSliverAndNoBisectorProblem(regions, tool.liveLabel(join));
    }

    /**
     * Anchors kept in the order they were clicked double back and cross; the same anchors placed
     * where they add least do not. A crossing bisector cannot be confirmed.
     */
    @Test
    void aBisectorCannotCrossItself() {
        MeshTopology tube = tube();
        int start = vertex(FIRST_RING_ROW, TOP_SIDE - 4);
        int far = vertex(MIDDLE_ROW + PATCH_HALF_SIDES, TOP_SIDE + 4);
        int near = vertex(MIDDLE_ROW - 4, TOP_SIDE + PATCH_HALF_ROWS);
        int end = vertex(SECOND_RING_ROW, TOP_SIDE - 4);

        RingTool placed = toolOn(tube, null);
        ring(placed, FIRST_RING_ROW);
        ring(placed, SECOND_RING_ROW);
        int bisector = bisect(placed, start, end, far, near);
        assertArrayEquals(new int[] { start, near, far, end },
                placed.confirmedAuthoredVertexId.get(bisector),
                "the anchors were not placed where they add least");

        // The same anchors in click order, as a statement holds them, cross.
        RingTool clicked = toolOn(tube, null);
        clicked.confirmedSourceLabel.set(ring(clicked, FIRST_RING_ROW), FIRST_WALL);
        clicked.confirmedSourceLabel.set(ring(clicked, SECOND_RING_ROW), SECOND_WALL);
        clicked.scene.graph = NodeGraphRuntime.fromSource(CARRIER + CLICKED_ORDER + " = "
                + SplineCutNode.NODE_ID + "(geometry=carrier.geometry, points=\""
                + SurfaceWaypoints.format(clicked.positionsOf(new int[] { start, far, near, end }),
                        COORDINATES + 1)
                + "\", ends=\"" + FIRST_WALL + SplineCutNode.END_SEPARATOR + " " + SECOND_WALL
                + "\", label=\"" + CLICKED_ORDER + "\")\n");
        clicked.scene.ringMarksByLabel.put(CLICKED_ORDER, new boolean[0]);
        clicked.adoptGraphRings(tube);
        int crossing = clicked.confirmedSourceLabel.indexOf(CLICKED_ORDER);
        assertTrue(crossing >= 0, "the clicked-order bisector was not adopted: "
                + clicked.lastError);
        assertTrue(clicked.reopenRing(crossing), clicked.lastError);
        assertFalse(clicked.draftSimple, "anchors in click order should double back and cross");
        assertFalse(clicked.confirmDraft(), "a crossing bisector was confirmed");
        assertTrue(clicked.lastError.contains("crosses itself"), clicked.lastError);
    }

    /** No region is a sliver, and no problem the regions report names the bisector. */
    private static void assertNoSliverAndNoBisectorProblem(RingRegions regions, String bisector) {
        for (int region = 0; region < regions.regionCount; region++) {
            assertTrue(regions.regionFaceCount[region] >= FEWEST_FACES_OF_A_REGION,
                    "region " + region + " is a sliver of " + regions.regionFaceCount[region]
                            + " faces");
        }
        for (String problem : regions.problems) {
            assertFalse(problem.contains(bisector), "a bisector reads as a problem: " + problem);
        }
    }

    /** The regions the tool's live rings and bisectors wall. */
    private static RingRegions regions(RingTool tool) {
        Map<String, boolean[]> live = tool.liveRingMarks();
        return new RingRegions(tool.geodesics.mesh, live.keySet().toArray(new String[0]),
                live.values().toArray(new boolean[0][])).build();
    }

    /**
     * Draw and confirm a bisector as clicks do: K, the two ends, then each steering anchor.
     *
     * @return the confirmed bisector's index
     */
    private static int bisect(RingTool tool, int firstEnd, int secondEnd, int... steering) {
        assertTrue(tool.bisecting || tool.startBisector(), tool.lastError);
        assertTrue(click(tool, firstEnd), tool.lastError);
        assertTrue(click(tool, secondEnd), tool.lastError);
        for (int anchor : steering) {
            assertTrue(tool.addAuthoredAnchor(anchor), tool.lastError);
        }
        assertTrue(tool.draftSimple, tool.lastRow);
        assertTrue(tool.confirmDraft(), tool.lastError);
        return tool.confirmedRings.size() - 1;
    }

    private static boolean click(RingTool tool, int row, int side) {
        return click(tool, vertex(row, side));
    }

    private static boolean click(RingTool tool, int vertexId) {
        Vector3f position = new Vector3f();
        tool.geodesics.mesh.vertexPosition(vertexId, position);
        return tool.placeBisectorEnd(vertexId, new float[] { position.x, position.y, position.z });
    }

    /** Run the action a key is bound to in the ring tool, as pressing it does. */
    private static void press(RingTool tool, int key) {
        List<ControlHint> controls = new ArrayList<>();
        tool.addControls(controls);
        for (ControlHint control : controls) {
            if (control.keyCode == key && !control.controlHeld) {
                control.action.perform();
                return;
            }
        }
        throw new AssertionError("no control for key " + key);
    }

    /** An unsaved ring round the tube through one row, confirmed as the tool confirms a loop. */
    private static int ring(RingTool tool, int row) {
        int[] loop = new int[TUBE_SIDES];
        for (int side = 0; side < TUBE_SIDES; side++) {
            loop[side] = vertex(row, side);
        }
        int ring = tool.confirmLoop(loop, "ring at row " + row);
        assertTrue(ring >= 0, tool.lastError);
        return ring;
    }

    /** An unsaved ring round a disc-like patch on top of the tube, through four corners. */
    private static int patch(RingTool tool, int row) {
        AuthoredSplineRing loop = new AuthoredSplineRing(tool.geodesics);
        int[] corners = { vertex(row - PATCH_HALF_ROWS, TOP_SIDE),
            vertex(row, TOP_SIDE - PATCH_HALF_SIDES), vertex(row + PATCH_HALF_ROWS, TOP_SIDE),
            vertex(row, TOP_SIDE + PATCH_HALF_SIDES) };
        assertTrue(loop.trace(corners, corners.length, null,
                SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH), loop.failure);
        int ring = tool.confirmLoop(RingTool.orderedLoop(tool.geodesics.mesh,
                SurfaceSpline.of(loop.tracer).markedByEdgeId), "patch at row " + row);
        assertTrue(ring >= 0, tool.lastError);
        return ring;
    }

    /**
     * The ring tool on a scene showing the tube, its geodesics ready and the tool active.
     *
     * @param dsl working .dsl a save writes, or {@code null} for none
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
            public double worldPerPixel() {
                return WORLD_PER_PIXEL;
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

    /** A straight open tube along x. */
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
        return HalfEdgeMeshEngine.buildFromIndexedMesh(positions, faces);
    }
}
