package ixdar.platform;

import java.util.Map;

import ixdar.canvas.Canvas3D;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.MouseTrap;

/**
 * The automation server and input recorder, supplied by the platform rather than referenced
 * directly.
 *
 * <p>The indirection is what keeps the HTTP server out of the browser build: only the desktop and
 * headless backends name {@code AutomationRuntime}, so the web launcher never reaches it.
 */
public interface AutomationBackend {

    /**
     * Bind to a live render canvas and start the HTTP server; later calls only refresh the canvas.
     *
     * @param canvas3D the active render canvas
     */
    void start(Canvas3D canvas3D);

    /**
     * Cancel any replay, stop the HTTP server and release the canvas; idempotent.
     */
    void stop();

    /**
     * Run the commands endpoints queued for the render thread, once per frame.
     */
    void processMainThreadCommands();

    /**
     * Record a high-level action in the recorder's abstract-action log.
     *
     * @param type    action type tag
     * @param payload action-specific fields; numbers and booleans stay typed, the rest become strings
     */
    void recordAbstractActionMap(String type, Map<String, Object> payload);

    /**
     * Install platform input callbacks that record each event before forwarding it to the handlers.
     *
     * @param platform platform whose input callbacks are replaced
     * @param keys     keyboard handler that receives forwarded key and char events
     * @param mouse    mouse handler that receives forwarded button, move and scroll events
     */
    void bindInput(Platform platform, KeyGuy keys, MouseTrap mouse);
}
