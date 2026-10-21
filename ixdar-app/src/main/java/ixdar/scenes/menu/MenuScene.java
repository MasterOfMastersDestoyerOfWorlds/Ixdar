package ixdar.scenes.menu;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.graphics.render.sdf.SDFFluid;
import ixdar.gui.ui.menu.MenuBox;
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
     * The menu's one key: Esc steps back out of a submenu.
     */
    @Override
    public void setControls() {
        controls.add(new ControlHint(Keys.ESCAPE, "esc", "back out of the submenu", () -> {
            if (menu != null) {
                menu.back();
            }
        }));
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
     * A click goes to the menu item under it.
     *
     * @param windowX click x in window pixels
     * @param windowY click y in window pixels, top-down
     * @param button  button index
     */
    @Override
    public void viewClicked(float windowX, float windowY, int button) {
        if (menu != null) {
            menu.click(mouse.normalizedPosX, mouse.normalizedPosY);
        }
    }
}
