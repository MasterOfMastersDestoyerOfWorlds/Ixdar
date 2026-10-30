package ixdar.game.trade.routes;

/**
 * One edit to the player's network, kept so the network can be rebuilt by replaying the list: what it
 * did, the hierarchy layer it acted at, and the places it names by index.
 *
 * @param kind   what the operation does
 * @param layer  the knot layer it acted at, 0 for whole networks
 * @param places the places it names, by index
 */
public record RouteOperation(Kind kind, int layer, int... places) {

    /**
     * The four operations: start a loop from two lone places, grow a loop by a lone place, join two
     * loops, and collapse a chain of knots.
     */
    public enum Kind {
        PIPE_NEW,
        GROW,
        JOIN,
        COLLAPSE
    }
}
