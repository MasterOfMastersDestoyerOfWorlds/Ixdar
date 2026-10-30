package ixdar.game.trade.routes;

import ixdar.game.trade.map.Place;

/**
 * A lone place a loop could grow to: the cells growing there adds and the loop's stops once grown.
 *
 * @param place      the lone place
 * @param addedCells the extra blood one wagon burns going round
 * @param grownStops the loop's places in travel order with the new one in
 */
public record GrowTarget(Place place, int addedCells, Place[] grownStops) {
}
