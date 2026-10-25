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
import ixdar.geometry.mesh.data.bundle.RingBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.RingRegions;
import ixdar.geometry.mesh.data.rings.AuthoredSplineRing;
import ixdar.geometry.mesh.data.rings.RingSegmentMode;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceMetric;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSplineTracer;
import ixdar.geometry.mesh.data.paths.spline.SurfaceWaypoints;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.nodegraph.api.MapNodeContext;
import ixdar.nodegraph.nodes.selection.SplineCutNode;
import ixdar.nodegraph.syntax.PythonParser;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.input.Keys;
import ixdar.scenes.Scene;
import ixdar.scenes.ring.RingScene;
import ixdar.tools.ControlHint;
import ixdar.tools.ring.BisectorTool;
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

    // A ring between the two, which a bisector from the first to the second crosses.
    private static final int CROSSED_RING_ROW = 40;

    // A ring past the second, which a dragged end moves onto.
    private static final int FAR_RING_ROW = 56;

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

    private static final String WORKING_DSL = "tube.dsl";

    // The groove dips a tenth of the radius, about a side wide, and bows this many sides off the
    // straight line between its ends at the middle row.
    private static final double GROOVE_DEPTH = 0.04;

    private static final double GROOVE_HALF_WIDTH_SIDES = 1.0;

    private static final int GROOVE_BEND = 8;

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
        press(tool, Keys.K);
        assertEquals(tool.bisector, tool.activeSubTool, tool.lastError);
        assertTrue(click(tool, FIRST_RING_ROW, TOP_SIDE), tool.lastError);
        assertTrue(click(tool, SECOND_RING_ROW, TOP_SIDE), tool.lastError);
        int steer = vertex(MIDDLE_ROW, TOP_SIDE + PATCH_HALF_SIDES);
        assertTrue(tool.addAuthoredAnchor(steer), tool.lastError);
        assertEquals(COORDINATES, tool.draftAuthoredVertexId.length);
        assertEquals(steer, tool.selectedAnchorVertexId, "the new anchor is not selected");

        press(tool, Keys.X);
        assertEquals(2, tool.draftAuthoredVertexId.length, "X kept the selected anchor");
        assertEquals(tool.bisector, tool.activeSubTool,
                "X left the bisector instead of deleting a point");
        press(tool, Keys.X);
        assertNull(tool.draft, "X did not delete the second end");
        assertTrue(tool.bisector.startVertexId >= 0, "the first end went with the second");
        press(tool, Keys.X);
        assertEquals(-1, tool.bisector.startVertexId, "X did not delete the first end");
        assertEquals(tool.bisector, tool.activeSubTool);
        assertTrue(tool.escapePressed());
        assertEquals(tool.closedRing, tool.activeSubTool, "Esc did not leave the bisector");
    }

    /**
     * The bisector is a sub-tool: K hands it the ring tool's input, the controls list the shared
     * rows then the bisector's own, and Esc gives the input back to the rings.
     */
    @Test
    void kHandsTheInputToTheBisectorAndEscGivesItBackToTheRings() {
        RingTool tool = toolOn(tube(), null);
        tool.scene.switchTool(tool);
        assertEquals(tool.closedRing, tool.activeSubTool, "the rings are not the default sub-tool");
        List<String> ringRows = rows(tool);
        assertTrue(ringRows.contains("K  bisect") && ringRows.contains("X  discard draft"),
                ringRows.toString());
        press(tool, Keys.K);
        assertEquals(tool.bisector, tool.activeSubTool);
        assertEquals(tool, tool.scene.activeTool, "the scene sees another tool than the ring tool");
        List<String> bisectorRows = rows(tool);
        assertEquals(ringRows.subList(0, ringRows.indexOf("click  draft ring / add or pick anchor")),
                bisectorRows.subList(0, bisectorRows.indexOf("click  " + BisectorTool.CLICK_HINT)),
                "the shared rows changed with the sub-tool");
        assertTrue(bisectorRows.contains("X  " + BisectorTool.DELETE_POINT_HINT)
                && bisectorRows.contains("K  " + BisectorTool.LEAVE), bisectorRows.toString());
        assertTrue(tool.escapePressed());
        assertEquals(tool.closedRing, tool.activeSubTool);
        assertEquals(ringRows, rows(tool), "Esc did not bring the rings' rows back");
    }

    /**
     * T switches the bisector to crease mode: it follows a bent groove the geodesic between its
     * ends cuts across, and its spline_cut statement reloads the same edges in crease mode.
     */
    @Test
    void aCreaseBisectorFollowsTheGrooveAndSavesAndReloadsTheSameEdges(@TempDir Path folder)
            throws IOException {
        MeshTopology grooved = tube(GROOVE_DEPTH);
        Path dsl = folder.resolve(WORKING_DSL);
        Files.write(dsl, CARRIER.getBytes(StandardCharsets.UTF_8));
        RingTool tool = toolOn(grooved, dsl);
        int first = ring(tool, FIRST_RING_ROW);
        int second = ring(tool, SECOND_RING_ROW);
        press(tool, Keys.K);
        press(tool, Keys.T);
        assertEquals(RingSegmentMode.CREASE, tool.newDraftMode(), tool.lastError);
        int crease = bisect(tool, vertex(FIRST_RING_ROW, TOP_SIDE), vertex(SECOND_RING_ROW, TOP_SIDE));
        assertEquals(RingSegmentMode.CREASE, tool.confirmedRings.get(crease).mode);
        boolean[] drawn = tool.confirmedRings.get(crease).spline.marks.markedByEdgeId;
        assertTrue(touches(grooved, drawn, vertex(MIDDLE_ROW, TOP_SIDE + GROOVE_BEND)),
                "the crease bisector does not follow the groove");
        SurfaceSpline geodesic = SurfaceSpline.bisector(tool.geodesics, null,
                new int[] { vertex(FIRST_RING_ROW, TOP_SIDE), vertex(SECOND_RING_ROW, TOP_SIDE) },
                tool.confirmedRings.get(first).spline.marks.markedByEdgeId,
                tool.confirmedRings.get(second).spline.marks.markedByEdgeId);
        assertFalse(touches(grooved, geodesic.marks.markedByEdgeId,
                vertex(MIDDLE_ROW, TOP_SIDE + GROOVE_BEND)), "the geodesic follows the groove too, "
                        + "so the groove tests nothing");

        assertTrue(tool.saveRings(), tool.lastError);
        String saved = new String(Files.readAllBytes(dsl), StandardCharsets.UTF_8);
        PythonParser.ParsedNode written = PythonParser.parse(saved).get(PythonParser.parse(saved)
                .size() - 1);
        assertEquals(SplineCutNode.NODE_ID, written.type, saved);
        assertEquals(RingSegmentMode.CREASE.dslName, written.arguments.get(SplineCutNode.MODE.name),
                saved);
        assertTrue(written.arguments.get(SplineCutNode.CREASES.name)
                instanceof PythonParser.NodeReference, "the save left the crease bisector without "
                        + "creases:\n" + saved);
        String bisectorId = tool.confirmedRings.get(crease).statementId;
        GeometryBundle bundle = GeometryBundle.ofMesh(grooved);
        bundle = EdgeMarks.with(bundle, tool.confirmedRings.get(first).statementId,
                tool.confirmedRings.get(first).spline.marks.markedByEdgeId);
        bundle = EdgeMarks.with(bundle, tool.confirmedRings.get(second).statementId,
                tool.confirmedRings.get(second).spline.marks.markedByEdgeId);
        SplineCutNode node = new SplineCutNode();
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput(SplineCutNode.GEOMETRY.name, bundle);
        ctx.setInput(SplineCutNode.POINTS.name, written.arguments.get(SplineCutNode.POINTS.name));
        ctx.setInput(SplineCutNode.ENDS.name, written.arguments.get(SplineCutNode.ENDS.name));
        ctx.setInput(SplineCutNode.LABEL.name, bisectorId);
        ctx.setInput(SplineCutNode.METRIC.name, tool.geodesics.metric);
        ctx.setInput(SplineCutNode.MODE.name, written.arguments.get(SplineCutNode.MODE.name));
        ctx.setInput(SplineCutNode.CREASES.name, tool.creases);
        node.evaluate(ctx);
        assertArrayEquals(drawn, EdgeMarks.bools(
                ctx.getOutput(SplineCutNode.GEOMETRY.name, GeometryBundle.class), bisectorId),
                "the crease spline_cut statement reloads other edges than the bisector drawn");

        tool.scene.ringMarksByLabel.put(tool.confirmedRings.get(first).statementId,
                tool.confirmedRings.get(first).spline.marks.markedByEdgeId);
        tool.scene.ringMarksByLabel.put(tool.confirmedRings.get(second).statementId,
                tool.confirmedRings.get(second).spline.marks.markedByEdgeId);
        tool.scene.ringMarksByLabel.put(bisectorId, drawn);
        tool.scene.graph = NodeGraphRuntime.fromSource(saved);
        tool.graphRingsChanged();
        tool.adoptGraphRings(grooved);
        int reloaded = tool.ringOfSourceLabel(bisectorId);
        assertTrue(reloaded >= 0, "the crease bisector was not adopted: " + tool.lastError);
        assertEquals(RingSegmentMode.CREASE, tool.confirmedRings.get(reloaded).mode);
        assertArrayEquals(drawn, tool.confirmedRings.get(reloaded).spline.marks.markedByEdgeId,
                "the reloaded crease bisector runs along other edges");
    }

    @Test
    void anUnsavedRingIsAWallAndTheBisectorSavesAndReloadsWithIt(@TempDir Path folder)
            throws IOException {
        MeshTopology tube = tube();
        Path dsl = folder.resolve(WORKING_DSL);
        Files.write(dsl, CARRIER.getBytes(StandardCharsets.UTF_8));
        RingTool tool = toolOn(tube, dsl);
        int first = ring(tool, FIRST_RING_ROW);
        int second = ring(tool, SECOND_RING_ROW);
        assertTrue(tool.confirmedRings.get(first).unsaved && tool.confirmedRings.get(second).unsaved);
        int bisector = bisect(tool, vertex(FIRST_RING_ROW, TOP_SIDE),
                vertex(SECOND_RING_ROW, TOP_SIDE), vertex(MIDDLE_ROW, TOP_SIDE + 2));
        boolean[] drawn = tool.confirmedRings.get(bisector).spline.marks.markedByEdgeId;
        int[] serials = { tool.confirmedRings.get(first).serial, tool.confirmedRings.get(second).serial,
            tool.confirmedRings.get(bisector).serial };

        assertTrue(tool.saveRings(), tool.lastError);
        String saved = new String(Files.readAllBytes(dsl), StandardCharsets.UTF_8);
        List<PythonParser.ParsedNode> statements = PythonParser.parse(saved);
        String firstId = tool.confirmedRings.get(first).statementId;
        String secondId = tool.confirmedRings.get(second).statementId;
        String bisectorId = tool.confirmedRings.get(bisector).statementId;
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
        bundle = EdgeMarks.with(bundle, firstId, tool.confirmedRings.get(first).spline.marks.markedByEdgeId);
        bundle = EdgeMarks.with(bundle, secondId, tool.confirmedRings.get(second).spline.marks.markedByEdgeId);
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
        tool.scene.ringMarksByLabel.put(firstId, tool.confirmedRings.get(first).spline.marks.markedByEdgeId);
        tool.scene.ringMarksByLabel.put(secondId, tool.confirmedRings.get(second).spline.marks.markedByEdgeId);
        tool.scene.ringMarksByLabel.put(bisectorId, drawn);
        tool.scene.graph = NodeGraphRuntime.fromSource(saved);
        tool.graphRingsChanged();
        tool.adoptGraphRings(tube);
        int reloaded = tool.ringOfSourceLabel(bisectorId);
        assertTrue(reloaded >= 0, "the bisector was not adopted: " + tool.lastError);
        assertArrayEquals(drawn, tool.confirmedRings.get(reloaded).spline.marks.markedByEdgeId);
        assertArrayEquals(new int[] { serials[0], serials[1] },
                tool.confirmedRings.get(reloaded).bisectorEnds, "the reloaded bisector lost its walls");
        assertEquals(serials[2], tool.confirmedRings.get(reloaded).serial);
    }

    @Test
    void aGraphRingAuthoredAsEdgesWallsWithItsOwnEdgesNotItsFittedSpline() {
        MeshTopology tube = tube();
        RingTool tool = toolOn(tube, null);
        // A staircase round the tube between two rows: diagonal, along, back down. The spline the
        // tool fits through it runs smooth, so its snapped cycle is other edges than these.
        int[] loop = new int[3 * TUBE_SIDES / 2];
        for (int step = 0; step < TUBE_SIDES / 2; step++) {
            loop[3 * step] = vertex(MIDDLE_ROW, 2 * step);
            loop[3 * step + 1] = vertex(MIDDLE_ROW + 1, 2 * step + 1);
            loop[3 * step + 2] = vertex(MIDDLE_ROW + 1, (2 * step + 2) % TUBE_SIDES);
        }
        boolean[] mask = new boolean[RingBundle.edgeIdCeiling(tube)];
        for (int point = 0; point < loop.length; point++) {
            int edgeId = tube.edgeBetween(loop[point], loop[(point + 1) % loop.length]);
            assertTrue(edgeId >= 0, "the staircase steps off the mesh at point " + point);
            mask[edgeId] = true;
        }
        tool.scene.ringMarksByLabel.put(FIRST_WALL, mask);

        tool.adoptGraphRings(tube);

        int adopted = tool.ringOfSourceLabel(FIRST_WALL);
        assertTrue(adopted >= 0, "the edge-mask ring was not adopted: " + tool.lastError);
        SurfaceSpline ring = tool.confirmedRings.get(adopted).spline;
        assertArrayEquals(new EdgeMarks(mask, 0).markedEdgeIds, ring.marks.markedEdgeIds,
                "the adopted ring lists other edges than the ones it was authored as, so a "
                        + "bisector would snap to walls the ring does not draw");
        assertEquals(loop.length, ring.path.pointCount);
        double around = 0.0;
        for (int point = 0; point < loop.length; point++) {
            assertTrue(ring.path.vertexId[point] >= 0, "point " + point + " is off a vertex");
            around += tube.edgeLength(tube.edgeBetween(loop[point],
                    loop[(point + 1) % loop.length]));
        }
        assertEquals(around, ring.path.length, 1e-5,
                "the adopted ring measures another curve than the edges it follows");
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
            boolean[] marks = tool.confirmedRings.get(bisector).spline.marks.markedByEdgeId;
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
        int[] ends = tool.confirmedRings.get(join).bisectorEnds.clone();
        Arrays.sort(ends);
        assertArrayEquals(new int[] { tool.confirmedRings.get(ring).serial,
            tool.confirmedRings.get(bite).serial }, ends,
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
                placed.confirmedRings.get(bisector).authoredVertexId,
                "the anchors were not placed where they add least");

        // The same anchors in click order, as a statement holds them, cross.
        RingTool clicked = toolOn(tube, null);
        clicked.confirmedRings.get(ring(clicked, FIRST_RING_ROW)).sourceLabel = FIRST_WALL;
        clicked.confirmedRings.get(ring(clicked, SECOND_RING_ROW)).sourceLabel = SECOND_WALL;
        clicked.scene.graph = NodeGraphRuntime.fromSource(CARRIER + CLICKED_ORDER + " = "
                + SplineCutNode.NODE_ID + "(geometry=carrier.geometry, points=\""
                + SurfaceWaypoints.format(clicked.positionsOf(new int[] { start, far, near, end }),
                        COORDINATES + 1)
                + "\", ends=\"" + FIRST_WALL + SplineCutNode.END_SEPARATOR + " " + SECOND_WALL
                + "\", label=\"" + CLICKED_ORDER + "\")\n");
        clicked.scene.ringMarksByLabel.put(CLICKED_ORDER, new boolean[0]);
        clicked.adoptGraphRings(tube);
        int crossing = clicked.ringOfSourceLabel(CLICKED_ORDER);
        assertTrue(crossing >= 0, "the clicked-order bisector was not adopted: "
                + clicked.lastError);
        assertTrue(clicked.reopenRing(crossing), clicked.lastError);
        assertEquals(clicked.bisector, clicked.activeSubTool, "the bisector re-opened in the rings");
        assertFalse(clicked.bisector.draftSimple,
                "anchors in click order should double back and cross");
        assertFalse(clicked.confirmDraft(), "a crossing bisector was confirmed");
        assertTrue(clicked.lastError.contains("crosses itself"), clicked.lastError);

        // Esc on the crossing draft, edited, drops it and leaves the bisector; Ctrl+Z restores it.
        int[] confirmedAnchors = clicked.confirmedRings.get(crossing).authoredVertexId;
        press(clicked, Keys.T);
        assertFalse(clicked.bisector.draftSimple, "the crease draft no longer crosses itself");
        assertTrue(clicked.escapePressed());
        assertNull(clicked.draft, "Esc kept the crossing bisector open");
        assertEquals(clicked.closedRing, clicked.activeSubTool,
                "Esc did not leave the crossing bisector");
        assertArrayEquals(confirmedAnchors, clicked.confirmedRings.get(crossing).authoredVertexId,
                "dropping the draft changed the confirmed bisector");
        assertTrue(clicked.lastRow.contains("dropped"), clicked.lastRow);
        assertTrue(clicked.undo(), clicked.lastError);
        assertEquals(clicked.bisector, clicked.activeSubTool,
                "undo did not return to the crossing bisector");
        assertEquals(crossing, clicked.draftSourceRing, "undo did not bring the edited draft back");
    }

    /**
     * The user's IMG_4111 tail bisector: a re-opened bisector whose end is dragged onto another
     * wall saves that wall, so the save writes exactly the anchors placed and a reload keeps them,
     * rather than pulling the moved end back to the old wall and doubling the cut back on itself.
     */
    @Test
    void aReopenedBisectorWhoseEndMovesToAnotherWallSavesAndReloadsThatWall(@TempDir Path folder)
            throws IOException {
        MeshTopology tube = tube();
        Path dsl = folder.resolve(WORKING_DSL);
        Files.write(dsl, CARRIER.getBytes(StandardCharsets.UTF_8));
        RingTool tool = toolOn(tube, dsl);
        int first = ring(tool, FIRST_RING_ROW);
        ring(tool, SECOND_RING_ROW);
        int far = ring(tool, FAR_RING_ROW);
        int bisector = bisect(tool, vertex(FIRST_RING_ROW, TOP_SIDE),
                vertex(SECOND_RING_ROW, TOP_SIDE), vertex(MIDDLE_ROW, TOP_SIDE + 2));
        assertTrue(tool.saveRings(), tool.lastError);
        assertTrue(tool.escapePressed());
        assertEquals(tool.closedRing, tool.activeSubTool);

        assertTrue(tool.reopenRing(bisector), tool.lastError);
        assertEquals(tool.bisector, tool.activeSubTool, "the moved bisector re-opened in the rings");
        assertEquals(tool.bisector, tool.draftOwner, "the rings own the re-opened bisector");
        int[] held = tool.draftAuthoredVertexId;
        tool.selectedAnchorVertexId = held[held.length - 1];
        assertTrue(tool.moveSelectedAnchor(vertex(FAR_RING_ROW, TOP_SIDE)), tool.lastError);
        tool.releaseAnchor();
        int[] placed = tool.draftAuthoredVertexId.clone();
        boolean[] drawn = tool.draft.marks.markedByEdgeId;
        assertTrue(tool.confirmDraft(), tool.lastError);
        int[] farEnds = { tool.confirmedRings.get(first).serial, tool.confirmedRings.get(far).serial };
        assertArrayEquals(farEnds, tool.confirmedRings.get(bisector).bisectorEnds,
                "the confirmed bisector kept the wall its end was dragged off");

        assertTrue(tool.saveRings(), tool.lastError);
        String saved = new String(Files.readAllBytes(dsl), StandardCharsets.UTF_8);
        String bisectorId = tool.confirmedRings.get(bisector).statementId;
        PythonParser.ParsedNode written = null;
        for (PythonParser.ParsedNode statement : PythonParser.parse(saved)) {
            written = bisectorId.equals(statement.id) ? statement : written;
        }
        assertEquals(tool.confirmedRings.get(first).statementId + SplineCutNode.END_SEPARATOR + " "
                + tool.confirmedRings.get(far).statementId,
                written.arguments.get(SplineCutNode.ENDS.name), saved);
        float[] points = SurfaceWaypoints.parse(String.valueOf(
                written.arguments.get(SplineCutNode.POINTS.name)));
        assertArrayEquals(placed, SurfaceWaypoints.snap(tool.geodesics.metric.nearestVertex, points,
                points.length / COORDINATES), "the save wrote other anchors than the ones placed");

        for (int ring = 0; ring < tool.confirmedRings.size(); ring++) {
            tool.scene.ringMarksByLabel.put(tool.confirmedRings.get(ring).statementId,
                    tool.confirmedRings.get(ring).spline.marks.markedByEdgeId);
        }
        tool.scene.graph = NodeGraphRuntime.fromSource(saved);
        tool.graphRingsChanged();
        tool.adoptGraphRings(tube);
        int reloaded = tool.ringOfSourceLabel(bisectorId);
        assertTrue(reloaded >= 0, "the bisector was not adopted: " + tool.lastError);
        assertArrayEquals(placed, tool.confirmedRings.get(reloaded).authoredVertexId,
                "the reload moved an end back onto the wall it was dragged off");
        assertArrayEquals(drawn, tool.confirmedRings.get(reloaded).spline.marks.markedByEdgeId,
                "the reloaded bisector runs along other edges than the one confirmed");
    }

    /**
     * X on an end hands its place to the anchor next to it when that one lies on a wall the
     * bisector crosses; with an anchor off every wall in the way it refuses, naming that anchor and
     * the one on a wall that would take the end's place.
     */
    @Test
    void deletingAnEndPromotesTheAnchorNextToItOntoItsWall() {
        MeshTopology tube = tube();
        RingTool tool = toolOn(tube, null);
        int first = ring(tool, FIRST_RING_ROW);
        int crossed = ring(tool, CROSSED_RING_ROW);
        ring(tool, SECOND_RING_ROW);
        int offWalls = vertex(FIRST_PATCH_ROW, TOP_SIDE + 2);
        int onCrossed = vertex(CROSSED_RING_ROW, TOP_SIDE);
        press(tool, Keys.K);
        assertTrue(click(tool, FIRST_RING_ROW, TOP_SIDE), tool.lastError);
        assertTrue(click(tool, SECOND_RING_ROW, TOP_SIDE), tool.lastError);
        assertTrue(tool.addAuthoredAnchor(offWalls), tool.lastError);
        assertTrue(tool.addAuthoredAnchor(onCrossed), tool.lastError);
        int[] held = tool.draftAuthoredVertexId.clone();
        assertEquals(offWalls, held[1], Arrays.toString(held));
        assertEquals(onCrossed, held[2], Arrays.toString(held));

        tool.selectedAnchorVertexId = held[0];
        press(tool, Keys.X);
        assertArrayEquals(held, tool.draftAuthoredVertexId, "X deleted an end with an anchor off "
                + "every wall next to it");
        assertTrue(tool.lastError.contains("anchor 1 lies") && tool.lastError.contains("anchor 2 on "
                + tool.liveLabel(crossed)), tool.lastError);

        tool.selectedAnchorVertexId = held[held.length - 1];
        press(tool, Keys.X);
        assertEquals("", tool.lastError);
        assertEquals(held.length - 1, tool.draftAuthoredVertexId.length);
        int promoted = tool.draftAuthoredVertexId[held.length - 2];
        assertTrue(touches(tube, tool.confirmedRings.get(crossed).spline.marks.markedByEdgeId,
                promoted), "the promoted end is not on the crossed ring");
        assertArrayEquals(new int[] { tool.confirmedRings.get(first).serial,
            tool.confirmedRings.get(crossed).serial }, tool.bisector.draftEnds,
                "the bisector does not end on the crossed ring now");
        assertEquals(promoted, tool.selectedAnchorVertexId, "the new end is not selected");
        assertTrue(tool.confirmDraft(), tool.lastError);
    }

    /**
     * Esc always leaves the bisector for the rings: an unedited new or re-opened draft closes,
     * nothing recorded, the waiting end going too; an edited one is confirmed as one edit.
     */
    @Test
    void escLeavesTheBisectorForNewAndReopenedDrafts() {
        MeshTopology tube = tube();
        RingTool tool = toolOn(tube, null);
        ring(tool, FIRST_RING_ROW);
        ring(tool, SECOND_RING_ROW);
        tool.scene.switchTool(tool);
        int rings = tool.confirmedRings.size();
        press(tool, Keys.K);
        assertTrue(click(tool, FIRST_RING_ROW, TOP_SIDE), tool.lastError);
        assertTrue(click(tool, SECOND_RING_ROW, TOP_SIDE), tool.lastError);
        assertTrue(tool.escapePressed());
        assertNull(tool.draft, "Esc kept the unedited new bisector");
        assertEquals(-1, tool.bisector.startVertexId, "Esc left the first end waiting");
        assertEquals(tool.closedRing, tool.activeSubTool, "Esc did not leave the new bisector");
        assertEquals(rings, tool.confirmedRings.size());
        press(tool, Keys.K);
        assertTrue(tool.escapePressed());
        assertEquals(tool.closedRing, tool.activeSubTool, "Esc did not leave the empty bisector");
        List<String> shown = new ArrayList<>();
        for (ControlHint control : tool.scene.controls.get(Scene.TOOL_SECTION)) {
            shown.add(control.toString());
        }
        assertEquals(rows(tool), shown, "the scene still lists the bisector's rows after Esc");

        press(tool, Keys.K);
        assertTrue(click(tool, FIRST_RING_ROW, TOP_SIDE), tool.lastError);
        assertTrue(click(tool, SECOND_RING_ROW, TOP_SIDE), tool.lastError);
        int steer = vertex(MIDDLE_ROW, TOP_SIDE + 2);
        assertTrue(tool.addAuthoredAnchor(steer), tool.lastError);
        assertTrue(tool.escapePressed());
        assertNull(tool.draft, "Esc kept the edited new bisector open");
        assertEquals(tool.closedRing, tool.activeSubTool, "Esc did not leave the edited bisector");
        assertEquals(rings + 1, tool.confirmedRings.size(), "Esc did not confirm the bisector");
        int bisector = rings;
        assertEquals(steer, tool.confirmedRings.get(bisector).authoredVertexId[1]);

        assertTrue(tool.reopenRing(bisector), tool.lastError);
        assertEquals(tool.bisector, tool.activeSubTool);
        assertTrue(tool.escapePressed());
        assertNull(tool.draft, "Esc kept the unedited re-opened bisector");
        assertEquals(tool.closedRing, tool.activeSubTool, "Esc did not leave the re-opened bisector");

        assertTrue(tool.reopenRing(bisector), tool.lastError);
        int moreSteer = vertex(MIDDLE_ROW + PATCH_HALF_ROWS, TOP_SIDE + 2);
        assertTrue(tool.addAuthoredAnchor(moreSteer), tool.lastError);
        int[] edited = tool.draftAuthoredVertexId;
        press(tool, Keys.K);
        assertNull(tool.draft, "K kept the edited re-opened bisector open");
        assertEquals(tool.closedRing, tool.activeSubTool, "K did not leave the re-opened bisector");
        assertArrayEquals(edited, tool.confirmedRings.get(bisector).authoredVertexId,
                "leaving did not confirm the edited bisector");
        assertTrue(tool.undo(), tool.lastError);
        assertEquals(tool.bisector, tool.activeSubTool, "undo did not return to the bisector");
        assertArrayEquals(edited, tool.draftAuthoredVertexId, "undo did not bring the draft back");
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
        if (tool.activeSubTool != tool.bisector) {
            press(tool, Keys.K);
        }
        assertEquals(tool.bisector, tool.activeSubTool, tool.lastError);
        assertTrue(click(tool, firstEnd), tool.lastError);
        assertTrue(click(tool, secondEnd), tool.lastError);
        for (int anchor : steering) {
            assertTrue(tool.addAuthoredAnchor(anchor), tool.lastError);
        }
        assertTrue(tool.bisector.draftSimple, tool.lastRow);
        assertTrue(tool.confirmDraft(), tool.lastError);
        return tool.confirmedRings.size() - 1;
    }

    private static boolean click(RingTool tool, int row, int side) {
        return click(tool, vertex(row, side));
    }

    /** A click on a vertex as the ring tool hands it to the bisector, once its pick found it. */
    private static boolean click(RingTool tool, int vertexId) {
        Vector3f position = new Vector3f();
        tool.geodesics.mesh.vertexPosition(vertexId, position);
        tool.scene.cursorVertexId = vertexId;
        tool.scene.cursorPoint[0] = position.x;
        tool.scene.cursorPoint[1] = position.y;
        tool.scene.cursorPoint[2] = position.z;
        tool.lastError = "";
        return tool.bisector.onClick(0f, 0f);
    }

    /** The ring tool's control rows as the controls menu lists them. */
    private static List<String> rows(RingTool tool) {
        List<ControlHint> controls = new ArrayList<>();
        tool.addControls(controls);
        List<String> rows = new ArrayList<>();
        for (ControlHint control : controls) {
            rows.add(control.toString());
        }
        return rows;
    }

    /** Whether any marked edge ends on a vertex or on one of its neighbours. */
    private static boolean touches(MeshTopology mesh, boolean[] marks, int vertexId) {
        for (int spoke = 0; spoke < mesh.vertexEdgeCount(vertexId); spoke++) {
            int near = mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertexId, spoke), vertexId);
            for (int around : new int[] { vertexId, near }) {
                for (int edge = 0; edge < mesh.vertexEdgeCount(around); edge++) {
                    int edgeId = mesh.vertexEdgeAt(around, edge);
                    if (edgeId < marks.length && marks[edgeId]) {
                        return true;
                    }
                }
            }
        }
        return false;
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
                SurfaceSpline.of(loop.tracer).marks.markedByEdgeId), "patch at row " + row);
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
        return tube(0.0);
    }

    /**
     * A straight open tube along x, a groove of the given depth running from the top of the first
     * ring row to the top of the second, bowed {@link #GROOVE_BEND} sides round at the middle row.
     */
    private static MeshTopology tube(double grooveDepth) {
        float[] positions = new float[COORDINATES * TUBE_SIDES * TUBE_RINGS];
        for (int row = 0; row < TUBE_RINGS; row++) {
            float x = -TUBE_HALF_LENGTH + 2f * TUBE_HALF_LENGTH * row / (TUBE_RINGS - 1);
            double along = (row - FIRST_RING_ROW) / (double) (SECOND_RING_ROW - FIRST_RING_ROW);
            double grooveSide = TOP_SIDE + GROOVE_BEND * Math.sin(Math.PI * along);
            for (int side = 0; side < TUBE_SIDES; side++) {
                double angle = 2.0 * Math.PI * side / TUBE_SIDES;
                double fromGroove = (side - grooveSide) / GROOVE_HALF_WIDTH_SIDES;
                double radius = TUBE_RADIUS - (along < 0.0 || along > 1.0 ? 0.0
                        : grooveDepth * Math.exp(-fromGroove * fromGroove));
                int base = COORDINATES * vertex(row, side);
                positions[base] = x;
                positions[base + 1] = (float) (radius * Math.cos(angle));
                positions[base + 2] = (float) (radius * Math.sin(angle));
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
