package ixdar.scenes.main;

import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.text.HyperString;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.tools.Tool;
import ixdar.platform.input.MouseTrap;

public class InfoPane implements MouseTrap.ScrollHandler {

    private HyperString cachedInfo;

    public float scrollOffsetY = 0;
    public float SCROLL_SPEED = 4f;

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

}
