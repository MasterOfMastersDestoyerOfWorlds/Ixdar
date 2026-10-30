package ixdar.game.trade.economy;

/**
 * What one company brings to one market this lap: the units its loops carry there and their cost, a
 * units-weighted average when several loops serve the same city.
 */
public class Offer {
    public int units;
    private double weightedCost;

    /**
     * Add one loop's units at that loop's cost per unit.
     *
     * @param loopUnits units the loop brings
     * @param unitCost  the loop's cost per unit
     */
    public void add(int loopUnits, double unitCost) {
        if (loopUnits > 0) {
            units += loopUnits;
            weightedCost += loopUnits * unitCost;
        }
    }

    /**
     * The offer's cost per unit.
     *
     * @return the weighted cost, or zero with no units
     */
    public double unitCost() {
        return units == 0 ? 0 : weightedCost / units;
    }
}
