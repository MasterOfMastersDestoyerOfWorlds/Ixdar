package ixdar.game.trade.routes;

import ixdar.game.trade.map.Place;
import ixdar.game.trade.map.TradeMap;

/**
 * The leg between every pair of places, routed once when a level is built; the leg back is the leg out
 * reversed, so both directions cost the same.
 */
public class RouteTable {
    private final Leg[][] legs;

    /**
     * Route every pair of the map's places.
     *
     * @param map the level's terrain and places
     */
    public RouteTable(TradeMap map) {
        int count = map.places.size();
        legs = new Leg[count][count];
        for (int a = 0; a < count; a++) {
            for (int b = a; b < count; b++) {
                Place from = map.places.get(a);
                Place to = map.places.get(b);
                Leg out = new Leg(map, from, to);
                legs[a][b] = out;
                legs[b][a] = new Leg(map, to, from,
                        out.cost == Leg.UNREACHABLE_COST_BEYOND_ANY_TANK ? null : out.reversedNodes());
            }
        }
    }

    /**
     * The leg from one place to another.
     *
     * @param from where it starts
     * @param to   where it ends
     * @return the leg
     */
    public Leg leg(Place from, Place to) {
        return legs[from.index][to.index];
    }

    /**
     * The blood a wagon burns from one place to another.
     *
     * @param from where it starts
     * @param to   where it ends
     * @return the leg's cost in cells
     */
    public int cost(Place from, Place to) {
        return legs[from.index][to.index].cost;
    }
}
