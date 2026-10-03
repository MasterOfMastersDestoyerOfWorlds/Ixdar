package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.csg.MeshBooleanBackend;
import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.RingBundle;
import ixdar.geometry.mesh.data.RingRegionExtraction;
import ixdar.geometry.mesh.data.RingRegions;
import ixdar.geometry.mesh.data.paths.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.TracedSurfacePath;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.nodes.geometry.MeshBooleanNode;
import ixdar.geometry.mesh.nodes.selection.ExtractRingRegionNode;
import ixdar.platform.Platforms;

/**
 * Region extraction on a procedural capped cylinder: smooth rings off the edge lines cut a closed
 * middle whose boundary lies on the splines, a crossing beside a vertex snaps to it, and the node's
 * pieces union through mesh_boolean.
 */
class RingRegionExtractionTest {

    private static final int AROUND = 24;

    private static final int ROWS = 16;

    private static final float HEIGHT = 4f;

    private static final float ROW_SPACING = HEIGHT / ROWS;

    private static final String[] LABELS = { "ring_low", "ring_high" };

    private static final float LOW_RING_HEIGHT = 1.1f;

    private static final float HIGH_RING_HEIGHT = 2.9f;

    private static final float RING_TILT = 0.2f;

    private static final int ANCHORS = 6;

    private static final String MIDDLE_POINT = "point 1,0,2";

    private static final String BOTTOM_POINT = "point 0,0,0";

    private static final float NEAR_ROW_FRACTION = 0.01f;

    private static final int SNAP_ROW = 8;

    private static final double POSITION_TOLERANCE = 1e-5;

    private static final double VOLUME_SHARE_TOLERANCE = 0.1;

    private static final String LINE_BREAK = "\n";

    @Test
    void twoSmoothRingsCutAClosedMiddleWhoseBoundaryLiesOnTheSplines() {
        MeshTopology cylinder = cappedCylinder();
        float[] positionsBefore = positionsOf(cylinder);
        int[] cornersBefore = cornersOf(cylinder);
        SurfaceSpline[] splines = { tiltedRing(cylinder, LOW_RING_HEIGHT, 0),
            tiltedRing(cylinder, HIGH_RING_HEIGHT, Math.PI / 2) };
        RingRegions regions = new RingRegions(cylinder, LABELS,
                new boolean[][] { splines[0].markedByEdgeId, splines[1].markedByEdgeId }).build();
        assertEquals(3, regions.regionCount, String.join(LINE_BREAK, regions.reportLines()));

        RingRegionExtraction extraction = new RingRegionExtraction(regions, splines,
                regions.select(MIDDLE_POINT));
        extraction.solidCheck = Platforms.get().meshBooleanBackend();
        extraction.build();
        String report = String.join(LINE_BREAK, extraction.reportLines());

        assertTrue(extraction.problems.isEmpty(), report);
        assertTrue(extraction.insertedVertexCount > 0, report);
        assertTrue(extraction.closed, report);
        assertEquals(2, extraction.capReport.filledHoleCount, report);
        assertEquals(MeshBooleanBackend.ACCEPTED_SOLID, extraction.solidStatus, report);
        double longestEdge = 0;
        for (int activeEdge = 0; activeEdge < cylinder.edgeCount(); activeEdge++) {
            longestEdge = Math.max(longestEdge, cylinder.edgeLength(cylinder.edgeIdAt(activeEdge)));
        }
        assertTrue(extraction.boundaryToSplineDistance
                <= extraction.snapFraction * longestEdge + POSITION_TOLERANCE, report);
        assertTrue(extraction.boundaryToSplineDistance < extraction.snappedLoopToSplineDistance,
                report);
        for (int activeVertex = 0; activeVertex < extraction.openMesh.vertexCount();
                activeVertex++) {
            int vertexId = extraction.openMesh.vertexIdAt(activeVertex);
            if (!extraction.openMesh.isBoundaryVertex(vertexId)) {
                continue;
            }
            float z = extraction.openMesh.vertexPosition(vertexId, new Vector3f()).z;
            boolean nearLow = Math.abs(z - LOW_RING_HEIGHT) <= RING_TILT + ROW_SPACING;
            boolean nearHigh = Math.abs(z - HIGH_RING_HEIGHT) <= RING_TILT + ROW_SPACING;
            assertTrue(nearLow || nearHigh, "boundary vertex at z=" + z + LINE_BREAK + report);
        }
        // The faired caps continue the surface's tangent, so each domes out by at most a radius.
        double prism = polygonArea() * (HIGH_RING_HEIGHT - LOW_RING_HEIGHT);
        double volume = signedVolume(extraction.closedMesh);
        assertTrue(volume > (1 - VOLUME_SHARE_TOLERANCE) * prism, volume + LINE_BREAK + report);
        assertTrue(volume < polygonArea() * (HIGH_RING_HEIGHT - LOW_RING_HEIGHT + 2),
                volume + LINE_BREAK + report);
        assertArrayEquals(positionsBefore, positionsOf(cylinder), "the source moved");
        assertArrayEquals(cornersBefore, cornersOf(cylinder), "the source's faces changed");
    }

    @Test
    void aCrossingBesideAVertexSnapsToItRatherThanCuttingASliver() {
        MeshTopology cylinder = cappedCylinder();
        double smallestSourceArea = Double.POSITIVE_INFINITY;
        for (int activeFace = 0; activeFace < cylinder.faceCount(); activeFace++) {
            int faceId = cylinder.faceIdAt(activeFace);
            Vector3f first = cylinder.vertexPosition(cylinder.faceVertexAt(faceId, 0),
                    new Vector3f());
            Vector3f second = cylinder.vertexPosition(cylinder.faceVertexAt(faceId, 1),
                    new Vector3f());
            Vector3f third = cylinder.vertexPosition(cylinder.faceVertexAt(faceId, 2),
                    new Vector3f());
            smallestSourceArea = Math.min(smallestSourceArea,
                    0.5 * second.sub(first).cross(third.sub(first)).length());
        }

        RingRegionExtraction snapped = nearRowExtraction(cylinder,
                RingRegionExtraction.DEFAULT_SNAP_FRACTION);
        String report = String.join(LINE_BREAK, snapped.reportLines());
        assertEquals(0, snapped.insertedVertexCount, report);
        assertEquals(2 * AROUND, snapped.snappedCrossingCount, report);
        assertEquals(cylinder.faceCount(), snapped.cutTriangleCount, report);
        assertTrue(snapped.closed, report);
        assertTrue(smallestCutArea(snapped) >= smallestSourceArea * (1 - POSITION_TOLERANCE),
                report);

        RingRegionExtraction unsnapped = nearRowExtraction(cylinder, 0);
        assertEquals(2 * AROUND, unsnapped.insertedVertexCount);
        assertTrue(smallestCutArea(unsnapped) < 2 * NEAR_ROW_FRACTION * smallestSourceArea,
                "without the snap the cut leaves slivers");
    }

    @Test
    void theNodeExtractsTwoNeighbouringRegionsThatUnionIntoOneSolid() {
        MeshTopology cylinder = cappedCylinder();
        GeometryBundle bundle = GeometryBundle.ofMesh(cylinder);
        for (int ring = 0; ring < LABELS.length; ring++) {
            SurfaceSpline spline = tiltedRing(cylinder,
                    ring == 0 ? LOW_RING_HEIGHT : HIGH_RING_HEIGHT, ring * Math.PI / 2);
            bundle = SurfaceSpline.with(EdgeMarks.with(bundle, LABELS[ring],
                    spline.markedByEdgeId), LABELS[ring], spline);
        }
        GeometryBundle[] pieces = new GeometryBundle[2];
        double[] volumes = new double[2];
        String[] selections = { BOTTOM_POINT, MIDDLE_POINT };
        for (int piece = 0; piece < 2; piece++) {
            MapNodeContext context = new MapNodeContext(new ExtractRingRegionNode())
                    .with(ExtractRingRegionNode.GEOMETRY, bundle)
                    .with(ExtractRingRegionNode.SELECT, selections[piece])
                    .eval();
            String report = context.output(ExtractRingRegionNode.REPORT, String.class);
            assertTrue(report.contains("closed=true"), report);
            assertTrue(report.contains("manifold " + MeshBooleanBackend.ACCEPTED_SOLID), report);
            pieces[piece] = context.output(ExtractRingRegionNode.GEOMETRY_OUT,
                    GeometryBundle.class);
            volumes[piece] = signedVolume(pieces[piece].mesh());
        }

        GeometryBundle union = new MapNodeContext(new MeshBooleanNode())
                .with(MeshBooleanNode.MESH_A, pieces[0])
                .with(MeshBooleanNode.MESH_B, pieces[1])
                .with(MeshBooleanNode.OPERATION, MeshBooleanNode.UNION)
                .eval().output(MeshBooleanNode.GEOMETRY, GeometryBundle.class);
        double unionVolume = signedVolume(union.mesh());
        assertTrue(unionVolume > Math.max(volumes[0], volumes[1]));
        assertTrue(unionVolume <= volumes[0] + volumes[1] + POSITION_TOLERANCE);
        assertEquals(polygonArea() * HIGH_RING_HEIGHT, unionVolume,
                VOLUME_SHARE_TOLERANCE * polygonArea() * HIGH_RING_HEIGHT);
    }

    /**
     * Extraction of the cylinder's top above a ring cut just over a vertex row, its crossings a
     * hundredth of an edge above the row's vertices.
     */
    private static RingRegionExtraction nearRowExtraction(MeshTopology cylinder, double snap) {
        double[] positions = new double[3 * 2 * AROUND];
        int[] vertexIds = new int[2 * AROUND];
        int[] edgeIds = new int[2 * AROUND];
        double[] fractions = new double[2 * AROUND];
        boolean[] rowMarks = new boolean[RingBundle.edgeIdCeiling(cylinder)];
        Vector3f low = new Vector3f();
        Vector3f high = new Vector3f();
        for (int segment = 0; segment < AROUND; segment++) {
            int lowLeft = SNAP_ROW * AROUND + segment;
            int lowRight = SNAP_ROW * AROUND + (segment + 1) % AROUND;
            rowMarks[cylinder.edgeBetween(cylinder.vertexIdAt(lowLeft),
                    cylinder.vertexIdAt(lowRight))] = true;
            int[][] crossed = { { lowLeft, lowLeft + AROUND }, { lowLeft, lowRight + AROUND } };
            for (int step = 0; step < 2; step++) {
                int point = 2 * segment + step;
                int lowId = cylinder.vertexIdAt(crossed[step][0]);
                int edgeId = cylinder.edgeBetween(lowId, cylinder.vertexIdAt(crossed[step][1]));
                boolean fromLow = cylinder.halfEdgeVertex(cylinder.edgeHalfEdge(edgeId)) == lowId;
                cylinder.vertexPosition(lowId, low);
                cylinder.vertexPosition(cylinder.vertexIdAt(crossed[step][1]), high);
                low.lerp(high, NEAR_ROW_FRACTION);
                positions[3 * point] = low.x;
                positions[3 * point + 1] = low.y;
                positions[3 * point + 2] = low.z;
                vertexIds[point] = -1;
                edgeIds[point] = edgeId;
                fractions[point] = fromLow ? NEAR_ROW_FRACTION : 1 - NEAR_ROW_FRACTION;
            }
        }
        SurfaceSpline spline = tiltedRing(cylinder, LOW_RING_HEIGHT, 0);
        spline.followPath(cylinder, new TracedSurfacePath(positions, vertexIds, edgeIds,
                fractions, 2 * AROUND, true));
        RingRegions regions = new RingRegions(cylinder, new String[] { LABELS[0] },
                new boolean[][] { rowMarks }).build();
        RingRegionExtraction extraction = new RingRegionExtraction(regions,
                new SurfaceSpline[] { spline }, regions.select("point 0,0,4"));
        extraction.snapFraction = snap;
        return extraction.build();
    }

    /** A spline ring around the cylinder through anchors on a circle tilted about its height. */
    private static SurfaceSpline tiltedRing(MeshTopology cylinder, float height, double phase) {
        float[] anchors = new float[3 * ANCHORS];
        for (int anchor = 0; anchor < ANCHORS; anchor++) {
            double angle = 2 * Math.PI * (anchor + 0.5) / ANCHORS;
            anchors[3 * anchor] = (float) Math.cos(angle);
            anchors[3 * anchor + 1] = (float) Math.sin(angle);
            anchors[3 * anchor + 2] = height + RING_TILT * (float) Math.cos(angle + phase);
        }
        return SurfaceSpline.through(cylinder, anchors, ANCHORS);
    }

    private static double smallestCutArea(RingRegionExtraction extraction) {
        double smallest = Double.POSITIVE_INFINITY;
        float[] xyz = extraction.cutPositions;
        for (int triangle = 0; triangle < extraction.cutTriangleCount; triangle++) {
            int first = 3 * extraction.cutTriangles[3 * triangle];
            int second = 3 * extraction.cutTriangles[3 * triangle + 1];
            int third = 3 * extraction.cutTriangles[3 * triangle + 2];
            Vector3f edge = new Vector3f(xyz[second] - xyz[first], xyz[second + 1] - xyz[first + 1],
                    xyz[second + 2] - xyz[first + 2]);
            Vector3f other = new Vector3f(xyz[third] - xyz[first], xyz[third + 1] - xyz[first + 1],
                    xyz[third + 2] - xyz[first + 2]);
            smallest = Math.min(smallest, 0.5 * edge.cross(other).length());
        }
        return smallest;
    }

    private static double signedVolume(MeshTopology mesh) {
        double volume = 0;
        Vector3f first = new Vector3f();
        Vector3f second = new Vector3f();
        Vector3f third = new Vector3f();
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            mesh.vertexPosition(mesh.faceVertexAt(faceId, 0), first);
            for (int corner = 2; corner < mesh.faceVertexCount(faceId); corner++) {
                mesh.vertexPosition(mesh.faceVertexAt(faceId, corner - 1), second);
                mesh.vertexPosition(mesh.faceVertexAt(faceId, corner), third);
                volume += first.dot(new Vector3f(second).cross(third)) / 6.0;
            }
        }
        return volume;
    }

    /** Area of the cylinder's regular polygonal cross-section. */
    private static double polygonArea() {
        return 0.5 * AROUND * Math.sin(2 * Math.PI / AROUND);
    }

    private static float[] positionsOf(MeshTopology mesh) {
        float[] xyz = new float[3 * mesh.vertexCount()];
        Vector3f position = new Vector3f();
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            mesh.vertexPosition(mesh.vertexIdAt(activeVertex), position);
            xyz[3 * activeVertex] = position.x;
            xyz[3 * activeVertex + 1] = position.y;
            xyz[3 * activeVertex + 2] = position.z;
        }
        return xyz;
    }

    private static int[] cornersOf(MeshTopology mesh) {
        int[] corners = new int[3 * mesh.faceCount()];
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            for (int corner = 0; corner < 3; corner++) {
                corners[3 * activeFace + corner] = mesh.faceVertexAt(mesh.faceIdAt(activeFace),
                        corner);
            }
        }
        return corners;
    }

    /**
     * A closed cylinder of radius 1 along z, its side split into triangles and each end capped by
     * a fan around a centre vertex; side vertex {@code row * AROUND + segment}.
     */
    private static MeshTopology cappedCylinder() {
        int sideVertices = (ROWS + 1) * AROUND;
        float[] positions = new float[3 * (sideVertices + 2)];
        for (int row = 0; row <= ROWS; row++) {
            for (int segment = 0; segment < AROUND; segment++) {
                double angle = 2 * Math.PI * segment / AROUND;
                int vertex = row * AROUND + segment;
                positions[3 * vertex] = (float) Math.cos(angle);
                positions[3 * vertex + 1] = (float) Math.sin(angle);
                positions[3 * vertex + 2] = ROW_SPACING * row;
            }
        }
        int bottomCentre = sideVertices;
        int topCentre = sideVertices + 1;
        positions[3 * topCentre + 2] = HEIGHT;
        int topRow = ROWS * AROUND;
        int[] triangles = new int[3 * 2 * (ROWS + 1) * AROUND];
        int cursor = 0;
        for (int row = 0; row < ROWS; row++) {
            for (int segment = 0; segment < AROUND; segment++) {
                int next = (segment + 1) % AROUND;
                int lowLeft = row * AROUND + segment;
                int lowRight = row * AROUND + next;
                cursor = put(triangles, cursor, lowLeft, lowRight, lowRight + AROUND);
                cursor = put(triangles, cursor, lowLeft, lowRight + AROUND, lowLeft + AROUND);
            }
        }
        for (int segment = 0; segment < AROUND; segment++) {
            int next = (segment + 1) % AROUND;
            cursor = put(triangles, cursor, bottomCentre, next, segment);
            cursor = put(triangles, cursor, topCentre, topRow + segment, topRow + next);
        }
        return HalfEdgeMeshEngine.buildFromIndexedMesh(positions, triangles);
    }

    private static int put(int[] triangles, int cursor, int first, int second, int third) {
        triangles[cursor] = first;
        triangles[cursor + 1] = second;
        triangles[cursor + 2] = third;
        return cursor + 3;
    }
}
