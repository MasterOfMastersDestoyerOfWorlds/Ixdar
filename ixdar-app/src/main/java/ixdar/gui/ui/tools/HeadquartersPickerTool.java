package ixdar.gui.ui.tools;

import ixdar.game.City;
import ixdar.game.CityNetwork;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.text.HyperString;
import ixdar.scenes.trade.TradeScene;

/**
 * Tool for selecting the headquarters city at the start of a new trade game.
 * This is the first tool active when a new game begins.
 */
public class HeadquartersPickerTool extends Tool {

    private TradeScene tradeScene;
    private CityNetwork network;
    private City hoveredCity;

    /**
     * Build the picker, binding it to the trade scene it should hand control
     * to once a city is chosen, and the city network it picks from.
     *
     * @param tradeScene the trade scene that owns this tool
     * @param network    the city network whose cities are picker targets
     */
    public HeadquartersPickerTool(TradeScene tradeScene, CityNetwork network) {
        this.tradeScene = tradeScene;
        this.network = network;
    }

    /**
     * No-op draw override: cities are rendered by the network's own draw
     * pass, and this tool needs no extra overlay.
     *
     * @param camera        the scene camera (unused)
     * @param lineThickness base line thickness in pixels (unused)
     */
    @Override
    public void draw(Camera2D camera, float lineThickness) {
        // The cities are drawn by the network; this tool doesn't need extra drawing
    }

    /**
     * Handle a city click - sets the headquarters and transitions to route planning.
     *
     * @param city the city that was clicked
     */
    public void onCityClick(City city) {
        if (city != null) {
            network.setHeadquarters(city);
            System.out.println("Headquarters placed at: " + city.name);
            tradeScene.activateRoutePlanningTool();
        }
    }

    /**
     * Update the hovered city for tooltip display.
     *
     * @param city the city being hovered, or null
     */
    public void updateHoveredCity(City city) {
        this.hoveredCity = city;
    }

    /**
     * Get the currently hovered city.
     *
     * @return the hovered city, or null
     */
    public City getHoveredCity() {
        return hoveredCity;
    }

    /**
     * Clear the cached hovered city so the next frame starts fresh.
     */
    @Override
    public void reset() {
        hoveredCity = null;
    }

    /**
     * No-op: this tool commits via {@link #onCityClick(City)}, not via the
     * generic confirm key.
     */
    @Override
    public void confirm() {
        // Nothing to confirm - clicking a city handles the action
    }

    /**
     * Build the side-panel text instructing the player to select a city.
     *
     * @return the formatted info text
     */
    @Override
    public HyperString buildInfoText() {
        HyperString h = new HyperString();
        h.addWord("Select a city to place your headquarters", Color.WHITE);
        h.wrap();
        h.addWord("Click on any city to begin your trade empire", Color.LIGHT_GRAY);
        return h;
    }

    /**
     * {@inheritDoc}.
     *
     * @return the display name "Headquarters Picker"
     */
    @Override
    public String displayName() {
        return "Headquarters Picker";
    }

    /**
     * {@inheritDoc}.
     *
     * @return the full terminal name {@code "hqpicker"}
     */
    @Override
    public String fullName() {
        return "hqpicker";
    }

    /**
     * {@inheritDoc}.
     *
     * @return the short terminal alias {@code "hq"}
     */
    @Override
    public String shortName() {
        return "hq";
    }

    /**
     * {@inheritDoc}.
     *
     * @return the one-line description of this tool's purpose
     */
    @Override
    public String desc() {
        return "Select a city to place your company headquarters.";
    }
}
