package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.csg.MeshBooleanBackend;
import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.RingRegions;
import ixdar.geometry.mesh.data.paths.AuthoredSplineRing;
import ixdar.geometry.mesh.data.paths.NearestVertex;
import ixdar.geometry.mesh.data.paths.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.SurfaceMetric;
import ixdar.geometry.mesh.data.paths.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.SurfaceSplineTracer;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.nodes.data.SurfaceMetricNode;
import ixdar.geometry.mesh.nodes.selection.ExtractRingRegionNode;
import ixdar.geometry.mesh.nodes.selection.RingDslWriter;
import ixdar.geometry.mesh.nodes.selection.SplineRingNode;
import ixdar.scenes.ring.RingScene;
import ixdar.scenes.ring.RingTool;

/**
 * The ring tool on quads: the intrinsic triangulation splits every polygon inside itself, a graph
 * ring gets anchors on a procedural quad cylinder, and a saved spline ring passes the quads through
 * and marks the same closed loop of quad edges.
 */
class QuadRingToolTest {

    private static final int SEGMENTS_AROUND = 16;

    private static final int SIDE_ROWS = 8;

    private static final int RING_ROW = 4;

    private static final int TILTED_ANCHORS = 4;

    private static final int TILT_ROWS = 2;

    private static final float CYLINDER_HEIGHT = 4f;

    private static final float POSITION_TOLERANCE = 1e-6f;

    private static final String RING_LABEL = "ring_00";

    private static final String SAVED_LABEL = "ring_01";

    private static final String FIXTURE = "\"fixture\"";

    private static final int QUAD_CORNERS = 4;

    private static final int TRIANGLE_CORNERS = 3;

    private static final int GRID_COLUMNS = 7;

    private static final int GRID_ROWS = 5;

    private static final int RUN_ACROSS = 5;

    private static final int RUN_UP = 3;

    private static final double GEODESIC_TOLERANCE = 1e-9;

    private static final double CAP_TOLERANCE = 1e-5;

    @Test
    void aGeodesicOnAQuadGridIsTheStraightLineThroughItsQuads() {
        // A flat grid of unit squares in the xy plane, the vertex at column c, row r at (c, r).
        float[] positions = new float[RingTool.COORDINATES_PER_POINT * (GRID_COLUMNS + 1)
                * (GRID_ROWS + 1)];
        for (int row = 0; row <= GRID_ROWS; row++) {
            for (int column = 0; column <= GRID_COLUMNS; column++) {
                int base = RingTool.COORDINATES_PER_POINT * gridVertexId(column, row);
                positions[base] = column;
                positions[base + 1] = row;
            }
        }
        int[] cornerCounts = new int[GRID_COLUMNS * GRID_ROWS];
        int[] corners = new int[QUAD_CORNERS * cornerCounts.length];
        int cursor = 0;
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int column = 0; column < GRID_COLUMNS; column++) {
                cornerCounts[row * GRID_COLUMNS + column] = QUAD_CORNERS;
                corners[cursor++] = gridVertexId(column, row);
                corners[cursor++] = gridVertexId(column + 1, row);
                corners[cursor++] = gridVertexId(column + 1, row + 1);
                corners[cursor++] = gridVertexId(column, row + 1);
            }
        }
        HalfEdgeMesh grid = HalfEdgeMeshEngine.bulkAllocateMixed(positions, cornerCounts, corners);
        SurfaceMetric metric = SurfaceMetric.of(grid);
        assertCoversEveryPolygon(grid, metric);
        assertEquals(grid.edgeCount() + grid.faceCount(), metric.edgeLength.length,
                "one split edge per quad");

        // Interior endpoints: FlipOut leaves a path hugging the boundary as it does on triangles.
        SurfaceGeodesics geodesics = SurfaceGeodesics.over(metric);
        assertTrue(geodesics.geodesic(gridVertexId(1, 1),
                gridVertexId(1 + RUN_ACROSS, 1 + RUN_UP)));

        double analytic = Math.hypot(RUN_ACROSS, RUN_UP);
        assertEquals(analytic, geodesics.pathLength, GEODESIC_TOLERANCE * analytic);
        int insidePoints = 0;
        for (int point = 0; point < geodesics.tracedPointCount; point++) {
            double x = geodesics.tracedXyz[RingTool.COORDINATES_PER_POINT * point];
            double y = geodesics.tracedXyz[RingTool.COORDINATES_PER_POINT * point + 1];
            assertEquals(0.0, Math.abs((x - 1) * RUN_UP - (y - 1) * RUN_ACROSS) / analytic,
                    GEODESIC_TOLERANCE, "traced point " + point + " left the straight line");
            int faceId = geodesics.tracedFaceId[point];
            if (faceId < 0) {
                continue;
            }
            insidePoints++;
            assertTrue(geodesics.tracedVertexId[point] < 0 && geodesics.tracedEdgeId[point] < 0);
            Vector3f corner = new Vector3f();
            grid.vertexPosition(grid.faceVertexAt(faceId, 0), corner);
            assertTrue(x > corner.x && x < corner.x + 1 && y > corner.y && y < corner.y + 1,
                    "inside point " + point + " lies outside its face " + faceId);
        }
        assertTrue(insidePoints > 0, "the line crosses quad interiors, so it must pass splits");
    }

    @Test
    void theIntrinsicTriangulationSplitsEachPolygonOfTheQuadCylinder() {
        MeshTopology quads = quadCylinder();
        SurfaceMetric metric = SurfaceMetric.of(quads);
        assertCoversEveryPolygon(quads, metric);
        int sideQuads = SIDE_ROWS * SEGMENTS_AROUND;
        int capSplits = 2 * (SEGMENTS_AROUND - 3);
        assertEquals(quads.edgeCount() + sideQuads + capSplits, metric.edgeLength.length);
        for (int edge = 0; edge < quads.edgeCount(); edge++) {
            assertEquals(quads.edgeIdAt(edge), metric.sourceEdgeId[edge]);
        }
        for (int edge = quads.edgeCount(); edge < metric.edgeLength.length; edge++) {
            assertEquals(MeshTopology.NONE, metric.sourceEdgeId[edge]);
        }

        SurfaceGeodesics geodesics = SurfaceGeodesics.over(metric);
        assertTrue(geodesics.geodesic(0, SEGMENTS_AROUND / 2));
        assertEquals(2.0, geodesics.pathLength, CAP_TOLERANCE,
                "the shortest way across the flat cap is its diameter");
    }

    @Test
    void aQuadSplitsOnItsShorterDiagonal() {
        float[] rhombus = { 0f, 0f, 0f, 2f, 0f, 0f, 3f, 1f, 0f, 1f, 1f, 0f };
        MeshTopology quad = HalfEdgeMeshEngine.bulkAllocateMixed(rhombus,
                new int[] { QUAD_CORNERS }, new int[] { 0, 1, 2, 3 });
        SurfaceMetric metric = SurfaceMetric.of(quad);

        assertEquals(QUAD_CORNERS + 1, metric.edgeLength.length);
        assertEquals(Math.sqrt(2.0), metric.edgeLength[QUAD_CORNERS], CAP_TOLERANCE);
        assertCoversEveryPolygon(quad, metric);
    }

    @Test
    void adoptGraphRingsGivesAQuadCylinderRingAnchors() {
        MeshTopology quads = quadCylinder();
        RingScene scene = sceneShowing(quads);
        RingTool tool = scene.ringTool;
        MeshTopology surface = scene.halfEdgeSurface();
        scene.ringMarksByLabel.put(RING_LABEL, rowLoop(quads, RING_ROW));

        tool.adoptGraphRings(surface);

        assertEquals("", tool.lastError);
        assertNotNull(tool.geodesics, "the geodesic engine was refused on the quad cylinder");
        assertSame(surface, tool.geodesics.mesh);
        assertEquals(1, tool.confirmedRings.size(), tool.lastRow);
        assertEquals(SEGMENTS_AROUND, tool.confirmedRings.get(0).markedEdgeCount);
        float[] anchors = tool.positionsOf(tool.confirmedAuthoredVertexId.get(0));
        assertTrue(anchors.length >= RingTool.COORDINATES_PER_POINT);
        for (int anchor = 2; anchor < anchors.length; anchor += RingTool.COORDINATES_PER_POINT) {
            assertEquals(rowHeight(RING_ROW), anchors[anchor], POSITION_TOLERANCE,
                    "an anchor left the ring's row");
        }
    }

    @Test
    void aSplineRingSavedOnAQuadCylinderReloadsElementExact() throws Exception {
        MeshTopology quads = quadCylinder();
        RingScene scene = sceneShowing(quads);
        RingTool tool = scene.ringTool;
        MeshTopology surface = scene.halfEdgeSurface();
        scene.ringMarksByLabel.put(RING_LABEL, rowLoop(quads, RING_ROW));
        tool.adoptGraphRings(surface);
        int[] authored = tool.confirmedAuthoredVertexId.get(0);
        float[] normal = tool.confirmedBaseNormal.get(0);

        assertToolAndGraphAgree(quads, scene, tool.positionsOf(authored), authored.length,
                normal);
    }

    @Test
    void aTiltedSplineRingOnQuadsMarksOnlyQuadEdgesAndReloadsExact() throws Exception {
        MeshTopology quads = quadCylinder();
        RingScene scene = sceneShowing(quads);
        float[] points = new float[RingTool.COORDINATES_PER_POINT * TILTED_ANCHORS];
        for (int anchor = 0; anchor < TILTED_ANCHORS; anchor++) {
            int segment = anchor * SEGMENTS_AROUND / TILTED_ANCHORS;
            int row = RING_ROW + (anchor == 0 ? -TILT_ROWS : anchor == 2 ? TILT_ROWS : 0);
            int vertexId = row * SEGMENTS_AROUND + segment;
            Vector3f position = new Vector3f();
            quads.vertexPosition(vertexId, position);
            points[RingTool.COORDINATES_PER_POINT * anchor] = position.x;
            points[RingTool.COORDINATES_PER_POINT * anchor + 1] = position.y;
            points[RingTool.COORDINATES_PER_POINT * anchor + 2] = position.z;
        }

        boolean[] written = assertToolAndGraphAgree(quads, scene, points, TILTED_ANCHORS, null);

        int marked = 0;
        for (boolean edge : written) {
            marked += edge ? 1 : 0;
        }
        assertTrue(marked > SEGMENTS_AROUND, "a tilted ring should climb quad edges, marked "
                + marked);
        RingRegions regions = new RingRegions(quads, new String[] { SAVED_LABEL },
                new boolean[][] { written }).build();
        assertEquals(2, regions.regionCount, "the ring should cut the quad cylinder in two");
        assertEquals(quads.faceCount(), regions.regionFaceCount[0] + regions.regionFaceCount[1]);
    }

    @Test
    void aRegionCutFromAQuadCylinderAlongASplineRingIsAClosedSolid() {
        MeshTopology quads = quadCylinder();
        StringBuilder points = new StringBuilder();
        Vector3f position = new Vector3f();
        for (int anchor = 0; anchor < TILTED_ANCHORS; anchor++) {
            int segment = anchor * SEGMENTS_AROUND / TILTED_ANCHORS;
            int row = RING_ROW + (anchor == 0 ? -TILT_ROWS : anchor == 2 ? TILT_ROWS : 0);
            quads.vertexPosition(row * SEGMENTS_AROUND + segment, position);
            points.append(points.length() == 0 ? "" : "; ").append(String.format(Locale.ROOT,
                    "%f,%f,%f", position.x, position.y, position.z));
        }
        MapNodeContext measured = new MapNodeContext(new SurfaceMetricNode())
                .with(SurfaceMetricNode.GEOMETRY, GeometryBundle.ofMesh(quads)).eval();
        GeometryBundle ringed = new MapNodeContext(new SplineRingNode())
                .with(SplineRingNode.GEOMETRY,
                        measured.output(SurfaceMetricNode.GEOMETRY_OUT, GeometryBundle.class))
                .with(SplineRingNode.METRIC,
                        measured.output(SurfaceMetricNode.METRIC, SurfaceMetric.class))
                .with(SplineRingNode.POINTS, points.toString())
                .with(SplineRingNode.LABEL, RING_LABEL)
                .eval().output(SplineRingNode.GEOMETRY_OUT, GeometryBundle.class);
        SurfaceSpline spline = SurfaceSpline.inBundle(ringed, RING_LABEL);
        assertNotNull(spline, "spline_ring left no spline the cut can follow");
        assertSame(ringed.mesh(), spline.mesh);
        int insidePoints = 0;
        for (int point = 0; point < spline.pointFaceId.length; point++) {
            int faceId = spline.pointFaceId[point];
            insidePoints += faceId >= 0 ? 1 : 0;
            assertTrue(faceId < 0 || spline.mesh.faceVertexCount(faceId) > TRIANGLE_CORNERS,
                    "a point inside face " + faceId + ", which is no polygon");
        }
        assertTrue(insidePoints > 0, "a tilted ring crosses quad interiors");
        int facesBefore = ringed.mesh().faceCount();

        MapNodeContext context = new MapNodeContext(new ExtractRingRegionNode())
                .with(ExtractRingRegionNode.GEOMETRY, ringed)
                .with(ExtractRingRegionNode.SELECT, "point 0,0,0")
                .eval();
        String report = context.output(ExtractRingRegionNode.REPORT, String.class);
        MeshTopology piece = context.output(ExtractRingRegionNode.GEOMETRY_OUT,
                GeometryBundle.class).mesh();

        assertTrue(report.contains("cut 1 ring(s) along their splines"), report);
        assertTrue(report.contains("closed=true"), report);
        assertTrue(report.contains("manifold " + MeshBooleanBackend.ACCEPTED_SOLID), report);
        assertFalse(report.contains("problem:"), report);
        assertTrue(report.contains("inside polygons"), report);
        assertTrue(allTriangles(piece), report);
        assertEquals(facesBefore, ringed.mesh().faceCount(), "the quad source changed");
        assertFalse(allTriangles(ringed.mesh()), "the source lost its quads");
    }

    /**
     * Check every intrinsic triangle maps to a live source face and each source polygon of
     * {@code n} corners owns exactly {@code n - 2} of them.
     *
     * @param mesh   the source mesh
     * @param metric its metric, the unflipped intrinsic triangulation
     */
    private static void assertCoversEveryPolygon(MeshTopology mesh, SurfaceMetric metric) {
        Map<Integer, Integer> trianglesByFaceId = new HashMap<>();
        for (int face = 0; face < metric.sourceFaceId.length; face++) {
            int faceId = metric.sourceFaceId[face];
            assertTrue(mesh.hasFace(faceId), "intrinsic face " + face + " maps to no face");
            trianglesByFaceId.merge(faceId, 1, Integer::sum);
        }
        assertEquals(mesh.faceCount(), trianglesByFaceId.size(), "a polygon has no triangle");
        for (int index = 0; index < mesh.faceCount(); index++) {
            int faceId = mesh.faceIdAt(index);
            assertEquals(mesh.faceVertexCount(faceId) - 2, trianglesByFaceId.get(faceId),
                    "face " + faceId + " split into the wrong number of triangles");
        }
    }

    private static boolean allTriangles(MeshTopology mesh) {
        for (int index = 0; index < mesh.faceCount(); index++) {
            if (mesh.faceVertexCount(mesh.faceIdAt(index)) != TRIANGLE_CORNERS) {
                return false;
            }
        }
        return true;
    }

    /**
     * Trace a spline ring in the editing scene and through a saved spline_ring statement on the
     * quad mesh, and check the statement passes the quads through and both mark one closed loop of
     * quad edges with the same fingerprint.
     *
     * @param quads       the shown quad mesh
     * @param scene       editing scene showing {@code quads}
     * @param points      authored anchor positions, packed xyz
     * @param anchorCount anchors in {@code points}
     * @param normal      the ring's base normal, or {@code null}
     * @return the statement's mask over the quad mesh's edge ids
     * @throws Exception when the statement fails to run
     */
    private static boolean[] assertToolAndGraphAgree(MeshTopology quads, RingScene scene,
            float[] points, int anchorCount, float[] normal) throws Exception {
        MeshTopology surface = scene.halfEdgeSurface();
        if (scene.ringTool.geodesics == null) {
            scene.ringTool.geodesics = SurfaceGeodesics.over(SurfaceMetric.of(surface));
        }
        AuthoredSplineRing edited = new AuthoredSplineRing(scene.ringTool.geodesics);
        assertTrue(edited.trace(SurfaceWaypoints.snap(NearestVertex.over(surface), points, anchorCount), anchorCount,
                normal, SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH), edited.failure);
        SurfaceSpline held = SurfaceSpline.of(edited.tracer);
        assertEquals(0, held.unresolvedGaps);
        assertClosedLoopOfQuadEdges(quads, held.markedByEdgeId, "the tool's ring");

        String measuredSurface = RingDslWriter.METRIC_STATEMENT_ID + "."
                + RingDslWriter.DEFAULT_UPSTREAM_PORT;
        String statement = RingDslWriter.wireRingInputs(RingDslWriter.METRIC_STATEMENT_ID
                + " = " + RingDslWriter.SURFACE_METRIC_NODE + "(geometry=" + FIXTURE + ")"
                + RingDslWriter.LINE_BREAK + RingDslWriter.splineStatement(SAVED_LABEL,
                        measuredSurface, points, anchorCount, normal));
        NodeGraphRuntime runtime = NodeGraphRuntime.fromSource(statement);
        Object result = runtime.executeGraphResult(runtime.statements, SAVED_LABEL,
                RingDslWriter.DEFAULT_UPSTREAM_PORT,
                Map.of(measuredSurface, GeometryBundle.ofMesh(quads)));
        assertTrue(result instanceof GeometryBundle, "the statement left no bundle");
        MeshTopology reloaded = ((GeometryBundle) result).mesh();

        assertEquals(quads.faceCount(), reloaded.faceCount(), "spline_ring changed the faces");
        for (int index = 0; index < quads.faceCount(); index++) {
            assertEquals(quads.faceVertexCount(quads.faceIdAt(index)),
                    reloaded.faceVertexCount(reloaded.faceIdAt(index)),
                    "spline_ring changed the arity of face " + index);
        }
        assertEquals(quads.edgeCount(), reloaded.edgeCount(), "spline_ring added edges");
        assertTrue(((GeometryBundle) result).slots().get(EdgeMarks.SLOT) instanceof Map<?, ?>,
                statement);
        boolean[] written = (boolean[]) ((Map<?, ?>) ((GeometryBundle) result).slots()
                .get(EdgeMarks.SLOT)).get(SAVED_LABEL);
        assertNotNull(written, statement);
        assertClosedLoopOfQuadEdges(reloaded, written, "the saved ring");
        assertEquals(EdgeMarks.fingerprint(surface, held.markedByEdgeId),
                EdgeMarks.fingerprint(reloaded, written),
                "the saved ring reloaded onto different edges: " + statement);
        assertEquals(EdgeMarks.fingerprint(surface, held.markedByEdgeId),
                EdgeMarks.fingerprint(quads, held.markedByEdgeId),
                "the fingerprint depends on the mesh it is measured on");
        return written;
    }

    /**
     * Check a mask marks only edges the quad mesh has, every marked vertex on exactly two of
     * them, all in one loop.
     *
     * @param quads the quad mesh the mask indexes
     * @param marks edge-id-indexed mask
     * @param what  the mask's name for failure messages
     */
    private static void assertClosedLoopOfQuadEdges(MeshTopology quads, boolean[] marks,
            String what) {
        int marked = 0;
        Map<Integer, Integer> degreeByVertexId = new HashMap<>();
        for (int edgeId = 0; edgeId < marks.length; edgeId++) {
            if (!marks[edgeId]) {
                continue;
            }
            assertTrue(quads.hasEdge(edgeId), what + " marks edge " + edgeId
                    + ", which is no edge of the quad mesh");
            marked++;
            int halfEdge = quads.edgeHalfEdge(edgeId);
            degreeByVertexId.merge(quads.halfEdgeVertex(halfEdge), 1, Integer::sum);
            degreeByVertexId.merge(quads.halfEdgeEndVertex(halfEdge), 1, Integer::sum);
        }
        assertTrue(marked >= RingTool.COORDINATES_PER_POINT, what + " marks " + marked + " edges");
        for (Map.Entry<Integer, Integer> vertex : degreeByVertexId.entrySet()) {
            assertEquals(2, vertex.getValue(), what + ": vertex " + vertex.getKey()
                    + " is on " + vertex.getValue() + " marked edges");
        }
        assertEquals(marked, RingTool.orderedLoop(quads, marks).length,
                what + " is not one loop");
    }

    /**
     * An editing scene showing a fixed mesh, with no window or graph behind it.
     *
     * @param shown the mesh the scene shows
     * @return the scene
     */
    private static RingScene sceneShowing(MeshTopology shown) {
        return new RingScene() {
            @Override
            public MeshTopology getMesh() {
                return shown;
            }

            @Override
            public MeshTopology halfEdgeSurface() {
                return shown;
            }
        };
    }

    /**
     * The horizontal edge loop of one vertex row, as a mask over the quad mesh's edge ids.
     *
     * @param mesh the quad cylinder
     * @param row  vertex row the loop runs along
     * @return the edge-id-indexed mask
     */
    private static boolean[] rowLoop(MeshTopology mesh, int row) {
        int bound = 0;
        for (int index = 0; index < mesh.edgeCount(); index++) {
            bound = Math.max(bound, mesh.edgeIdAt(index) + 1);
        }
        boolean[] marks = new boolean[bound];
        for (int segment = 0; segment < SEGMENTS_AROUND; segment++) {
            int vertexId = row * SEGMENTS_AROUND + segment;
            int nextId = row * SEGMENTS_AROUND + (segment + 1) % SEGMENTS_AROUND;
            marks[mesh.edgeBetween(vertexId, nextId)] = true;
        }
        return marks;
    }

    private static float rowHeight(int row) {
        return CYLINDER_HEIGHT * row / SIDE_ROWS;
    }

    /**
     * A cylinder of quads around the z axis, closed by one polygon at each end.
     *
     * @return the half-edge mesh, quads on the sides and two polygon caps
     */
    private static MeshTopology quadCylinder() {
        float[] positions = new float[RingTool.COORDINATES_PER_POINT * (SIDE_ROWS + 1)
                * SEGMENTS_AROUND];
        for (int row = 0; row <= SIDE_ROWS; row++) {
            for (int segment = 0; segment < SEGMENTS_AROUND; segment++) {
                double angle = 2 * Math.PI * segment / SEGMENTS_AROUND;
                int base = RingTool.COORDINATES_PER_POINT * (row * SEGMENTS_AROUND + segment);
                positions[base] = (float) Math.cos(angle);
                positions[base + 1] = (float) Math.sin(angle);
                positions[base + 2] = rowHeight(row);
            }
        }
        int sideQuads = SIDE_ROWS * SEGMENTS_AROUND;
        int[] cornerCounts = new int[sideQuads + 2];
        int[] corners = new int[QUAD_CORNERS * sideQuads + 2 * SEGMENTS_AROUND];
        int cursor = 0;
        int face = 0;
        for (int row = 0; row < SIDE_ROWS; row++) {
            for (int segment = 0; segment < SEGMENTS_AROUND; segment++) {
                int next = (segment + 1) % SEGMENTS_AROUND;
                corners[cursor++] = row * SEGMENTS_AROUND + segment;
                corners[cursor++] = row * SEGMENTS_AROUND + next;
                corners[cursor++] = (row + 1) * SEGMENTS_AROUND + next;
                corners[cursor++] = (row + 1) * SEGMENTS_AROUND + segment;
                cornerCounts[face++] = QUAD_CORNERS;
            }
        }
        for (int segment = SEGMENTS_AROUND - 1; segment >= 0; segment--) {
            corners[cursor++] = segment;
        }
        cornerCounts[face++] = SEGMENTS_AROUND;
        for (int segment = 0; segment < SEGMENTS_AROUND; segment++) {
            corners[cursor++] = SIDE_ROWS * SEGMENTS_AROUND + segment;
        }
        cornerCounts[face] = SEGMENTS_AROUND;
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.bulkAllocateMixed(positions, cornerCounts, corners);
        mesh.computeNormals();
        return mesh;
    }

    private static int gridVertexId(int column, int row) {
        return row * (GRID_COLUMNS + 1) + column;
    }
}
