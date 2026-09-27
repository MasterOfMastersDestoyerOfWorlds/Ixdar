package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.primitives.GridMeshNode;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetworkRecarve;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * A contracted arc a drag left folded back on itself is re-carved taut and simple, as block's
 * arc 178 at curvature bias -1 was not.
 */
class RecarveFoldedArcTest {

    /** Grid vertex columns. */
    private static final int COLUMNS = 7;

    /** Grid vertex rows. */
    private static final int ROWS = 5;

    /** Column the middle arc belongs on, between the two patches. */
    private static final int MIDDLE = 1;

    /** Rightmost column, the right patch's far side. */
    private static final int RIGHT = COLUMNS - 1;

    /** Bottom row. */
    private static final int BOTTOM = ROWS - 1;

    /** Column of C, which the returning leg runs exactly through. */
    private static final int C_COLUMN = 3;

    /** Column of W, which the fold circles. */
    private static final int W_COLUMN = 5;

    /** Row of the band's lower side, where C and W sit. */
    private static final int BAND_LOW = 2;

    /** Where the outward leg crosses the band's edges, from their row-1 end. */
    private static final double OUTWARD = 0.3;

    /** Where the returning leg crosses the band's edges, from their row-1 end. */
    private static final double RETURNING = 0.7;

    /** Where the circle round W crosses each edge of W's fan. */
    private static final double MID_EDGE = 0.5;

    /** Where the bridge into C sits along the edge it splits. */
    private static final double BRIDGE = 0.8;

    /** Quantized length every fixture arc carries; the re-carve never reads it. */
    private static final int LENGTH = 1;

    /**
     * The folded middle arc is re-routed down its own column, so the re-carved path is the
     * straight column and visits every vertex once.
     */
    @Test
    void aFoldedArcIsRecarvedTautAndSimple() {
        HalfEdgeMesh grid = GridMeshNode.triangulated(COLUMNS, ROWS);
        EmbeddedMeshTopology contracted = new EmbeddedMeshTopology(grid);
        ArcNetwork layout = new ArcNetwork(contracted);
        int topLeft = layout.addNode(ArcNetwork.NONE, vertex(contracted, 0, 0), false, true);
        int topMiddle = layout.addNode(ArcNetwork.NONE, vertex(contracted, MIDDLE, 0), false, true);
        int topRight = layout.addNode(ArcNetwork.NONE, vertex(contracted, RIGHT, 0), false, true);
        int bottomRight = layout.addNode(ArcNetwork.NONE, vertex(contracted, RIGHT, BOTTOM), false,
                true);
        int bottomMiddle = layout.addNode(ArcNetwork.NONE, vertex(contracted, MIDDLE, BOTTOM), false,
                true);
        int bottomLeft = layout.addNode(ArcNetwork.NONE, vertex(contracted, 0, BOTTOM), false, true);
        int top = layout.addArc(ArcNetwork.NONE, topMiddle, topRight, LENGTH, true,
                row(contracted, 0, MIDDLE, RIGHT));
        int right = layout.addArc(ArcNetwork.NONE, topRight, bottomRight, LENGTH, true,
                column(contracted, RIGHT, 0, BOTTOM));
        int bottom = layout.addArc(ArcNetwork.NONE, bottomRight, bottomMiddle, LENGTH, true,
                row(contracted, BOTTOM, RIGHT, MIDDLE));
        int bottomShort = layout.addArc(ArcNetwork.NONE, bottomMiddle, bottomLeft, LENGTH, true,
                row(contracted, BOTTOM, MIDDLE, 0));
        int left = layout.addArc(ArcNetwork.NONE, bottomLeft, topLeft, LENGTH, true,
                column(contracted, 0, BOTTOM, 0));
        int topShort = layout.addArc(ArcNetwork.NONE, topLeft, topMiddle, LENGTH, true,
                row(contracted, 0, 0, MIDDLE));
        int middle = layout.addArc(ArcNetwork.NONE, topMiddle, bottomMiddle, LENGTH, false,
                foldedPath(contracted));
        layout.addPatch(ArcNetwork.NONE, List.of(List.of(left), List.of(bottomShort),
                List.of(middle), List.of(topShort)), topLeft);
        layout.addPatch(ArcNetwork.NONE, List.of(List.of(middle), List.of(bottom),
                List.of(right), List.of(top)), topMiddle);

        ArcNetwork rebuilt = new ArcNetworkRecarve(layout, grid).build();

        EmbeddedMeshTopology fresh = rebuilt.topology;
        assertEquals(column(fresh, MIDDLE, 0, BOTTOM),
                rebuilt.arcs.get(middle).path.copyVertexPath,
                "the folded arc is re-carved straight down its column, visiting each vertex once");
        assertEquals(column(contracted, MIDDLE, 0, BOTTOM),
                layout.arcs.get(middle).path.copyVertexPath,
                "the fold was pulled taut on the contracted mesh before the replay");
    }

    /**
     * The middle arc's folded route on the contracted mesh, refining the grid as a drag
     * would: down to row 1, right across the band below row 2 by {@link #OUTWARD}, once
     * round W, back by {@link #RETURNING} and through C, then down column 1.
     *
     * @param contracted working copy the route is carved into, split along the way
     * @return the route's copy vertices, top to bottom
     */
    private static List<Integer> foldedPath(EmbeddedMeshTopology contracted) {
        int cornerC = vertex(contracted, C_COLUMN, BAND_LOW);
        int cornerW = vertex(contracted, W_COLUMN, BAND_LOW);
        int beyondC = vertex(contracted, C_COLUMN + 1, BAND_LOW);
        int outOnSecond = split(contracted, vertex(contracted, 2, 1),
                vertex(contracted, 2, BAND_LOW), OUTWARD);
        int outOnSecondDiagonal = split(contracted, vertex(contracted, 2, 1), cornerC, OUTWARD);
        int outOnThird = split(contracted, vertex(contracted, C_COLUMN, 1), cornerC, OUTWARD);
        int outOnThirdDiagonal = split(contracted, vertex(contracted, C_COLUMN, 1), beyondC,
                OUTWARD);
        int outOnFourth = split(contracted, vertex(contracted, C_COLUMN + 1, 1), beyondC, OUTWARD);
        int roundAboveLeft = split(contracted, vertex(contracted, C_COLUMN + 1, 1), cornerW,
                MID_EDGE);
        int roundAbove = split(contracted, vertex(contracted, W_COLUMN, 1), cornerW, MID_EDGE);
        int roundRight = split(contracted, cornerW, vertex(contracted, RIGHT, BAND_LOW), MID_EDGE);
        int roundBelowRight = split(contracted, cornerW, vertex(contracted, RIGHT, BAND_LOW + 1),
                MID_EDGE);
        int roundBelow = split(contracted, cornerW, vertex(contracted, W_COLUMN, BAND_LOW + 1),
                MID_EDGE);
        int roundLeft = split(contracted, beyondC, cornerW, MID_EDGE);
        double returningPastOutward = (RETURNING - OUTWARD) / (1.0 - OUTWARD);
        int backOnFourth = split(contracted, outOnFourth, beyondC, returningPastOutward);
        int backOnThirdDiagonal = split(contracted, outOnThirdDiagonal, beyondC,
                returningPastOutward);
        int bridgeIntoC = split(contracted, outOnThird, beyondC, BRIDGE);
        int backOnSecond = split(contracted, outOnSecond, vertex(contracted, 2, BAND_LOW),
                returningPastOutward);
        int backOnFirstDiagonal = split(contracted, vertex(contracted, MIDDLE, 1),
                vertex(contracted, 2, BAND_LOW), MID_EDGE);
        return List.of(vertex(contracted, MIDDLE, 0), vertex(contracted, MIDDLE, 1), outOnSecond,
                outOnSecondDiagonal, outOnThird, outOnThirdDiagonal, outOnFourth, roundAboveLeft,
                roundAbove, roundRight, roundBelowRight, roundBelow, roundLeft, backOnFourth,
                backOnThirdDiagonal, bridgeIntoC, cornerC, backOnSecond, backOnFirstDiagonal,
                vertex(contracted, MIDDLE, BAND_LOW), vertex(contracted, MIDDLE, BAND_LOW + 1),
                vertex(contracted, MIDDLE, BOTTOM));
    }

    /**
     * Splits the copy edge between two vertices, the way a contraction route refines it.
     *
     * @param topology working copy holding the edge
     * @param from     end the parameter is measured from
     * @param to       the other end
     * @param along    split position from {@code from}
     * @return the minted vertex
     */
    private static int split(EmbeddedMeshTopology topology, int from, int to, double along) {
        int edge = topology.copy.edgeBetween(from, to);
        int canonicalStart = topology.copy.halfEdgeVertex(topology.copy.edgeHalfEdge(edge));
        return topology.splitEdgeAtParameter(edge, canonicalStart == from ? along : 1.0 - along);
    }

    /**
     * The copy vertices along one grid row between two columns, inclusive.
     *
     * @param topology working copy over the grid
     * @param gridRow  the row
     * @param from     first column
     * @param to       last column
     * @return the vertices in walking order
     */
    private static List<Integer> row(EmbeddedMeshTopology topology, int gridRow, int from, int to) {
        int step = to >= from ? 1 : -1;
        List<Integer> vertices = new ArrayList<>();
        for (int gridColumn = from; gridColumn != to + step; gridColumn += step) {
            vertices.add(vertex(topology, gridColumn, gridRow));
        }
        return vertices;
    }

    /**
     * The copy vertices along one grid column between two rows, inclusive.
     *
     * @param topology   working copy over the grid
     * @param gridColumn the column
     * @param from       first row
     * @param to         last row
     * @return the vertices in walking order
     */
    private static List<Integer> column(EmbeddedMeshTopology topology, int gridColumn, int from,
            int to) {
        int step = to >= from ? 1 : -1;
        List<Integer> vertices = new ArrayList<>();
        for (int gridRow = from; gridRow != to + step; gridRow += step) {
            vertices.add(vertex(topology, gridColumn, gridRow));
        }
        return vertices;
    }

    /**
     * The copy vertex at a grid position.
     *
     * @param topology   working copy over the grid
     * @param gridColumn grid column
     * @param gridRow    grid row
     * @return the copy vertex there
     */
    private static int vertex(EmbeddedMeshTopology topology, int gridColumn, int gridRow) {
        return topology.copyVertexForSourceVertexId(GridMeshNode.vertexId(ROWS, gridColumn,
                gridRow));
    }
}
