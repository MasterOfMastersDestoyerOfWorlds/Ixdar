package ixdar.game.trade.map;

import java.util.HashSet;

import ixdar.geometry.grid2d.IrregularQuadGrid;

/**
 * A river along cell sides: every step between two cells whose shared side the river follows is closed,
 * unless a {@link Bridge} opens it.
 */
public class River extends GridPath {
    /** The sides the river runs along, as order-free corner-pair keys. */
    public final HashSet<Long> sides = new HashSet<>();

    /**
     * A river through the given corners.
     *
     * @param grid    the grid
     * @param corners the corners it runs through, source to mouth
     */
    public River(IrregularQuadGrid grid, int[] corners) {
        super(grid, corners, true);
        for (int k = 1; k < corners.length; k++) {
            sides.add((long) Math.min(corners[k - 1], corners[k]) * grid.anchorCount()
                    + Math.max(corners[k - 1], corners[k]));
        }
    }

    /**
     * Whether the river runs between two neighbouring cells.
     *
     * @param a one cell
     * @param b a neighbouring cell
     * @return true when their shared side is the river's
     */
    public boolean separates(int a, int b) {
        int[] side = grid.sharedSide(a, b);
        return side != null
                && sides.contains((long) Math.min(side[0], side[1]) * grid.anchorCount() + Math.max(side[0], side[1]));
    }
}
