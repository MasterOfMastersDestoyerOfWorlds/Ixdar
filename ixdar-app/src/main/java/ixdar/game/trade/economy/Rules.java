package ixdar.game.trade.economy;

/**
 * The economy's tuning for one level: prices, upkeep, production and how towns starve. A level file's
 * {@code RULES key value} records override the defaults by field name.
 */
public class Rules {
    public double harvestCost = 1.0;
    public int stationPrice = 50;
    public int stationUpkeep = 6;
    /** Upkeep per lap of each wagon; a loop runs one wagon for every base on it. */
    public int wagonUpkeep = 4;
    /** A base's price is this plus {@link #basePricePerCell} for each cell of route from headquarters. */
    public int basePrice = 60;
    public double basePricePerCell = 4;
    /** A base's upkeep per lap for each cell of route from headquarters, which itself costs nothing. */
    public double baseUpkeepPerCell = 0.25;
    public int homeProduction = 400;
    public int baseProduction = 200;
    public int bankruptLaps = 3;
    public int withdrawLaps = 3;
    public double demandShrink = 0.25;
    public double ceilingRise = 1.1;
    public double ceilingRelax = 0.97;
    public double ceilingMaxFactor = 1.6;
    public int minimumDemand = 5;

    /**
     * Set one rule by its field name, as a level file's {@code RULES} record does.
     *
     * @param key   the field name
     * @param value its new value
     * @throws IllegalArgumentException for an unknown rule
     */
    public void set(String key, double value) {
        switch (key) {
        case "harvestCost" -> harvestCost = value;
        case "stationPrice" -> stationPrice = (int) value;
        case "stationUpkeep" -> stationUpkeep = (int) value;
        case "wagonUpkeep" -> wagonUpkeep = (int) value;
        case "basePrice" -> basePrice = (int) value;
        case "basePricePerCell" -> basePricePerCell = value;
        case "baseUpkeepPerCell" -> baseUpkeepPerCell = value;
        case "homeProduction" -> homeProduction = (int) value;
        case "baseProduction" -> baseProduction = (int) value;
        case "bankruptLaps" -> bankruptLaps = (int) value;
        case "withdrawLaps" -> withdrawLaps = (int) value;
        case "demandShrink" -> demandShrink = value;
        case "ceilingRise" -> ceilingRise = value;
        case "ceilingRelax" -> ceilingRelax = value;
        case "ceilingMaxFactor" -> ceilingMaxFactor = value;
        case "minimumDemand" -> minimumDemand = (int) value;
        default -> throw new IllegalArgumentException("unknown rule " + key);
        }
    }
}
