package ixdar.scenes.trade.hud;

import java.util.ArrayList;
import java.util.function.Consumer;

import ixdar.game.trade.TradeGame;
import ixdar.game.trade.economy.Company;
import ixdar.game.trade.economy.Facility;
import ixdar.game.trade.economy.Loop;
import ixdar.game.trade.economy.PriceWar;
import ixdar.game.trade.economy.Rules;
import ixdar.game.trade.map.City;
import ixdar.game.trade.map.Place;
import ixdar.graphics.render.color.Palette;
import ixdar.scenes.trade.TradeScene;

/**
 * The selected place's panel: its name and type (Headquarters, City or Town), a city's market, and what
 * you can build there with its price, upkeep and carrying capacity.
 */
public class PlacePanel {
    public static final String[] MEDAL_NAMES = { "no medal", "bronze", "silver", "gold" };
    public static final String[] WAR_LABELS = { "Price war: off", "Price war: at cost", "Price war: half cost" };
    public static final String COST_FORMAT = "%.2f";
    public static final String SEPARATOR = ", ";
    public static final String GOLD_PER_LAP = " gold / lap";
    public static final float TITLE_ROW = Hud.ROW_HEIGHT * 1.4f;
    public static final float BUTTON_ROW = Hud.BUTTON_HEIGHT + Hud.PADDING;
    public static final float CLOSE_WIDTH_PER_HEIGHT = 2.2f;

    /**
     * Draw the panel hanging from a point.
     *
     * @param scene the trade scene, whose {@code selectedPlace} is set
     * @param left  the panel's left edge
     * @param top   its top edge
     * @param width its width
     */
    public void draw(TradeScene scene, float left, float top, float width) {
        Hud hud = scene.hud;
        TradeGame game = scene.game;
        Place place = scene.selectedPlace;
        Company you = game.player;
        Rules rules = game.level.rules;
        Palette palette = scene.palette;
        float centre = left + width * 0.5f;
        float buttonLeft = left + Hud.PADDING;
        float buttonWidth = width - 2 * Hud.PADDING;
        ArrayList<Float> heights = new ArrayList<>();
        ArrayList<Consumer<Float>> rows = new ArrayList<>();

        heights.add(TITLE_ROW);
        rows.add(y -> {
            hud.text(place.name, centre, y, Hud.TITLE_TEXT_HEIGHT, palette.text);
            float closeWidth = Hud.ROW_HEIGHT * CLOSE_WIDTH_PER_HEIGHT;
            hud.button(left + width - closeWidth - Hud.PADDING, y - Hud.ROW_HEIGHT * 0.5f, closeWidth, Hud.ROW_HEIGHT,
                    "x",
                    palette.panel, true, () -> scene.selectedPlace = null);
        });
        String type = place instanceof City ? "City" : "Town";
        for (Company company : game.companies) {
            type = company.home == place ? (company.player ? "Headquarters" : company.name + " headquarters") : type;
        }
        String placeType = type;
        heights.add(Hud.ROW_HEIGHT);
        rows.add(y -> hud.text(placeType, centre, y, Hud.SMALL_TEXT_HEIGHT, palette.mutedText));
        Loop loop = game.network.loopOf(place);
        if (loop != null) {
            heights.add(Hud.ROW_HEIGHT);
            rows.add(y -> hud.text("on your loop" + SEPARATOR + loop.wagons + (loop.wagons == 1 ? " wagon" : " wagons"),
                    centre, y, Hud.SMALL_TEXT_HEIGHT, palette.accent));
        }
        if (!(place instanceof City city)) {
            heights.add(Hud.ROW_HEIGHT);
            rows.add(y -> hud.text("no market: loops pass through", centre, y, Hud.SMALL_TEXT_HEIGHT,
                    palette.mutedText));
            layOut(hud, left, top, width, heights, rows);
            return;
        }

        heights.add(Hud.ROW_HEIGHT);
        rows.add(y -> hud.text(city.baseDemand == 0 ? "produces blood, buys none"
                : "needs " + city.demand + " / lap" + SEPARATOR + "pays up to "
                        + String.format(COST_FORMAT, city.ceiling), centre, y, Hud.SMALL_TEXT_HEIGHT,
                palette.mutedText));
        if (city.facility(you) != Facility.NONE && city.baseDemand > 0) {
            heights.add(Hud.ROW_HEIGHT);
            rows.add(y -> hud.text("you sold " + city.lastSold.getOrDefault(you, 0) + " at "
                    + String.format(COST_FORMAT, city.lastPrice) + SEPARATOR + "cost "
                    + String.format(COST_FORMAT, you.offer(city).unitCost()), centre, y, Hud.SMALL_TEXT_HEIGHT,
                    palette.accent));
        }
        Company rival = game.rival;
        if (rival != null && city.facility(you) != Facility.NONE && city.facility(rival) != Facility.NONE) {
            heights.add(Hud.ROW_HEIGHT);
            rows.add(y -> {
                String reserve = rival.gold > rival.startGold * 0.5 ? "healthy" : rival.gold > 0 ? "strained"
                        : "distressed";
                hud.text("rival sold " + city.lastSold.getOrDefault(rival, 0) + SEPARATOR + "cost "
                        + String.format(COST_FORMAT, rival.offer(city).unitCost()) + SEPARATOR + reserve, centre, y,
                        Hud.SMALL_TEXT_HEIGHT, palette.warning);
            });
            heights.add(Hud.ROW_HEIGHT);
            rows.add(y -> hud.text("market: " + MEDAL_NAMES[game.marketMedal(city)], centre, y,
                    Hud.SMALL_TEXT_HEIGHT, palette.highlight));
            heights.add(BUTTON_ROW);
            rows.add(y -> hud.button(buttonLeft, y - Hud.BUTTON_HEIGHT * 0.5f, buttonWidth, Hud.BUTTON_HEIGHT,
                    WAR_LABELS[city.priceWar.ordinal()], city.priceWar == PriceWar.OFF ? palette.panel
                            : palette.accent, true, () -> {
                                city.priceWar = city.priceWar.next();
                                game.economy.estimate();
                            }));
        }
        if (city.facility(you) == Facility.NONE) {
            heights.add(BUTTON_ROW);
            rows.add(y -> hud.button(buttonLeft, y - Hud.BUTTON_HEIGHT * 0.5f, buttonWidth, Hud.BUTTON_HEIGHT,
                    "Buy station: " + rules.stationPrice + " gold", palette.panel,
                    you.gold >= rules.stationPrice, () -> game.buyStation(city)));
            heights.add(Hud.ROW_HEIGHT);
            rows.add(y -> hud.text("upkeep " + rules.stationUpkeep + GOLD_PER_LAP + SEPARATOR + "sells what passes",
                    centre, y, Hud.SMALL_TEXT_HEIGHT, palette.mutedText));
        }
        if (city.facility(you) != Facility.BASE) {
            long price = Math.round(game.economy.basePrice(you, city));
            long upkeep = Math.round(game.economy.baseUpkeep(you, city) + rules.wagonUpkeep);
            heights.add(BUTTON_ROW);
            rows.add(y -> hud.button(buttonLeft, y - Hud.BUTTON_HEIGHT * 0.5f, buttonWidth, Hud.BUTTON_HEIGHT,
                    "Build base: " + price + " gold", palette.panel, you.gold >= price, () -> game.buildBase(city)));
            heights.add(Hud.ROW_HEIGHT);
            rows.add(y -> hud.text("upkeep " + upkeep + GOLD_PER_LAP + SEPARATOR + "1 wagon carrying "
                    + you.wagonCapacity, centre, y, Hud.SMALL_TEXT_HEIGHT, palette.mutedText));
        }
        layOut(hud, left, top, width, heights, rows);
    }

    /**
     * Draw the card behind the rows, then each row centred in its height, top down.
     *
     * @param hud     the HUD kit
     * @param left    the card's left edge
     * @param top     its top edge
     * @param width   its width
     * @param heights each row's height
     * @param rows    each row's painter, given its centre y
     */
    private static void layOut(Hud hud, float left, float top, float width, ArrayList<Float> heights,
            ArrayList<Consumer<Float>> rows) {
        float total = Hud.PADDING;
        for (float h : heights) {
            total += h;
        }
        hud.panel(left, top - total, width, total, hud.palette.panel, hud.palette.panelBorder);
        float y = top - Hud.PADDING * 0.5f;
        for (int k = 0; k < rows.size(); k++) {
            rows.get(k).accept(y - heights.get(k) * 0.5f);
            y -= heights.get(k);
        }
    }
}
