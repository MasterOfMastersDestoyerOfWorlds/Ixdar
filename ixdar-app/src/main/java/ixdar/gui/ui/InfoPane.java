package ixdar.gui.ui;

import java.util.List;

import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.gui.ui.text.HyperString;
import ixdar.platform.Clock;
import ixdar.platform.input.PointerRegion;
import ixdar.tools.ControlHint;
import ixdar.tools.Tool;

public class InfoPane implements PointerRegion {

    public float scrollOffsetY = 0;
    public float SCROLL_SPEED = 4f;

    /** Window height of the pane's bottom edge: scrolling down stops once the last row is above it. */
    public float bottomEdgeY;

    private HyperString cachedInfo;

    /** The controls listing as last drawn, the pane's last block, whose last row bounds the scroll. */
    private HyperString controlsText;

    /**
     * Build an empty info pane whose bottom edge sits {@code bottomEdgeY} pixels above the window's.
     *
     * @param bottomEdgeY window height of the pane's bottom edge
     */
    public InfoPane(float bottomEdgeY) {
        this.bottomEdgeY = bottomEdgeY;
    }

    /**
     * Render the info pane stacked from the top with the scroll offset applied: the frame rate and
     * the active tool's name, the tool's {@link Tool#info}, then the scene's controls listing.
     *
     * @param camera   2D camera providing the pane's view bounds
     * @param tool     the active tool, whose info is shown
     * @param controls the scene's controls, one row per key
     */
    public void draw(Camera2D camera, Tool tool, List<ControlHint> controls) {
        int row = 0;
        float rowHeight = Drawing.FONT_HEIGHT_PIXELS;
        HyperString toolGeneralInfo = new HyperString();
        toolGeneralInfo.addWord("FPS:" + Clock.fps());
        toolGeneralInfo.newLine();
        toolGeneralInfo.addWord("Tool: " + tool.displayName());
        toolGeneralInfo.wrap();
        Drawing.getDrawing().font.drawHyperStringRows(toolGeneralInfo, row, scrollOffsetY, rowHeight, camera);
        row += toolGeneralInfo.getLines();

        cachedInfo = tool.info();
        Drawing.getDrawing().font.drawHyperStringRows(cachedInfo, row, scrollOffsetY, rowHeight, camera);
        row += cachedInfo.getLines();

        controlsText = new HyperString();
        controlsText.newLine();
        controlsText.addLine("Controls:", Color.IXDAR);
        for (ControlHint hint : controls) {
            controlsText.addWord(hint.key, Color.BLUE_GREY);
            controlsText.addLine(hint.description, Color.WHITE);
        }
        controlsText.wrap();
        Drawing.getDrawing().font.drawHyperStringRows(controlsText, row, scrollOffsetY, rowHeight, camera);
    }

    /**
     * Mouse-wheel scroll handler: shift {@link #scrollOffsetY} up or down
     * by {@link #SCROLL_SPEED} times the frame delta, clamped at the top
     * (offset never goes negative) and limited downward to keep the last
     * info row visible.
     *
     * @param scrollUp true if the wheel scrolled upward (content moves down)
     * @param deltaSeconds wheel-delta scaling factor in seconds
     */
    @Override
    public void onScroll(boolean scrollUp, double deltaSeconds) {
        float menuBottom = controlsText != null ? controlsText.getLastWord().yScreenOffset : 0;
        if (scrollUp) {
            scrollOffsetY -= SCROLL_SPEED * deltaSeconds;
            if (scrollOffsetY < 0) {
                scrollOffsetY = 0;
            }
        } else if (menuBottom < bottomEdgeY) {
            scrollOffsetY += SCROLL_SPEED * deltaSeconds;
        }
    }

    /**
     * The most recently rendered tool info block (set during {@link #draw}).
     *
     * @return the cached info string, or {@code null} if {@link #draw} has not run yet
     */
    public HyperString getCachedInfo() {
        return cachedInfo;
    }

}
