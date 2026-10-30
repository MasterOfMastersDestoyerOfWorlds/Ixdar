package ixdar.game.trade.map;

/**
 * An intermediate town at a crossroads or along a long road: loops may route through it, but it has no
 * market.
 */
public class Town extends Place {

    /**
     * A town on a cell.
     *
     * @param name  its name
     * @param cell  the cell it stands on
     * @param index its index among the map's places
     */
    public Town(String name, int cell, int index) {
        super(name, cell, index);
    }

    /**
     * Towns have no market.
     *
     * @return false
     */
    @Override
    public boolean hasMarket() {
        return false;
    }
}
