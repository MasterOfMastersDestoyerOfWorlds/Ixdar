package shell.ui.scenes;

import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;

import shell.annotations.SceneRegistry_Scenes;
import shell.exceptions.TerminalParseException;
import shell.ui.Canvas3D;
import shell.ui.main.Main;

public class CanvasScene {

    public static final Map<String, Supplier<? extends Canvas3D>> MAP;

    static {

        MAP = SceneRegistry_Scenes.MAP;
        MAP.put("ixdar", () -> new Canvas3D());
        MAP.put("ixdar-canvas", () -> {
            try {
                Main.main = new Main("djbouti");
            } catch (TerminalParseException | IOException e) {
                throw new RuntimeException(e);
            }
            return new Canvas3D();
        });
    }
}
