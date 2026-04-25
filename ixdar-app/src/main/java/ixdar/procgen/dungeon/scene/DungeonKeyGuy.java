package ixdar.procgen.dungeon.scene;

import java.util.function.BooleanSupplier;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Camera;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.Keys;

/**
 * Dungeon-viewer key handler. Adds two behaviors on top of base {@link KeyGuy}:
 *
 * <ul>
 *   <li>Edge-detected <kbd>F</kbd> press toggles between fly-cam and player-walk modes via the
 *       supplied callback (only fires on the leading edge — holding F doesn't repeat).</li>
 *   <li>When in player mode, the parent's WASD-to-camera-move loop is suppressed so the
 *       {@code PlayerController} owns horizontal motion. Otherwise behaves like base
 *       {@link KeyGuy} (fly-cam fallback).</li>
 * </ul>
 */
public class DungeonKeyGuy extends KeyGuy {

    private final BooleanSupplier inPlayerMode;
    private final Runnable toggleMode;
    private boolean lastFState = false;
    private boolean lastEscState = false;

    public DungeonKeyGuy(Camera camera, Canvas3D canvas,
                         BooleanSupplier inPlayerMode, Runnable toggleMode) {
        super(camera, canvas);
        this.inPlayerMode = inPlayerMode;
        this.toggleMode = toggleMode;
    }

    @Override
    public void paintUpdate(float shiftMod) {
        // Edge-detect F so a hold doesn't oscillate the mode.
        boolean fNow = pressedKeys.contains(Keys.F);
        if (fNow && !lastFState) {
            toggleMode.run();
        }
        lastFState = fNow;

        // Edge-detect Escape: in player mode, releases cursor capture by toggling to fly-cam.
        // In fly-cam mode it's a no-op (leaves room for other Escape handlers).
        boolean escNow = pressedKeys.contains(Keys.ESCAPE);
        if (escNow && !lastEscState && inPlayerMode.getAsBoolean()) {
            toggleMode.run();
        }
        lastEscState = escNow;

        // In player mode, the PlayerController owns motion — don't let base KeyGuy fly the camera.
        if (inPlayerMode.getAsBoolean()) {
            return;
        }
        super.paintUpdate(shiftMod);
    }
}
