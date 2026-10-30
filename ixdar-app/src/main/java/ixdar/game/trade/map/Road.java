package ixdar.game.trade.map;

import ixdar.geometry.grid2d.IrregularQuadGrid;

/**
 * A stretch of road through cell centres, from one node of the road network to the next (a place, or a
 * cell where roads meet): a wagon steps along it at the cheap on-road price. No two stretches share a step.
 */
public class Road extends GridPath {

    /**
     * A road through the given cells.
     *
     * @param grid  the grid
     * @param cells the cells it runs through, end to end
     */
    public Road(IrregularQuadGrid grid, int[] cells) {
        super(grid, cells, false);
    }
}
