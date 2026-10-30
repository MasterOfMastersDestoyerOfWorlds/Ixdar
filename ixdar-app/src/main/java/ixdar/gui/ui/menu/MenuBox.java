package ixdar.gui.ui.menu;

import java.util.ArrayList;
import java.util.List;

import java.util.HashMap;

import ixdar.graphics.cameras.Bounds;

import java.util.Map;
import ixdar.graphics.cameras.Camera;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.ColorBox;
import ixdar.graphics.render.color.Palette;
import ixdar.graphics.render.sdf.SDFTexture;
import ixdar.gui.ui.text.HyperWord;
import ixdar.scenes.Scene;
import ixdar.platform.Platforms;
import ixdar.scenes.main.Toggle;
import ixdar.platform.AutomationBackend;
import ixdar.input.PointerDispatcher;
import ixdar.platform.input.PointerRegion;

public class MenuBox implements PointerRegion {
    public static final float ITEM_SPACING_FACTOR = 1.5f;
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

    /**
     * The shown items' labels as rows, one per button, each row's word placed on its button's
     * rectangle as last drawn; the buttons draw themselves, so the box is never drawn.
     */
    public final MenuScrollBox itemBox;

    public SDFTexture logo;

    int hoverItem = -1;
    float scale = 2f;
    float hoverX;
    float hoverY;
    ColorBox boundingBox;
    private float itemWidth;
    private float itemHeight;
    /** This frame's buttons, one per shown item, and the item each stands for. */
    private final ArrayList<MenuButton> buttons = new ArrayList<>();
    private final ArrayList<MenuItem> buttonItems = new ArrayList<>();

    /**
     * Build the menu-box widget: load button textures, create the debug and game
     * menus, pick the active one based on {@code Toggle.GameMode}, and subscribe the
     * {@link #itemColumn} so the menu takes the wheel and the clicks on its buttons.
     *
     * @param owner the scene the menu is drawn in
     */
    public MenuBox(Scene owner) {
        this.owner = owner;
        logo = new SDFTexture("decal_sdf.png", true);
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
        itemBox = new MenuScrollBox(itemColumn, ITEM_COLUMN_SCROLL_BAR, this::claimsWheel);
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
     * Draw the logo, then (when {@link #menuVisible}) the scrolled stack of menu buttons with their
     * bar. A hovered enabled button's rim flashes towards the theme's hover flash; a disabled one
     * keeps the disabled rim.
     *
     * @param camera the active camera used for screen-space rendering
     */
    public void draw(Camera camera) {
        if (!MenuButton.loaded()) {
            return;
        }

        float centerX = Platforms.get().getFrameBufferWidth() / 2;
        float centerY = Platforms.get().getFrameBufferHeight() / 2;
        int min = Math.min(Platforms.get().getFrameBufferWidth(), Platforms.get().getFrameBufferHeight());
        int logoWidth = (int) min / 2;
        int halfWidth = logoWidth / 2;
        Palette palette = owner.palette;
        logo.draw((centerX - halfWidth), (centerY + centerY / 2 - halfWidth), logoWidth, logoWidth, palette.accent,
                camera);
        if (!menuVisible) {
            return;
        }
        itemHeight = MenuButton.naturalHeight(scale);
        itemWidth = MenuButton.naturalWidth(scale);
        float plateWidth = itemWidth / MenuButton.BUTTON_WIDTH_PER_PLATE_WIDTH;
        MenuScrollBar bar = ITEM_COLUMN_SCROLL_BAR;
        float columnLeft = centerX - itemWidth / 2;
        float barRight = centerX + plateWidth / 2 + MenuScrollBar.WIDTH_PIXELS;
        bar.bounds.update(columnLeft, 0, barRight - columnLeft, centerY);
        itemColumn.update(columnLeft, 0, barRight - columnLeft, Platforms.get().getFrameBufferHeight());
        bar.contentLength = itemHeight * menuItems.size() * ITEM_SPACING_FACTOR;
        bar.visibleLength = centerY;
        bar.scrollBy(0);
        itemBox.clearRows();
        buttons.clear();
        buttonItems.clear();
        for (int i = 0; i < menuItems.size(); i++) {
            MenuItem item = menuItems.get(i);
            float leftBoundX = centerX - itemWidth / 2;
            float downBoundX = centerY - itemHeight - (itemHeight * i * ITEM_SPACING_FACTOR) + bar.scrollOffset
                    - itemHeight / 2;
            MenuButton button = new MenuButton(item.itemString(), item.action != null, item::performAction);
            button.draw(leftBoundX, downBoundX, itemWidth, itemHeight, itemHeight / 2, palette, palette.panel,
                    hoverX, hoverY, (Camera2D) camera);
            buttons.add(button);
            buttonItems.add(item);
            int firstWord = itemBox.text.words.size();
            itemBox.addRow(item.getHeading(), palette.accent, item.action);
            for (HyperWord word : itemBox.text.words.subList(firstWord, itemBox.text.words.size())) {
                word.xScreenOffset = leftBoundX;
                word.yScreenOffset = downBoundX;
                word.drawnWidth = itemWidth;
                word.rowHeight = itemHeight;
            }
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
     * Which of this frame's buttons, padding included, holds a framebuffer point, enabled or not.
     *
     * @param x framebuffer x
     * @param y framebuffer y, y up
     * @return the button's index, or -1 when the point is on no button
     */
    private int buttonAt(float x, float y) {
        for (int i = 0; i < buttons.size(); i++) {
            if (buttons.get(i).contains(x, y)) {
                return i;
            }
        }
        return -1;
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
     * @param x      framebuffer x of the cursor, ignored
     * @param y      framebuffer y of the cursor, ignored
     * @param mods   held modifiers, ignored
     */
    @Override
    public void onScrollDelta(double deltaX, double deltaY, float x, float y, int mods) {
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
        return claimsWheel() && (buttonAt(x, y) >= 0
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
     * Let go of the thumb and, for a click on an enabled button, record an automation event and let
     * the button answer it, the one path a menu action runs by.
     *
     * @param x framebuffer x of the release
     * @param y framebuffer y of the release, y up
     * @param click whether the press and release make a click
     */
    @Override
    public void onRelease(float x, float y, boolean click) {
        ITEM_COLUMN_SCROLL_BAR.release();
        int clicked = click ? buttonAt(x, y) : -1;
        if (clicked < 0 || !buttons.get(clicked).enabled) {
            return;
        }
        AutomationBackend automation = Platforms.get().automation();
        if (automation != null) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("label", buttonItems.get(clicked).getHeading());
            payload.put("xNormalized", x / Platforms.get().getFrameBufferWidth());
            payload.put("yNormalized", 1 - y / Platforms.get().getFrameBufferHeight());
            automation.recordAbstractActionMap("menu_select", payload);
        }
        buttons.get(clicked).click(x, y);
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

}
