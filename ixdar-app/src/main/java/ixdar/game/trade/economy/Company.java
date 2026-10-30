package ixdar.game.trade.economy;

import java.util.ArrayList;
import java.util.HashMap;

import ixdar.game.trade.map.City;

/**
 * A trading company: the player or a rival. It owns gold, a home base, the facilities it builds in cities
 * and the loops its wagons run, and works out what it offers each market this lap.
 */
public class Company {
    public final String name;
    public final boolean player;
    public final City home;
    public final double startGold;
    public double gold;
    public int wagonCapacity;
    public int lapsBelowZero;
    public boolean bankrupt;
    public double lastProfit;
    public final ArrayList<Loop> loops = new ArrayList<>();
    /** What the company brings to each city this lap, rebuilt by every estimate. */
    public final HashMap<City, Offer> offers = new HashMap<>();

    /**
     * A company with its home base.
     *
     * @param name          its name
     * @param player        whether the player runs it
     * @param home          its home city, a base that costs nothing
     * @param gold          its starting gold
     * @param wagonCapacity blood one wagon carries, fuel and cargo together
     */
    public Company(String name, boolean player, City home, double gold, int wagonCapacity) {
        this.name = name;
        this.player = player;
        this.home = home;
        this.startGold = gold;
        this.gold = gold;
        this.wagonCapacity = wagonCapacity;
        home.facilities.put(this, Facility.BASE);
    }

    /**
     * The offer this company brings to a city this lap.
     *
     * @param city a city
     * @return the offer, empty when the company brings nothing
     */
    public Offer offer(City city) {
        return offers.computeIfAbsent(city, c -> new Offer());
    }
}
