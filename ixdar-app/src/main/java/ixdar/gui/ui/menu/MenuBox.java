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
    public static final String VIEW_MENU_SCROLL = "MENU_SCROLL";
    public static final String VIEW_MENU_ITEMS = "MENU_ITEMS";
    public static final MenuScrollBar ITEM_COLUMN_SCROLL_BAR = new MenuScrollBar(
            new Bounds(0, 0, 0, 0, VIEW_MENU_SCROLL), false, MenuScrollBox.PIXELS_PER_SCROLL_UNIT);
    public static ArrayList<MenuItem> menuItems;
    public static Menu activeMenu;
    public static boolean menuVisible = true;

    private static Menu debugMenu;
    private static Menu gameMenu;

    /** The scene the menu is drawn in; the menu takes input only while it is active. */
    public final Scene owner;

    /**
     * The item column, its scroll bar included, from the bottom of the framebuffer to the top: the
     * region in which the shown menu takes the wheel and the presses on its buttons and bar.
     */
    public final Bounds itemColumn = new Bounds(0, 0, 0, 0, VIEW_MENU_ITEMS);

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

    /**
     * Build the menu-box widget: load button textures, create the debug and game
     * menus, pick the active one based on {@code Toggle.GameMode}, and subscribe the
     * {@link #itemColumn} so the menu takes the wheel and the clicks on its buttons.
     *
     * @param owner the scene the menu is drawn in
     */
    public MenuBox(Scene owner) {
        this.owner = owner;
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
        PointerDispatcher.current().subscribe(itemColumn, this);
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
     * Draw the logo, then (when {@link #menuVisible}) the stack of menu-item buttons centered on
     * screen, scrolled up by {@link #ITEM_COLUMN_SCROLL_BAR}, and that bar beside them while they
     * overflow the screen's lower half. Highlights the enabled button the cursor hovers; disabled
     * buttons keep a dark grey rim.
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
        MenuScrollBar bar = ITEM_COLUMN_SCROLL_BAR;
        float columnLeft = centerX - itemWidth / 2;
        float barRight = centerX + menuOuterBorder.outerTexture.width * scale / 2 + MenuScrollBar.WIDTH_PIXELS;
        bar.bounds.update(columnLeft, 0, barRight - columnLeft, centerY);
        itemColumn.update(columnLeft, 0, barRight - columnLeft, Platforms.get().getFrameBufferHeight());
        bar.contentLength = itemHeight * menuItems.size() * ITEM_SPACING_FACTOR;
        bar.visibleLength = centerY;
        bar.scrollBy(0);
        for (int i = 0; i < menuItems.size(); i++) {
            float itemCenterY = centerY - itemHeight - (itemHeight * i * ITEM_SPACING_FACTOR) + bar.scrollOffset;
            float leftBoundX = centerX - itemWidth / 2;
            float rightBoundX = centerX + itemWidth / 2;
            float upBoundX = itemCenterY + itemHeight / 2;
            float downBoundX = itemCenterY - itemHeight / 2;
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
        if (bar.isNeeded()) {
            bar.draw((Camera2D) camera);
            ((Camera2D) camera).resetView();
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
     * The item whose button, padding included, holds a framebuffer point, enabled or not.
     *
     * @param x framebuffer x
     * @param y framebuffer y, y up
     * @return that item, or {@code null} when the point is on no button
     */
    private MenuItem itemAt(float x, float y) {
        float centerX = Platforms.get().getFrameBufferWidth() / 2;
        for (int i = 0; i < menuItems.size(); i++) {
            float centerY = Platforms.get().getFrameBufferHeight() / 2 - itemHeight
                    - (itemHeight * i * ITEM_SPACING_FACTOR) + ITEM_COLUMN_SCROLL_BAR.scrollOffset;
            if (x > centerX - itemWidth / 2 && x < centerX + itemWidth / 2 && y > centerY - itemHeight / 2
                    && y < centerY + itemHeight / 2) {
                return menuItems.get(i);
            }
        }
        return null;
    }

    /**
     * Make the given menu the active screen, resetting scroll and refreshing the
     * displayed item list.
     *
     * @param parent the menu to display
     */
    public static void load(Menu parent) {
        ITEM_COLUMN_SCROLL_BAR.scrollOffset = 0;
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
     * Unused: the menu claims the wheel and scrolls through {@link #onScrollDelta}.
     *
     * @param scrollUp     unused
     * @param deltaSeconds unused
     */
    @Override
    public void onScroll(boolean scrollUp, double deltaSeconds) {
    }

    /**
     * The shown menu of the active scene takes the wheel over its item column.
     *
     * @return whether the menu is shown in the active scene
     */
    @Override
    public boolean claimsWheel() {
        return menuVisible && owner.active;
    }

    /**
     * Scroll the items as a {@link MenuScrollBox} does, {@link MenuScrollBox#PIXELS_PER_SCROLL_UNIT}
     * per unit of delta, clamped so the first item starts at the top and the last stops at the
     * bottom; a delta below zero (wheel toward the user) moves on to later items.
     *
     * @param deltaX raw horizontal delta, ignored
     * @param deltaY raw vertical delta, 1.0 per wheel notch
     */
    @Override
    public void onScrollDelta(double deltaX, double deltaY) {
        ITEM_COLUMN_SCROLL_BAR.scrollBy((float) (-deltaY * MenuScrollBox.PIXELS_PER_SCROLL_UNIT));
    }

    /**
     * Presses on the shown menu's buttons and needed bar are the menu's, so a click on a button's
     * label reaches no other handler.
     *
     * @param x framebuffer x of the press
     * @param y framebuffer y of the press, y up
     * @return whether the press lands on a button or a needed bar of the shown menu
     */
    @Override
    public boolean claimsPointer(float x, float y) {
        return claimsWheel() && (itemAt(x, y) != null
                || ITEM_COLUMN_SCROLL_BAR.isNeeded() && ITEM_COLUMN_SCROLL_BAR.contains(x, y));
    }

    /**
     * Step, page or grab the bar where the press lands.
     *
     * @param x framebuffer x of the press
     * @param y framebuffer y of the press, y up
     */
    @Override
    public void onPress(float x, float y) {
        ITEM_COLUMN_SCROLL_BAR.press(x, y);
    }

    /**
     * Move a grabbed thumb.
     *
     * @param x framebuffer x of the cursor
     * @param y framebuffer y of the cursor, y up
     */
    @Override
    public void onDrag(float x, float y) {
        ITEM_COLUMN_SCROLL_BAR.dragTo(x, y);
    }

    /**
     * Let go of the thumb and, for a click on an enabled button, record an automation event, play
     * the click SFX and run the button's action, the one path a menu action runs by.
     *
     * @param x framebuffer x of the release
     * @param y framebuffer y of the release, y up
     * @param click whether the press and release make a click
     */
    @Override
    public void onRelease(float x, float y, boolean click) {
        ITEM_COLUMN_SCROLL_BAR.release();
        MenuItem clickedItem = click ? itemAt(x, y) : null;
        if (clickedItem == null || clickedItem.action == null) {
            return;
        }
        AutomationBackend automation = Platforms.get().automation();
        if (automation != null) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("label", clickedItem.getHeading());
            payload.put("xNormalized", x / Platforms.get().getFrameBufferWidth());
            payload.put("yNormalized", 1 - y / Platforms.get().getFrameBufferHeight());
            automation.recordAbstractActionMap("menu_select", payload);
        }
        Scene.audioPlaySfx(AudioAssets.MENU_CLICK_SFX);
        clickedItem.performAction();
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
            float itemCenterY = centerY - itemHeight - (itemHeight * i * ITEM_SPACING_FACTOR)
                    + ITEM_COLUMN_SCROLL_BAR.scrollOffset;
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
