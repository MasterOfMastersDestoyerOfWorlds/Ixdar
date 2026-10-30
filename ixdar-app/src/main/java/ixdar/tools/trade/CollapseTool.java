package ixdar.tools.trade;

import org.joml.Vector2f;

import ixdar.annotations.tool.ToolAnnotation;
import ixdar.game.trade.map.Place;
import ixdar.geometry.tsp.knot.Knot;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.scenes.trade.TradeScene;

/**
 * Collapse: click a place in the knot a chain starts at and one in the knot it ends at, both inside one
 * knot at the current layer; one pipe leg of each link is dropped and the chain's ends are joined.
 */
@ToolAnnotation(id = CollapseTool.TOOL_NAME)
public class CollapseTool extends TradeRouteTool {
    public static final String TOOL_NAME = "trade-collapse";
    public static final float MEMBER_RING_SHARE = 0.7f;

    /**
     * Collapse over the trade scene.
     *
     * @param scene the scene whose game the tool edits
     */
    public CollapseTool(TradeScene scene) {
        super(scene);
    }

    /**
     * The first click picks the chain's start, the second its end, and the chain collapses.
     *
     * @param x click x in pixels
     * @param y click y in pixels, y up
     * @return true when a place took the click
     */
    @Override
    public boolean onClick(float x, float y) {
        Place place = selectAt(x, y);
        if (place == null) {
            return false;
        }
        if (pending == null || pending == place) {
            pending = pending == place ? null : place;
            return true;
        }
        report(scene.game.network.collapse(pending, place, scene.layer), capitalised(scene.game.network.lastRefusal));
        return true;
    }

    /**
     * Ring every place of the knot the first click picked at the current layer, so the chain's start is
     * plain.
     *
     * @param camera        the map camera
     * @param lineThickness lane width in pixels
     */
    @Override
    public void drawOverlay(Camera2D camera, float lineThickness) {
        if (ringPending(camera) == null) {
            return;
        }
        Knot parent = scene.game.network.knotAt(pending, scene.layer);
        Knot start = scene.game.network.knotOf(pending).childOf(parent);
        float radius = RING_RADIUS_IN_CELLS * MEMBER_RING_SHARE * scene.game.map.grid.cellSpacing() * scene.scale();
        for (Place place : scene.game.map.places) {
            Knot leaf = scene.game.network.knotOf(place);
            if (leaf != scene.game.network.knotOf(pending) && leaf.childOf(parent) == start && leaf.matchCount > 0) {
                Vector2f p = scene.placePixel(place);
                ring.draw(p, radius, Color.TRANSPARENT, scene.palette.highlight, camera);
            }
        }
    }

    /**
     * {@inheritDoc}.
     *
     * @return "Collapse"
     */
    @Override
    public String displayName() {
        return "Collapse";
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
        return "Join the ends of a chain of knots into one.";
    }
}
