package ixdar.game.trade.economy;

import ixdar.game.trade.map.City;
import ixdar.game.trade.map.Place;

/**
 * One loop a company's wagons run: its places in travel order, starting at a base when it passes one,
 * one wagon for each of the company's bases on it, and what the last estimate worked out for it.
 */
public class Loop {
    public final Company owner;
    public final Place[] stops;
    /** One per base of the owner's on the loop; a loop without a base runs none. */
    public int wagons;
    /** Blood one wagon burns going once round. */
    public int burn;
    /** Units the loop's wagons sell this lap, all of them together. */
    public int cargo;
    /** How far the longest stretch between bases outruns a wagon's tank, zero when it does not. */
    public int fuelDeficit;
    /** Cost per unit sold, infinite when the loop sells nothing. */
    public double unitCost = Double.POSITIVE_INFINITY;
    public boolean passesBase;

    /**
     * A loop with its stops, running one wagon per base on it.
     *
     * @param owner the company that runs it
     * @param stops its places in travel order
     */
    public Loop(Company owner, Place[] stops) {
        this.owner = owner;
        this.stops = stops;
        this.wagons = baseCount();
    }

    /**
     * How many of the owner's bases the loop passes, which is how many wagons it runs.
     *
     * @return the bases on the loop
     */
    public int baseCount() {
        int bases = 0;
        for (Place stop : stops) {
            bases += stop instanceof City city && city.facility(owner) == Facility.BASE ? 1 : 0;
        }
        return bases;
    }
}
