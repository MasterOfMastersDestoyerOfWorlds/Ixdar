package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetworkRecarve;
import ixdar.geometry.mesh.quadlayout.embedding.FaceStripPath;
import ixdar.geometry.mesh.quadlayout.embedding.SnappingCarve;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * Contracted routes replayed by the re-carve can wiggle: enter and leave a face through one
 * edge (a dip), or pass through a vertex and then cross an edge incident to it (a fan
 * wind). Both lay chords collinear with the edge, walking over other arcs' lanes.
 */
class RecarveTautStripsTest {

    /** Bottom-left mesh vertex, the inner arc's end node in the dip scenario. */
    private static final int VERTEX_BOTTOM_LEFT = 0;

    /** Bottom-middle mesh vertex, one endpoint of the contested interior edge. */
    private static final int VERTEX_BOTTOM_MIDDLE = 1;

    /** Bottom-right mesh vertex, the west-dipping arc's start in the fan scenario. */
    private static final int VERTEX_BOTTOM_RIGHT = 2;

    /** Top-left mesh vertex, the inner arc's start node in the dip scenario. */
    private static final int VERTEX_TOP_LEFT = 3;

    /** Top-middle mesh vertex, the other endpoint of the contested interior edge. */
    private static final int VERTEX_TOP_MIDDLE = 4;

    /** Top-right mesh vertex, the west-dipping arc's end in the fan scenario. */
    private static final int VERTEX_TOP_RIGHT = 5;

    /** Source face 0, the triangle (0, 1, 3). */
    private static final int FACE_LEFT_LOWER = 0;

    /** Source face 1, the triangle (3, 1, 4), west of the contested edge. */
    private static final int FACE_LEFT_UPPER = 1;

    /** Source face 2, the triangle (1, 2, 4), east of the contested edge. */
    private static final int FACE_RIGHT_LOWER = 2;

    /** Source face 3, the triangle (4, 2, 5). */
    private static final int FACE_RIGHT_UPPER = 3;

    /** Where the inner arc's dip leaves the contested edge, from vertex 1. */
    private static final double INNER_DIP_ENTRY = 0.55;

    /** Where the inner arc's dip returns across the contested edge. */
    private static final double INNER_DIP_EXIT = 0.45;

    /** Where the outer arc's dip leaves the contested edge, straddling the inner dip. */
    private static final double OUTER_DIP_ENTRY = 0.7;

    /** Where the outer arc's dip returns across the contested edge. */
    private static final double OUTER_DIP_EXIT = 0.3;

    /** Where the inner arc's taut route crosses the hypotenuse edge 1..3. */
    private static final double HYPOTENUSE_CROSSING = 0.4;

    /** Where the fan-winding arc crosses the contested edge after its vertex pass. */
    private static final double FAN_EXIT = 0.85;

    /** Where the west-dipping arc's dip leaves the contested edge. */
    private static final double WEST_DIP_ENTRY = 0.6;

    /** Where the west-dipping arc's route crosses edge 2..4 on its way out. */
    private static final double EAST_EXIT = 0.5;

    /** Vertices per row of the fixture strip. */
    private static final int ROW_VERTICES = 3;

    /** Corners of a triangle, and coordinates of a position. */
    private static final int CORNERS = 3;

    /**
     * Two arcs whose contracted routes dip through the same interior edge, one nested
     * inside the other, must not be laid over one another along that edge.
     */
    @Test
    void nestedDipsDoNotOverlapOnTheSharedEdge() {
        EmbeddedMeshTopology topology = stripTopology();
        SnappingCarve snapping = new SnappingCarve(topology);
        placeNodes(snapping, topology, VERTEX_TOP_LEFT, VERTEX_BOTTOM_LEFT,
                VERTEX_TOP_MIDDLE, VERTEX_BOTTOM_MIDDLE);

        FaceStripPath inner = new FaceStripPath(topology, 0);
        inner.addPassage(FACE_LEFT_UPPER, corner(0), westEdgePoint(INNER_DIP_ENTRY));
        inner.addPassage(FACE_RIGHT_LOWER, eastEdgePoint(INNER_DIP_ENTRY),
                eastEdgePoint(INNER_DIP_EXIT));
        inner.addPassage(FACE_LEFT_UPPER, westEdgePoint(INNER_DIP_EXIT),
                hypotenusePointUpper(HYPOTENUSE_CROSSING));
        inner.addPassage(FACE_LEFT_LOWER, hypotenusePointLower(HYPOTENUSE_CROSSING),
                corner(0));

        FaceStripPath outer = new FaceStripPath(topology, 1);
        outer.addPassage(FACE_LEFT_UPPER, corner(2), westEdgePoint(OUTER_DIP_ENTRY));
        outer.addPassage(FACE_RIGHT_LOWER, eastEdgePoint(OUTER_DIP_ENTRY),
                eastEdgePoint(OUTER_DIP_EXIT));
        outer.addPassage(FACE_LEFT_UPPER, westEdgePoint(OUTER_DIP_EXIT), corner(1));

        snapping.stripByArc = List.of(inner, outer);
        snapping.startNodeByArc = new int[] { 0, 2 };
        snapping.endNodeByArc = new int[] { 1, 3 };
        assertEquals(CORNERS, inner.crossedEdges.size(), "inner route's crossings");
        assertEquals(2, outer.crossedEdges.size(), "outer route's crossings");

        ArcNetworkRecarve recarve = pullTaut(topology, snapping);
        snapping.carve();

        assertEquals(0, topology.claimConflictCount,
                "the nested dips were laid over one another on the shared edge; first: "
                        + topology.firstClaimConflict);
        assertEquals(2, recarve.dipCrossingsRemovedCount, "the inner dip removed");
        assertEquals(CORNERS, recarve.endCrossingsTrimmedCount,
                "the exposed crossings on the arcs' own node edges trimmed");
        assertEquals(0, recarve.fanSlideCrossingsRemovedCount, "no fan wind here");
        assertEquals(List.of(VERTEX_TOP_MIDDLE, VERTEX_BOTTOM_MIDDLE),
                snapping.pathByArc[1].copyVertexPath,
                "the outer arc's taut route runs straight along the contested edge");
    }

    /**
     * An arc passing exactly through a vertex and then crossing an edge incident to it must
     * not walk along that edge over another arc's lanes.
     */
    @Test
    void fanWindDoesNotWalkForeignLanesOnTheIncidentEdge() {
        EmbeddedMeshTopology topology = stripTopology();
        SnappingCarve snapping = new SnappingCarve(topology);
        placeNodes(snapping, topology, VERTEX_BOTTOM_LEFT, VERTEX_TOP_LEFT,
                VERTEX_BOTTOM_RIGHT, VERTEX_TOP_RIGHT);

        FaceStripPath fan = new FaceStripPath(topology, 0);
        fan.addPassage(FACE_LEFT_LOWER, corner(0), corner(1));
        fan.addPassage(FACE_RIGHT_LOWER, eastEdgePoint(0.0), eastEdgePoint(FAN_EXIT));
        fan.addPassage(FACE_LEFT_UPPER, westEdgePoint(FAN_EXIT), corner(0));

        FaceStripPath dipper = new FaceStripPath(topology, 1);
        dipper.addPassage(FACE_RIGHT_LOWER, corner(1), eastEdgePoint(WEST_DIP_ENTRY));
        dipper.addPassage(FACE_LEFT_UPPER, westEdgePoint(WEST_DIP_ENTRY),
                westEdgePoint(OUTER_DIP_EXIT));
        dipper.addPassage(FACE_RIGHT_LOWER, eastEdgePoint(OUTER_DIP_EXIT),
                risingEdgePointRightLower(EAST_EXIT));
        dipper.addPassage(FACE_RIGHT_UPPER, risingEdgePointRightUpper(EAST_EXIT), corner(2));

        snapping.stripByArc = List.of(fan, dipper);
        snapping.startNodeByArc = new int[] { 0, 2 };
        snapping.endNodeByArc = new int[] { 1, 3 };
        assertEquals(2, fan.crossedEdges.size(), "fan route's crossings");
        assertEquals(CORNERS, dipper.crossedEdges.size(), "dipper route's crossings");

        ArcNetworkRecarve recarve = pullTaut(topology, snapping);
        snapping.carve();

        assertEquals(0, topology.claimConflictCount,
                "the fan wind walked the incident edge over the dipper's lanes; first: "
                        + topology.firstClaimConflict);
        assertEquals(2, recarve.dipCrossingsRemovedCount, "the dipper's dip removed");
        assertEquals(1, recarve.fanSlideCrossingsRemovedCount,
                "the fan crossing slid into its vertex");
    }

    /**
     * Runs the re-carve's taut pass over hand-built strips, the way {@code build()} runs it
     * between refining the arcs and carving.
     *
     * @param topology working copy the strips lie on
     * @param snapping carve holding the strips and node placements
     * @return the re-carve wrapper, for its removal counters
     */
    private ArcNetworkRecarve pullTaut(EmbeddedMeshTopology topology,
            SnappingCarve snapping) {
        ArcNetworkRecarve recarve = new ArcNetworkRecarve(null, topology.sourceMesh);
        recarve.snapping = snapping;
        recarve.pullStripsTaut();
        return recarve;
    }

    /**
     * Registers four nodes on mesh vertices the way the re-carve's node placement does.
     *
     * @param snapping carve being prepared
     * @param topology working copy the nodes sit on
     * @param vertices copy vertex per node id, in node id order
     */
    private void placeNodes(SnappingCarve snapping, EmbeddedMeshTopology topology,
            int... vertices) {
        snapping.nodeCount = vertices.length;
        snapping.vertexIdByNode = vertices.clone();
        for (int nodeId = 0; nodeId < vertices.length; nodeId++) {
            topology.ownerNodeByCopyVertex[vertices[nodeId]] = nodeId;
        }
        snapping.constraintVertexCount = topology.copy.vertexCount();
        snapping.constraintFaceCount = topology.copy.faceCount();
    }

    /**
     * A corner barycentric with weight one at the given local corner.
     *
     * @param localCorner local corner index of the face the passage runs in
     * @return that corner's barycentric triple
     */
    private double[] corner(int localCorner) {
        double[] barycentric = new double[CORNERS];
        barycentric[localCorner] = 1.0;
        return barycentric;
    }

    /**
     * A point on the contested edge 1..4, in the west face's frame (corners 3, 1, 4).
     *
     * @param fromBottomMiddle position along the edge measured from vertex 1
     * @return its barycentric in the west face
     */
    private double[] westEdgePoint(double fromBottomMiddle) {
        return new double[] { 0.0, 1.0 - fromBottomMiddle, fromBottomMiddle };
    }

    /**
     * A point on the contested edge 1..4, in the east face's frame (corners 1, 2, 4).
     *
     * @param fromBottomMiddle position along the edge measured from vertex 1
     * @return its barycentric in the east face
     */
    private double[] eastEdgePoint(double fromBottomMiddle) {
        return new double[] { 1.0 - fromBottomMiddle, 0.0, fromBottomMiddle };
    }

    /**
     * A point on the hypotenuse edge 1..3, in the upper face's frame (corners 3, 1, 4).
     *
     * @param fromBottomMiddle position along the edge measured from vertex 1
     * @return its barycentric in the upper face
     */
    private double[] hypotenusePointUpper(double fromBottomMiddle) {
        return new double[] { fromBottomMiddle, 1.0 - fromBottomMiddle, 0.0 };
    }

    /**
     * A point on the hypotenuse edge 1..3, in the lower face's frame (corners 0, 1, 3).
     *
     * @param fromBottomMiddle position along the edge measured from vertex 1
     * @return its barycentric in the lower face
     */
    private double[] hypotenusePointLower(double fromBottomMiddle) {
        return new double[] { 0.0, 1.0 - fromBottomMiddle, fromBottomMiddle };
    }

    /**
     * A point on the rising edge 2..4, in the right-lower face's frame (corners 1, 2, 4).
     *
     * @param fromBottomRight position along the edge measured from vertex 2
     * @return its barycentric in the right-lower face
     */
    private double[] risingEdgePointRightLower(double fromBottomRight) {
        return new double[] { 0.0, 1.0 - fromBottomRight, fromBottomRight };
    }

    /**
     * A point on the rising edge 2..4, in the right-upper face's frame (corners 4, 2, 5).
     *
     * @param fromBottomRight position along the edge measured from vertex 2
     * @return its barycentric in the right-upper face
     */
    private double[] risingEdgePointRightUpper(double fromBottomRight) {
        return new double[] { fromBottomRight, 1.0 - fromBottomRight, 0.0 };
    }

    /**
     * A two-row strip of four triangles: bottom vertices 0, 1, 2 and top vertices 3, 4, 5,
     * with faces (0,1,3), (3,1,4), (1,2,4), (4,2,5). Edge 1..4 is the interior edge the
     * scenarios contest.
     *
     * @return a working copy of that strip
     */
    private EmbeddedMeshTopology stripTopology() {
        float[] positions = new float[ROW_VERTICES * 2 * CORNERS];
        for (int column = 0; column < ROW_VERTICES; column++) {
            positions[column * CORNERS] = column;
            positions[(ROW_VERTICES + column) * CORNERS] = column;
            positions[(ROW_VERTICES + column) * CORNERS + 1] = 1.0f;
        }
        List<Integer> faces = new ArrayList<>();
        for (int column = 0; column < ROW_VERTICES - 1; column++) {
            faces.add(column);
            faces.add(column + 1);
            faces.add(ROW_VERTICES + column);
            faces.add(ROW_VERTICES + column);
            faces.add(column + 1);
            faces.add(ROW_VERTICES + column + 1);
        }
        int[] faceIndices = new int[faces.size()];
        for (int index = 0; index < faces.size(); index++) {
            faceIndices[index] = faces.get(index);
        }
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(positions, faceIndices);
        return new EmbeddedMeshTopology(mesh);
    }
}
