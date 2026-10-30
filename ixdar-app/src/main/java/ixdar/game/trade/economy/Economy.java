package ixdar.game.trade.economy;

import java.util.ArrayList;
import java.util.List;

import ixdar.game.trade.map.City;
import ixdar.game.trade.map.Place;
import ixdar.game.trade.map.TradeMap;
import ixdar.game.trade.routes.RouteTable;

/**
 * The lap economy: what each company's loops carry and what it costs them, and settling a lap by clearing
 * every market, paying harvest and upkeep, and letting starved cities shrink.
 */
public class Economy {
    public final TradeMap map;
    public final RouteTable table;
    public final Rules rules;
    public final List<Company> companies;

    /**
     * The economy of one level.
     *
     * @param map       the level's map
     * @param table     the legs between its places
     * @param rules     its tuning
     * @param companies every company, the player first
     */
    public Economy(TradeMap map, RouteTable table, Rules rules, List<Company> companies) {
        this.map = map;
        this.table = table;
        this.rules = rules;
        this.companies = companies;
    }

    /**
     * What a base in a city costs a company: dearer the further it is from headquarters by road.
     *
     * @param company the builder
     * @param city    the city
     * @return the price in gold
     */
    public double basePrice(Company company, City city) {
        return rules.basePrice + rules.basePricePerCell * table.cost(company.home, city);
    }

    /**
     * What a base in a city costs a company each lap, before the wagon it runs: nothing at headquarters,
     * more the further from it.
     *
     * @param company the owner
     * @param city    the city
     * @return gold per lap
     */
    public double baseUpkeep(Company company, City city) {
        return rules.baseUpkeepPerCell * table.cost(company.home, city);
    }

    /**
     * The most blood a wagon burns between two refills at the company's bases, or going once round when
     * the loop passes one base or none.
     *
     * @param company the loop's owner
     * @param stops   the loop's places in travel order
     * @return blood burned on the longest stretch
     */
    public int longestStretch(Company company, Place[] stops) {
        int start = 0;
        for (int k = stops.length - 1; k >= 0; k--) {
            start = stops[k] instanceof City city && city.facility(company) == Facility.BASE ? k : start;
        }
        int longest = 0;
        int stretch = 0;
        for (int k = 0; k < stops.length; k++) {
            Place to = stops[(start + k + 1) % stops.length];
            stretch += table.cost(stops[(start + k) % stops.length], to);
            if (to instanceof City city && city.facility(company) == Facility.BASE) {
                longest = Math.max(longest, stretch);
                stretch = 0;
            }
        }
        return Math.max(longest, stretch);
    }

    /**
     * Work out every company's loops and offers for this lap without settling. A loop runs one wagon per
     * base on it; each burns the loop's cells and sells the rest, uncontested cities first.
     */
    public void estimate() {
        for (Company company : companies) {
            company.offers.clear();
            int production = 0;
            for (City city : map.cities) {
                if (city.facility(company) == Facility.BASE) {
                    production += city == company.home ? rules.homeProduction : rules.baseProduction;
                }
            }
            for (Loop loop : company.loops) {
                loop.burn = 0;
                int baseVisits = loop.baseCount();
                loop.wagons = baseVisits;
                ArrayList<City> markets = new ArrayList<>();
                for (int k = 0; k < loop.stops.length; k++) {
                    loop.burn += table.cost(loop.stops[k], loop.stops[(k + 1) % loop.stops.length]);
                    if (loop.stops[k] instanceof City city && city.facility(company) != Facility.NONE
                            && city.demand > 0 && !markets.contains(city)) {
                        markets.add(city);
                    }
                }
                loop.passesBase = baseVisits > 0;
                loop.fuelDeficit = Math.max(0, longestStretch(company, loop.stops) - company.wagonCapacity);
                loop.cargo = 0;
                loop.unitCost = Double.POSITIVE_INFINITY;
                if (!loop.passesBase || loop.fuelDeficit > 0 || company.bankrupt) {
                    continue;
                }
                // One tankful loads at each base per lap and every wagon burns the whole loop, so a long loop
                // through many bases pays its length once per wagon rather than gaining a tank per base squared.
                int capacityCargo = Math.max(0, company.wagonCapacity * baseVisits - loop.burn * loop.wagons);
                int remaining = Math.min(capacityCargo, Math.max(0, production - loop.burn * loop.wagons));
                markets.sort((x, y) -> Double.compare(contested(y, company) ? 0 : y.ceiling,
                        contested(x, company) ? 0 : x.ceiling));
                int[] given = new int[markets.size()];
                for (int m = 0; m < markets.size(); m++) {
                    City city = markets.get(m);
                    given[m] = Math.max(0, Math.min(remaining, city.demand - company.offer(city).units));
                    remaining -= given[m];
                    loop.cargo += given[m];
                }
                production -= loop.burn * loop.wagons + loop.cargo;
                if (loop.cargo > 0) {
                    loop.unitCost = (rules.harvestCost * (loop.burn * loop.wagons + loop.cargo)
                            + rules.stationUpkeep * markets.size() + rules.wagonUpkeep * loop.wagons) / loop.cargo;
                }
                for (int m = 0; m < markets.size(); m++) {
                    company.offer(markets.get(m)).add(given[m], loop.unitCost);
                }
            }
        }
    }

    /**
     * Settle the lap: every market clears by merit order, each company pays harvest on what it burned and
     * sold plus upkeep, starved cities shrink and pay more, losing rivals withdraw, and a company three
     * laps below zero goes bankrupt.
     */
    public void settle() {
        estimate();
        double[] revenue = new double[companies.size()];
        int[] sold = new int[companies.size()];
        for (City city : map.cities) {
            Market.clear(city, companies);
            int cityTotal = 0;
            for (int c = 0; c < companies.size(); c++) {
                Company company = companies.get(c);
                int units = city.lastSold.get(company);
                revenue[c] += units * city.lastPrice;
                sold[c] += units;
                cityTotal += units;
                city.rivalEverSold |= !company.player && units > 0;
                Offer offer = company.offer(city);
                boolean losing = !company.player && offer.units > 0
                        && (units == 0 || city.lastPrice < offer.unitCost());
                int laps = losing ? city.losingLaps.getOrDefault(company, 0) + 1 : 0;
                city.losingLaps.put(company, laps);
                if (laps >= rules.withdrawLaps && city.facility(company) == Facility.STATION) {
                    city.facilities.remove(company);
                }
            }
            int shortfall = city.demand - cityTotal;
            if (city.demand > 0 && shortfall > 0) {
                city.demand = Math.max(rules.minimumDemand,
                        city.demand - (int) Math.ceil(shortfall * rules.demandShrink));
                city.ceiling = Math.min(city.baseCeiling * rules.ceilingMaxFactor, city.ceiling * rules.ceilingRise);
            } else if (city.demand > 0) {
                city.demand = Math.min(city.baseDemand, city.demand + 1);
                city.ceiling = Math.max(city.baseCeiling, city.ceiling * rules.ceilingRelax);
            }
        }
        for (int c = 0; c < companies.size(); c++) {
            Company company = companies.get(c);
            if (company.bankrupt) {
                company.lastProfit = 0;
                continue;
            }
            double expenses = rules.harvestCost * sold[c];
            for (Loop loop : company.loops) {
                expenses += (loop.cargo > 0 ? rules.harvestCost * loop.burn * loop.wagons : 0)
                        + rules.wagonUpkeep * loop.wagons;
            }
            for (City city : map.cities) {
                Facility facility = city.facility(company);
                expenses += facility == Facility.STATION ? rules.stationUpkeep : 0;
                expenses += facility == Facility.BASE ? baseUpkeep(company, city) : 0;
            }
            company.lastProfit = revenue[c] - expenses;
            company.gold += company.lastProfit;
            company.lapsBelowZero = company.gold < 0 ? company.lapsBelowZero + 1 : 0;
            if (company.lapsBelowZero >= rules.bankruptLaps) {
                company.bankrupt = true;
                for (City city : map.cities) {
                    city.facilities.remove(company);
                }
                company.loops.clear();
            }
        }
    }

    /**
     * Whether another company also sells in a city.
     *
     * @param city    a city
     * @param company the company asking
     * @return true when some other company has a station or base there
     */
    public boolean contested(City city, Company company) {
        for (Company other : companies) {
            if (other != company && city.facility(other) != Facility.NONE) {
                return true;
            }
        }
        return false;
    }
}
