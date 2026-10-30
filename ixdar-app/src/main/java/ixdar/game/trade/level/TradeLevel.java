package ixdar.game.trade.level;

import java.util.ArrayList;

import ixdar.game.trade.economy.Facility;
import ixdar.game.trade.economy.Rules;

/**
 * One level as its {@code .ix} file describes it: the grid to generate, the terrain and places laid on
 * it, the companies with their starting networks, the tuning, and how the level is won.
 */
public class TradeLevel {
    public static final String[] CAMPAIGN = { "first_blood.ix", "fuel.ix", "small_loops.ix", "far_bases.ix",
        "blood_war.ix", "generated.ix" };

    public String title = "";
    public String goal = "";
    public int laps = 10;
    /** The unit share of all demand that wins; zero on a level won on gold. */
    public double shareGoal;
    /** Gold earned by the lap limit for bronze, silver and gold. */
    public int[] medals = new int[0];
    public long seed;
    public float width;
    public float height;
    public int targetCells;
    public int generatedTowns;
    public int generatedCities;
    public final Rules rules = new Rules();

    public final ArrayList<CitySpec> cities = new ArrayList<>();
    public final ArrayList<TownSpec> towns = new ArrayList<>();
    /** Each river as x, y pairs. */
    public final ArrayList<float[]> rivers = new ArrayList<>();
    /** Each bridge as the x, y it should sit nearest. */
    public final ArrayList<float[]> bridges = new ArrayList<>();
    /** Each moor as an ellipse: centre x, centre y, radius x, radius y. */
    public final ArrayList<float[]> moors = new ArrayList<>();
    public final ArrayList<CompanySpec> companies = new ArrayList<>();
    public final ArrayList<FacilitySpec> facilities = new ArrayList<>();
    public final ArrayList<LoopSpec> loops = new ArrayList<>();

    /**
     * A market city.
     *
     * @param name    its name, one word
     * @param x       where it should sit
     * @param y       where it should sit
     * @param demand  units it needs per lap
     * @param ceiling the most it pays per unit
     */
    public record CitySpec(String name, float x, float y, int demand, double ceiling) {
    }

    /**
     * A town: a stop for loops with no market.
     *
     * @param name its name, one word
     * @param x    where it should sit
     * @param y    where it should sit
     */
    public record TownSpec(String name, float x, float y) {
    }

    /**
     * A company and its home base.
     *
     * @param name          its name, one word
     * @param player        whether the player runs it
     * @param home          its home city's name
     * @param gold          its starting gold
     * @param wagonCapacity blood one wagon carries
     */
    public record CompanySpec(String name, boolean player, String home, int gold, int wagonCapacity) {
    }

    /**
     * A station or base a company starts with.
     *
     * @param company  the company's name
     * @param city     the city's name
     * @param facility what is built there
     */
    public record FacilitySpec(String company, String city, Facility facility) {
    }

    /**
     * A loop a company starts with.
     *
     * @param company the company's name
     * @param wagons  wagons on it
     * @param places  its places in visiting order
     */
    public record LoopSpec(String company, int wagons, String[] places) {
    }
}
