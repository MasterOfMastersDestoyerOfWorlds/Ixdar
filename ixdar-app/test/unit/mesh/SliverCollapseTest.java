package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.bundle.RingBundle;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceMetric;
import ixdar.geometry.mesh.data.paths.query.SurfaceCreases;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.spline.SurfaceWaypoints;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.RingRegions;
import ixdar.geometry.mesh.data.rings.RingSegmentMode;
import ixdar.geometry.mesh.data.rings.SliverCollapse;
import ixdar.nodegraph.nodes.data.SurfaceCreasesNode;
import ixdar.nodegraph.nodes.selection.SplineCutNode;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.tools.ring.RingDslWriter;

/**
 * CRAW-89: rings on a sphere pinching lens slivers. A keeps each run on one ring and deletes the
 * other, each stretch of it outside the runs a spline_cut bisector on the ring's own route, trimmed
 * where it meets the keeper; saved and reloaded, no sliver is left.
 */
class SliverCollapseTest {

    private static final String SPHERE = "ball = icosphere(radius=1.0, subdivisions=4)\n"
            + "surface = surface_metric(geometry=ball.mesh)\n";

    private static final String SURFACE = "surface";

    private static final String METRIC = "metric";

    private static final String SURFACE_GEOMETRY = "surface.geometry";

    private static final String[] LABELS = { "ring_00", "ring_01" };

    private static final String BISECTOR_PREFIX = "ring_cut_";

    private static final String LINE_BREAK = "\n";

    private static final double GEODESIC_TILT_RADIANS = Math.toRadians(6.0);

    // The crease ring runs along edges, a cheapest path, which leaves its lenses wider.
    private static final double CREASE_TILT_RADIANS = Math.toRadians(3.0);

    // A circle cut from the sphere by the plane y = SLOPE z + OFFSET: north of the equator but
    // for a shallow dip below it round z = -1, the one lens it pinches against the equator.
    private static final float SLOPE = 0.3f;

    private static final float OFFSET = 0.2f;

    private static final int CIRCLE_ANCHORS = 8;

    private static final float[] EQUATOR = { 1f, 0f, 0f, 0f, 0f, 1f, -1f, 0f, 0f, 0f, 0f, -1f };

    private static final float[] EQUATOR_NORMAL = { 0f, 1f, 0f };

    private static final RingSegmentMode[] GEODESIC = { RingSegmentMode.GEODESIC,
        RingSegmentMode.GEODESIC };

    private static final int TIP_ROW = 4;

    private static final int FINE_SEGMENTS = 48;

    private static final int FINE_ROWS = 12;

    private static final int LENS_END = 6;

    // A stretch of a latitude circle between two meridians this far apart in longitude, which a
    // geodesic between its ends alone misses by several edges.
    private static final double STRETCH_DEGREES = 150.0;

    private static final double LATITUDE_DEGREES = 45.0;

    private static final int LATITUDE_ANCHORS = 12;

    @Test
    void aLensBecomesABisectorOnTheKeeperThatReloadsWithNoSliver() throws Exception {
        float[][] points = { EQUATOR, dippingCircle() };
        float[][] normals = { EQUATOR_NORMAL, dippingNormal() };
        Collapsed result = collapse(points, normals, GEODESIC);
        int dropped = result.collapse.ringDropped[0] ? 0 : 1;
        int keeper = 1 - dropped;
        assertTrue(result.collapse.ringDropped[dropped], result.collapse.refusals.toString());
        assertFalse(result.collapse.ringDropped[keeper]);
        assertEquals(1, result.collapse.bisectorSplines.size(),
                "one stretch of the dipping ring lies outside its one lens");
        assertEquals(dropped, (int) result.collapse.bisectorSource.get(0));
        assertArrayEquals(new int[] { keeper, keeper }, result.collapse.bisectorWalls.get(0));

        RingRegions reloaded = reload(result, points, normals, GEODESIC);

        // The regions as A leaves them: the kept ring and the new bisector.
        List<String> labels = new ArrayList<>(List.of(LABELS[keeper], BISECTOR_PREFIX + 0));
        List<boolean[]> marks = new ArrayList<>(List.of(result.marks[keeper],
                result.collapse.bisectorSplines.get(0).marks.markedByEdgeId));
        RingRegions afterA = new RingRegions(result.bundle.mesh(), labels.toArray(new String[0]),
                marks.toArray(new boolean[0][])).build();
        assertEquals(afterA.regionCount, reloaded.regionCount);
        int[] before = afterA.regionFaceCount.clone();
        int[] after = reloaded.regionFaceCount.clone();
        Arrays.sort(before);
        Arrays.sort(after);
        assertArrayEquals(before, after, "the reload does not reproduce the regions after A");
    }

    @Test
    void aDoubleLensDeletesTheDroppedRingWithNoBisector() throws Exception {
        Collapsed result = collapse(tiltedGreatCircles(GEODESIC_TILT_RADIANS),
                tiltedNormals(GEODESIC_TILT_RADIANS), GEODESIC);
        assertTrue(result.collapse.ringDropped[0] || result.collapse.ringDropped[1],
                result.collapse.refusals.toString());
        assertTrue(result.collapse.bisectorSplines.isEmpty(),
                "every stretch of the dropped ring runs along the keeper");
        reload(result, tiltedGreatCircles(GEODESIC_TILT_RADIANS),
                tiltedNormals(GEODESIC_TILT_RADIANS), GEODESIC);
    }

    @Test
    void theCreaseRingKeepsTheRunAgainstAGeodesicRing() throws Exception {
        RingSegmentMode[] modes = { RingSegmentMode.GEODESIC, RingSegmentMode.CREASE };
        Collapsed result = collapse(tiltedGreatCircles(CREASE_TILT_RADIANS),
                tiltedNormals(CREASE_TILT_RADIANS), modes);
        assertTrue(result.collapse.ringDropped[0], result.collapse.refusals.toString());
        assertFalse(result.collapse.ringDropped[1]);
        reload(result, tiltedGreatCircles(CREASE_TILT_RADIANS),
                tiltedNormals(CREASE_TILT_RADIANS), modes);
    }

    @Test
    void theBisectorMarksTheDroppedRingsOwnEdgesOnItsStretch() throws Exception {
        Collapsed result = collapse(new float[][] { EQUATOR, dippingCircle() },
                new float[][] { EQUATOR_NORMAL, dippingNormal() }, GEODESIC);
        assertTrue(result.collapse.routeDifferences.isEmpty(),
                result.collapse.routeDifferences.toString());
        int dropped = result.collapse.bisectorSource.get(0);
        int keeper = 1 - dropped;
        // Every edge of the bisector is an edge of the old ring, and every edge of the old ring
        // the keeper does not wall off with the lens is one of the bisector's.
        boolean[] bisector = result.collapse.bisectorSplines.get(0).marks.markedByEdgeId;
        for (int edgeId : result.collapse.bisectorSplines.get(0).marks.markedEdgeIds) {
            assertTrue(result.marks[dropped][edgeId], "edge " + edgeId + " is off the old ring");
        }
        RingRegions before = result.before;
        MeshTopology mesh = result.bundle.mesh();
        for (int edgeId = 0; edgeId < result.marks[dropped].length; edgeId++) {
            if (!result.marks[dropped][edgeId] || result.marks[keeper][edgeId]) {
                continue;
            }
            boolean bordersLens = false;
            for (int side = 0; side < 2; side++) {
                int faceId = mesh.edgeFace(edgeId, side);
                int region = faceId == MeshTopology.NONE ? MeshTopology.NONE
                        : before.regionOfFace(faceId);
                bordersLens |= region != MeshTopology.NONE && before.isSliver(region);
            }
            assertTrue(bordersLens || bisector[edgeId], "old ring edge " + edgeId
                    + " outside the lens is not on the bisector");
        }
        // Its anchors are the dropped ring's own spline anchors, in the ring's order.
        SurfaceSpline ring = SurfaceSpline.inBundle(result.bundle, LABELS[dropped]);
        int[] carried = result.collapse.bisectorAnchors.get(0);
        int at = -1;
        for (int anchor = 0; anchor < ring.anchorCount && at < 0; anchor++) {
            at = ring.anchorVertexId[anchor] == carried[0] ? anchor : at;
        }
        assertTrue(at >= 0, "the bisector's first anchor is not one of the ring's");
        for (int slot = 0; slot < carried.length; slot++) {
            assertEquals(ring.anchorVertexId[(at + slot) % ring.anchorCount], carried[slot],
                    Arrays.toString(carried));
        }
    }

    @Test
    void aRingStretchMarksTheSameEdgesAsTheRing() throws Exception {
        // Two meridians and a latitude circle; the circle's stretch between them becomes a
        // bisector through its own anchors.
        float height = (float) Math.sin(Math.toRadians(LATITUDE_DEGREES));
        float radius = (float) Math.cos(Math.toRadians(LATITUDE_DEGREES));
        float[] circle = new float[SurfaceWaypoints.COORDINATES_PER_WAYPOINT * LATITUDE_ANCHORS];
        for (int anchor = 0; anchor < LATITUDE_ANCHORS; anchor++) {
            double angle = 2 * Math.PI * (anchor + 0.5) / LATITUDE_ANCHORS;
            circle[3 * anchor] = (float) (radius * Math.cos(angle));
            circle[3 * anchor + 1] = height;
            circle[3 * anchor + 2] = (float) (radius * Math.sin(angle));
        }
        float[][] points = { meridian(0.0), meridian(STRETCH_DEGREES), circle };
        String[] labels = { LABELS[0], LABELS[1], "ring_02" };
        StringBuilder source = new StringBuilder(SPHERE);
        String input = SURFACE_GEOMETRY;
        for (int ring = 0; ring < labels.length; ring++) {
            source.append(RingDslWriter.splineStatement(labels[ring], input, points[ring],
                    points[ring].length / SurfaceWaypoints.COORDINATES_PER_WAYPOINT, null))
                    .append(LINE_BREAK);
            input = labels[ring] + "." + RingDslWriter.DEFAULT_UPSTREAM_PORT;
        }
        NodeGraphRuntime runtime = NodeGraphRuntime.fromSource(
                RingDslWriter.wireRingInputs(source.toString()));
        GeometryBundle bundle = (GeometryBundle) runtime.executeGraphResult(labels[2],
                RingDslWriter.DEFAULT_UPSTREAM_PORT);
        SurfaceMetric metric = (SurfaceMetric) runtime.node(SURFACE).getOutput(METRIC);
        MeshTopology mesh = bundle.mesh();
        boolean[][] marks = new boolean[labels.length][];
        for (int ring = 0; ring < labels.length; ring++) {
            marks[ring] = EdgeMarks.bools(bundle, labels[ring]);
        }
        // The circle's loop from where it meets the first meridian, the short way, to the second.
        int[] loop = EdgeMarks.orderedLoop(mesh, marks[2]);
        int first = -1;
        int second = -1;
        for (int index = 0; index < loop.length; index++) {
            first = first < 0 && EdgeMarks.markedAt(mesh, loop[index], marks[0], null) > 0 ? index
                    : first;
            second = second < 0 && EdgeMarks.markedAt(mesh, loop[index], marks[1], null) > 0
                    ? index : second;
        }
        assertTrue(first >= 0 && second >= 0, "the circle does not meet both meridians");
        int span = Math.floorMod(second - first, loop.length);
        boolean forward = span < loop.length / 2;
        int length = forward ? span : loop.length - span;
        int[] path = new int[length + 1];
        for (int step = 0; step <= length; step++) {
            path[step] = loop[Math.floorMod(forward ? first + step : first - step, loop.length)];
        }
        // The circle's spline anchors, authored and supporting, strictly inside the stretch, in
        // ring order, and one more either side of it past the meridians.
        SurfaceSpline circleSpline = SurfaceSpline.inBundle(bundle, labels[2]);
        int count = circleSpline.anchorCount;
        int[] onPath = new int[count];
        int firstInner = -1;
        for (int anchor = 0; anchor < count; anchor++) {
            onPath[anchor] = -1;
            for (int step = 1; step < length; step++) {
                onPath[anchor] = path[step] == circleSpline.anchorVertexId[anchor] ? step
                        : onPath[anchor];
            }
            boolean opens = onPath[anchor] > 0
                    && onPath[Math.floorMod(anchor - 1, count)] <= 0;
            firstInner = opens && firstInner < 0 ? anchor : firstInner;
        }
        assertTrue(firstInner >= 0, "no anchor of the circle lies on the stretch");
        List<Integer> carried = new ArrayList<>(List.of(Math.floorMod(firstInner - 1, count)));
        for (int anchor = firstInner; onPath[anchor] > 0; anchor = (anchor + 1) % count) {
            carried.add(anchor);
        }
        carried.add((carried.get(carried.size() - 1) + 1) % count);
        int[] anchors = new int[carried.size()];
        for (int slot = 0; slot < anchors.length; slot++) {
            anchors[slot] = circleSpline.anchorVertexId[carried.get(slot)];
        }
        // The anchors may run along the path or against it, which says which meridian it starts on:
        // the anchor before the stretch lies past the meridian nearer it. One inner anchor alone
        // cannot say which way the ring runs.
        Vector3f before = mesh.vertexPosition(anchors[0], new Vector3f());
        boolean alongPath = before.distance(mesh.vertexPosition(path[0], new Vector3f()))
                < before.distance(mesh.vertexPosition(path[length], new Vector3f()));
        int[] frame = { circleSpline.anchorVertexId[Math.floorMod(carried.get(0) - 1, count)],
            alongPath ? path[0] : path[length], alongPath ? path[length] : path[0],
            circleSpline.anchorVertexId[(carried.get(carried.size() - 1) + 1) % count] };

        SurfaceSpline stretch = SurfaceSpline.ringStretch(SurfaceGeodesics.over(metric), anchors,
                frame, alongPath ? marks[0] : marks[1], alongPath ? marks[1] : marks[0]);

        boolean[] expected = new boolean[marks[2].length];
        for (int step = 0; step < length; step++) {
            expected[mesh.edgeBetween(path[step], path[step + 1])] = true;
        }
        boolean[] actual = Arrays.copyOf(stretch.marks.markedByEdgeId, expected.length);
        assertArrayEquals(expected, actual, "the stretch marks " + stretch.marks.markedEdgeCount
                + " edges where the circle marked " + length);
        assertEquals(length, stretch.marks.markedEdgeCount);
    }

    @Test
    void aLensWhoseTipIsAnEdgeBothRingsShareCollapses() throws Exception {
        // On the test cylinder, ring_00 runs along row 4 from segment 2 the long way round to
        // segment 0, up the diagonal to row 5 and back to (5, 2), then down to (4, 2). ring_01 runs
        // row 5 from segment 2 to 6, down through (4, 6) to row 3, back along row 3 to segment 2
        // and up to (4, 2) and (5, 2). They cross at (4, 6), and over the edge (4, 2)-(5, 2) both
        // mark, which is the tip of the lens between rows 4 and 5.
        int around = FINE_SEGMENTS;
        int rows = FINE_ROWS;
        float[] positions = new float[3 * (rows + 1) * around];
        for (int row = 0; row <= rows; row++) {
            for (int segment = 0; segment < around; segment++) {
                double angle = 2 * Math.PI * segment / around;
                int vertex = row * around + segment;
                positions[3 * vertex] = (float) Math.cos(angle);
                positions[3 * vertex + 1] = (float) Math.sin(angle);
                positions[3 * vertex + 2] = (float) row / around * 2;
            }
        }
        int[] triangles = new int[3 * 2 * rows * around];
        int cursor = 0;
        for (int row = 0; row < rows; row++) {
            for (int segment = 0; segment < around; segment++) {
                int lowLeft = row * around + segment;
                int lowRight = row * around + (segment + 1) % around;
                int[] faces = { lowLeft, lowRight, lowRight + around, lowLeft, lowRight + around,
                    lowLeft + around };
                System.arraycopy(faces, 0, triangles, cursor, faces.length);
                cursor += faces.length;
            }
        }
        MeshTopology mesh = HalfEdgeMeshOps.buildFromIndexedMesh(positions, triangles);
        List<Integer> first = new ArrayList<>();
        for (int segment = 2; segment != 1; segment = (segment + 1) % around) {
            first.add(TIP_ROW * around + segment);
        }
        first.add((TIP_ROW + 1) * around + 1);
        first.add((TIP_ROW + 1) * around + 2);
        List<Integer> second = new ArrayList<>();
        for (int segment = 2; segment <= LENS_END; segment++) {
            second.add((TIP_ROW + 1) * around + segment);
        }
        second.add(TIP_ROW * around + LENS_END);
        for (int segment = LENS_END; segment >= 2; segment--) {
            second.add((TIP_ROW - 1) * around + segment);
        }
        second.add(TIP_ROW * around + 2);
        boolean[][] marks = { loopMarks(mesh, first), loopMarks(mesh, second) };
        RingRegions before = new RingRegions(mesh, LABELS, marks).build();
        int slivers = slivers(before);
        assertEquals(2, slivers, String.join(LINE_BREAK, before.reportLines()));
        // No authored anchors, so neither ring is preferred. Each ring's spline runs through every
        // vertex of its loop, one edge a segment, so a stretch of it traces the loop's own edges.
        int[][] anchors = { new int[0], new int[0] };
        int[][] splineAnchors = { vertexIds(mesh, first), vertexIds(mesh, second) };
        SliverCollapse collapse = new SliverCollapse(SurfaceGeodesics.over(SurfaceMetric.of(mesh)),
                null);
        boolean[] every = { true, true };

        int removed = collapse.collapse(before, anchors, splineAnchors, GEODESIC, every,
                new int[2][], new int[2][]);

        assertEquals(slivers, removed, collapse.refusals.toString());
        assertTrue(collapse.routeDifferences.isEmpty(), collapse.routeDifferences.toString());
        int dropped = collapse.ringDropped[0] ? 0 : 1;
        List<String> labels = new ArrayList<>(List.of(LABELS[1 - dropped]));
        List<boolean[]> standing = new ArrayList<>(List.of(marks[1 - dropped]));
        for (int bisector = 0; bisector < collapse.bisectorSplines.size(); bisector++) {
            labels.add(BISECTOR_PREFIX + bisector);
            standing.add(collapse.bisectorSplines.get(bisector).marks.markedByEdgeId);
        }
        RingRegions after = new RingRegions(mesh, labels.toArray(new String[0]),
                standing.toArray(new boolean[0][])).build();
        assertEquals(0, slivers(after), String.join(LINE_BREAK, after.reportLines()));
    }

    /** One collapse: the bundle the rings were traced on, their marks, regions and the result. */
    private static final class Collapsed {
        GeometryBundle bundle;
        SurfaceMetric metric;
        boolean[][] marks;
        int[][] anchors;
        RingRegions before;
        SliverCollapse collapse;
    }

    /**
     * Trace the rings and collapse every sliver they pinch.
     *
     * @param points  each ring's anchors, packed xyz
     * @param normals each ring's base normal, or {@code null}
     * @param modes   each ring's segment mode
     * @return the collapse and what it ran on
     */
    private static Collapsed collapse(float[][] points, float[][] normals, RingSegmentMode[] modes)
            throws Exception {
        StringBuilder source = new StringBuilder(SPHERE);
        String input = SURFACE_GEOMETRY;
        for (int ring = 0; ring < LABELS.length; ring++) {
            source.append(ringStatement(ring, input, points, normals, modes)).append(LINE_BREAK);
            input = LABELS[ring] + "." + RingDslWriter.DEFAULT_UPSTREAM_PORT;
        }
        NodeGraphRuntime pinched = NodeGraphRuntime.fromSource(
                RingDslWriter.wireRingInputs(source.toString()));
        Collapsed result = new Collapsed();
        result.bundle = (GeometryBundle) pinched.executeGraphResult(LABELS[1],
                RingDslWriter.DEFAULT_UPSTREAM_PORT);
        result.metric = (SurfaceMetric) pinched.node(SURFACE).getOutput(METRIC);
        boolean crease = Arrays.asList(modes).contains(RingSegmentMode.CREASE);
        SurfaceCreases creases = crease ? (SurfaceCreases) pinched.node(
                RingDslWriter.CREASES_STATEMENT_ID).getOutput(SurfaceCreasesNode.CREASES.name)
                : null;
        result.marks = new boolean[LABELS.length][];
        result.anchors = new int[LABELS.length][];
        int[][] splineAnchors = new int[LABELS.length][];
        for (int ring = 0; ring < LABELS.length; ring++) {
            result.marks[ring] = EdgeMarks.bools(result.bundle, LABELS[ring]);
            SurfaceSpline spline = SurfaceSpline.inBundle(result.bundle, LABELS[ring]);
            assertNotNull(spline, LABELS[ring] + " left no spline");
            splineAnchors[ring] = Arrays.copyOf(spline.anchorVertexId, spline.anchorCount);
            result.anchors[ring] = new int[spline.authoredCount()];
            for (int anchor = 0, held = 0; anchor < spline.anchorCount; anchor++) {
                if (spline.anchorAuthored[anchor]) {
                    result.anchors[ring][held++] = spline.anchorVertexId[anchor];
                }
            }
        }
        result.before = new RingRegions(result.bundle.mesh(), LABELS, result.marks).build();
        int sliversBefore = slivers(result.before);
        assertTrue(sliversBefore > 0, "the rings pinch no sliver: "
                + String.join(LINE_BREAK, result.before.reportLines()));
        result.collapse = new SliverCollapse(SurfaceGeodesics.over(result.metric), creases);
        boolean[] every = { true, true };
        int removed = result.collapse.collapse(result.before, result.anchors, splineAnchors, modes,
                every, new int[LABELS.length][], new int[LABELS.length][]);
        assertEquals(sliversBefore, removed, result.collapse.refusals.toString());
        assertTrue(result.collapse.routeDifferences.isEmpty(),
                result.collapse.routeDifferences.toString());
        return result;
    }

    /**
     * Write the kept rings as they were and each new bisector as a spline_cut after them, the
     * dropped ring's statement gone, reload them, and check no sliver is left.
     *
     * @param result  the collapse to write
     * @param points  each ring's anchors as the statements hold them
     * @param normals each ring's base normal
     * @param modes   each ring's mode
     * @return the reloaded regions
     */
    private static RingRegions reload(Collapsed result, float[][] points, float[][] normals,
            RingSegmentMode[] modes) throws Exception {
        String saved = SPHERE;
        String input = SURFACE_GEOMETRY;
        String last = null;
        List<String> labels = new ArrayList<>();
        for (int ring = 0; ring < LABELS.length; ring++) {
            if (!result.collapse.ringDropped[ring]) {
                saved += ringStatement(ring, input, points, normals, modes) + LINE_BREAK;
                input = LABELS[ring] + "." + RingDslWriter.DEFAULT_UPSTREAM_PORT;
                last = LABELS[ring];
                labels.add(last);
            }
        }
        for (int bisector = 0; bisector < result.collapse.bisectorSplines.size(); bisector++) {
            int[] anchors = result.collapse.bisectorAnchors.get(bisector);
            String[] ends = new String[2];
            for (int end = 0; end < 2; end++) {
                int wall = result.collapse.bisectorWalls.get(bisector)[end];
                ends[end] = wall < LABELS.length ? LABELS[wall]
                        : BISECTOR_PREFIX + (wall - LABELS.length);
            }
            String id = BISECTOR_PREFIX + bisector;
            saved = RingDslWriter.appendBisector(saved, id,
                    SurfaceWaypoints.resolvingPoints(result.metric.nearestVertex, anchors,
                            anchors.length), anchors.length, ends,
                    SurfaceWaypoints.resolvingPoints(result.metric.nearestVertex,
                            result.collapse.bisectorStretches.get(bisector),
                            SplineCutNode.STRETCH_POINTS));
            last = id;
            labels.add(id);
        }
        NodeGraphRuntime reloading = NodeGraphRuntime.fromSource(RingDslWriter.wireRingInputs(saved));
        GeometryBundle reloaded = (GeometryBundle) reloading.executeGraphResult(last,
                RingDslWriter.DEFAULT_UPSTREAM_PORT);
        boolean[][] marks = new boolean[labels.size()][];
        for (int index = 0; index < marks.length; index++) {
            marks[index] = EdgeMarks.bools(reloaded, labels.get(index));
        }
        RingRegions after = new RingRegions(reloaded.mesh(), labels.toArray(new String[0]), marks)
                .build();
        assertEquals(0, slivers(after), saved + LINE_BREAK
                + String.join(LINE_BREAK, after.reportLines()));
        return after;
    }

    /**
     * One ring's spline_ring statement, its mode written in.
     *
     * @param ring    the ring
     * @param input   reference it reads its geometry from
     * @param points  each ring's anchors
     * @param normals each ring's normal
     * @param modes   each ring's mode
     * @return the statement
     */
    private static String ringStatement(int ring, String input, float[][] points,
            float[][] normals, RingSegmentMode[] modes) {
        String statement = RingDslWriter.splineStatement(LABELS[ring], input, points[ring],
                points[ring].length / SurfaceWaypoints.COORDINATES_PER_WAYPOINT, normals[ring]);
        return RingDslWriter.withMode(statement, LABELS[ring], modes[ring]);
    }

    /**
     * A loop's vertex ids, the anchors of a spline through every vertex of it.
     *
     * @param mesh the surface
     * @param loop dense vertex indices in walking order
     * @return the vertex ids in the same order
     */
    private static int[] vertexIds(MeshTopology mesh, List<Integer> loop) {
        int[] vertexIds = new int[loop.size()];
        for (int index = 0; index < vertexIds.length; index++) {
            vertexIds[index] = mesh.vertexIdAt(loop.get(index));
        }
        return vertexIds;
    }

    /**
     * A closed vertex loop's edges, as an edge-id-indexed mask.
     *
     * @param mesh the surface
     * @param loop dense vertex indices in walking order
     * @return the mask
     */
    private static boolean[] loopMarks(MeshTopology mesh, List<Integer> loop) {
        boolean[] marks = new boolean[RingBundle.edgeIdCeiling(mesh)];
        for (int index = 0; index < loop.size(); index++) {
            int edgeId = mesh.edgeBetween(mesh.vertexIdAt(loop.get(index)),
                    mesh.vertexIdAt(loop.get((index + 1) % loop.size())));
            assertTrue(edgeId != MeshTopology.NONE, "no edge between loop vertices " + index);
            marks[edgeId] = true;
        }
        return marks;
    }

    /**
     * The equator and a great circle tilted from it about the x axis.
     *
     * @param tilt the tilt in radians
     * @return both rings' anchors
     */
    private static float[][] tiltedGreatCircles(double tilt) {
        float sine = (float) Math.sin(tilt);
        float cosine = (float) Math.cos(tilt);
        return new float[][] { EQUATOR,
            { 1f, 0f, 0f, 0f, sine, cosine, -1f, 0f, 0f, 0f, -sine, -cosine } };
    }

    /**
     * The normals of {@link #tiltedGreatCircles}.
     *
     * @param tilt the tilt in radians
     * @return both rings' normals
     */
    private static float[][] tiltedNormals(double tilt) {
        float sine = (float) Math.sin(tilt);
        float cosine = (float) Math.cos(tilt);
        return new float[][] { EQUATOR_NORMAL, { 0f, cosine, -sine } };
    }

    /**
     * Four points of the meridian great circle at a longitude, through both poles.
     *
     * @param longitudeDegrees the meridian's longitude
     * @return its anchors, packed xyz
     */
    private static float[] meridian(double longitudeDegrees) {
        float x = (float) Math.cos(Math.toRadians(longitudeDegrees));
        float z = (float) Math.sin(Math.toRadians(longitudeDegrees));
        return new float[] { x, 0f, z, 0f, 1f, 0f, -x, 0f, -z, 0f, -1f, 0f };
    }

    /** Evenly spaced points round the circle of the plane y = SLOPE z + OFFSET on the sphere. */
    private static float[] dippingCircle() {
        Vector3f normal = new Vector3f(dippingNormal());
        float distance = OFFSET / new Vector3f(0f, 1f, -SLOPE).length();
        float radius = (float) Math.sqrt(1.0 - distance * distance);
        Vector3f across = new Vector3f(1f, 0f, 0f);
        Vector3f down = new Vector3f(normal).cross(across);
        float[] points = new float[SurfaceWaypoints.COORDINATES_PER_WAYPOINT * CIRCLE_ANCHORS];
        for (int anchor = 0; anchor < CIRCLE_ANCHORS; anchor++) {
            double angle = 2 * Math.PI * anchor / CIRCLE_ANCHORS;
            Vector3f point = new Vector3f(normal).mul(distance)
                    .add(new Vector3f(across).mul((float) (radius * Math.cos(angle))))
                    .add(new Vector3f(down).mul((float) (radius * Math.sin(angle))));
            int base = SurfaceWaypoints.COORDINATES_PER_WAYPOINT * anchor;
            points[base] = point.x;
            points[base + 1] = point.y;
            points[base + 2] = point.z;
        }
        return points;
    }

    /** The unit normal of the plane {@link #dippingCircle} lies in. */
    private static float[] dippingNormal() {
        Vector3f normal = new Vector3f(0f, 1f, -SLOPE).normalize();
        return new float[] { normal.x, normal.y, normal.z };
    }

    private static int slivers(RingRegions regions) {
        int slivers = 0;
        for (int region = 0; region < regions.regionCount; region++) {
            slivers += regions.isSliver(region) ? 1 : 0;
        }
        return slivers;
    }
}
