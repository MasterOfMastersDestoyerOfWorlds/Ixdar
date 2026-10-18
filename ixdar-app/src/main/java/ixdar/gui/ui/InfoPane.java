package ixdar.gui.ui;

import ixdar.graphics.cameras.Camera2D;
import ixdar.gui.ui.text.HyperString;
import ixdar.platform.input.PointerRegion;
import ixdar.tools.knot.Tool;

public class InfoPane implements PointerRegion {

    public float scrollOffsetY = 0;
    public float SCROLL_SPEED = 4f;

    /** Window height of the pane's bottom edge: scrolling down stops once the last row is above it. */
    public float bottomEdgeY;

    private HyperString cachedInfo;

    /**
     * Build an empty info pane whose bottom edge sits {@code bottomEdgeY} pixels above the window's.
     *
     * @param bottomEdgeY window height of the pane's bottom edge
     */
    public InfoPane(float bottomEdgeY) {
        this.bottomEdgeY = bottomEdgeY;
    }

    /**
     * Render the info pane: the active tool's general info followed by
     * its dynamic info block, both stacked from the top with the current
     * scroll offset applied.
     *
     * @param camera 2D camera providing the pane's view bounds
     * @param tool   the active tool, whose info is shown
     */
    public void draw(Camera2D camera, Tool tool) {
        int row = 0;
        float rowHeight = Drawing.FONT_HEIGHT_PIXELS;
        HyperString toolGeneralInfo = tool.toolGeneralInfo();
        Drawing.getDrawing().font.drawHyperStringRows(toolGeneralInfo, row, scrollOffsetY, rowHeight, camera);
        row += toolGeneralInfo.getLines();

        cachedInfo = tool.info();

        Drawing.getDrawing().font.drawHyperStringRows(cachedInfo, row, scrollOffsetY, rowHeight, camera);
        row += cachedInfo.getLines();

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
        float menuBottom = cachedInfo != null ? cachedInfo.getLastWord().yScreenOffset : 0;
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
