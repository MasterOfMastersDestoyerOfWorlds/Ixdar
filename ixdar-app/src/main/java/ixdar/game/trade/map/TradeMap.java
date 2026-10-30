package ixdar.game.trade.map;

import java.util.ArrayList;
import java.util.HashSet;

import ixdar.geometry.grid2d.IrregularQuadGrid;

/**
 * A trade level's terrain on its irregular grid: the places loops route through, the roads between them,
 * rivers and their bridges, and the moor. It prices each step a wagon takes between neighbouring cells.
 */
public class TradeMap {
    public static final int ON_ROAD_COST = 1;
    public static final int OFF_ROAD_COST = 2;

    public final IrregularQuadGrid grid;
    public final ArrayList<Place> places = new ArrayList<>();
    public final ArrayList<City> cities = new ArrayList<>();
    public final ArrayList<Town> towns = new ArrayList<>();
    public final ArrayList<Road> roads = new ArrayList<>();
    public final ArrayList<River> rivers = new ArrayList<>();
    public final ArrayList<Bridge> bridges = new ArrayList<>();
    public final Moor moor = new Moor();
    /** Every step some road takes, by {@link #stepKey}. */
    public final HashSet<Long> roadSteps = new HashSet<>();

    /**
     * An empty map on a grid; the level reader or generator adds its objects.
     *
     * @param grid the grid the map lies on
     */
    public TradeMap(IrregularQuadGrid grid) {
        this.grid = grid;
    }

    /**
     * Add a place, keeping the places, cities and towns lists in step.
     *
     * @param place a city or a town whose index is the next free one
     */
    public void add(Place place) {
        places.add(place);
        if (place instanceof City city) {
            cities.add(city);
        } else if (place instanceof Town town) {
            towns.add(town);
        }
    }

    /**
     * Add a road and index its steps for {@link #onRoad}.
     *
     * @param road a stretch of road from one node of the network to the next
     */
    public void add(Road road) {
        roads.add(road);
        for (int k = 1; k < road.nodes.length; k++) {
            roadSteps.add(stepKey(road.nodes[k - 1], road.nodes[k]));
        }
    }

    /**
     * One key for the step between two cells, the same either way round.
     *
     * @param a one cell
     * @param b the other
     * @return the step's key
     */
    public long stepKey(int a, int b) {
        return (long) Math.min(a, b) * grid.dualPoints().size() + Math.max(a, b);
    }

    /**
     * Whether a wagon may step between two neighbouring cells: not into the moor, and not across a river
     * except over a bridge.
     *
     * @param a one cell
     * @param b a neighbouring cell
     * @return true when the step is open
     */
    public boolean canStep(int a, int b) {
        if (moor.blocks(a) || moor.blocks(b)) {
            return false;
        }
        for (River river : rivers) {
            if (river.separates(a, b)) {
                for (Bridge bridge : bridges) {
                    if (bridge.river == river && bridge.opens(a, b)) {
                        return true;
                    }
                }
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a step runs along a road.
     *
     * @param a one cell
     * @param b a neighbouring cell
     * @return true on a road
     */
    public boolean onRoad(int a, int b) {
        return roadSteps.contains(stepKey(a, b));
    }

    /**
     * What a step costs a wagon in blood: cheap on a road, dearer off it, impossible when closed.
     *
     * @param a one cell
     * @param b a neighbouring cell
     * @return the step's cost, or infinity when closed
     */
    public double stepCost(int a, int b) {
        if (!canStep(a, b)) {
            return Double.POSITIVE_INFINITY;
        }
        return onRoad(a, b) ? ON_ROAD_COST : OFF_ROAD_COST;
    }
}
