package ixdar.platform.input;

import java.util.ArrayList;

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
     * Drive a single input frame: invoke {@code paintUpdate(speedMod)} on each non-null handler,
     * then drop the hyper strings the last frame registered, which this frame's draw registers
     * again. Mouse traps that override {@code paintUpdate} never cleared them.
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
        MouseTrap.hyperStrings = new ArrayList<>();
    }
}
