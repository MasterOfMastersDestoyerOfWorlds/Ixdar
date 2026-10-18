package ixdar.scenes.menu;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.graphics.render.sdf.SDFFluid;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.platform.Platforms;
import ixdar.scenes.Scene;

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
            menu = new MenuBox();
            fluid = new SDFFluid();
        }
        if (MenuBox.menuVisible) {
            fluid.draw(0, 0, Platforms.get().getFrameBufferWidth(), Platforms.get().getFrameBufferHeight(), null,
                    camera2D);
            menu.draw(camera2D);
        }
    }

    /**
     * The menu lists no key hints: its keys (Back, the terminal) are handled by the key handler
     * itself, not by the scene's controls.
     */
    @Override
    public void setControls() {
    }
}
