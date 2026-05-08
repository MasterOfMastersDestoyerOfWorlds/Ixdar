package ixdar.game;

import java.util.ArrayList;
import java.util.HashMap;

import ixdar.geometry.knot.Knot;
import ixdar.geometry.point.Grid;
import ixdar.geometry.point.PointND;
import ixdar.geometry.point.PointSet;
import ixdar.geometry.shell.DistanceMatrix;
import ixdar.geometry.shell.Shell;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.sdf.SDFLine;

/**
 * Represents the network of cities and roads in the trade game world. This is
 * the infrastructure that exists - all possible routes between cities. Distinct
 * from player routes (Knot) which are the actual trade routes built by the
 * player.
 */
public class CityNetwork {
    public static final float NUM_50 = 50f;

    public ArrayList<City> cities;
    public ArrayList<Road> roads;
    public Grid grid;
    public City headquartersCity;

    private HashMap<String, City> cityLookup;
    private SDFLine roadLine = new SDFLine();

    // Trade route infrastructure
    private Shell tradeShell;
    private HashMap<City, Knot> cityToKnot = new HashMap<>();
    private boolean tradeInitialized = false;

    /**
     * Create an empty city network with the given grid type.
     *
     * @param grid the grid for city placement (CartesianGrid or HexGrid)
     */
    public CityNetwork(Grid grid) {
        this.grid = grid;
        this.cities = new ArrayList<>();
        this.roads = new ArrayList<>();
        this.cityLookup = new HashMap<>();
    }

    /**
     * Create a city network with existing cities.
     *
     * @param cities list of cities
     * @param grid   the grid for city placement
     */
    public CityNetwork(ArrayList<City> cities, Grid grid) {
        this.grid = grid;
        this.cities = cities;
        this.roads = new ArrayList<>();
        this.cityLookup = new HashMap<>();
        for (City city : cities) {
            cityLookup.put(city.id, city);
        }
    }

    /**
     * Add a city to the network.
     *
     * @param city the city to add
     */
    public void addCity(City city) {
        cities.add(city);
        cityLookup.put(city.id, city);
    }

    /**
     * Get a city by its ID.
     *
     * @param id the city ID
     * @return the city, or null if not found
     */
    public City getCityById(String id) {
        return cityLookup.get(id);
    }

    /**
     * Find which city is at the given world coordinates.
     *
     * @param worldX x coordinate in world space
     * @param worldY y coordinate in world space
     * @param radius click radius threshold
     * @return the city at that location, or null if none
     */
    public City getCityAt(float worldX, float worldY, float radius) {
        for (City city : cities) {
            if (city.containsPoint(worldX, worldY, radius)) {
                return city;
            }
        }
        return null;
    }

    /**
     * Add a road between two cities.
     *
     * @param from the first city
     * @param to   the second city
     */
    public void addRoad(City from, City to) {
        if (getRoad(from, to) == null) {
            roads.add(new Road(from, to));
        }
    }

    /**
     * Get the road between two cities.
     *
     * @param a first city
     * @param b second city
     * @return the road, or null if no direct road exists
     */
    public Road getRoad(City a, City b) {
        for (Road road : roads) {
            if ((road.from == a && road.to == b) || (road.from == b && road.to == a)) {
                return road;
            }
        }
        return null;
    }

    /**
     * Check if two cities are connected by a road.
     *
     * @param a first city
     * @param b second city
     * @return true if a road exists between them
     */
    public boolean canConnect(City a, City b) {
        return getRoad(a, b) != null;
    }

    /**
     * Get all cities connected to a given city by roads.
     *
     * @param from the city to find connections for
     * @return list of connected cities
     */
    public ArrayList<City> getConnectedCities(City from) {
        ArrayList<City> connected = new ArrayList<>();
        for (Road road : roads) {
            if (road.from == from) {
                connected.add(road.to);
            } else if (road.to == from) {
                connected.add(road.from);
            }
        }
        return connected;
    }

    /**
     * Generate roads between cities based on proximity.
     *
     * @param maxDistance maximum distance for automatic road creation
     */
    public void generateRoadsFromProximity(float maxDistance) {
        for (int i = 0; i < cities.size(); i++) {
            City a = cities.get(i);
            for (int j = i + 1; j < cities.size(); j++) {
                City b = cities.get(j);
                float dist = a.getDistanceFrom(b);
                if (dist <= maxDistance) {
                    addRoad(a, b);
                }
            }
        }
    }

    /**
     * Draw the entire network (cities and roads).
     *
     * @param camera      the camera for coordinate transformation
     * @param hoveredCity the currently hovered city, or null
     */
    public void draw(Camera2D camera, City hoveredCity) {
        for (Road road : roads) {
            road.draw(camera, roadLine);
        }
        for (City city : cities) {
            boolean isHQ = city == headquartersCity;
            boolean isHovered = city == hoveredCity;
            city.draw(camera, isHQ, isHovered);
        }
    }

    /**
     * Set the headquarters city.
     *
     * @param city the city to set as headquarters
     */
    public void setHeadquarters(City city) {
        if (headquartersCity != null) {
            headquartersCity.removeHeadquarters();
        }
        headquartersCity = city;
        if (city != null) {
            city.placeHeadquarters();
        }
    }

    // ==================== TRADE ROUTE SUPPORT ====================

    /**
     * Initialize the trade route infrastructure. Creates a Shell and wraps each
     * city as a singleton Knot. Call this before any route operations.
     */
    public void initTradeRoutes() {
        if (tradeInitialized) {
            return;
        }

        tradeShell = new Shell();
        cityToKnot.clear();

        // Create a point for each city and wrap as a Knot
        int id = 0;
        for (City city : cities) {
            PointND.Float point = new PointND.Float(city.getX(), city.getY());
            point.setID(id);
            tradeShell.add(point);

            // Create singleton Knot for this city
            Knot knot = new Knot(point, tradeShell);
            cityToKnot.put(city, knot);
            city.setKnot(knot);

            id++;
        }

        // Initialize distance matrix from point set
        tradeShell.distanceMatrix = new DistanceMatrix(tradeShell.toPointSet());

        tradeInitialized = true;
        System.out.println("Trade routes initialized for " + cities.size() + " cities");
    }

    /**
     * Get the Knot wrapper for a city.
     *
     * @param city the city
     * @return the Knot for this city, or null if not initialized
     */
    public Knot getKnotForCity(City city) {
        if (!tradeInitialized) {
            initTradeRoutes();
        }
        return cityToKnot.get(city);
    }

    /**
     * Get the trade Shell.
     *
     * @return the Shell used for trade routes
     */
    public Shell getTradeShell() {
        if (!tradeInitialized) {
            initTradeRoutes();
        }
        return tradeShell;
    }

    /**
     * Check if trade routes have been initialized.
     *
     * @return true if initialized
     */
    public boolean isTradeInitialized() {
        return tradeInitialized;
    }

    /**
     * Create a PointSet from all cities for camera bounds calculation.
     *
     * @return PointSet containing all city locations
     */
    public PointSet toPointSet() {
        Shell shell = new Shell();
        for (City city : cities) {
            shell.add(new PointND.Float(city.getX(), city.getY()));
        }
        if (cities.size() > 0) {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (City city : cities) {
                minX = Math.min(minX, city.getX());
                minY = Math.min(minY, city.getY());
                maxX = Math.max(maxX, city.getX());
                maxY = Math.max(maxY, city.getY());
            }
            float margin = NUM_50;
            shell.add(new PointND.Float(minX - margin, minY - margin));
            shell.add(new PointND.Float(maxX + margin, maxY + margin));
        }
        return shell.toPointSet();
    }
}
