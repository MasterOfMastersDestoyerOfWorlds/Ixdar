package ixdar.game.trade;

import java.util.ArrayList;
import java.util.HashMap;

import ixdar.game.trade.economy.Company;
import ixdar.game.trade.economy.Economy;
import ixdar.game.trade.economy.Facility;
import ixdar.game.trade.economy.Loop;
import ixdar.game.trade.economy.Outcome;
import ixdar.game.trade.level.TradeLevel;
import ixdar.game.trade.level.TradeLevel.CompanySpec;
import ixdar.game.trade.level.TradeLevel.FacilitySpec;
import ixdar.game.trade.level.TradeLevel.LoopSpec;
import ixdar.game.trade.level.TradeMapGenerator;
import ixdar.game.trade.map.City;
import ixdar.game.trade.map.Place;
import ixdar.game.trade.map.TradeMap;
import ixdar.game.trade.routes.RouteNetwork;
import ixdar.game.trade.routes.RouteTable;

/**
 * One game of a trade level: its generated map and legs, the companies with the player's knot network
 * and the rivals' fixed loops, the lap economy, and whether the level is won.
 */
public class TradeGame {
    public static final int MEDAL_GOLD = 3;
    public static final int PERCENT = 100;

    public final TradeLevel level;
    public final TradeMap map;
    public final RouteTable table;
    public final ArrayList<Company> companies = new ArrayList<>();
    public final Company player;
    /** The first rival, or null on a level without one. */
    public final Company rival;
    public final RouteNetwork network;
    public final Economy economy;

    public int lap = 1;
    public Outcome outcome = Outcome.PLAYING;
    public int medal;
    public String outcomeText = "";

    /**
     * Build a level from its file: the map, the legs between every pair of places, the companies with
     * their bases, stations and starting loops, and a first estimate.
     *
     * @param level the level to play
     */
    public TradeGame(TradeLevel level) {
        this.level = level;
        map = TradeMapGenerator.build(level);
        table = new RouteTable(map);
        HashMap<String, Place> byName = new HashMap<>();
        for (Place place : map.places) {
            byName.put(place.name, place);
        }
        // The file's own city names, which differ from the drawn ones where headquarters is renamed.
        for (int k = 0; k < level.cities.size(); k++) {
            byName.put(level.cities.get(k).name(), map.cities.get(k));
        }
        HashMap<String, Company> companyByName = new HashMap<>();
        Company playerCompany = null;
        Company rivalCompany = null;
        for (CompanySpec spec : level.companies) {
            Company company = new Company(spec.name(), spec.player(), (City) byName.get(spec.home()), spec.gold(),
                    spec.wagonCapacity());
            companies.add(company);
            companyByName.put(spec.name(), company);
            playerCompany = spec.player() && playerCompany == null ? company : playerCompany;
            rivalCompany = !spec.player() && rivalCompany == null ? company : rivalCompany;
        }
        player = playerCompany;
        rival = rivalCompany;
        companies.remove(player);
        companies.add(0, player);
        for (FacilitySpec spec : level.facilities) {
            ((City) byName.get(spec.city())).facilities.put(companyByName.get(spec.company()), spec.facility());
        }
        network = new RouteNetwork(map, table, player);
        for (LoopSpec spec : level.loops) {
            Company company = companyByName.get(spec.company());
            Place[] stops = new Place[spec.places().length];
            for (int k = 0; k < stops.length; k++) {
                stops[k] = byName.get(spec.places()[k]);
            }
            if (company != player) {
                company.loops.add(new Loop(company, stops));
                continue;
            }
            network.pipe(stops[0], stops[1], 0);
            for (int k = 2; k < stops.length; k++) {
                network.grow(stops[0], stops[k], 0);
            }
        }
        network.history.clear();
        economy = new Economy(map, table, level.rules, companies);
        economy.estimate();
    }

    /**
     * End the lap: settle every market and company, then check for a win, a bankruptcy or the lap limit.
     * Route edits from earlier laps stay on the undo history.
     */
    public void endLap() {
        if (outcome != Outcome.PLAYING) {
            return;
        }
        economy.settle();
        double share = share(player);
        long sharePercent = Math.round(share * PERCENT);
        boolean rivalsGone = rival != null && companies.stream().allMatch(c -> c.player || c.bankrupt);
        if (player.bankrupt) {
            outcome = Outcome.LOST;
            outcomeText = "Bankrupt: " + level.rules.bankruptLaps + " laps below zero gold.";
        } else if (level.shareGoal > 0 && share >= level.shareGoal) {
            outcome = Outcome.WON;
            outcomeText = "You hold " + sharePercent + "% of the blood trade.";
        } else if (level.shareGoal > 0 && rivalsGone) {
            outcome = Outcome.WON;
            outcomeText = "The rival is bankrupt.";
        } else if (lap >= level.laps && level.shareGoal > 0) {
            outcome = Outcome.LOST;
            outcomeText = "Out of laps at " + sharePercent + "% of the trade.";
        } else if (lap >= level.laps) {
            double earned = player.gold - player.startGold;
            medal = 0;
            for (int threshold : level.medals) {
                medal += earned >= threshold ? 1 : 0;
            }
            outcome = medal > 0 ? Outcome.WON : Outcome.LOST;
            outcomeText = "Earned " + Math.round(earned) + " gold.";
        }
        lap++;
        economy.estimate();
    }

    /**
     * A company's share of all demand on the last lap.
     *
     * @param company a company
     * @return units sold over demand, between zero and one
     */
    public double share(Company company) {
        int sold = 0;
        int total = 0;
        for (City city : map.cities) {
            sold += city.lastSold.getOrDefault(company, 0);
            total += city.demand;
        }
        return total == 0 ? 0 : (double) sold / total;
    }

    /**
     * A contested city's medal: bronze when the player outsells the rival, silver when the rival is also
     * selling below its cost, gold once the rival has withdrawn.
     *
     * @param city a city
     * @return 0 for none up to {@link #MEDAL_GOLD}
     */
    public int marketMedal(City city) {
        int yours = city.lastSold.getOrDefault(player, 0);
        if (rival == null || !city.rivalEverSold || yours == 0) {
            return 0;
        }
        if (city.facility(rival) == Facility.NONE) {
            return MEDAL_GOLD;
        }
        int theirs = city.lastSold.getOrDefault(rival, 0);
        if (yours <= theirs) {
            return 0;
        }
        return theirs > 0 && city.lastPrice < rival.offer(city).unitCost() ? 2 : 1;
    }

    /**
     * Buy a station in a city, where the player's wagons may then sell.
     *
     * @param city the city
     * @return true when bought
     */
    public boolean buyStation(City city) {
        if (city.facility(player) != Facility.NONE || player.gold < level.rules.stationPrice) {
            return false;
        }
        player.gold -= level.rules.stationPrice;
        city.facilities.put(player, Facility.STATION);
        economy.estimate();
        return true;
    }

    /**
     * Build a base in any city, priced by its distance from headquarters: it produces blood, refills
     * wagons and runs one wagon on the loop through it.
     *
     * @param city the city
     * @return true when built
     */
    public boolean buildBase(City city) {
        double price = economy.basePrice(player, city);
        if (city.facility(player) == Facility.BASE || player.gold < price) {
            return false;
        }
        player.gold -= price;
        city.facilities.put(player, Facility.BASE);
        economy.estimate();
        return true;
    }
}
