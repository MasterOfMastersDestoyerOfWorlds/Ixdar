package shell.ui.menu;

import java.util.ArrayList;

import shell.cameras.Camera;
import shell.file.FileManagement;
import shell.render.color.Color;
import shell.render.color.ColorBox;
import shell.render.color.ColorLerp;
import shell.render.color.ColorRGB;
import shell.render.sdf.SDFUnion;
import shell.ui.Canvas3D;
import shell.ui.Drawing;

public class MenuBox {
    SDFUnion menuOuterBorder;
    int hoverItem = -1;
    float scale = 3f;
    float hoverX;
    float hoverY;
    float alpha;
    Color outerColor;
    Color outerFlash;
    Color innerColor;
    ColorBox boundingBox;
    private float itemWidth;
    private float itemHeight;
    public static ArrayList<MenuItem> menuItems;
    public static Menu activeMenu;
    public static float scrollOffsetY;
    public float SCROLL_SPEED = 20f;
    public static boolean menuVisible = true;

    public MenuBox() {
        alpha = 0.95f;

        innerColor = new ColorRGB(Color.NAVY, alpha);
        outerColor = new ColorRGB(Color.BLUE_WHITE, alpha);
        outerFlash = new ColorLerp(Color.BLUE_WHITE, Color.TRANSPARENT, new byte[] { 0, 0, 0, 1 });
        menuOuterBorder = new SDFUnion("menu_inner.png", Color.NAVY, 0.95f, 0, -0.02f, "menu_outer.png",
                Color.BLUE_WHITE, alpha, 5, 2);
        boundingBox = new ColorBox();
        String cachedFileName = FileManagement.getTestFileCache();
        activeMenu = new Menu.MainMenu(cachedFileName);
        menuItems = activeMenu.loadMenu();
    }

    public void draw(Camera camera) {

        if (!menuVisible) {
            return;
        }
        itemHeight = menuOuterBorder.outerTexture.height * scale / 2;
        itemWidth = menuOuterBorder.outerTexture.width * scale * 0.91f;
        for (int i = 0; i < menuItems.size(); i++) {
            float centerX = Canvas3D.frameBufferWidth / 2;
            float centerY = Canvas3D.frameBufferHeight / 2 - (itemHeight * i * 1.5f) - scrollOffsetY;
            float leftBoundX = centerX - itemWidth / 2;
            float rightBoundX = centerX + itemWidth / 2;
            float upBoundX = centerY + itemHeight / 2;
            float downBoundX = centerY - itemHeight / 2;
            if (hoverX > leftBoundX && hoverX < rightBoundX && hoverY > downBoundX && hoverY < upBoundX) {

                menuOuterBorder.drawCentered(centerX, centerY, scale, innerColor, outerFlash, camera);
                // boundingBox.drawCoords(leftBoundX, downBoundX, rightBoundX, upBoundX, zIndex
                // + 0.1f,
                // new Color(Color.YELLOW, 0.5f));
            } else {
                menuOuterBorder.drawCentered(centerX, centerY, scale, camera);

            }
            Drawing.font.drawTextCentered(menuItems.get(i).itemString(), centerX, centerY + itemHeight * 0.045f,
                    itemHeight / 2,
                    Color.BLUE_WHITE, camera);
        }
    }

    public void setHover(float x, float y) {
        hoverX = x;
        hoverY = y;
    }

    public void click(float x, float y) {
        if (!menuVisible) {
            return;
        }
        MenuItem clickedItem = null;
        for (int i = 0; i < menuItems.size(); i++) {
            float centerX = Canvas3D.frameBufferWidth / 2;
            float centerY = Canvas3D.frameBufferHeight / 2 - (itemHeight * i * 1.5f) - scrollOffsetY;
            float leftBoundX = centerX - itemWidth / 2;
            float rightBoundX = centerX + itemWidth / 2;
            float upBoundX = centerY + itemHeight / 2;
            float downBoundX = centerY - itemHeight / 2;
            if (hoverX > leftBoundX && hoverX < rightBoundX && hoverY > downBoundX && hoverY < upBoundX) {
                clickedItem = menuItems.get(i);
                break;
            }
        }
        if (clickedItem == null) {
            return;
        }
        clickedItem.performAction();
    }

    public static void load(Menu parent) {
        scrollOffsetY = 0;
        activeMenu = parent;
        menuItems = parent.loadMenu();
    }

    public void back() {
        activeMenu.back();
    }

    public void scroll(boolean scrollUp) {
        float menuBottom = Canvas3D.frameBufferHeight / 2 - (itemHeight * menuItems.size() * 1.5f);

        if (menuBottom > 0) {
            scrollOffsetY = 0;
            return;
        }
        if (scrollUp) {
            scrollOffsetY += SCROLL_SPEED;
            if (scrollOffsetY > 0) {
                scrollOffsetY = 0;
            }
        } else {
            scrollOffsetY -= SCROLL_SPEED;
            float centerY = menuBottom - scrollOffsetY;
            if (!(centerY < 0)) {
                scrollOffsetY = centerY + scrollOffsetY;
            }
        }
    }

}
