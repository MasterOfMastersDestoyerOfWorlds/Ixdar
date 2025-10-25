package ixdar.canvas;

import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;

import ixdar.annotations.scene.SceneRegistry_Scenes;
import ixdar.annotations.scene.SceneDrawable;
import ixdar.common.exceptions.TerminalParseException;
import ixdar.scenes.main.MainScene;

public class CanvasSceneMap {

    public static final Map<String, Supplier<? extends SceneDrawable>> MAP;

    static {

        MAP = SceneRegistry_Scenes.MAP;
        MAP.put("ixdar", () -> new Canvas3D());
        MAP.put("ixdar-canvas", () -> {
            try {
                MainScene.main = new MainScene("djbouti", new Canvas3D());
            } catch (TerminalParseException | IOException e) {
                throw new RuntimeException(e);
            }
            return MainScene.canvas;
        });
    }
}
