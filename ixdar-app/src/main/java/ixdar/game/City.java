package ixdar.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.joml.Vector2f;

import ixdar.geometry.knot.Knot;
import ixdar.geometry.point.PointND;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.sdf.SDFCircleSimple;
import ixdar.graphics.render.text.HyperString;
import ixdar.gui.ui.Drawing;

/**
 * Represents a city in the trade game. Cities are locations on the map where
 * the player can establish headquarters, buy/sell goods, and create trade
 * routes.
 */
public class City {
    public static final float CITY_RADIUS = 20f;
    public static final float CLICK_RADIUS = 20f;

    private static SDFCircleSimple cityCircle = new SDFCircleSimple();

    public String id;
    public String name;
    public PointND.Float location;
    public int population;
    public ArrayList<String> resources;
    public HashMap<String, Integer> produces;
    public HashMap<String, Integer> consumes;
    public boolean hasHeadquarters;
    private HyperString nameLabel;
    private Knot knot; // Trade route Knot wrapper for this city

    /**
     * Create a city with the given properties
     * 
     * @param id         unique identifier for the city
     * @param name       display name of the city
     * @param x          x coordinate on the map
     * @param y          y coordinate on the map
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
     * 
     * @param id         unique identifier
     * @param name       display name
     * @param x          x coordinate
     * @param y          y coordinate
     * @param population city population
     * @param resources  list of resource types available
     * @param produces   map of resource -> quantity produced per turn
     * @param consumes   map of resource -> quantity consumed per turn
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
     * 
     * @return x coordinate
     */
    public float getX() {
        return (float) location.getCoord(0);
    }

    /**
     * Get the y coordinate of the city
     * 
     * @return y coordinate
     */
    public float getY() {
        return (float) location.getCoord(1);
    }

    /**
     * Check if a point is within click distance of this city
     * 
     * @param x      x coordinate to check
     * @param y      y coordinate to check
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
     * Set the Knot wrapper for this city (used by trade routes).
     * 
     * @param knot the Knot to associate with this city
     */
    public void setKnot(Knot knot) {
        this.knot = knot;
    }

    /**
     * Get the Knot wrapper for this city.
     * 
     * @return the Knot, or null if trade routes not initialized
     */
    public Knot getKnot() {
        return knot;
    }

    /**
     * Add a resource that this city produces
     * 
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
     * 
     * @param resource resource type
     * @param quantity quantity consumed per turn
     */
    public void addConsumption(String resource, int quantity) {
        consumes.put(resource, quantity);
    }

    /**
     * Get the cached name label for rendering
     * 
     * @return HyperString containing the city name
     */
    public HyperString getNameLabel() {
        if (nameLabel == null) {
            nameLabel = new HyperString();
            nameLabel.addWord(name, Color.WHITE);
        }
        return nameLabel;
    }

    /**
     * Calculate the distance from this city to another
     * 
     * @param other the other city
     * @return euclidean distance between the two cities
     */
    public float getDistanceFrom(City other) {
        float dx = getX() - other.getX();
        float dy = getY() - other.getY();
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Draw this city on the screen
     * 
     * @param camera    the camera for coordinate transformation
     * @param isHQ      true if this city is the headquarters
     * @param isHovered true if this city is currently hovered
     */
    public void draw(Camera2D camera, boolean isHQ, boolean isHovered) {
        Color cityColor = Color.CYAN;
        if (isHQ) {
            cityColor = Color.YELLOW;
        } else if (isHovered) {
            cityColor = Color.GREEN;
        }

        float screenX = camera.pointTransformX(getX());
        float screenY = camera.pointTransformY(getY());
        Vector2f screenPos = new Vector2f(screenX, screenY);

        cityCircle.draw(screenPos, CITY_RADIUS, cityColor, camera);

        float labelScreenY = screenY - CITY_RADIUS - 20;
        Drawing.getDrawing().font.drawHyperString(getNameLabel(), screenX, labelScreenY, Drawing.FONT_HEIGHT_PIXELS,
                camera);
    }

    /**
     * Build a tooltip HyperString with city information
     * 
     * @param headquartersCity the player's headquarters city, or null if not set
     * @return HyperString containing city tooltip information
     */
    public HyperString buildTooltip(City headquartersCity) {
        HyperString tip = new HyperString();

        tip.addWord(name, Color.WHITE);
        tip.newLine();

        tip.addWord("Pop: " + population, Color.LIGHT_GRAY);
        tip.newLine();

        if (!resources.isEmpty()) {
            tip.addWord("Resources: ", Color.LIGHT_GRAY);
            for (int i = 0; i < resources.size(); i++) {
                String resource = resources.get(i);
                tip.addWord(resource, Color.CYAN);
                if (i < resources.size() - 1) {
                    tip.addWord(", ", Color.LIGHT_GRAY);
                }
            }
            tip.newLine();
        }

        if (!produces.isEmpty()) {
            tip.addWord("Produces: ", Color.LIGHT_GRAY);
            boolean first = true;
            for (Map.Entry<String, Integer> entry : produces.entrySet()) {
                if (!first) {
                    tip.addWord(", ", Color.LIGHT_GRAY);
                }
                tip.addWord(entry.getKey() + " (" + entry.getValue() + ")", Color.GREEN);
                first = false;
            }
            tip.newLine();
        }

        if (!consumes.isEmpty()) {
            tip.addWord("Consumes: ", Color.LIGHT_GRAY);
            boolean first = true;
            for (Map.Entry<String, Integer> entry : consumes.entrySet()) {
                if (!first) {
                    tip.addWord(", ", Color.LIGHT_GRAY);
                }
                tip.addWord(entry.getKey() + " (" + entry.getValue() + ")", Color.ORANGE);
                first = false;
            }
            tip.newLine();
        }

        if (headquartersCity != null && headquartersCity != this) {
            float distance = getDistanceFrom(headquartersCity);
            tip.addWord("Distance from HQ: " + String.format("%.1f", distance), Color.YELLOW);
        }

        return tip;
    }

    @Override
    public String toString() {
        return name + " (" + id + ") at (" + getX() + ", " + getY() + ")";
    }
}
