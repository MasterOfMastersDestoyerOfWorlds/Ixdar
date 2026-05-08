package ixdar.platform.automation;

import ixdar.platform.Platforms;
import ixdar.platform.automation.endpoints.AutomationRuntime;
import ixdar.platform.gl.Platform;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.MouseTrap;

public class AutomationInputBinder {

    private AutomationInputBinder() {
    }

    /**
     * Install platform input callbacks that tee each event into the
     * {@link AutomationRuntime} recorder before forwarding it to the normal
     * {@link KeyGuy}/{@link MouseTrap} handlers.
     *
     * @param platform platform whose key/char/mouse/scroll callbacks will be replaced
     * @param keys keyboard handler that should receive forwarded key and char events
     * @param mouse mouse handler that should receive forwarded button, move, and
     *              scroll events
     */
    public static void bind(Platform platform, KeyGuy keys, MouseTrap mouse) {
        platform.setKeyCallback((key, scancode, action, mods) -> {
            AutomationRuntime.get().recordRawKey(key, scancode, action, mods);
            keys.keyCallback(0L, key, scancode, action, mods);
        });
        platform.setCharCallback(codepoint -> {
            AutomationRuntime.get().recordRawChar(codepoint);
            keys.charCallback(0L, codepoint);
        });
        platform.setMouseButtonCallback((button, action, mods) -> {
            AutomationRuntime.get().recordRawMouseButton(button, action, mods, mouse.lastX, mouse.lastY);
            mouse.mouseButton(button, action, mods);
        });
        platform.setCursorPosCallback((window, x, y) -> {
            AutomationRuntime.get().recordRawMouseMove((float) x, (float) y);
            mouse.moveOrDrag(window, (float) x, (float) y);
        });
        platform.setScrollCallback((xoff, yoff) -> {
            AutomationRuntime.get().recordRawScroll(yoff);
            mouse.scrollCallback(yoff);
        });
    }
}
