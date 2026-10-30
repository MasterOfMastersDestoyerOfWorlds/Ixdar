package ixdar.game.trade.map;

/**
 * A point loops can route through: a city with a market or a town without one. Every place is a knot point.
 */
public abstract class Place {
    public final String name;
    public final int cell;
    /** This place's index among the map's places, which is also its knot point's id. */
    public final int index;

    /**
     * A place on a cell.
     *
     * @param name  its name
     * @param cell  the cell it stands on
     * @param index its index among the map's places
     */
    protected Place(String name, int cell, int index) {
        this.name = name;
        this.cell = cell;
        this.index = index;
    }

    /**
     * Whether goods can be sold here.
     *
     * @return true for a market
     */
    public abstract boolean hasMarket();
}
