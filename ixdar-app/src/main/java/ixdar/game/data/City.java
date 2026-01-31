package ixdar.game.data;

import java.util.ArrayList;
import java.util.HashMap;

import ixdar.geometry.point.PointND;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.text.HyperString;

/**
 * Represents a city in the trade game.
 * Cities are locations on the map where the player can establish headquarters,
 * buy/sell goods, and create trade routes.
 */
public class City {
    public String id;
    public String name;
    public PointND.Float location;
    public int population;
    public ArrayList<String> resources;
    public HashMap<String, Integer> produces;
    public HashMap<String, Integer> consumes;
    public boolean hasHeadquarters;
    private HyperString nameLabel;

    /**
     * Create a city with the given properties
     * @param id unique identifier for the city
     * @param name display name of the city
     * @param x x coordinate on the map
     * @param y y coordinate on the map
     * @param population city population (affects trade volume)
     */
    public City(String id, String name, float x, float y, int population) {
        this.id = id;
        this.name = name;
        this.location = new PointND.Float(x, y);
        this.population = population;
        this.resources = new ArrayList<>();
        this.produces = new HashMap<>();
        this.consumes = new HashMap<>();
        this.hasHeadquarters = false;
    }

    /**
     * Create a city from JSON-like data
     * @param id unique identifier
     * @param name display name
     * @param x x coordinate
     * @param y y coordinate
     * @param population city population
     * @param resources list of resource types available
     * @param produces map of resource -> quantity produced per turn
     * @param consumes map of resource -> quantity consumed per turn
     */
    public City(String id, String name, float x, float y, int population,
                ArrayList<String> resources, HashMap<String, Integer> produces,
                HashMap<String, Integer> consumes) {
        this.id = id;
        this.name = name;
        this.location = new PointND.Float(x, y);
        this.population = population;
        this.resources = resources;
        this.produces = produces;
        this.consumes = consumes;
        this.hasHeadquarters = false;
    }

    /**
     * Get the x coordinate of the city
     * @return x coordinate
     */
    public float getX() {
        return (float) location.getCoord(0);
    }

    /**
     * Get the y coordinate of the city
     * @return y coordinate
     */
    public float getY() {
        return (float) location.getCoord(1);
    }

    /**
     * Check if a point is within click distance of this city
     * @param x x coordinate to check
     * @param y y coordinate to check
     * @param radius click radius threshold
     * @return true if the point is within the radius
     */
    public boolean containsPoint(float x, float y, float radius) {
        float dx = getX() - x;
        float dy = getY() - y;
        return (dx * dx + dy * dy) <= (radius * radius);
    }

    /**
     * Set this city as having the player's headquarters
     */
    public void placeHeadquarters() {
        this.hasHeadquarters = true;
    }

    /**
     * Remove headquarters from this city
     */
    public void removeHeadquarters() {
        this.hasHeadquarters = false;
    }

    /**
     * Add a resource that this city produces
     * @param resource resource type
     * @param quantity quantity produced per turn
     */
    public void addProduction(String resource, int quantity) {
        produces.put(resource, quantity);
        if (!resources.contains(resource)) {
            resources.add(resource);
        }
    }

    /**
     * Add a resource that this city consumes
     * @param resource resource type
     * @param quantity quantity consumed per turn
     */
    public void addConsumption(String resource, int quantity) {
        consumes.put(resource, quantity);
    }

    /**
     * Get the cached name label for rendering
     * @return HyperString containing the city name
     */
    public HyperString getNameLabel() {
        if (nameLabel == null) {
            nameLabel = new HyperString();
            nameLabel.addWord(name, Color.WHITE);
        }
        return nameLabel;
    }

    @Override
    public String toString() {
        return name + " (" + id + ") at (" + getX() + ", " + getY() + ")";
    }
}
