package ixdar.tools.trade;

import org.joml.Vector2f;

import ixdar.annotations.tool.ToolAnnotation;
import ixdar.game.trade.map.Place;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.sdf.SDFLine;
import ixdar.scenes.trade.TradeScene;

/**
 * Pipe: click two places. Two lone places start an out-and-back loop, a lone place joins a loop, and two
 * networks join by cutting a leg of each inside their knots at the current layer.
 */
@ToolAnnotation(id = PipeTool.TOOL_NAME)
public class PipeTool extends TradeRouteTool {
    public static final String TOOL_NAME = "trade-pipe";
    public static final float PREVIEW_WIDTH = 3f;
    public static final float DASH_PIXELS = 14f;

    private final SDFLine previewLine = new SDFLine();

    /**
     * Pipe over the trade scene.
     *
     * @param scene the scene whose game the tool edits
     */
    public PipeTool(TradeScene scene) {
        super(scene);
    }

    /**
     * The first click picks a place, the second pipes the two.
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
        Place first = pending;
        report(scene.game.network.pipe(first, place, scene.layer), capitalised(scene.game.network.lastRefusal));
        return true;
    }

    /**
     * A dashed line from the first place to the hovered one with the leg's cost.
     *
     * @param camera        the map camera
     * @param lineThickness lane width in pixels
     */
    @Override
    public void drawOverlay(Camera2D camera, float lineThickness) {
        Vector2f from = ringPending(camera);
        Place hovered = scene.hoveredPlace;
        if (from == null || hovered == null || hovered == pending) {
            return;
        }
        Vector2f to = scene.placePixel(hovered);
        previewLine.setStroke(PREVIEW_WIDTH, true, DASH_PIXELS, 0f, true, false, false);
        previewLine.draw(from, to, scene.palette.highlight, camera);
        scene.hud.tag("+" + scene.game.table.cost(pending, hovered) + CELLS, (from.x + to.x) * 0.5f,
                (from.y + to.y) * 0.5f, scene.palette.highlight);
    }

    /**
     * {@inheritDoc}.
     *
     * @return "Pipe"
     */
    @Override
    public String displayName() {
        return "Pipe";
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
        return "Join two places or two loops into one.";
    }
}
