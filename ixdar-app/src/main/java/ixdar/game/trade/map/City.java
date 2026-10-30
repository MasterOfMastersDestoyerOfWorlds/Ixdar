package ixdar.game.trade.map;

import java.util.HashMap;

import ixdar.game.trade.economy.Company;
import ixdar.game.trade.economy.Facility;
import ixdar.game.trade.economy.PriceWar;

/**
 * A city: a place with a market that needs a fixed amount of blood each lap. Its demand shrinks when it
 * starves and regrows towards what its farmland carries; each company may own a station or a base here.
 */
public class City extends Place {
    public final int baseDemand;
    public final double baseCeiling;
    public int demand;
    public double ceiling;
    /** The player's price-war setting in this market. */
    public PriceWar priceWar = PriceWar.OFF;
    public final HashMap<Company, Facility> facilities = new HashMap<>();
    /** Consecutive laps each company has sold here at a loss or not at all while offering. */
    public final HashMap<Company, Integer> losingLaps = new HashMap<>();
    /** Whether the rival has ever sold here: a market is only contested once it has. */
    public boolean rivalEverSold;
    /** The price the last lap cleared at. */
    public double lastPrice;
    /** Units each company sold here on the last lap. */
    public final HashMap<Company, Integer> lastSold = new HashMap<>();

    /**
     * A city with a market.
     *
     * @param name    its name
     * @param cell    the cell it stands on
     * @param index   its index among the map's places
     * @param demand  units it needs per lap
     * @param ceiling the most it pays per unit
     */
    public City(String name, int cell, int index, int demand, double ceiling) {
        super(name, cell, index);
        this.baseDemand = demand;
        this.demand = demand;
        this.baseCeiling = ceiling;
        this.ceiling = ceiling;
    }

    /**
     * Cities have a market.
     *
     * @return true
     */
    @Override
    public boolean hasMarket() {
        return true;
    }

    /**
     * What a company has built here.
     *
     * @param company a company
     * @return its facility, NONE when it has none
     */
    public Facility facility(Company company) {
        return facilities.getOrDefault(company, Facility.NONE);
    }
}
