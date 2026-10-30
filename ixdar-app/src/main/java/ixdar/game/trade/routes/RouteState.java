package ixdar.game.trade.routes;

import java.util.List;

/**
 * A snapshot of the player's network for undo: the operation list that rebuilds the knots. Wagons are not
 * part of it, since a loop runs one wagon per base on it.
 *
 * @param operations the operations, oldest first
 */
public record RouteState(List<RouteOperation> operations) {
}
