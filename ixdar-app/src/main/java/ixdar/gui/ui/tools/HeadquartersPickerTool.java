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
     * TODO: document {@code HeadquartersPickerTool}.
     *
     * @param tradeScene TODO: describe
     * @param network TODO: describe
     */
    public HeadquartersPickerTool(TradeScene tradeScene, CityNetwork network) {
        this.tradeScene = tradeScene;
        this.network = network;
    }

    /**
     * TODO: document {@code draw}.
     *
     * @param camera TODO: describe
     * @param lineThickness TODO: describe
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
     * TODO: document {@code reset}.
     */
    @Override
    public void reset() {
        hoveredCity = null;
    }

    /**
     * TODO: document {@code confirm}.
     */
    @Override
    public void confirm() {
        // Nothing to confirm - clicking a city handles the action
    }

    /**
     * TODO: document {@code buildInfoText}.
     *
     * @return TODO: describe
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
     * TODO: document {@code displayName}.
     *
     * @return TODO: describe
     */
    @Override
    public String displayName() {
        return "Headquarters Picker";
    }

    /**
     * TODO: document {@code fullName}.
     *
     * @return TODO: describe
     */
    @Override
    public String fullName() {
        return "hqpicker";
    }

    /**
     * TODO: document {@code shortName}.
     *
     * @return TODO: describe
     */
    @Override
    public String shortName() {
        return "hq";
    }

    /**
     * TODO: document {@code desc}.
     *
     * @return TODO: describe
     */
    @Override
    public String desc() {
        return "Select a city to place your company headquarters.";
    }
}
