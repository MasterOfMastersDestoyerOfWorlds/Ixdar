package ixdar.gui.ui.menu;

import java.util.ArrayList;
import java.util.List;

import java.util.HashMap;

import ixdar.graphics.cameras.Bounds;

import java.util.Map;
import ixdar.graphics.cameras.Camera;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorBox;
import ixdar.graphics.render.color.ColorLerp;
import ixdar.graphics.render.sdf.SDFTexture;
import ixdar.graphics.render.sdf.SDFUnion;
import ixdar.gui.ui.Drawing;
import ixdar.audio.AudioAssets;
import ixdar.scenes.Scene;
import ixdar.platform.Platforms;
import ixdar.scenes.main.Toggle;
import ixdar.platform.AutomationBackend;
import ixdar.input.PointerDispatcher;
import ixdar.platform.input.PointerRegion;

public class MenuBox implements PointerRegion {
    public static final float MENU_ALPHA = 0.95f;
    public static final float INNER_OFFSET_Y = 0.02f;
    public static final int PIN_STRIPE_COUNT = 5;
    public static final float LOGO_BORDER_DIST = 0.9f;
    public static final float ITEM_WIDTH_SCALE = 0.91f;
    public static final float ITEM_SPACING_FACTOR = 1.5f;
    public static final float LABEL_Y_OFFSET_FACTOR = 0.075f;
    public static ArrayList<MenuItem> menuItems;
    public static Menu activeMenu;
    public static float scrollOffsetY;
    public static boolean menuVisible = true;

    private static Menu debugMenu;
    private static Menu gameMenu;
    public float SCROLL_SPEED = 10f;
    public SDFTexture logo;

    SDFUnion menuOuterBorder;
    int hoverItem = -1;
    float scale = 2f;
    float hoverX;
    float hoverY;
    float alpha;
    Color outerColor;
    Color outerFlash;
    Color disabledOuterColor;
    Color innerColor;
    ColorBox boundingBox;
    private float itemWidth;
    private float itemHeight;
    private Bounds scrollBounds;

    /**
     * Build the menu-box widget: load button textures, create the debug and game
     * menus, pick the active one based on {@code Toggle.GameMode}, and subscribe to
     * scroll input so the user can scroll long menus.
     */
    public MenuBox() {
        alpha = MENU_ALPHA;

        innerColor = Color.BLUE_TEAL_VERY_DARK.withAlpha(alpha);
        outerColor = Color.BLUE_GREY.withAlpha(alpha);
        outerFlash = new ColorLerp(Color.BLUE_GREY, Color.BLACK_25, new byte[] { 0, 0, 0, 1 });
        disabledOuterColor = Color.GREY_DARK.withAlpha(alpha);
        menuOuterBorder = new SDFUnion("menu_inner.png", Color.BLUE_TEAL_VERY_DARK, MENU_ALPHA, 0, -INNER_OFFSET_Y,
                "menu_outer.png",
                Color.BLUE_GREY, alpha, PIN_STRIPE_COUNT, 2);
        logo = new SDFTexture("decal_sdf.png", Color.IXDAR_DARK, LOGO_BORDER_DIST, 0f, true);
        boundingBox = new ColorBox();
        // Create both menus and link them
        debugMenu = new Menu.MainMenu();
        gameMenu = new GameMenu(debugMenu);

        // Select active menu based on GameMode toggle
        if (Toggle.GameMode.value) {
            activeMenu = gameMenu;
        } else {
            activeMenu = debugMenu;
        }
        menuItems = activeMenu.loadMenu();
        scrollBounds = new Bounds(0, 0, 0, 0, "MENU_SCROLL");
        PointerDispatcher.current().subscribe(scrollBounds, this);
    }

    /**
     * Switch between debug and game menus based on Toggle.GameMode.
     */
    public static void refreshMenuForMode() {
        if (Toggle.GameMode.value) {
            if (gameMenu != null) {
                load(gameMenu);
            }
        } else {
            if (debugMenu != null) {
                load(debugMenu);
            }
        }
    }

    /**
     * Draw the logo, then (when {@link #menuVisible}) the stack of menu-item
     * buttons centered on screen. Highlights whichever enabled button the cursor currently
     * hovers, draws disabled buttons with a dark grey rim, and updates the scroll bounds to the
     * menu's extents.
     *
     * @param camera the active camera used for screen-space rendering
     */
    public void draw(Camera camera) {
        if (menuOuterBorder.outerTexture == null) {
            return;
        }

        float centerX = Platforms.get().getFrameBufferWidth() / 2;
        float centerY = Platforms.get().getFrameBufferHeight() / 2;
        int min = Math.min(Platforms.get().getFrameBufferWidth(), Platforms.get().getFrameBufferHeight());
        int logoWidth = (int) min / 2;
        int halfWidth = logoWidth / 2;
        logo.draw((centerX - halfWidth), (centerY + centerY / 2 - halfWidth), logoWidth, logoWidth, Color.IXDAR,
                camera);
        if (!menuVisible) {
            return;
        }
        itemHeight = menuOuterBorder.outerTexture.height * scale / 2;
        itemWidth = menuOuterBorder.outerTexture.width * scale * ITEM_WIDTH_SCALE;

        // Track menu extents to update scroll bounds each frame
        float minLeft = Float.MAX_VALUE;
        float maxRight = Float.MIN_VALUE;
        float minDown = Float.MAX_VALUE;
        float maxUp = Float.MIN_VALUE;
        for (int i = 0; i < menuItems.size(); i++) {
            float itemCenterY = centerY - itemHeight - (itemHeight * i * ITEM_SPACING_FACTOR) - scrollOffsetY;
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
            // A disabled row keeps a dark grey rim and does not flash under the cursor.
            Color rim = disabledOuterColor;
            if (menuItems.get(i).action != null) {
                boolean hovered = hoverX > leftBoundX && hoverX < rightBoundX && hoverY > downBoundX
                        && hoverY < upBoundX;
                rim = hovered ? outerFlash : outerColor;
            }
            menuOuterBorder.drawCentered(centerX, itemCenterY, scale, innerColor, rim, camera);
            Drawing.getDrawing().font.drawHyperString(menuItems.get(i).itemString(), centerX,
                    itemCenterY + itemHeight * LABEL_Y_OFFSET_FACTOR,
                    itemHeight / 2, (Camera2D) camera);
        }
        if (menuItems.size() > 0) {
            float width = Math.max(0, maxRight - minLeft);
            float height = Math.max(0, maxUp - minDown);
            scrollBounds.update(minLeft, Math.max(0, minDown), width, height);
        } else {
            scrollBounds.update(0, 0, 0, 0);
        }
    }

    /**
     * Record the current cursor position so that the next {@link #draw} can detect
     * which menu item (if any) is hovered.
     *
     * @param x cursor x in screen pixels
     * @param y cursor y in screen pixels
     */
    public void setHover(float x, float y) {
        hoverX = x;
        hoverY = y;
    }

    /**
     * Hit-test menu items against the current hover position; if an enabled one is hit,
     * record an automation event, play the click SFX, and fire its action.
     *
     * @param x cursor x in screen pixels (used only for normalized telemetry)
     * @param y cursor y in screen pixels (used only for normalized telemetry)
     */
    public void click(float x, float y) {
        if (!menuVisible) {
            return;
        }
        MenuItem clickedItem = null;
        for (int i = 0; i < menuItems.size(); i++) {
            float centerX = Platforms.get().getFrameBufferWidth() / 2;
            float centerY = Platforms.get().getFrameBufferHeight() / 2 - itemHeight
                    - (itemHeight * i * ITEM_SPACING_FACTOR) - scrollOffsetY;
            float leftBoundX = centerX - itemWidth / 2;
            float rightBoundX = centerX + itemWidth / 2;
            float upBoundX = centerY + itemHeight / 2;
            float downBoundX = centerY - itemHeight / 2;
            if (hoverX > leftBoundX && hoverX < rightBoundX && hoverY > downBoundX && hoverY < upBoundX) {
                clickedItem = menuItems.get(i);
                break;
            }
        }
        if (clickedItem == null || clickedItem.action == null) {
            return;
        }
        AutomationBackend automation =Platforms.get().automation();
        if (automation != null) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("label", clickedItem.getHeading());
            payload.put("xNormalized", x / Platforms.get().getWindowWidth());
            payload.put("yNormalized", y / Platforms.get().getWindowHeight());
            automation.recordAbstractActionMap("menu_select", payload);
        }
        Scene.audioPlaySfx(AudioAssets.MENU_CLICK_SFX);
        clickedItem.performAction();
    }

    /**
     * Make the given menu the active screen, resetting scroll and refreshing the
     * displayed item list.
     *
     * @param parent the menu to display
     */
    public static void load(Menu parent) {
        scrollOffsetY = 0;
        activeMenu = parent;
        menuItems = parent.loadMenu();
    }

    /**
     * Forward a back-navigation gesture to the active menu's {@link Menu#back()}
     * hook.
     */
    public void back() {
        activeMenu.back();
    }

    /**
     * Scroll the menu list up or down. Clamps so the top item never goes below its
     * starting position and the bottom item never scrolls past the screen floor; if
     * the whole menu fits on screen, scrolling is disabled.
     *
     * @param scrollUp     true if the wheel ticked up, false for down
     * @param deltaSeconds time since the last frame, used to scale scroll speed
     */
    public void onScroll(boolean scrollUp, double deltaSeconds) {
        float menuBottom = Platforms.get().getFrameBufferHeight() / 2
                - (itemHeight * menuItems.size() * ITEM_SPACING_FACTOR);

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

    /**
     * Get the calculated item height for menu buttons. Returns 0 if textures are
     * not yet loaded.
     *
     * @return the height of each menu item in pixels
     */
    public float getItemHeight() {
        return itemHeight;
    }

    /**
     * Get the calculated item width for menu buttons. Returns 0 if textures are not
     * yet loaded.
     *
     * @return the width of each menu item in pixels
     */
    public float getItemWidth() {
        return itemWidth;
    }

    /**
     * Compute per-item screen-space bounds for the currently visible menu. Used by
     * automation/test harnesses to locate buttons without having to replicate the
     * layout math.
     *
     * @return a list of {@link MenuItemBounds}, one per item, or empty if the menu
     *         has no items or its textures are not yet loaded
     */
    public List<MenuItemBounds> getMenuItemBounds() {
        ArrayList<MenuItemBounds> bounds = new ArrayList<>();
        if (menuItems == null || menuItems.isEmpty() || itemWidth <= 0 || itemHeight <= 0) {
            return bounds;
        }
        float centerX = Platforms.get().getFrameBufferWidth() / 2f;
        float centerY = Platforms.get().getFrameBufferHeight() / 2f;
        for (int i = 0; i < menuItems.size(); i++) {
            float itemCenterY = centerY - itemHeight - (itemHeight * i * ITEM_SPACING_FACTOR) - scrollOffsetY;
            MenuItemBounds item = new MenuItemBounds();
            item.label = menuItems.get(i).getHeading();
            item.enabled = menuItems.get(i).action != null;
            item.left = centerX - itemWidth / 2f;
            item.bottom = itemCenterY - itemHeight / 2f;
            item.width = itemWidth;
            item.height = itemHeight;
            item.centerX = centerX;
            item.centerY = itemCenterY;
            bounds.add(item);
        }
        return bounds;
    }

    public static class MenuItemBounds {
        public String label;
        public boolean enabled;
        public float left;
        public float bottom;
        public float width;
        public float height;
        public float centerX;
        public float centerY;
    }

}
