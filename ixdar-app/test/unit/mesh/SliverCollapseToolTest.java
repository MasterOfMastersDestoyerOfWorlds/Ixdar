package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceMetric;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSpline;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.RingRegions;
import ixdar.geometry.mesh.data.rings.RingSegmentMode;
import ixdar.geometry.mesh.data.rings.SliverCollapse;
import ixdar.nodegraph.nodes.selection.SplineCutNode;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.nodegraph.syntax.PythonParser;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.input.KeyNames;
import ixdar.platform.input.Keys;
import ixdar.scenes.ring.RingScene;
import ixdar.tools.Tool;
import ixdar.tools.ring.ConfirmedRing;
import ixdar.tools.ring.RingDslWriter;
import ixdar.tools.ring.RingTool;
import unit.GlStandIn;

/**
 * CRAW-89, through the ring tool: A replaces dropped rings with ordinary bisectors that save as
 * spline_cut statements and reload with no sliver, moves bisectors off them, and undoes in one step.
 */
class SliverCollapseToolTest {

    private static final String SPHERE = "ball = icosphere(radius=1.0, subdivisions=4)\n"
            + "surface = surface_metric(geometry=ball.mesh)\n";

    private static final String SURFACE = "surface";

    private static final String METRIC = "metric";

    private static final String SURFACE_GEOMETRY = "surface.geometry";

    private static final String WORKING_DSL = "chain.dsl";

    private static final String SPLINE_RING = "spline_ring";

    private static final String LINE_BREAK = "\n";

    private static final String[] LABELS = { "ring_00", "ring_01", "ring_02" };

    private static final double WORLD_PER_PIXEL = 0.002;

    // ring_01 dips across the equator as ring_02 dips across ring_01, on the far side.
    private static final float SLOPE = 0.2f;

    private static final float OFFSET = 0.12f;

    private static final int[] ANCHORS = { 32, 16, 4 };

    // Where the meridian crosses the zipper's run, among its lenses.
    private static final double MERIDIAN_DEGREES = 20.0;

    private Platform suitePlatform;

    private GL suiteGl;

    private SurfaceMetric metric;

    /** Put a GL stand-in under the scene, as the other ring tool tests do. */
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
    void theDroppedRingsBecomeBisectorsThatSaveAndReloadWithNoSliver(@TempDir Path folder)
            throws Exception {
        RingTool tool = chain(folder);
        int before = twoRingSlivers(tool.scene.regionLayer.regions);
        assertTrue(before >= 2, "the chain pinches too few slivers");

        assertEquals(before, tool.collapseSlivers(), tool.lastError);

        assertFalse(tool.confirmedRings.get(0).deleted, "the equator keeps its runs");
        assertTrue(tool.confirmedRings.get(1).deleted, tool.lastRow);
        assertTrue(tool.confirmedRings.get(2).deleted, tool.lastRow);
        assertTrue(tool.confirmedRings.size() > LABELS.length, "no bisector was added");
        for (int ring = LABELS.length; ring < tool.confirmedRings.size(); ring++) {
            ConfirmedRing added = tool.confirmedRings.get(ring);
            assertTrue(added.isBisector() && added.unsaved, tool.liveLabel(ring));
            assertArrayEquals(added.authoredVertexId, authored(added.spline),
                    "a new bisector's anchors are not its spline's own");
            assertNotNull(added.bisectorStretch, "a ring stretch carries no stretch vertices");
        }
        assertTrue(tool.lastRow.contains(" 0 of them off their old edges"), tool.lastRow);
        assertEquals(0, twoRingSlivers(rebuilt(tool)), "A left slivers in the tool");
        int added = tool.confirmedRings.size() - LABELS.length;
        assertTrue(tool.saveRings(), tool.lastError);
        String saved = new String(Files.readAllBytes(folder.resolve(WORKING_DSL)),
                StandardCharsets.UTF_8);
        // The save may hand a dropped ring's freed id to a bisector, never keep its spline_ring.
        int rings = 0;
        int bisectors = 0;
        for (PythonParser.ParsedNode statement : PythonParser.parse(saved)) {
            rings += SPLINE_RING.equals(statement.type) ? 1 : 0;
            bisectors += SplineCutNode.NODE_ID.equals(statement.type) ? 1 : 0;
        }
        assertEquals(1, rings, "a dropped ring's statement is still saved:\n" + saved);
        assertEquals(added, bisectors, saved);
        assertEquals(0, twoRingSlivers(reloaded(folder)), saved);
    }

    @Test
    void aBisectorEndingOnADroppedRingMovesToTheKeeperAndTheNewBisector(@TempDir Path folder)
            throws Exception {
        RingTool tool = chain(folder);
        int bisector = bisectAcrossTheMiddleRing(tool);
        int middleSerial = tool.confirmedRings.get(1).serial;

        tool.collapseSlivers();

        int[] ends = tool.confirmedRings.get(bisector).bisectorEnds;
        assertEquals(tool.confirmedRings.get(0).serial, ends[1],
                "the end inside the run did not move to the equator: " + tool.lastRow);
        int firstEndWall = tool.ringOfSerial(ends[0]);
        assertNotEquals(middleSerial, ends[0], "the end outside the run stayed on ring_01");
        assertTrue(firstEndWall >= LABELS.length + 1
                && tool.confirmedRings.get(firstEndWall).isBisector(),
                "the end outside the run is not on one of ring_01's new bisectors");
        assertTrue(tool.saveRings(), tool.lastError);
        String id = tool.confirmedRings.get(bisector).statementId;
        String saved = new String(Files.readAllBytes(folder.resolve(WORKING_DSL)),
                StandardCharsets.UTF_8);
        String savedEnds = null;
        for (PythonParser.ParsedNode statement : PythonParser.parse(saved)) {
            savedEnds = statement.id.equals(id)
                    ? String.valueOf(statement.arguments.get(SplineCutNode.ENDS.name)) : savedEnds;
        }
        assertNotNull(savedEnds, saved);
        assertTrue(savedEnds.endsWith(LABELS[0]), savedEnds);
        assertEquals(0, twoRingSlivers(reloaded(folder)), saved);
    }

    @Test
    void undoBringsTheDroppedRingAndTheBisectorEndsBack(@TempDir Path folder) throws Exception {
        RingTool tool = chain(folder);
        int bisector = bisectAcrossTheMiddleRing(tool);
        int[] before = tool.confirmedRings.get(bisector).bisectorEnds.clone();
        int rings = tool.confirmedRings.size();
        tool.collapseSlivers();

        assertTrue(tool.undo(), tool.lastError);

        assertArrayEquals(before, tool.confirmedRings.get(bisector).bisectorEnds);
        assertEquals(rings, tool.confirmedRings.size(), "the new bisectors stayed");
        assertFalse(tool.confirmedRings.get(1).deleted, "ring_01 is still deleted");
    }

    @Test
    void ctrlZUndoesTheCollapseAndCtrlYRedoesItWhateverToolIsUp(@TempDir Path folder)
            throws Exception {
        RingTool tool = chain(folder);
        RingScene scene = tool.scene;
        int before = twoRingSlivers(rebuilt(tool));
        int rings = tool.confirmedRings.size();
        assertTrue(before >= 2, String.valueOf(before));
        for (Tool active : scene.tools) {
            scene.switchTool(active);
            String name = active.toolName();
            // A is one of the scene's rows, which a tool that replaces the view hides; ctrl+Z is not.
            if (active.replacesView()) {
                tool.collapseSlivers();
            } else {
                scene.keys.handleSceneKeys(Keys.A, 0);
            }
            assertEquals(0, twoRingSlivers(rebuilt(tool)), name + ": A left slivers");

            scene.keys.handleSceneKeys(Keys.Z, KeyNames.MOD_CONTROL);
            assertEquals(before, twoRingSlivers(rebuilt(tool)), name + ": " + active.lastError);
            assertTrue(active.lastRow.startsWith("undid slivers collapsed"), name + ": " + active.lastRow);
            assertEquals(rings, tool.confirmedRings.size(), name + ": the bisectors stayed");
            assertFalse(tool.confirmedRings.get(1).deleted, name + ": ring_01 stayed deleted");

            scene.keys.handleSceneKeys(Keys.Y, KeyNames.MOD_CONTROL);
            assertEquals(0, twoRingSlivers(rebuilt(tool)), name + ": ctrl+Y " + active.lastError);
            scene.keys.handleSceneKeys(Keys.Z, KeyNames.MOD_CONTROL);
            scene.keys.handleSceneKeys(Keys.Z, KeyNames.MOD_CONTROL | KeyNames.MOD_SHIFT);
            assertEquals(0, twoRingSlivers(rebuilt(tool)), name + ": ctrl+shift+Z " + active.lastError);
            scene.keys.handleSceneKeys(Keys.Z, KeyNames.MOD_CONTROL);
            assertEquals(before, twoRingSlivers(rebuilt(tool)), name + ": " + active.lastError);
        }
    }

    @Test
    void aRunAThirdRingCrossesIsStillCollapsed() throws Exception {
        // ring_01 zips along the equator over +-10 degrees of longitude, and a meridian crosses
        // both inside that run, pinching slivers of three rings there.
        double[][] zipperLatLon = { { 0, -10 }, { 0, 0 }, { 0, 10 }, { 1, 30 }, { 60, 90 },
            { 70, 180 }, { 60, 270 }, { 1, -30 } };
        float cosine = (float) Math.cos(Math.toRadians(MERIDIAN_DEGREES));
        float sine = (float) Math.sin(Math.toRadians(MERIDIAN_DEGREES));
        float[][] rings = { new float[3 * ANCHORS[0]], new float[3 * zipperLatLon.length],
            { cosine, 0f, sine, 0f, 1f, 0f, -cosine, 0f, -sine, 0f, -1f, 0f } };
        for (int index = 0; index < ANCHORS[0]; index++) {
            double angle = 2 * Math.PI * index / ANCHORS[0];
            rings[0][3 * index] = (float) Math.cos(angle);
            rings[0][3 * index + 2] = (float) Math.sin(angle);
        }
        for (int index = 0; index < zipperLatLon.length; index++) {
            double lat = Math.toRadians(zipperLatLon[index][0]);
            double lon = Math.toRadians(zipperLatLon[index][1]);
            rings[1][3 * index] = (float) (Math.cos(lat) * Math.cos(lon));
            rings[1][3 * index + 1] = (float) Math.sin(lat);
            rings[1][3 * index + 2] = (float) (Math.cos(lat) * Math.sin(lon));
        }
        float[][] ringNormals = { { 0f, 1f, 0f }, null, { -sine, 0f, cosine } };
        StringBuilder source = new StringBuilder(SPHERE);
        String input = SURFACE_GEOMETRY;
        for (int ring = 0; ring < LABELS.length; ring++) {
            source.append(RingDslWriter.splineStatement(LABELS[ring], input, rings[ring],
                    rings[ring].length / 3, ringNormals[ring])).append(LINE_BREAK);
            input = LABELS[ring] + "." + RingDslWriter.DEFAULT_UPSTREAM_PORT;
        }
        NodeGraphRuntime runtime = NodeGraphRuntime.fromSource(
                RingDslWriter.wireRingInputs(source.toString()));
        GeometryBundle bundle = (GeometryBundle) runtime.executeGraphResult(LABELS[2],
                RingDslWriter.DEFAULT_UPSTREAM_PORT);
        SurfaceMetric surfaceMetric = (SurfaceMetric) runtime.node(SURFACE).getOutput(METRIC);
        boolean[][] marks = new boolean[LABELS.length][];
        int[][] anchors = new int[LABELS.length][];
        int[][] splineAnchors = new int[LABELS.length][];
        for (int ring = 0; ring < LABELS.length; ring++) {
            marks[ring] = EdgeMarks.bools(bundle, LABELS[ring]);
            SurfaceSpline spline = SurfaceSpline.inBundle(bundle, LABELS[ring]);
            anchors[ring] = authored(spline);
            splineAnchors[ring] = Arrays.copyOf(spline.anchorVertexId, spline.anchorCount);
        }
        RingRegions regions = new RingRegions(bundle.mesh(), LABELS, marks).build();
        int threeRing = 0;
        for (int region = 0; region < regions.regionCount; region++) {
            threeRing += regions.isSliver(region)
                    && regions.boundingRingsByRegion[region].length == 3 ? 1 : 0;
        }
        int twoRing = twoRingSlivers(regions);
        assertTrue(threeRing > 0 && twoRing > 0, "the meridian pinches no three-ring sliver in the "
                + "run: " + twoRing + " two-ring, " + threeRing + " three-ring");
        SliverCollapse collapse = new SliverCollapse(SurfaceGeodesics.over(surfaceMetric), null);
        boolean[] every = { true, true, true };
        RingSegmentMode[] modes = { RingSegmentMode.GEODESIC, RingSegmentMode.GEODESIC,
            RingSegmentMode.GEODESIC };

        int removed = collapse.collapse(regions, anchors, splineAnchors, modes, every,
                new int[LABELS.length][], new int[LABELS.length][]);

        assertEquals(twoRing, removed, collapse.refusals.toString());
        assertTrue(collapse.routeDifferences.isEmpty(), collapse.routeDifferences.toString());
    }

    /**
     * A spline's authored anchors in order, what a confirmed ring holds as its anchors.
     *
     * @param spline the spline
     * @return the anchors' vertex ids
     */
    private static int[] authored(SurfaceSpline spline) {
        int[] authored = new int[spline.authoredCount()];
        for (int anchor = 0, held = 0; anchor < spline.anchorCount; anchor++) {
            if (spline.anchorAuthored[anchor]) {
                authored[held++] = spline.anchorVertexId[anchor];
            }
        }
        return authored;
    }

    /**
     * The three circles traced by the graph, held by a ring tool over the same surface as saved
     * graph rings, the working .dsl the graph itself.
     *
     * @param folder where the working .dsl goes
     * @return the tool, its regions built
     */
    private RingTool chain(Path folder) throws Exception {
        Vector3f normalA = new Vector3f(0f, 1f, 0f);
        Vector3f normalB = new Vector3f(0f, 1f, -SLOPE).normalize();
        float distanceB = OFFSET / new Vector3f(0f, 1f, -SLOPE).length();
        Vector3f across = new Vector3f(0f, 0f, -1f);
        across.sub(new Vector3f(normalB).mul(across.dot(normalB))).normalize();
        Vector3f raw = new Vector3f(normalB).sub(new Vector3f(across).mul(SLOPE));
        float distanceC = (distanceB + OFFSET) / raw.length();
        Vector3f normalC = raw.normalize();
        Vector3f[] planeNormals = { normalA, normalB, normalC };
        float[] distances = { 0f, distanceB, distanceC };
        float[][] points = new float[LABELS.length][];
        float[][] normals = new float[LABELS.length][];
        for (int ring = 0; ring < LABELS.length; ring++) {
            Vector3f normal = planeNormals[ring];
            float radius = (float) Math.sqrt(1 - distances[ring] * distances[ring]);
            Vector3f first = new Vector3f(1f, 0f, 0f);
            first.sub(new Vector3f(normal).mul(first.dot(normal))).normalize();
            Vector3f second = new Vector3f(normal).cross(first);
            points[ring] = new float[3 * ANCHORS[ring]];
            for (int anchor = 0; anchor < ANCHORS[ring]; anchor++) {
                double angle = 2 * Math.PI * (anchor + 0.5) / ANCHORS[ring];
                Vector3f point = new Vector3f(normal).mul(distances[ring])
                        .add(new Vector3f(first).mul((float) (radius * Math.cos(angle))))
                        .add(new Vector3f(second).mul((float) (radius * Math.sin(angle))));
                points[ring][3 * anchor] = point.x;
                points[ring][3 * anchor + 1] = point.y;
                points[ring][3 * anchor + 2] = point.z;
            }
            normals[ring] = new float[] { normal.x, normal.y, normal.z };
        }
        StringBuilder source = new StringBuilder(SPHERE);
        String input = SURFACE_GEOMETRY;
        for (int ring = 0; ring < LABELS.length; ring++) {
            source.append(RingDslWriter.splineStatement(LABELS[ring], input, points[ring],
                    ANCHORS[ring], normals[ring])).append(LINE_BREAK);
            input = LABELS[ring] + "." + RingDslWriter.DEFAULT_UPSTREAM_PORT;
        }
        String graph = RingDslWriter.wireRingInputs(source.toString());
        Path dsl = folder.resolve(WORKING_DSL);
        Files.write(dsl, graph.getBytes(StandardCharsets.UTF_8));
        NodeGraphRuntime runtime = NodeGraphRuntime.fromSource(graph);
        GeometryBundle bundle = (GeometryBundle) runtime.executeGraphResult(LABELS[2],
                RingDslWriter.DEFAULT_UPSTREAM_PORT);
        metric = (SurfaceMetric) runtime.node(SURFACE).getOutput(METRIC);
        MeshTopology mesh = metric.sourceMesh;
        RingScene scene = new RingScene() {
            @Override
            public MeshTopology getMesh() {
                return mesh;
            }

            @Override
            public MeshTopology halfEdgeSurface() {
                return mesh;
            }

            @Override
            public double worldPerPixel() {
                return WORLD_PER_PIXEL;
            }

            @Override
            public String workingDslPath() {
                return dsl.toString();
            }
        };
        scene.initPoints();
        scene.setTools();
        scene.switchTool(scene.defaultTool);
        RingTool tool = scene.ringTool;
        tool.geodesics = SurfaceGeodesics.over(metric);
        tool.active = true;
        tool.graphRingsPending = false;
        tool.nextRingSerial = LABELS.length;
        for (int ring = 0; ring < LABELS.length; ring++) {
            SurfaceSpline spline = SurfaceSpline.inBundle(bundle, LABELS[ring]);
            tool.confirmedRings.add(new ConfirmedRing(spline, authored(spline), normals[ring],
                    RingSegmentMode.GEODESIC, LABELS[ring], false, LABELS[ring], ring, null));
            tool.knownRingStatementIds.add(LABELS[ring]);
        }
        tool.invalidateRings();
        rebuilt(tool);
        return tool;
    }

    /**
     * An unsaved bisector from ring_01's highest vertex over the south pole to its lowest, which
     * lies inside the run ring_01 loses to the equator.
     *
     * @param tool the chain's ring tool
     * @return the bisector's index among the confirmed rings
     */
    private int bisectAcrossTheMiddleRing(RingTool tool) {
        MeshTopology mesh = metric.sourceMesh;
        ConfirmedRing middle = tool.confirmedRings.get(1);
        int highest = -1;
        int lowest = -1;
        Vector3f position = new Vector3f();
        float top = Float.NEGATIVE_INFINITY;
        float bottom = Float.POSITIVE_INFINITY;
        for (int vertexId : EdgeMarks.orderedLoop(mesh, middle.spline.marks.markedByEdgeId)) {
            float height = mesh.vertexPosition(vertexId, position).y;
            highest = height > top ? vertexId : highest;
            top = Math.max(top, height);
            lowest = height < bottom ? vertexId : lowest;
            bottom = Math.min(bottom, height);
        }
        // Over the south pole, so it reaches the lowest vertex from the side away from the lens.
        int[] anchors = { highest, metric.nearestVertex.find(0f, -1f, 0f), lowest };
        int[] ends = { middle.serial, middle.serial };
        SurfaceSpline traced = tool.bisector.traced(anchors, ends, RingSegmentMode.GEODESIC, null);
        assertNotNull(traced, tool.lastError);
        assertTrue(traced.marks.markedEdgeCount > 0, "the bisector walls nothing");
        tool.confirmedRings.add(new ConfirmedRing(traced, anchors, new float[3],
                RingSegmentMode.GEODESIC, null, true, null, LABELS.length, ends));
        tool.nextRingSerial = LABELS.length + 1;
        tool.invalidateRings();
        rebuilt(tool);
        return tool.confirmedRings.size() - 1;
    }

    /**
     * The saved working .dsl run again: the regions of every ring and bisector it marks.
     *
     * @param folder where the working .dsl is
     * @return the reloaded regions
     */
    private static RingRegions reloaded(Path folder) throws Exception {
        String saved = new String(Files.readAllBytes(folder.resolve(WORKING_DSL)),
                StandardCharsets.UTF_8);
        NodeGraphRuntime runtime = NodeGraphRuntime.fromSource(saved);
        GeometryBundle reloaded = (GeometryBundle) runtime.executeGraphResult(runtime.lastId(),
                RingDslWriter.DEFAULT_UPSTREAM_PORT);
        List<String> labels = new ArrayList<>();
        List<boolean[]> marks = new ArrayList<>();
        for (PythonParser.ParsedNode statement : PythonParser.parse(saved)) {
            boolean[] mask = statement.id.startsWith("ring_") ? EdgeMarks.bools(reloaded,
                    statement.id) : null;
            if (mask != null) {
                labels.add(statement.id);
                marks.add(mask);
            }
        }
        return new RingRegions(reloaded.mesh(), labels.toArray(new String[0]),
                marks.toArray(new boolean[0][])).build();
    }

    /**
     * The tool's regions rebuilt from its rings as they stand.
     *
     * @param tool the ring tool
     * @return its scene's regions
     */
    private static RingRegions rebuilt(RingTool tool) {
        tool.scene.regionLayer.rebuild();
        return tool.scene.regionLayer.regions;
    }

    private static int twoRingSlivers(RingRegions regions) {
        int slivers = 0;
        for (int region = 0; region < regions.regionCount; region++) {
            slivers += regions.isSliver(region)
                    && regions.boundingRingsByRegion[region].length == 2 ? 1 : 0;
        }
        return slivers;
    }
}
