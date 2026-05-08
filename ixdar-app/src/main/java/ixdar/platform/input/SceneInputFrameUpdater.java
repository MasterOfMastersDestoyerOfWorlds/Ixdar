package ixdar.platform.input;

/**
 * Per-frame input pumping helper: resolves the shift-speed modifier and forwards
 * {@code paintUpdate} ticks to the supplied {@link KeyGuy} / {@link MouseTrap} pair.
 */
public class SceneInputFrameUpdater {
    /**
     * Compute the camera-speed multiplier for this frame.
     *
     * @param keys current keyboard handler (may be null)
     * @return {@code 2f} when {@link KeyActions#DoubleSpeed} is held, otherwise {@code 1f}
     */
    public static float resolveSpeedMod(KeyGuy keys) {
        if (keys != null && KeyActions.DoubleSpeed.keyPressed(keys.pressedKeys)) {
            return 2f;
        }
        return 1f;
    }

    /**
     * Drive a single input frame: resolve the speed modifier and invoke
     * {@code paintUpdate(speedMod)} on each handler if non-null.
     *
     * @param keys keyboard handler (may be null)
     * @param mouse mouse handler (may be null)
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
