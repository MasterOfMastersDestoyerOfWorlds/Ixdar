package ixdar.tools.trade;

import java.util.List;

import ixdar.annotations.tool.ToolAnnotation;
import ixdar.game.City;
import ixdar.game.CityNetwork;
import ixdar.graphics.render.color.Color;
import ixdar.gui.ui.text.HyperString;
import ixdar.scenes.trade.TradeScene;
import ixdar.tools.ControlHint;
import ixdar.tools.Tool;

/**
 * Tool for selecting the headquarters city at the start of a new trade game.
 * This is the first tool active when a new game begins.
 */
@ToolAnnotation(id = HeadquartersPickerTool.TOOL_NAME, host = TradeScene.class)
public class HeadquartersPickerTool implements Tool {

    public static final String TOOL_NAME = "hqpicker";

    private TradeScene tradeScene;
    private CityNetwork network;

    /**
     * Build the picker over the trade scene it hands control to once a city is chosen, picking
     * from the scene's city network.
     *
     * @param tradeScene the trade scene that owns this tool
     */
    public HeadquartersPickerTool(TradeScene tradeScene) {
        this.tradeScene = tradeScene;
        this.network = tradeScene.network;
    }

    /** Nothing to set up: the scene tracks the hovered city. */
    @Override
    public void activate() {
    }

    /** Nothing to hand back. */
    @Override
    public void deactivate() {
    }

    /**
     * The picker binds no keys: a click picks.
     *
     * @param controls the scene's control list
     */
    @Override
    public void addControls(List<ControlHint> controls) {
    }

    /**
     * Place the headquarters at the city under the click and hand over to route planning.
     *
     * @param x framebuffer x of the click
     * @param y framebuffer y of the click, y up
     * @return true when a city was under the click
     */
    @Override
    public boolean onClick(float x, float y) {
        City city = tradeScene.getCityAt(tradeScene.mapCamera.screenTransformX(x),
                tradeScene.mapCamera.screenTransformY(y));
        if (city == null) {
            return false;
        }
        network.setHeadquarters(city);
        tradeScene.switchTool(tradeScene.routePlanningTool);
        return true;
    }

    /**
     * Build the side-panel text instructing the player to select a city.
     *
     * @return the formatted info text
     */
    @Override
    public HyperString info() {
        HyperString h = new HyperString();
        h.addWord("Select a city to place your headquarters", Color.WHITE);
        h.wrap();
        h.addWord("Click on any city to begin your trade empire", Color.GREY_DARK);
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
    public String toolName() {
        return TOOL_NAME;
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
