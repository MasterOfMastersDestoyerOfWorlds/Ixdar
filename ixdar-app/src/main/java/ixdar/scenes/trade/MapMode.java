package ixdar.scenes.trade;

/**
 * What the map's cells are tinted by: nothing, which company sells most in the nearest market, or how
 * much blood the nearest market needs.
 */
public enum MapMode {
    NETWORK("Network"),
    OWNER("Owner"),
    MARKET_SIZE("Market size");

    public final String label;

    MapMode(String label) {
        this.label = label;
    }

    /**
     * The next mode, wrapping round.
     *
     * @return the next mode
     */
    public MapMode next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
