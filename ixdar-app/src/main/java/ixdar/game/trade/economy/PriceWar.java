package ixdar.game.trade.economy;

/**
 * The player's price-war switch in one market: off, selling at cost, or at half cost. At war the player
 * goes first in the sell order and the war price is everyone's price.
 */
public enum PriceWar {
    OFF(-1),
    AT_COST(1),
    HALF_COST(0.5);

    /** The war price as a share of cost; negative when not at war. */
    public final double shareOfCost;

    PriceWar(double shareOfCost) {
        this.shareOfCost = shareOfCost;
    }

    /**
     * The next setting, wrapping from half cost back to off.
     *
     * @return the next setting
     */
    public PriceWar next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
