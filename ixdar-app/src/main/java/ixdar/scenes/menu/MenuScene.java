package ixdar.scenes.menu;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.graphics.render.sdf.SDFFluid;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.platform.Platforms;
import ixdar.scenes.Scene;
import ixdar.scenes.main.MainScene;
import ixdar.scenes.trade.TradeScene;

/**
 * The "ixdar" main menu over its fluid backdrop. Until the TSP editor and the trade game are
 * scenes of their own, it also hosts them: once the menu is hidden it draws whichever one runs.
 */
@SceneAnnotation(id = "ixdar")
public class MenuScene extends Scene {

    /** The main menu, built on the render thread by the first {@link #drawScene()}. */
    public MenuBox menu;

    /** The animated backdrop drawn behind the visible menu. */
    public SDFFluid fluid;

    /**
     * Draw the menu over its backdrop while it is visible; otherwise the running trade game, else
     * the TSP editor. The menu and backdrop are built here because they bind shader programs.
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
        }

        if (TradeScene.active && TradeScene.instance != null && !MenuBox.menuVisible) {
            TradeScene.instance.draw(camera2D);
        } else if (MainScene.main != null && !MenuBox.menuVisible) {
            MainScene.main.draw(camera2D);
        }

        if (MenuBox.menuVisible) {
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
