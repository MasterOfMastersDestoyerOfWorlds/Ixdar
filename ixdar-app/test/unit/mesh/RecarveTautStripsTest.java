package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetworkRecarve;
import ixdar.geometry.mesh.quadlayout.embedding.FaceStripPath;
import ixdar.geometry.mesh.quadlayout.embedding.SnappingCarve;
import ixdar.geometry.mesh.quadlayout.embedding.model.EmbeddedMeshTopology;

/**
 * Contracted routes replayed by the re-carve can wiggle: enter and leave a face through one
 * edge (a dip), or pass through a vertex and then cross an edge incident to it (a fan
 * wind). Both lay chords collinear with the edge, walking over other arcs' lanes.
 */
class RecarveTautStripsTest {

    private static final int VERTEX_BOTTOM_LEFT = 0;

    private static final int VERTEX_BOTTOM_MIDDLE = 1;

    private static final int VERTEX_BOTTOM_RIGHT = 2;

    private static final int VERTEX_TOP_LEFT = 3;

    private static final int VERTEX_TOP_MIDDLE = 4;

    private static final int VERTEX_TOP_RIGHT = 5;

    private static final int FACE_LEFT_LOWER = 0;

    private static final int FACE_LEFT_UPPER = 1;

    private static final int FACE_RIGHT_LOWER = 2;

    private static final int FACE_RIGHT_UPPER = 3;

    private static final double INNER_DIP_ENTRY_ALONG_CONTESTED_EDGE = 0.55;

    private static final double INNER_DIP_EXIT_ALONG_CONTESTED_EDGE = 0.45;

    private static final double OUTER_DIP_ENTRY_ALONG_CONTESTED_EDGE = 0.7;

    private static final double OUTER_DIP_EXIT_ALONG_CONTESTED_EDGE = 0.3;

    private static final double INNER_HYPOTENUSE_CROSSING = 0.4;

    private static final double FAN_EXIT_ALONG_CONTESTED_EDGE = 0.85;

    private static final double WEST_DIP_ENTRY_ALONG_CONTESTED_EDGE = 0.6;

    private static final double WEST_DIPPER_EXIT_ALONG_RISING_EDGE = 0.5;

    private static final double[] SKULL_NODE_NEAR_TOP_MIDDLE = { 0.045811039995265146,
        0.015600226982154086, 0.9385887330225807 };

    private static final double[] SKULL_RUNNER_SIXTH_TOWARD_NODE = { 0.8409685066658775,
        0.0026000378303590137, 0.15643145550376347 };

    private static final double[] SKULL_RUNNER_THIRD_TOWARD_NODE = { 0.681937013331755,
        0.005200075660718027, 0.31286291100752683 };

    private static final double[] SKULL_RUNNER_TURN_TOWARD_RISING_EDGE = { 0.01527034666508838,
        0.6718667423273847, 0.3128629110075269 };

    private static final double[] SKULL_CROSSER_HALFWAY_TOWARD_NODE = { 0.5229055199976326,
        0.007800113491077042, 0.4692943665112903 };

    private static final double[] SKULL_CROSSER_TURN_TOWARD_RISING_EDGE = {
        0.022905519997632573, 0.5078001134910771, 0.46929436651129036 };

    private static final double SKULL_CROSSER_ENTRY_ALONG_CONTESTED_EDGE = 0.375;

    private static final double SKULL_RUNNER_EXIT_ALONG_RISING_EDGE = 0.25;

    private static final double SKULL_CROSSER_EXIT_ALONG_RISING_EDGE = 0.375;

    private static final int ROW_VERTICES = 3;

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
        inner.addPassage(FACE_LEFT_UPPER, corner(0), westEdgePoint(INNER_DIP_ENTRY_ALONG_CONTESTED_EDGE));
        inner.addPassage(FACE_RIGHT_LOWER, eastEdgePoint(INNER_DIP_ENTRY_ALONG_CONTESTED_EDGE),
                eastEdgePoint(INNER_DIP_EXIT_ALONG_CONTESTED_EDGE));
        inner.addPassage(FACE_LEFT_UPPER, westEdgePoint(INNER_DIP_EXIT_ALONG_CONTESTED_EDGE),
                hypotenusePointUpper(INNER_HYPOTENUSE_CROSSING));
        inner.addPassage(FACE_LEFT_LOWER, hypotenusePointLower(INNER_HYPOTENUSE_CROSSING),
                corner(0));

        FaceStripPath outer = new FaceStripPath(topology, 1);
        outer.addPassage(FACE_LEFT_UPPER, corner(2), westEdgePoint(OUTER_DIP_ENTRY_ALONG_CONTESTED_EDGE));
        outer.addPassage(FACE_RIGHT_LOWER, eastEdgePoint(OUTER_DIP_ENTRY_ALONG_CONTESTED_EDGE),
                eastEdgePoint(OUTER_DIP_EXIT_ALONG_CONTESTED_EDGE));
        outer.addPassage(FACE_LEFT_UPPER, westEdgePoint(OUTER_DIP_EXIT_ALONG_CONTESTED_EDGE), corner(1));

        assertEquals(CORNERS, inner.crossedEdges.size(), "inner route's crossings");
        assertEquals(2, outer.crossedEdges.size(), "outer route's crossings");

        ArcNetworkRecarve recarve = pullTautAndCarve(snapping, inner, outer, 1, 3);

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
        fan.addPassage(FACE_RIGHT_LOWER, eastEdgePoint(0.0), eastEdgePoint(FAN_EXIT_ALONG_CONTESTED_EDGE));
        fan.addPassage(FACE_LEFT_UPPER, westEdgePoint(FAN_EXIT_ALONG_CONTESTED_EDGE), corner(0));

        FaceStripPath dipper = new FaceStripPath(topology, 1);
        dipper.addPassage(FACE_RIGHT_LOWER, corner(1), eastEdgePoint(WEST_DIP_ENTRY_ALONG_CONTESTED_EDGE));
        dipper.addPassage(FACE_LEFT_UPPER, westEdgePoint(WEST_DIP_ENTRY_ALONG_CONTESTED_EDGE),
                westEdgePoint(OUTER_DIP_EXIT_ALONG_CONTESTED_EDGE));
        dipper.addPassage(FACE_RIGHT_LOWER, eastEdgePoint(OUTER_DIP_EXIT_ALONG_CONTESTED_EDGE),
                risingEdgePointRightLower(WEST_DIPPER_EXIT_ALONG_RISING_EDGE));
        dipper.addPassage(FACE_RIGHT_UPPER, risingEdgePointRightUpper(WEST_DIPPER_EXIT_ALONG_RISING_EDGE), corner(2));

        assertEquals(2, fan.crossedEdges.size(), "fan route's crossings");
        assertEquals(CORNERS, dipper.crossedEdges.size(), "dipper route's crossings");

        ArcNetworkRecarve recarve = pullTautAndCarve(snapping, fan, dipper, 1, 3);

        assertEquals(2, recarve.dipCrossingsRemovedCount, "the dipper's dip removed");
        assertEquals(1, recarve.fanSlideCrossingsRemovedCount,
                "the fan crossing slid into its vertex");
    }

    /**
     * The T-Rex skull's case: a runner leaves vertex 1 along the constraint edge to a node,
     * a rounding step to one side of it, and turns off before a crosser crosses that edge
     * farther out. Its slide back into vertex 1 must not be blocked by the crosser.
     */
    @Test
    void runnerLeavingAConstraintEdgeIsNotBlockedByACrosserFartherOut() {
        EmbeddedMeshTopology topology = stripTopology();
        int nodeVertex = topology.splitFaceAtBarycentric(FACE_RIGHT_LOWER,
                SKULL_NODE_NEAR_TOP_MIDDLE.clone());
        SnappingCarve snapping = new SnappingCarve(topology);
        placeNodes(snapping, topology, VERTEX_BOTTOM_LEFT, VERTEX_TOP_RIGHT, VERTEX_TOP_LEFT,
                nodeVertex);

        FaceStripPath runner = new FaceStripPath(topology, 0);
        runner.addPassage(FACE_LEFT_LOWER, corner(0), corner(1));
        runner.addPassage(FACE_RIGHT_LOWER, corner(0), SKULL_RUNNER_SIXTH_TOWARD_NODE);
        runner.addPassage(FACE_RIGHT_LOWER, SKULL_RUNNER_SIXTH_TOWARD_NODE,
                SKULL_RUNNER_THIRD_TOWARD_NODE);
        runner.addPassage(FACE_RIGHT_LOWER, SKULL_RUNNER_THIRD_TOWARD_NODE,
                SKULL_RUNNER_TURN_TOWARD_RISING_EDGE);
        runner.addPassage(FACE_RIGHT_LOWER, SKULL_RUNNER_TURN_TOWARD_RISING_EDGE,
                risingEdgePointRightLower(SKULL_RUNNER_EXIT_ALONG_RISING_EDGE));
        runner.addPassage(FACE_RIGHT_UPPER,
                risingEdgePointRightUpper(SKULL_RUNNER_EXIT_ALONG_RISING_EDGE), corner(2));

        FaceStripPath crosser = new FaceStripPath(topology, 1);
        crosser.addPassage(FACE_LEFT_UPPER, corner(0),
                westEdgePoint(SKULL_CROSSER_ENTRY_ALONG_CONTESTED_EDGE));
        crosser.addPassage(FACE_RIGHT_LOWER,
                eastEdgePoint(SKULL_CROSSER_ENTRY_ALONG_CONTESTED_EDGE),
                SKULL_CROSSER_HALFWAY_TOWARD_NODE);
        crosser.addPassage(FACE_RIGHT_LOWER, SKULL_CROSSER_HALFWAY_TOWARD_NODE,
                SKULL_CROSSER_TURN_TOWARD_RISING_EDGE);
        crosser.addPassage(FACE_RIGHT_LOWER, SKULL_CROSSER_TURN_TOWARD_RISING_EDGE,
                risingEdgePointRightLower(SKULL_CROSSER_EXIT_ALONG_RISING_EDGE));
        crosser.addPassage(FACE_RIGHT_UPPER,
                risingEdgePointRightUpper(SKULL_CROSSER_EXIT_ALONG_RISING_EDGE), corner(2));

        ArcNetworkRecarve recarve = pullTautAndCarve(snapping, runner, crosser, 1, 1);

        assertEquals(1, recarve.fanSlideCrossingsRemovedCount,
                "the runner's crossing of the node edge slid back into vertex 1");
    }

    /**
     * Runs the re-carve's taut pass and the carve over two hand-built strips, starting at
     * nodes 0 and 2, as {@code build()} does, and checks no two arcs were laid over each other.
     *
     * @param snapping     carve holding the node placements
     * @param first        arc 0's strip
     * @param second       arc 1's strip
     * @param firstEndNode node arc 0 ends at
     * @param secondEndNode node arc 1 ends at
     * @return the re-carve wrapper, for its removal counters
     */
    private ArcNetworkRecarve pullTautAndCarve(SnappingCarve snapping, FaceStripPath first,
            FaceStripPath second, int firstEndNode, int secondEndNode) {
        snapping.stripByArc = List.of(first, second);
        snapping.startNodeByArc = new int[] { 0, 2 };
        snapping.endNodeByArc = new int[] { firstEndNode, secondEndNode };
        ArcNetworkRecarve recarve = new ArcNetworkRecarve(null, snapping.topology.sourceMesh);
        recarve.snapping = snapping;
        recarve.pullStripsTaut();
        snapping.carve();
        assertEquals(0, snapping.topology.claimConflictCount, "two arcs were laid over one"
                + " another; first: " + snapping.topology.firstClaimConflict);
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
            topology.ownerNodeByCopyVertex()[vertices[nodeId]] = nodeId;
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
        PackedPoints positions = new PackedPoints(ROW_VERTICES * 2);
        for (int column = 0; column < ROW_VERTICES; column++) {
            positions.set(column, column, 0f, 0f);
            positions.set(ROW_VERTICES + column, column, 1.0f, 0f);
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
        HalfEdgeMesh mesh = HalfEdgeMeshOps.buildFromIndexedMesh(positions, faceIndices);
        return new EmbeddedMeshTopology(mesh);
    }
}
