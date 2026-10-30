package ixdar.game.trade.map;

import org.joml.Vector2f;

import ixdar.geometry.grid2d.IrregularQuadGrid;

/**
 * A path through the irregular grid: either through cell centres (the dual graph roads and legs walk) or
 * along cell sides through their corners (the primal graph a river follows). Roads, rivers and legs are all
 * grid paths, so they are drawn and tested for crossings the same way.
 */
public class GridPath {
    public final IrregularQuadGrid grid;
    /** Cell indices for a path through cells, corner indices for a path along sides. */
    public final int[] nodes;
    public final boolean alongSides;
    /** The path's points in world units, one per node. */
    public final Vector2f[] points;

    /**
     * A path over the given nodes.
     *
     * @param grid       the grid the nodes index into
     * @param nodes      cell or corner indices in travel order
     * @param alongSides true when the nodes are corners, false when they are cells
     */
    public GridPath(IrregularQuadGrid grid, int[] nodes, boolean alongSides) {
        this.grid = grid;
        this.nodes = nodes;
        this.alongSides = alongSides;
        this.points = new Vector2f[nodes.length];
        for (int k = 0; k < nodes.length; k++) {
            points[k] = new Vector2f(alongSides ? grid.anchorPoints().get(nodes[k]) : grid.dualPoints().get(nodes[k]));
        }
    }

    /**
     * How many steps the path takes.
     *
     * @return the node count less one
     */
    public int steps() {
        return Math.max(0, nodes.length - 1);
    }

    /**
     * Whether the path steps directly between two nodes, in either direction.
     *
     * @param a one node
     * @param b the other
     * @return true when consecutive on the path
     */
    public boolean hasStep(int a, int b) {
        for (int k = 1; k < nodes.length; k++) {
            if ((nodes[k - 1] == a && nodes[k] == b) || (nodes[k - 1] == b && nodes[k] == a)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The path's nodes in the opposite order.
     *
     * @return the reversed node list
     */
    public int[] reversedNodes() {
        int[] reversed = new int[nodes.length];
        for (int k = 0; k < nodes.length; k++) {
            reversed[k] = nodes[nodes.length - 1 - k];
        }
        return reversed;
    }
}
