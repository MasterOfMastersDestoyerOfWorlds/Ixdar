package ixdar.scenes.menu;

import java.util.List;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.graphics.render.sdf.SDFFluid;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.gui.ui.menu.MenuScrollBox;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.Scene;
import ixdar.tools.ControlHint;

/**
 * The "ixdar" main menu over its fluid backdrop. Its items switch the window to the TSP editor or
 * the trade game, which hand the window back on Escape.
 */
@SceneAnnotation(id = "ixdar")
public class MenuScene extends Scene {

    /** The main menu, built on the render thread by the first {@link #drawScene()}. */
    public MenuBox menu;

    /** The animated backdrop drawn behind the visible menu. */
    public SDFFluid fluid;

    /**
     * Draw the menu over its backdrop while it is visible. The menu and backdrop are built here
     * because they bind shader programs.
     */
    @Override
    public void drawScene() {
        if (menu == null) {
            menu = new MenuBox(this);
            fluid = new SDFFluid();
        }
        if (MenuBox.menuVisible) {
            fluid.draw(0, 0, Platforms.get().getFrameBufferWidth(), Platforms.get().getFrameBufferHeight(), null,
                    camera2D);
            menu.draw(camera2D);
        }
    }

    /**
     * Take or release input; on activation reload the shown menu screen, so a Continue reflects
     * the file the editor just opened.
     *
     * @param state true to activate the menu, false to suspend it
     */
    @Override
    public void activate(boolean state) {
        super.activate(state);
        if (state && menu != null) {
            MenuBox.load(MenuBox.activeMenu);
        }
    }

    /**
     * The main menu's item rows, once the first frame has built the menu.
     *
     * @return the shown panes' boxes
     */
    @Override
    public List<MenuScrollBox> panes() {
        List<MenuScrollBox> panes = super.panes();
        if (menu != null) {
            panes.add(menu.itemBox);
        }
        return panes;
    }

    /**
     * The menu's one key: Esc steps back out of a submenu.
     */
    @Override
    public void setControls(List<ControlHint> controls) {
        controls.add(new ControlHint("escape", Keys.ESCAPE, "esc", "back out of the submenu", this::escapePressed));
    }

    /** Esc steps back out of a submenu. */
    @Override
    public void escapePressed() {
        if (menu != null) {
            menu.back();
        }
    }

    /**
     * The menu item under the cursor takes the hover.
     *
     * @param windowX cursor x in window pixels
     * @param windowY cursor y in window pixels, top-down
     */
    @Override
    public void viewHovered(float windowX, float windowY) {
        if (menu != null) {
            menu.setHover(mouse.normalizedPosX, mouse.normalizedPosY);
        }
    }

    /**
     * A click off the menu's buttons does nothing: the menu, a pointer region, takes the clicks on
     * its buttons itself.
     *
     * @param windowX unused
     * @param windowY unused
     * @param button  unused
     */
    @Override
    public void viewClicked(float windowX, float windowY, int button) {
    }
}
