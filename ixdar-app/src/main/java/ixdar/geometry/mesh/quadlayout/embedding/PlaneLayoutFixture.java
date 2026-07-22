package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.nodes.primitives.GridMeshNode;

/**
 * A hand-authored embedded T-mesh on a flat grid, carrying LCBK19 Figure 9's configuration on a
 * surface that, like the figure's, <em>has a boundary</em>.
 *
 * <p>{@link TorusLayoutFixture} models the same combinatorics on a closed torus, which keeps the
 * arithmetic simple but cannot reach the case the figure is actually about. LCBK19 is a
 * free-boundary paper: every panel of Figure 9 has the trimmed boundary running down its right-hand
 * side, and Appendix A.3 shows that of the three kinds of non-collapsible zero-arc, the only kind
 * that can occur at all is <em>"a non-border zero-arc a between two non-critical border nodes"</em>,
 * whose ends <em>"will eventually be collapsed onto a common point on the surface boundary"</em> so
 * that <em>"the arc a thereby turns into a loop (same start and end point), which can be contracted
 * to a point"</em>. Loops are a boundary phenomenon, and a closed fixture cannot produce one on
 * purpose.
 *
 * <p>The layout below is Figure 9(a) reduced to its load-bearing parts. The blue zero-patch of the
 * figure is the upper cell here: its two vertical sides are quantized zero, so it is a zero-patch,
 * and its horizontal sides carry three non-zero arcs between them — 1 and 3 along the bottom, 4
 * along the top — which is exactly the paper's test for <em>non-simple</em>: <em>"more than two
 * non-zero arcs are involved, i.e. if there are flat arcs, corresponding to T-joints along the
 * patch's non-zero sides."</em> The T-joint sits at the middle node, where the figure's mismatched
 * side lengths (4 against 3) come from.
 *
 * <pre>
 *   row 4   G-----------------------------H      top     (arc 4)
 *           |   zero-patch, width 0       |
 *   row 2   D---------E-------------------F      middle  (arcs 1, 3 — T-joint at E)
 *           |         |                   |
 *   row 0   A---------B-------------------C      bottom  (arcs 1, 3)
 *         col 0       4                   8
 * </pre>
 *
 * <p>The vertical arcs {@code D–G} and {@code F–H} are quantized zero, which is what flattens the
 * upper cell. The complex is a disk: eight nodes, ten arcs and three patches give
 * {@code V - E + F = 1}, so the Euler characteristic is as complete a correctness check here as it
 * is on the torus, and the outer arcs bound one patch each rather than two, as a trimmed surface's
 * do.
 */
public final class PlaneLayoutFixture {

    /** Grid tiles along each axis; enough resolution to carve the arcs onto distinct vertices. */
    public static final int TILES = 16;

    /** A disk has Euler characteristic one, for any cell decomposition of it. */
    public static final int PLANE_EULER_CHARACTERISTIC = 1;

    /** Quantized length of the patch sides the figure labels 1. */
    private static final int SHORT_SIDE = 1;

    /** Quantized length of the patch sides the figure labels 3. */
    private static final int LONG_SIDE = 3;

    /** Quantized length of the zero-patch's top, the figure's 4. */
    private static final int TOP_SIDE = 4;

    /** Grid row of the bottom chain of nodes. */
    private static final int ROW_BOTTOM = 0;

    /** Grid row of the middle chain of nodes. */
    private static final int ROW_MIDDLE = 2;

    /** Grid row of the top chain of nodes. */
    private static final int ROW_TOP = 4;

    /** Grid column the T-joint sits at. */
    private static final int COLUMN_JOINT = 4;

    /** Grid column the right-hand chain of nodes sits at. */
    private static final int COLUMN_RIGHT = 8;

    public final HalfEdgeMesh plane;
    public final EmbeddedMeshTopology topology;
    public final EmbeddedTMesh tmesh;

    /** Node id by packed grid position. */
    private final Map<Long, Integer> nodeAt = new HashMap<>();

    /**
     * Builds the grid, the working copy over it, and the hand-authored T-mesh.
     */
    public PlaneLayoutFixture() {
        this.plane = buildTriangulatedGrid();
        this.topology = new EmbeddedMeshTopology(plane);
        this.tmesh = new EmbeddedTMesh(topology);
        build();
    }

    /**
     * Builds the triangulated grid the T-mesh is laid onto.
     *
     * <p>Built here rather than taken from {@link GridMeshNode} because that primitive emits quads
     * and {@link EmbeddedMeshTopology} reads three corners per face. Row-major vertex ids keep
     * {@link #copyVertex} a plain arithmetic lookup.
     *
     * @return the grid as a half-edge mesh
     */
    private static HalfEdgeMesh buildTriangulatedGrid() {
        int side = TILES + 1;
        float[] positions = new float[side * side * 3];
        for (int row = 0; row < side; row++) {
            for (int column = 0; column < side; column++) {
                int base = (row * side + column) * 3;
                positions[base] = column;
                positions[base + 1] = row;
                positions[base + 2] = 0f;
            }
        }
        int[] faces = new int[TILES * TILES * 2 * 3];
        int cursor = 0;
        for (int row = 0; row < TILES; row++) {
            for (int column = 0; column < TILES; column++) {
                int lowerLeft = row * side + column;
                int upperLeft = lowerLeft + side;
                faces[cursor++] = lowerLeft;
                faces[cursor++] = lowerLeft + 1;
                faces[cursor++] = upperLeft + 1;
                faces[cursor++] = lowerLeft;
                faces[cursor++] = upperLeft + 1;
                faces[cursor++] = upperLeft;
            }
        }
        return HalfEdgeMeshEngine.buildFromIndexedMesh(positions, faces);
    }

    /**
     * The node id at a grid position, for callers that want to reach into the fixture.
     *
     * @param column grid column
     * @param row    grid row
     * @return the node id there
     */
    public int nodeIdAt(int column, int row) {
        return nodeAt.get(key(column, row));
    }

    /**
     * Lays the nodes, arcs and patches onto the grid.
     */
    private void build() {
        for (int column : new int[] { 0, COLUMN_JOINT, COLUMN_RIGHT }) {
            addNode(column, ROW_BOTTOM);
            addNode(column, ROW_MIDDLE);
        }
        addNode(0, ROW_TOP);
        addNode(COLUMN_RIGHT, ROW_TOP);

        int bottomLeft = horizontalArc(ROW_BOTTOM, 0, COLUMN_JOINT, SHORT_SIDE);
        int bottomRight = horizontalArc(ROW_BOTTOM, COLUMN_JOINT, COLUMN_RIGHT, LONG_SIDE);
        int middleLeft = horizontalArc(ROW_MIDDLE, 0, COLUMN_JOINT, SHORT_SIDE);
        int middleRight = horizontalArc(ROW_MIDDLE, COLUMN_JOINT, COLUMN_RIGHT, LONG_SIDE);
        int top = horizontalArc(ROW_TOP, 0, COLUMN_RIGHT, TOP_SIDE);

        int leftLower = verticalArc(0, ROW_BOTTOM, ROW_MIDDLE, SHORT_SIDE);
        int jointLower = verticalArc(COLUMN_JOINT, ROW_BOTTOM, ROW_MIDDLE, SHORT_SIDE);
        int rightLower = verticalArc(COLUMN_RIGHT, ROW_BOTTOM, ROW_MIDDLE, SHORT_SIDE);

        int leftZero = verticalArc(0, ROW_MIDDLE, ROW_TOP, 0);
        int rightZero = verticalArc(COLUMN_RIGHT, ROW_MIDDLE, ROW_TOP, 0);

        addPatch(0, ROW_BOTTOM, List.of(bottomLeft), List.of(jointLower), List.of(middleLeft),
                List.of(leftLower));
        addPatch(COLUMN_JOINT, ROW_BOTTOM, List.of(bottomRight), List.of(rightLower),
                List.of(middleRight), List.of(jointLower));
        addPatch(0, ROW_MIDDLE, List.of(middleLeft, middleRight), List.of(rightZero),
                List.of(top), List.of(leftZero));
    }

    /**
     * Registers a T-mesh node at a grid position.
     *
     * @param column grid column
     * @param row    grid row
     */
    private void addNode(int column, int row) {
        int nodeId = tmesh.addNode(EmbeddedTMesh.NONE, copyVertex(column, row), false, false);
        nodeAt.put(key(column, row), nodeId);
    }

    /**
     * Adds an arc running along a row.
     *
     * @param row             grid row the arc runs along
     * @param fromColumn      column the arc starts at
     * @param toColumn        column the arc ends at
     * @param quantizedLength the arc's prescribed parametric length
     * @return the new arc's id
     */
    private int horizontalArc(int row, int fromColumn, int toColumn, int quantizedLength) {
        List<Integer> path = new ArrayList<>();
        for (int column = fromColumn; column <= toColumn; column++) {
            path.add(copyVertex(column, row));
        }
        return tmesh.addArc(EmbeddedTMesh.NONE, nodeIdAt(fromColumn, row), nodeIdAt(toColumn, row),
                quantizedLength, false, path);
    }

    /**
     * Adds an arc running up a column.
     *
     * @param column          grid column the arc runs along
     * @param fromRow         row the arc starts at
     * @param toRow           row the arc ends at
     * @param quantizedLength the arc's prescribed parametric length
     * @return the new arc's id
     */
    private int verticalArc(int column, int fromRow, int toRow, int quantizedLength) {
        List<Integer> path = new ArrayList<>();
        for (int row = fromRow; row <= toRow; row++) {
            path.add(copyVertex(column, row));
        }
        return tmesh.addArc(EmbeddedTMesh.NONE, nodeIdAt(column, fromRow), nodeIdAt(column, toRow),
                quantizedLength, false, path);
    }

    /**
     * Adds a patch whose lower-left corner sits at a grid position.
     *
     * @param column grid column of the patch's lower-left corner
     * @param row    grid row of the patch's lower-left corner
     * @param bottom arcs along the patch's bottom side
     * @param right  arcs along the patch's right side
     * @param top    arcs along the patch's top side
     * @param left   arcs along the patch's left side
     */
    private void addPatch(int column, int row, List<Integer> bottom, List<Integer> right,
            List<Integer> top, List<Integer> left) {
        tmesh.addPatch(EmbeddedTMesh.NONE, List.of(bottom, right, top, left), nodeIdAt(column, row));
    }

    /**
     * The copy vertex at a grid position.
     *
     * @param column grid column
     * @param row    grid row
     * @return the copy vertex there
     */
    private int copyVertex(int column, int row) {
        return topology.copyVertexForSourceVertexId(row * (TILES + 1) + column);
    }

    /**
     * Packs a grid position into a map key.
     *
     * @param column grid column
     * @param row    grid row
     * @return the packed key
     */
    private static long key(int column, int row) {
        return ((long) column << Integer.SIZE) | (row & 0xFFFFFFFFL);
    }
}
