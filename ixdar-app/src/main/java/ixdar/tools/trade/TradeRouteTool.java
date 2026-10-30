package ixdar.tools.trade;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector2f;

import ixdar.game.trade.TradeGame;
import ixdar.game.trade.economy.Loop;
import ixdar.game.trade.map.Place;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.sdf.SDFCircle;
import ixdar.gui.ui.text.HyperString;
import ixdar.platform.input.Keys;
import ixdar.scenes.trade.TradeScene;
import ixdar.scenes.trade.render.PathRenderer;
import ixdar.tools.ControlHint;
import ixdar.tools.Draft;
import ixdar.tools.EditHistory;
import ixdar.tools.Item;
import ixdar.tools.Tool;

/**
 * What the three route tools share: a click on a place selects it (opening a city's panel) and steps
 * the tool's operation, {@code [} and {@code ]} step the knot layer the tools act at, and Z and Y undo
 * and redo route edits.
 */
public abstract class TradeRouteTool extends Tool {
    public static final float RING_RADIUS_IN_CELLS = 0.38f;
    public static final String CELLS = " cells";

    /** The first place of a two-click operation, or null. */
    public Place pending;
    protected final TradeScene scene;
    protected final SDFCircle ring = new SDFCircle();

    /**
     * A route tool over the trade scene.
     *
     * @param scene the scene whose game the tool edits
     */
    protected TradeRouteTool(TradeScene scene) {
        this.scene = scene;
    }

    /**
     * Step the layer the tools act at, staying within the player's hierarchy.
     *
     * @param delta +1 to go a layer deeper, -1 to go up
     */
    public void stepLayer(int delta) {
        if (scene.game != null) {
            scene.layer = Math.max(0, Math.min(scene.game.network.deepestLayer(), scene.layer + delta));
        }
    }

    /**
     * Undo the last route edit, however many laps ago it was made.
     */
    public void undo() {
        if (scene.game != null) {
            report(scene.game.network.undo(), "Nothing to undo");
        }
    }

    /**
     * Redo the last undone route edit.
     */
    public void redo() {
        if (scene.game != null) {
            report(scene.game.network.redo(), "Nothing to redo");
        }
    }

    /**
     * Finish an edit: refuse it with a reason when it was not kept, then drop the pending place, keep the
     * layer inside the hierarchy and re-estimate the lap.
     *
     * @param done    whether the edit was kept
     * @param refusal why, when it was not
     */
    public void report(boolean done, String refusal) {
        if (!done) {
            scene.refuse(refusal);
        }
        pending = null;
        scene.layer = Math.min(scene.layer, scene.game.network.deepestLayer());
        scene.game.economy.estimate();
    }

    /**
     * A refusal reason as the error toasts show it, with a capital first letter.
     *
     * @param reason the network's lower-case reason
     * @return the sentence
     */
    public static String capitalised(String reason) {
        return reason.isEmpty() ? reason : Character.toUpperCase(reason.charAt(0)) + reason.substring(1);
    }

    /**
     * Select what a click lands on: a city or town opens its panel, empty map closes it and drops the
     * pending place.
     *
     * @param x click x in pixels
     * @param y click y in pixels, y up
     * @return the place clicked, or null on empty map or before a level is loaded
     */
    public Place selectAt(float x, float y) {
        if (scene.game == null) {
            return null;
        }
        Place place = scene.placeAt(x, y);
        scene.selectedPlace = place;
        if (place == null) {
            pending = null;
        }
        return place;
    }

    /**
     * Ring the pending place.
     *
     * @param camera the map camera
     * @return its pixel, or null when nothing is pending
     */
    public Vector2f ringPending(Camera2D camera) {
        if (pending == null || scene.game == null) {
            return null;
        }
        Vector2f from = scene.placePixel(pending);
        ring.borderThickness = PathRenderer.RING_BORDER_SHARE;
        ring.draw(from, RING_RADIUS_IN_CELLS * scene.game.map.grid.cellSpacing() * scene.scale(), Color.TRANSPARENT,
                scene.palette.highlight, camera);
        return from;
    }

    /**
     * The shared keys: {@code [} and {@code ]} step the layer, Z undoes and Y redoes.
     *
     * @param controls the scene's control list
     */
    @Override
    public void addControls(List<ControlHint> controls) {
        controls.add(new ControlHint("previous-layer", Keys.LEFT_BRACKET, "[", "act at the layer above",
                () -> stepLayer(-1)));
        controls.add(new ControlHint("next-layer", Keys.RIGHT_BRACKET, "]", "act at the layer below",
                () -> stepLayer(1)));
        controls.add(ControlHint.repeating("undo-route", Keys.Z, "Z", "undo the last route edit", this::undo));
        controls.add(ControlHint.repeating("redo-route", Keys.Y, "Y", "redo", this::redo));
    }

    /**
     * Every route edit of the game, across laps.
     *
     * @return the network's snapshot history
     */
    @Override
    public EditHistory<?> history() {
        return scene.game == null ? null : scene.game.network.history;
    }

    /**
     * Esc drops a half-made operation before it reaches the scene.
     *
     * @return true when there was one to drop
     */
    @Override
    public boolean escapePressed() {
        boolean had = pending != null;
        pending = null;
        return had;
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
        pending = null;
    }

    /** Hand over; the loops stay. */
    @Override
    public void deactivate() {
        pending = null;
    }

    /**
     * The pending operation: its first place, valid once a second click would apply it.
     *
     * @return the draft, or null while nothing is pending
     */
    @Override
    public Draft draft() {
        if (pending == null || scene.game == null) {
            return null;
        }
        Vector2f at = scene.game.map.grid.dualPoints().get(pending.cell);
        Draft operation = new Draft();
        operation.label = displayName();
        operation.points = new float[] { at.x, at.y, 0 };
        operation.pointLabels = new String[] { pending.name };
        operation.location[0] = at.x;
        operation.location[1] = at.y;
        operation.size = 1;
        operation.valid = true;
        operation.detail = "layer " + scene.layer;
        return operation;
    }

    /**
     * The place under the cursor, by its name, in map coordinates.
     *
     * @return the hovered place, or null
     */
    @Override
    public Item hovered() {
        Place place = scene.hoveredPlace;
        if (place == null || scene.game == null) {
            return null;
        }
        Vector2f at = scene.game.map.grid.dualPoints().get(place.cell);
        Item item = new Item();
        item.label = place.name;
        item.location[0] = at.x;
        item.location[1] = at.y;
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
        TradeGame game = scene.game;
        if (game == null) {
            return legs;
        }
        for (Loop loop : game.player.loops) {
            for (int k = 0; k < loop.stops.length; k++) {
                Place a = loop.stops[k];
                Place b = loop.stops[(k + 1) % loop.stops.length];
                Vector2f pa = game.map.grid.dualPoints().get(a.cell);
                Vector2f pb = game.map.grid.dualPoints().get(b.cell);
                Item item = new Item();
                item.label = a.name + "-" + b.name;
                item.location[0] = (pa.x + pb.x) * 0.5f;
                item.location[1] = (pa.y + pb.y) * 0.5f;
                item.size = loop.wagons;
                item.length = game.table.cost(a, b);
                item.saved = true;
                legs.add(item);
            }
        }
        return legs;
    }

    /**
     * The tool panel's text: the tool and the layer.
     *
     * @return the formatted info text
     */
    @Override
    public HyperString info() {
        HyperString h = new HyperString();
        h.addWord(displayName() + ", layer " + scene.layer, scene.palette.headingText);
        return h;
    }
}
