package ixdar.canvas;

import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;

import ixdar.annotations.scene.SceneRegistry_Scenes;
import ixdar.annotations.scene.SceneDrawable;
import ixdar.gui.terminal.TerminalParseException;
import ixdar.scenes.main.MainScene;
import ixdar.scenes.menu.MenuScene;

public class CanvasSceneMap {

    public static final Map<String, Supplier<? extends SceneDrawable>> MAP;

    static {

        MAP = SceneRegistry_Scenes.MAP;
        MAP.put("ixdar-canvas", () -> {
            try {
                return new MainScene("djbouti", new MenuScene());
            } catch (TerminalParseException | IOException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
