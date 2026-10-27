package ixdar.game;

import java.util.ArrayList;

/**
 * Clears one city's lap by merit order: cheapest sellers fill demand first, priced at the cheapest
 * seller left with blood unsold, else the ceiling. A price war goes first and sets everyone's price.
 */
public class TradeMarket {

    /**
     * Clear a city's demand among its sellers.
     *
     * @param demand    units the city needs this lap
     * @param ceiling   the most the town will pay per unit
     * @param offered   units each seller brings
     * @param unitCost  each seller's cost per unit
     * @param warPrice  each seller's price-war price, or a negative value when not at war
     * @param sold      written with the units each seller sells
     * @return the price every seller gets this lap
     */
    public double clear(int demand, double ceiling, int[] offered, double[] unitCost, double[] warPrice, int[] sold) {
        ArrayList<Integer> order = new ArrayList<>();
        for (int seller = 0; seller < offered.length; seller++) {
            sold[seller] = 0;
            if (offered[seller] > 0) {
                order.add(seller);
            }
        }
        order.sort((a, b) -> {
            boolean warA = warPrice[a] >= 0;
            boolean warB = warPrice[b] >= 0;
            if (warA != warB) {
                return warA ? -1 : 1;
            }
            return warA ? Double.compare(warPrice[a], warPrice[b]) : Double.compare(unitCost[a], unitCost[b]);
        });
        int remaining = demand;
        double lowestWarPrice = -1;
        double cheapestUnsold = ceiling;
        boolean anyUnsold = false;
        for (int seller : order) {
            int take = Math.min(offered[seller], remaining);
            sold[seller] = take;
            remaining -= take;
            if (take > 0 && warPrice[seller] >= 0) {
                lowestWarPrice = lowestWarPrice < 0 ? warPrice[seller] : Math.min(lowestWarPrice, warPrice[seller]);
            }
            if (take < offered[seller] && !anyUnsold && warPrice[seller] < 0) {
                anyUnsold = true;
                cheapestUnsold = unitCost[seller];
            }
        }
        if (lowestWarPrice >= 0) {
            return Math.min(lowestWarPrice, ceiling);
        }
        return Math.min(cheapestUnsold, ceiling);
    }
}
