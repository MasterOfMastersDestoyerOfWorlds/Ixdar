package ixdar.scenes.main;

import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.text.HyperString;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.tools.Tool;
import ixdar.platform.input.MouseTrap;

public class InfoPane implements MouseTrap.ScrollHandler {

    public float scrollOffsetY = 0;
    public float SCROLL_SPEED = 4f;

    private HyperString cachedInfo;

    /**
     * Render the info pane: the active tool's general info followed by
     * its dynamic info block, both stacked from the top with the current
     * scroll offset applied. Records how long {@code tool.info()} took
     * into {@link MainScene#canvas}'s paint-time tracker.
     *
     * @param camera 2D camera providing the pane's view bounds
     */
    public void draw(Camera2D camera) {
        int row = 0;
        float rowHeight = Drawing.FONT_HEIGHT_PIXELS;
        Tool tool = MainScene.tool;
        HyperString toolGeneralInfo = tool.toolGeneralInfo();
        Drawing.getDrawing().font.drawHyperStringRows(toolGeneralInfo, row, scrollOffsetY, rowHeight, camera);
        row += toolGeneralInfo.getLines();

        // 50-79% of draw time

        long start = System.nanoTime();
        cachedInfo = tool.info();
        long end = System.nanoTime();
        MainScene.canvas.checkPaintTime = end - start;

        // 6% of draw time
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
        } else if (menuBottom < MainScene.MAIN_VIEW_OFFSET_Y) {
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
