package ixdar.platform.input;

public class SceneInputFrameUpdater {
    /**
     * TODO: document {@code resolveSpeedMod}.
     *
     * @param keys TODO: describe
     * @return TODO: describe
     */
    public static float resolveSpeedMod(KeyGuy keys) {
        if (keys != null && KeyActions.DoubleSpeed.keyPressed(keys.pressedKeys)) {
            return 2f;
        }
        return 1f;
    }

    /**
     * TODO: document {@code update}.
     *
     * @param keys TODO: describe
     * @param mouse TODO: describe
     */
    public static void update(KeyGuy keys, MouseTrap mouse) {
        float speedMod = resolveSpeedMod(keys);
        if (keys != null) {
            keys.paintUpdate(speedMod);
        }
        if (mouse != null) {
            mouse.paintUpdate(speedMod);
        }
    }
}
