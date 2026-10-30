package ixdar.game.trade.economy;

import java.util.ArrayList;
import java.util.List;

import ixdar.game.trade.map.City;

/**
 * Clears one city's lap by merit order: cheapest sellers fill demand first, priced at the cheapest seller
 * left with blood unsold, else the ceiling. A player at price war goes first and sets everyone's price.
 */
public final class Market {

    private Market() {
    }

    /**
     * Clear a city's demand among the companies' offers there, writing the price and each company's sales
     * onto the city.
     *
     * @param city      the market
     * @param companies every company, the player among them
     */
    public static void clear(City city, List<Company> companies) {
        ArrayList<Company> order = new ArrayList<>();
        city.lastSold.clear();
        for (Company company : companies) {
            city.lastSold.put(company, 0);
            if (company.offer(city).units > 0) {
                order.add(company);
            }
        }
        order.sort((a, b) -> {
            double warA = warPrice(city, a);
            double warB = warPrice(city, b);
            if ((warA >= 0) != (warB >= 0)) {
                return warA >= 0 ? -1 : 1;
            }
            return warA >= 0 ? Double.compare(warA, warB)
                    : Double.compare(a.offer(city).unitCost(), b.offer(city).unitCost());
        });
        int remaining = city.demand;
        double lowestWarPrice = -1;
        double cheapestUnsold = city.ceiling;
        boolean anyUnsold = false;
        for (Company company : order) {
            Offer offer = company.offer(city);
            int take = Math.min(offer.units, remaining);
            city.lastSold.put(company, take);
            remaining -= take;
            double war = warPrice(city, company);
            if (take > 0 && war >= 0) {
                lowestWarPrice = lowestWarPrice < 0 ? war : Math.min(lowestWarPrice, war);
            }
            if (take < offer.units && !anyUnsold && war < 0) {
                anyUnsold = true;
                cheapestUnsold = offer.unitCost();
            }
        }
        city.lastPrice = Math.min(lowestWarPrice >= 0 ? lowestWarPrice : cheapestUnsold, city.ceiling);
    }

    /**
     * The price a company is selling at by price war in a city: only the player wages one.
     *
     * @param city    the market
     * @param company a seller
     * @return the war price, or a negative value when not at war
     */
    private static double warPrice(City city, Company company) {
        if (!company.player || city.priceWar == PriceWar.OFF) {
            return -1;
        }
        return company.offer(city).unitCost() * city.priceWar.shareOfCost;
    }
}
