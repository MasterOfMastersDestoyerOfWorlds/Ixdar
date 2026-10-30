package ixdar.tools.trade;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector2f;

import ixdar.annotations.tool.ToolAnnotation;
import ixdar.game.trade.TradeGame;
import ixdar.game.trade.map.Place;
import ixdar.game.trade.routes.GrowTarget;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.scenes.trade.TradeScene;

/**
 * Grow: click a place on a loop, then one of the highlighted lone places to add it. Each target shows the
 * cells it adds, or in red how far the wagon would run dry; at a deeper layer only the clicked knot's own
 * legs can be split.
 */
@ToolAnnotation(id = GrowTool.TOOL_NAME)
public class GrowTool extends TradeRouteTool {
    public static final String TOOL_NAME = "trade-grow";
    public static final float TARGET_LABEL_OFFSET_IN_CELLS = 0.55f;

    private final ArrayList<GrowTarget> targets = new ArrayList<>();

    /**
     * Grow over the trade scene.
     *
     * @param scene the scene whose game the tool edits
     */
    public GrowTool(TradeScene scene) {
        super(scene);
    }

    /**
     * The first click picks the loop and lists its targets; a click on a target grows to it.
     *
     * @param x click x in pixels
     * @param y click y in pixels, y up
     * @return true when a place took the click
     */
    @Override
    public boolean onClick(float x, float y) {
        Place place = selectAt(x, y);
        TradeGame game = scene.game;
        if (place == null) {
            targets.clear();
            return false;
        }
        GrowTarget chosen = null;
        for (GrowTarget target : targets) {
            chosen = target.place() == place ? target : chosen;
        }
        if (pending != null && chosen != null) {
            Place loopPlace = pending;
            report(game.network.grow(loopPlace, place, scene.layer), capitalised(game.network.lastRefusal));
            pending = loopPlace;
        } else if (game.network.loopOf(place) == null) {
            scene.refuse("Pick a place on one of your loops first");
            pending = null;
        } else {
            pending = place;
        }
        targets.clear();
        if (pending != null) {
            targets.addAll(game.network.growTargets(pending, scene.layer));
            if (targets.isEmpty()) {
                scene.refuse("Nothing can join this knot without a crossing");
            }
        }
        return true;
    }

    /**
     * Every target ringed, green with the cells it adds or red with how far the wagon runs dry.
     *
     * @param camera        the map camera
     * @param lineThickness lane width in pixels
     */
    @Override
    public void drawOverlay(Camera2D camera, float lineThickness) {
        if (ringPending(camera) == null) {
            return;
        }
        TradeGame game = scene.game;
        float cell = game.map.grid.cellSpacing() * scene.scale();
        for (GrowTarget target : targets) {
            int deficit = game.economy.longestStretch(game.player, target.grownStops()) - game.player.wagonCapacity;
            Color color = deficit > 0 ? scene.palette.error : scene.palette.success;
            Vector2f p = scene.placePixel(target.place());
            ring.draw(p, RING_RADIUS_IN_CELLS * cell, Color.TRANSPARENT, color, camera);
            scene.hud.tag("+" + target.addedCells() + (deficit > 0 ? ", dry by " + deficit : CELLS), p.x,
                    p.y + TARGET_LABEL_OFFSET_IN_CELLS * cell, color);
        }
    }

    /** Start with nothing pending and no targets. */
    @Override
    public void activate() {
        super.activate();
        targets.clear();
    }

    /**
     * The targets the pending loop can grow to, for automation.
     *
     * @return the current targets
     */
    public List<GrowTarget> targets() {
        return targets;
    }

    /**
     * {@inheritDoc}.
     *
     * @return "Grow"
     */
    @Override
    public String displayName() {
        return "Grow";
    }

    /**
     * {@inheritDoc}.
     *
     * @return {@value #TOOL_NAME}
     */
    @Override
    public String toolName() {
        return TOOL_NAME;
    }

    /**
     * {@inheritDoc}.
     *
     * @return the one-line purpose
     */
    @Override
    public String desc() {
        return "Add a lone place to a loop at its cheapest uncrossed leg.";
    }
}
