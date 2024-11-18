package shell.ui.main;

import shell.cameras.Camera2D;
import shell.render.Clock;
import shell.render.text.HyperString;
import shell.ui.Drawing;
import shell.ui.tools.Tool;

public class Info {

    private HyperString cachedInfo;

    public float scrollOffsetY = 0;
    public float SCROLL_SPEED = 300f;

    public void draw(Camera2D camera) {
        int row = 0;
        float rowHeight = Drawing.FONT_HEIGHT_PIXELS;
        Tool tool = Main.tool;
        HyperString toolGeneralInfo = tool.toolGeneralInfo();
        Drawing.font.drawHyperStringRows(toolGeneralInfo, row, scrollOffsetY, rowHeight, camera);
        row += toolGeneralInfo.getLines();
        cachedInfo = tool.info();

        Drawing.font.drawHyperStringRows(cachedInfo, row, scrollOffsetY, rowHeight, camera);
        row += cachedInfo.getLines();
    }

    public void scrollInfoPanel(boolean scrollUp) {

        float menuBottom = cachedInfo.getLastWord().yScreenOffset;
        double d = Clock.deltaTime();
        if (scrollUp) {
            scrollOffsetY -= SCROLL_SPEED * d;
            if (scrollOffsetY < 0) {
                scrollOffsetY = 0;
            }
        } else if (menuBottom < Main.MAIN_VIEW_OFFSET_Y) {
            scrollOffsetY += SCROLL_SPEED * d;
        }
    }

}
