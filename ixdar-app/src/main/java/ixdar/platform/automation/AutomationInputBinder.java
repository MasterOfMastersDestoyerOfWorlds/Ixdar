package ixdar.platform.automation;

import ixdar.platform.Platforms;
import ixdar.platform.gl.Platform;
import ixdar.platform.input.InputHandler;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.MouseTrap;

/**
 * Binds automation input recording to input handlers.
 * 
 * Supports both the legacy KeyGuy/MouseTrap API and the new InputHandler abstraction.
 */
public class AutomationInputBinder {

    private AutomationInputBinder() {
    }

    /**
     * Bind automation to the legacy KeyGuy/MouseTrap pair.
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

    /**
     * Bind automation to the new InputHandler abstraction.
     */
    public static void bind(Platform platform, InputHandler handler) {
        platform.setKeyCallback((key, scancode, action, mods) -> {
            AutomationRuntime.get().recordRawKey(key, scancode, action, mods);
            handler.keyCallback(0L, key, scancode, action, mods);
        });
        platform.setCharCallback(codepoint -> {
            AutomationRuntime.get().recordRawChar(codepoint);
            handler.charCallback(0L, codepoint);
        });
        platform.setMouseButtonCallback((button, action, mods) -> {
            AutomationRuntime.get().recordRawMouseButton(button, action, mods, handler.lastX, handler.lastY);
            handler.mouseButton(button, action, mods);
        });
        platform.setCursorPosCallback((window, x, y) -> {
            AutomationRuntime.get().recordRawMouseMove((float) x, (float) y);
            handler.moveOrDrag(window, (float) x, (float) y);
        });
        platform.setScrollCallback((xoff, yoff) -> {
            AutomationRuntime.get().recordRawScroll(yoff);
            handler.scrollCallback(yoff);
        });
    }
}
