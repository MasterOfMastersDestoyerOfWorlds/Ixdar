package ixdar.scenes.main;

import ixdar.graphics.cameras.Camera;
import ixdar.platform.input.KeyGuy;

/**
 * The tour editor's key handler: a first press fires the editor's control rows, the active tool's
 * and the tool switches, a plain key staying silent while Control is held.
 */
public class MainSceneKeyGuy extends KeyGuy {

    /**
     * Bind the tour editor's shortcuts to its 2D camera.
     *
     * @param editor the editor the keys act on, also the owning canvas
     * @param camera camera the controller drives
     */
    public MainSceneKeyGuy(MainScene editor, Camera camera) {
        super(camera, editor);
        controlSilencesPlainKeys = true;
    }

    /**
     * Fire the control row bound to a first press; a repeat fires nothing here, the held cycle
     * keys repeat on their own interval.
     *
     * @param key        pressed key code
     * @param mods       modifier-key bitmask
     * @param firstPress whether the key was up before this event, {@code false} on a repeat
     */
    @Override
    public void sceneKeyPressed(int key, int mods, boolean firstPress) {
        if (firstPress) {
            handleSceneKeys(key, mods);
        }
    }

    /**
     * The editor binds nothing on release: Escape and R act on the press, so the shared
     * menu-back and camera-reset releases stay off.
     */
    @Override
    public void sceneKeyReleased() {
    }
}
