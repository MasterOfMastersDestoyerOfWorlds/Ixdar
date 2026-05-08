package ixdar.procgen.dungeon.scene;

import java.util.function.BooleanSupplier;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Camera;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.Keys;

/**
 * Dungeon-viewer key handler. Adds three behaviors on top of base {@link KeyGuy}:
 *
 * <ul>
 *   <li>Edge-detected <kbd>F</kbd> press toggles between fly-cam and player-walk modes via the
 *       supplied callback (only fires on the leading edge — holding F doesn't repeat).</li>
 *   <li>Edge-detected <kbd>V</kbd> press swaps between first- and third-person view inside
 *       player mode (no-op in fly-cam).</li>
 *   <li>When in player mode, the parent's WASD-to-camera-move loop is suppressed so the
 *       {@code PlayerController} owns horizontal motion. Otherwise behaves like base
 *       {@link KeyGuy} (fly-cam fallback).</li>
 * </ul>
 */
public class DungeonKeyGuy extends KeyGuy {

    private final BooleanSupplier inPlayerMode;
    private final Runnable togglePlayerMode;
    private final Runnable toggleViewMode;
    private boolean lastFState = false;
    private boolean lastVState = false;
    private boolean lastEscState = false;

    /**
     * Wires the key handler to the scene's player-mode state and toggle callbacks.
     *
     * @param camera           camera passed through to base {@link KeyGuy} for fly-cam fallback
     * @param canvas           canvas passed through to base {@link KeyGuy}
     * @param inPlayerMode     supplier that returns {@code true} when the scene is in player mode
     * @param togglePlayerMode action to flip between player and fly-cam modes
     * @param toggleViewMode   action to swap first / third person within player mode
     */
    public DungeonKeyGuy(Camera camera, Canvas3D canvas,
                         BooleanSupplier inPlayerMode,
                         Runnable togglePlayerMode,
                         Runnable toggleViewMode) {
        super(camera, canvas);
        this.inPlayerMode = inPlayerMode;
        this.togglePlayerMode = togglePlayerMode;
        this.toggleViewMode = toggleViewMode;
    }

    /**
     * Per-frame key processing. Edge-detects F / V / Esc and suppresses the base WASD-camera
     * loop while in player mode.
     *
     * @param shiftMod movement-speed multiplier (forwarded to the base implementation in fly-cam)
     */
    @Override
    public void paintUpdate(float shiftMod) {
        boolean fNow = pressedKeys.contains(Keys.F);
        if (fNow && !lastFState) {
            togglePlayerMode.run();
        }
        lastFState = fNow;

        boolean vNow = pressedKeys.contains(Keys.V);
        if (vNow && !lastVState && inPlayerMode.getAsBoolean()) {
            toggleViewMode.run();
        }
        lastVState = vNow;

        boolean escNow = pressedKeys.contains(Keys.ESCAPE);
        if (escNow && !lastEscState && inPlayerMode.getAsBoolean()) {
            togglePlayerMode.run();
        }
        lastEscState = escNow;

        if (inPlayerMode.getAsBoolean()) {
            return;
        }
        super.paintUpdate(shiftMod);
    }
}
