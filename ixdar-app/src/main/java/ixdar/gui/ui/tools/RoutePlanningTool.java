package ixdar.gui.ui.tools;

import java.util.ArrayList;

import org.joml.Vector2f;

import ixdar.game.City;
import ixdar.game.CityNetwork;
import ixdar.game.Road;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorRGB;
import ixdar.graphics.render.sdf.SDFLine;
import ixdar.graphics.render.text.HyperString;
import ixdar.scenes.trade.TradeScene;

/**
 * Tool for planning and building trade routes between cities.
 * Activated after the player selects their headquarters.
 */
public class RoutePlanningTool extends Tool {

    public enum State {
        SelectStart,   // Pick first city for route (must be HQ or connected to existing route)
        ExtendRoute,   // Add cities to current route
        ConfirmRoute   // Review and finalize the route
    }

    private TradeScene tradeScene;
    private CityNetwork network;
    private State state = State.SelectStart;
    private ArrayList<City> routeCities = new ArrayList<>();
    private City hoveredCity;
    private SDFLine routeLine = new SDFLine();

    private static final Color ROUTE_PREVIEW_COLOR = Color.MAGENTA;
    private static final Color ROUTE_PREVIEW_HOVER_COLOR = new ColorRGB(1f, 0f, 1f, 0.5f);

    public RoutePlanningTool(TradeScene tradeScene, CityNetwork network) {
        this.tradeScene = tradeScene;
        this.network = network;
    }

    @Override
    public void draw(Camera2D camera, float lineThickness) {
        if (routeCities.isEmpty()) {
            return;
        }

        routeLine.setStroke(3f, false);

        // Draw the route being planned
        for (int i = 0; i < routeCities.size() - 1; i++) {
            City from = routeCities.get(i);
            City to = routeCities.get(i + 1);
            drawRouteLine(camera, from, to, ROUTE_PREVIEW_COLOR);
        }

        // Draw preview line to hovered city if valid connection
        if (hoveredCity != null && !routeCities.isEmpty()) {
            City lastCity = routeCities.get(routeCities.size() - 1);
            if (canConnectTo(hoveredCity)) {
                drawRouteLine(camera, lastCity, hoveredCity, ROUTE_PREVIEW_HOVER_COLOR);
            }
        }
    }

    private void drawRouteLine(Camera2D camera, City from, City to, Color color) {
        float x1 = camera.pointTransformX(from.getX());
        float y1 = camera.pointTransformY(from.getY());
        float x2 = camera.pointTransformX(to.getX());
        float y2 = camera.pointTransformY(to.getY());
        routeLine.draw(new Vector2f(x1, y1), new Vector2f(x2, y2), color, camera);
    }

    /**
     * Handle a city click during route planning
     * @param city the city that was clicked
     */
    public void onCityClick(City city) {
        if (city == null) {
            return;
        }

        switch (state) {
            case SelectStart:
                // Must start from headquarters
                if (city == network.headquartersCity) {
                    routeCities.add(city);
                    state = State.ExtendRoute;
                    System.out.println("Route started from HQ: " + city.name);
                } else {
                    System.out.println("Route must start from headquarters");
                }
                break;

            case ExtendRoute:
                if (canConnectTo(city)) {
                    routeCities.add(city);
                    System.out.println("Added to route: " + city.name);
                } else if (routeCities.contains(city)) {
                    // Clicking an existing city in route could complete a loop
                    System.out.println("City already in route: " + city.name);
                } else {
                    System.out.println("Cannot connect to: " + city.name + " (no road)");
                }
                break;

            case ConfirmRoute:
                // In confirm state, clicking anywhere confirms
                confirm();
                break;
        }
    }

    /**
     * Check if the route can be extended to the given city
     * @param city the city to check
     * @return true if a road exists from the last city in the route
     */
    private boolean canConnectTo(City city) {
        if (routeCities.isEmpty()) {
            return city == network.headquartersCity;
        }
        if (routeCities.contains(city)) {
            return false;
        }
        City lastCity = routeCities.get(routeCities.size() - 1);
        return network.canConnect(lastCity, city);
    }

    /**
     * Update the hovered city
     * @param city the city being hovered, or null
     */
    public void updateHoveredCity(City city) {
        this.hoveredCity = city;
    }

    /**
     * Get the currently hovered city
     * @return the hovered city, or null
     */
    public City getHoveredCity() {
        return hoveredCity;
    }

    @Override
    public void reset() {
        routeCities.clear();
        hoveredCity = null;
        state = State.SelectStart;
    }

    @Override
    public void confirm() {
        if (routeCities.size() < 2) {
            System.out.println("Route needs at least 2 cities");
            return;
        }

        // TODO: Create actual Knot from routeCities for player route
        System.out.println("Route confirmed with " + routeCities.size() + " cities:");
        for (City city : routeCities) {
            System.out.println("  - " + city.name);
        }

        // Mark roads as discovered
        for (int i = 0; i < routeCities.size() - 1; i++) {
            Road road = network.getRoad(routeCities.get(i), routeCities.get(i + 1));
            if (road != null) {
                road.discovered = true;
            }
        }

        reset();
    }

    /**
     * Cancel the current route and start over
     */
    public void cancel() {
        reset();
        System.out.println("Route cancelled");
    }

    /**
     * Get the current route state
     * @return the current state
     */
    public State getState() {
        return state;
    }

    /**
     * Get the cities in the current route
     * @return list of cities in the route
     */
    public ArrayList<City> getRouteCities() {
        return routeCities;
    }

    @Override
    public HyperString buildInfoText() {
        HyperString h = new HyperString();

        switch (state) {
            case SelectStart:
                h.addWord("Click your headquarters to start a route", Color.WHITE);
                break;
            case ExtendRoute:
                h.addWord("Click connected cities to extend the route", Color.WHITE);
                h.wrap();
                h.addWord("Route: ", Color.LIGHT_GRAY);
                for (int i = 0; i < routeCities.size(); i++) {
                    h.addWord(routeCities.get(i).name, Color.CYAN);
                    if (i < routeCities.size() - 1) {
                        h.addWord(" -> ", Color.LIGHT_GRAY);
                    }
                }
                h.wrap();
                h.addWord("Press Enter to confirm, Escape to cancel", Color.LIGHT_GRAY);
                break;
            case ConfirmRoute:
                h.addWord("Press Enter to confirm route", Color.WHITE);
                break;
        }

        return h;
    }

    @Override
    public String displayName() {
        return "Route Planning";
    }

    @Override
    public String fullName() {
        return "routeplanning";
    }

    @Override
    public String shortName() {
        return "rp";
    }

    @Override
    public String desc() {
        return "Plan and build trade routes between cities.";
    }
}
