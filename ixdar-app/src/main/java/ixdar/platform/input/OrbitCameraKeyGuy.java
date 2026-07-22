package ixdar.platform.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Camera;

/**
 * Shared key handler for orbit/mesh scenes, binding {@code Ctrl+R} to
 * {@link OrbitMouseTrap#resetTarget()}.
 *
 * <p>Subclasses add their own keys by overriding {@link #handleSceneKeys(int, int)} rather than
 * the base dispatch, which would drop the shared bindings.
 */
public class OrbitCameraKeyGuy extends KeyGuy {

    /** GLFW control modifier bit. */
    public static final int MOD_CONTROL = 0x0002;

    protected final OrbitMouseTrap orbitMouse;

    /**
     * Wire the key handler to the orbit controller it recentres.
     *
     * @param orbitMouse orbit controller whose centre {@code Ctrl+R} resets
     * @param camera     camera driven by this handler
     * @param canvas     owning canvas
     */
    public OrbitCameraKeyGuy(OrbitMouseTrap orbitMouse, Camera camera, Canvas3D canvas) {
        super(camera, canvas);
        this.orbitMouse = orbitMouse;
    }

    /**
     * Handle {@code Ctrl+R} as an orbit-centre reset; otherwise dispatch to
     * {@link #handleSceneKeys(int, int)}. {@code Ctrl+R} never falls through to a
     * scene's plain-{@code R} binding.
     *
     * @param window   platform window handle
     * @param key      key code
     * @param scancode platform scancode
     * @param action   {@code ACTION_PRESS}, repeat, or release
     * @param mods     modifier-key bitmask
     */
    @Override
    public void keyCallback(long window, int key, int scancode, int action, int mods) {
        if (active && action == ACTION_PRESS) {
            if (key == Keys.R && (mods & MOD_CONTROL) != 0) {
                orbitMouse.resetTarget();
            } else {
                handleSceneKeys(key, mods);
            }
        }
        super.keyCallback(window, key, scancode, action, mods);
    }

    /**
     * Scene-specific key handling, invoked on key-press for non-{@code Ctrl+R} keys.
     * The default is a no-op; scenes override to add their own toggles.
     *
     * @param key  pressed key code
     * @param mods modifier-key bitmask
     */
    protected void handleSceneKeys(int key, int mods) {
    }
}
