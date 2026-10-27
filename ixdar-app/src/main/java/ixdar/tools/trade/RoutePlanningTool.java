package ixdar.tools.trade;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector2f;

import ixdar.annotations.tool.ToolAnnotation;
import ixdar.game.City;
import ixdar.game.TradeGame;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.sdf.SDFCircle;
import ixdar.graphics.render.sdf.SDFLine;
import ixdar.gui.ui.text.HyperString;
import ixdar.platform.input.Keys;
import ixdar.scenes.trade.TradeScene;
import ixdar.tools.ControlHint;
import ixdar.tools.Draft;
import ixdar.tools.Item;
import ixdar.tools.Tool;

/**
 * Builds the player's loops with the three knot operations. Pipe joins two cities or two loops, Grow
 * adds a lone city to a loop at its cheapest uncrossed segment, and Collapse joins the ends of a chain
 * of knots. Each click applies at once; undo replays the operations before it.
 */
@ToolAnnotation(id = RoutePlanningTool.TOOL_NAME)
public class RoutePlanningTool extends Tool {
    public static final String TOOL_NAME = "routeplanning";
    public static final int PIPE = 0;
    public static final int GROW = 1;
    public static final int COLLAPSE = 2;
    public static final float RING_RADIUS = 18f;
    public static final float PREVIEW_WIDTH = 3f;
    public static final float TARGET_LABEL_OFFSET = 32f;
    public static final String CELLS = " cells";

    /** The first city of a two-click operation, or the loop Grow is extending; -1 for none. */
    public int firstCity = -1;

    private final TradeScene tradeScene;
    private final SDFLine previewLine = new SDFLine();
    private final SDFCircle ring = new SDFCircle();

    /**
     * Build route planning over the trade scene; its modes are Pipe, Grow and Collapse.
     *
     * @param tradeScene the scene whose game the tool edits
     */
    public RoutePlanningTool(TradeScene tradeScene) {
        super("PIPE", "GROW", "COLLAPSE");
        this.tradeScene = tradeScene;
    }

    /**
     * Drop the pending selection, as a new level does.
     */
    public void reset() {
        firstCity = -1;
    }

    /**
     * Switch operation, dropping any half-made one.
     *
     * @param next {@link #PIPE}, {@link #GROW} or {@link #COLLAPSE}
     */
    public void chooseMode(int next) {
        mode = next;
        firstCity = -1;
        String[] hints = { "Pipe: click two cities (two lone towns start a loop, two loops join)",
            "Grow: click a city on a loop, then a highlighted town", "Collapse: click a city at each end of a chain" };
        tradeScene.status = hints[next];
    }

    /**
     * Undo the last route operation.
     */
    public void undo() {
        tradeScene.game.undo();
        firstCity = -1;
        tradeScene.status = "Undone";
    }

    /**
     * A click on a city selects it for the city menu and steps the operation; a click on empty map
     * closes the menu and drops the selection.
     *
     * @param x framebuffer x of the click
     * @param y framebuffer y of the click, y up
     * @return true when a city took the click
     */
    @Override
    public boolean onClick(float x, float y) {
        City clicked = tradeScene.getCityAt(tradeScene.mapCamera.screenTransformX(x),
                tradeScene.mapCamera.screenTransformY(y));
        if (clicked == null) {
            firstCity = -1;
            tradeScene.selectedCity = -1;
            return false;
        }
        TradeGame game = tradeScene.game;
        int city = Integer.parseInt(clicked.id);
        tradeScene.selectedCity = city;
        if (mode == GROW) {
            if (firstCity >= 0 && game.growCity.contains(city)) {
                report(game.grow(firstCity, city), "Grew the loop to " + clicked.name);
                game.computeGrowTargets(firstCity);
            } else if (game.loopIndexOf(city) >= 0) {
                firstCity = city;
                game.computeGrowTargets(city);
                tradeScene.status = game.growCity.isEmpty() ? "No town can join this loop without a crossing"
                        : "Pick a highlighted town to add";
            } else {
                tradeScene.status = TradeGame.REFUSED_NOT_ON_LOOP;
            }
            return true;
        }
        if (firstCity < 0 || firstCity == city) {
            firstCity = firstCity == city ? -1 : city;
            return true;
        }
        int first = firstCity;
        firstCity = -1;
        if (mode == PIPE) {
            report(game.pipe(first, city), "Piped " + game.level.cityNames[first] + " and " + clicked.name);
        } else {
            report(game.collapse(first, city), "Collapsed the chain into a higher knot");
        }
        return true;
    }

    private void report(boolean done, String success) {
        tradeScene.status = done ? success : "Refused: " + tradeScene.game.lastRefusal;
    }

    /**
     * Draw the pending operation: the first city ringed, a preview line to the hovered city with its
     * cost, and in Grow every town the loop can take, green with its cells or red with its fuel deficit.
     *
     * @param camera        the map camera
     * @param lineThickness lane width in pixels
     */
    @Override
    public void drawOverlay(Camera2D camera, float lineThickness) {
        TradeGame game = tradeScene.game;
        if (firstCity < 0) {
            return;
        }
        Vector2f first = cityPixel(firstCity);
        ring.borderThickness = TradeScene.RING_BORDER_SHARE;
        ring.draw(first, RING_RADIUS, Color.TRANSPARENT, Color.ORANGE_YELLOW_DARK, camera);
        if (mode == GROW) {
            for (int k = 0; k < game.growCity.size(); k++) {
                int city = game.growCity.get(k);
                int deficit = game.growDeficit.get(k);
                Color color = deficit > 0 ? Color.IXDAR : Color.GREEN_DARK;
                Vector2f p = cityPixel(city);
                ring.draw(p, RING_RADIUS, Color.TRANSPARENT, color, camera);
                String label = "+" + game.growDelta.get(k) + (deficit > 0 ? " · dry by " + deficit : CELLS);
                tradeScene.drawTag(label, p.x, p.y - TARGET_LABEL_OFFSET, color);
            }
            return;
        }
        City hovered = tradeScene.hoveredCity;
        if (hovered != null && Integer.parseInt(hovered.id) != firstCity) {
            int city = Integer.parseInt(hovered.id);
            Vector2f p = cityPixel(city);
            previewLine.setStroke(PREVIEW_WIDTH, true, TradeScene.DASH_PIXELS, 0f, true, false, false);
            previewLine.draw(first, p, Color.ORANGE_YELLOW_DARK, camera);
            tradeScene.drawTag("+" + game.legCost[firstCity][city] + CELLS, (first.x + p.x) * 0.5f,
                    (first.y + p.y) * 0.5f, Color.ORANGE_YELLOW_DARK);
        }
    }

    private Vector2f cityPixel(int city) {
        TradeGame game = tradeScene.game;
        return tradeScene.screen(game.map.cellX[game.cityCell[city]], game.map.cellY[game.cityCell[city]]);
    }

    /**
     * The route keys: P, G and C pick the operation and Z undoes.
     *
     * @param controls the scene's control list
     */
    @Override
    public void addControls(List<ControlHint> controls) {
        controls.add(new ControlHint("pipe-mode", Keys.P, "P", "pipe: join two cities or two loops",
                () -> chooseMode(PIPE)));
        controls.add(new ControlHint("grow-mode", Keys.G, "G", "grow: add a town to a loop",
                () -> chooseMode(GROW)));
        controls.add(new ControlHint("collapse-mode", Keys.C, "C", "collapse: join the ends of a chain of knots",
                () -> chooseMode(COLLAPSE)));
        controls.add(ControlHint.repeating("undo", Keys.Z, "Z", "undo the last route operation", this::undo));
        controls.add(ControlHint.repeating("redo-route", Keys.Y, "Y", "redo", () -> {
            tradeScene.game.redo();
            firstCity = -1;
        }));
    }

    /**
     * Esc drops a half-made operation before it reaches the scene.
     *
     * @return true when there was one to drop
     */
    @Override
    public boolean escapePressed() {
        boolean pending = firstCity >= 0;
        firstCity = -1;
        return pending;
    }

    /**
     * What Esc does under this tool.
     *
     * @return the phrase
     */
    @Override
    public String escapeDescription() {
        return "drop the half-made operation";
    }

    /** Start with nothing pending. */
    @Override
    public void activate() {
        firstCity = -1;
    }

    /** Hand over; the loops stay. */
    @Override
    public void deactivate() {
    }

    /**
     * The pending operation: its first city, valid once a second click would apply it.
     *
     * @return the draft, or null while nothing is pending
     */
    @Override
    public Draft draft() {
        if (firstCity < 0 || tradeScene.game == null) {
            return null;
        }
        City city = tradeScene.network.cities.get(firstCity);
        Draft operation = new Draft();
        operation.label = modes[mode];
        operation.points = new float[] { city.getX(), city.getY(), 0 };
        operation.pointLabels = new String[] { city.name };
        operation.location[0] = city.getX();
        operation.location[1] = city.getY();
        operation.size = 1;
        operation.valid = true;
        operation.detail = modes[mode];
        return operation;
    }

    /**
     * The city under the cursor, by its name, in world coordinates.
     *
     * @return the hovered city, or null
     */
    @Override
    public Item hovered() {
        City city = tradeScene.hoveredCity;
        if (city == null) {
            return null;
        }
        Item item = new Item();
        item.label = city.name;
        item.location[0] = city.getX();
        item.location[1] = city.getY();
        item.saved = true;
        return item;
    }

    /**
     * Every leg of the player's loops at its midpoint, with its cost in cells as its length.
     *
     * @return one item per leg
     */
    @Override
    public List<Item> items() {
        List<Item> legs = new ArrayList<>();
        TradeGame game = tradeScene.game;
        if (game == null) {
            return legs;
        }
        for (int[] order : game.loopCities) {
            for (int k = 0; k < order.length; k++) {
                City a = tradeScene.network.cities.get(order[k]);
                City b = tradeScene.network.cities.get(order[(k + 1) % order.length]);
                Item item = new Item();
                item.label = a.name + "-" + b.name;
                item.location[0] = (a.getX() + b.getX()) * 0.5f;
                item.location[1] = (a.getY() + b.getY()) * 0.5f;
                item.size = 1;
                item.length = game.legCost[order[k]][order[(k + 1) % order.length]];
                item.saved = true;
                legs.add(item);
            }
        }
        return legs;
    }

    /**
     * The side-panel text: the mode and what the next click does.
     *
     * @return the formatted info text
     */
    @Override
    public HyperString info() {
        HyperString h = new HyperString();
        h.addWord("[" + modes[mode] + "] ", Color.TEAL);
        h.addWord(tradeScene.status, Color.WHITE);
        return h;
    }

    /**
     * {@inheritDoc}.
     *
     * @return the display name "Route Planning"
     */
    @Override
    public String displayName() {
        return "Route Planning";
    }

    /**
     * {@inheritDoc}.
     *
     * @return the full terminal name {@code "routeplanning"}
     */
    @Override
    public String toolName() {
        return TOOL_NAME;
    }

    /**
     * {@inheritDoc}.
     *
     * @return the short terminal alias {@code "rp"}
     */
    @Override
    public String shortName() {
        return "rp";
    }

    /**
     * {@inheritDoc}.
     *
     * @return the one-line description of this tool's purpose
     */
    @Override
    public String desc() {
        return "Build the blood trade's loops with Pipe, Grow and Collapse.";
    }
}
