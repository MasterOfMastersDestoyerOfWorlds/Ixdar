package ixdar.game.trade.routes;

import ixdar.game.trade.map.GridPath;
import ixdar.game.trade.map.Place;
import ixdar.game.trade.map.TradeMap;

/**
 * The cheapest grid route from one place to another: the cells a wagon passes, the blood it burns there
 * and how many of its steps leave the roads.
 */
public class Leg extends GridPath {
    public static final int UNREACHABLE_COST_BEYOND_ANY_TANK = 1_000_000;

    public final Place from;
    public final Place to;
    public final int cost;
    public final int offRoadSteps;

    /**
     * Route a leg on the map.
     *
     * @param map  the terrain to route over
     * @param from where the leg starts
     * @param to   where it ends
     */
    public Leg(TradeMap map, Place from, Place to) {
        this(map, from, to, map.grid.shortestPath(from.cell, to.cell, map::stepCost));
    }

    /**
     * A leg along known cells, as the reverse of a routed leg is.
     *
     * @param map   the terrain
     * @param from  where the leg starts
     * @param to    where it ends
     * @param cells the cells in travel order, or null when the grid has no route
     */
    public Leg(TradeMap map, Place from, Place to, int[] cells) {
        super(map.grid, cells == null ? new int[] { from.cell, to.cell } : cells, false);
        this.from = from;
        this.to = to;
        int burn = 0;
        int off = 0;
        for (int k = 1; k < nodes.length; k++) {
            boolean road = map.onRoad(nodes[k - 1], nodes[k]);
            burn += road ? TradeMap.ON_ROAD_COST : TradeMap.OFF_ROAD_COST;
            off += road ? 0 : 1;
        }
        this.cost = cells == null ? UNREACHABLE_COST_BEYOND_ANY_TANK : burn;
        this.offRoadSteps = off;
    }
}
