package shell.ui.menu;

import java.util.ArrayList;

import shell.cameras.Camera;
import shell.cameras.Bounds;
import shell.file.FileManagement;
import shell.render.color.Color;
import shell.render.color.ColorBox;
import shell.render.color.ColorLerp;
import shell.render.color.ColorRGB;
import shell.render.sdf.SDFTexture;
import shell.render.sdf.SDFUnion;
import shell.ui.Canvas3D;
import shell.ui.Drawing;
import shell.platform.input.MouseTrap;

public class MenuBox implements MouseTrap.ScrollHandler {
    SDFUnion menuOuterBorder;
    int hoverItem = -1;
    float scale = 2f;
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
    public float SCROLL_SPEED = 10f;
    public static boolean menuVisible = true;
    public SDFTexture logo;
    private Bounds scrollBounds;

    public MenuBox() {
        alpha = 0.95f;

        innerColor = new ColorRGB(Color.NAVY, alpha);
        outerColor = new ColorRGB(Color.BLUE_WHITE, alpha);
        outerFlash = new ColorLerp(Color.BLUE_WHITE, Color.TRANSPARENT25, new byte[] { 0, 0, 0, 1 });
        menuOuterBorder = new SDFUnion("menu_inner.png", Color.NAVY, 0.95f, 0, -0.02f, "menu_outer.png",
                Color.BLUE_WHITE, alpha, 5, 2);
        logo = new SDFTexture("decal_sdf.png", Color.DARK_IXDAR, 0.9f, 0f, true);
        boundingBox = new ColorBox();
        String cachedFileName = FileManagement.getTestFileCache();
        activeMenu = new Menu.MainMenu(cachedFileName);
        menuItems = activeMenu.loadMenu();
        scrollBounds = new Bounds(0, 0, 0, 0);
        MouseTrap.subscribeScrollRegion(scrollBounds, this);
    }

    public void draw(Camera camera) {
        if (menuOuterBorder.outerTexture == null) {
            return;
        }

        float centerX = Canvas3D.frameBufferWidth / 2;
        float centerY = Canvas3D.frameBufferHeight / 2;
        int min = Math.min(Canvas3D.frameBufferWidth, Canvas3D.frameBufferHeight);
        int logoWidth = (int) min / 2;
        int halfWidth = logoWidth / 2;
        logo.draw((centerX - halfWidth), (centerY + centerY / 2 - halfWidth), logoWidth, logoWidth, Color.IXDAR,
                camera);
        if (!menuVisible) {
            return;
        }
        itemHeight = menuOuterBorder.outerTexture.height * scale / 2;
        itemWidth = menuOuterBorder.outerTexture.width * scale * 0.91f;

        // Track menu extents to update scroll bounds each frame
        float minLeft = Float.MAX_VALUE;
        float maxRight = Float.MIN_VALUE;
        float minDown = Float.MAX_VALUE;
        float maxUp = Float.MIN_VALUE;
        for (int i = 0; i < menuItems.size(); i++) {
            float itemCenterY = centerY - itemHeight - (itemHeight * i * 1.5f) - scrollOffsetY;
            float leftBoundX = centerX - itemWidth / 2;
            float rightBoundX = centerX + itemWidth / 2;
            float upBoundX = itemCenterY + itemHeight / 2;
            float downBoundX = itemCenterY - itemHeight / 2;

            if (leftBoundX < minLeft)
                minLeft = leftBoundX;
            if (rightBoundX > maxRight)
                maxRight = rightBoundX;
            if (downBoundX < minDown)
                minDown = downBoundX;
            if (upBoundX > maxUp)
                maxUp = upBoundX;
            if (hoverX > leftBoundX && hoverX < rightBoundX && hoverY > downBoundX && hoverY < upBoundX) {

                menuOuterBorder.drawCentered(centerX, itemCenterY, scale, innerColor, outerFlash, camera);
                // boundingBox.drawCoords(leftBoundX, downBoundX, rightBoundX, upBoundX, zIndex
                // + 0.1f,
                // new Color(Color.YELLOW, 0.5f));
            } else {
                menuOuterBorder.drawCentered(centerX, itemCenterY, scale, camera);

            }
            Drawing.font.drawTextCentered(menuItems.get(i).itemString(), centerX, itemCenterY + itemHeight * 0.045f,
                    itemHeight / 2,
                    Color.BLUE_WHITE, camera);
        }
        if (menuItems.size() > 0) {
            float width = Math.max(0, maxRight - minLeft);
            float height = Math.max(0, maxUp - minDown);
            scrollBounds.update(minLeft, Math.max(0, minDown), width, height);
        } else {
            scrollBounds.update(0, 0, 0, 0);
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
            float centerY = Canvas3D.frameBufferHeight / 2 - itemHeight - (itemHeight * i * 1.5f) - scrollOffsetY;
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

    public void onScroll(boolean scrollUp, double deltaSeconds) {
        float menuBottom = Canvas3D.frameBufferHeight / 2 - (itemHeight * menuItems.size() * 1.5f);

        if (menuBottom > 0) {
            scrollOffsetY = 0;
            return;
        }
        if (scrollUp) {
            scrollOffsetY += SCROLL_SPEED * deltaSeconds;
            if (scrollOffsetY > 0) {
                scrollOffsetY = 0;
            }
        } else {
            scrollOffsetY -= SCROLL_SPEED * deltaSeconds;
            float centerY = menuBottom - scrollOffsetY;
            if (!(centerY < 0)) {
                scrollOffsetY = centerY + scrollOffsetY;
            }
        }
    }

}
