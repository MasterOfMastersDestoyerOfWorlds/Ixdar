package ixdar.procgen.dungeon.scene;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;
import static ixdar.platform.input.Keys.MOUSE_BUTTON_LEFT;

import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Camera;
import ixdar.platform.Platforms;
import ixdar.platform.input.MouseTrap;

/**
 * Mouse-look trap with two operating modes (selected by {@code captureSupplier}):
 *
 * <ul>
 *   <li><b>Drag-to-rotate</b> ({@code false}): hold left mouse and move to rotate. Used in fly-cam.</li>
 *   <li><b>Capture</b> ({@code true}): every cursor delta rotates without a button press. Used in
 *       player mode together with {@link ixdar.platform.gl.Platform.CursorMode#CAPTURED}.</li>
 * </ul>
 *
 * <p>Where the deltas go is decided by the caller via {@code onDelta} — first-person sends them
 * to the camera's mouse-look math; third-person sends them to a {@link
 * ixdar.procgen.dungeon.camera.ThirdPersonCamera}'s azimuth / elevation. Scroll ticks are routed
 * through {@code onScroll} (only meaningful in third-person zoom).
 */
public class FlyCamMouseTrap extends MouseTrap {

    @FunctionalInterface
    public interface DeltaHandler {
        void apply(float lastX, float lastY, float x, float y);
    }

    private final DeltaHandler onDelta;
    private final IntConsumer onScroll;
    private final BooleanSupplier captureSupplier;
    private boolean leftDown = false;

    public FlyCamMouseTrap(Camera camera, Canvas3D canvas,
                           BooleanSupplier captureSupplier,
                           DeltaHandler onDelta,
                           IntConsumer onScroll) {
        super(null, camera, canvas);
        this.captureSupplier = captureSupplier;
        this.onDelta = onDelta;
        this.onScroll = onScroll;
    }

    @Override
    public void mouseButton(int button, int action, int mods) {
        Platforms.init(Platforms.get().getPlatformID());
        if (!active) return;
        if (button == MOUSE_BUTTON_LEFT) {
            if (action == ACTION_PRESS) {
                leftDown = true;
            } else if (action == ACTION_RELEASE) {
                leftDown = false;
            }
        }
    }

    @Override
    public void mousePos(float x, float y) {
        if (!active) return;
        if (captureSupplier.getAsBoolean()) {
            if (lastX != Integer.MIN_VALUE && lastY != Integer.MIN_VALUE) {
                onDelta.apply(lastX, lastY, x, y);
            }
        }
        lastX = (int) x;
        lastY = (int) y;
    }

    @Override
    public void mouseDragged(float x, float y) {
        if (!active || !leftDown) return;
        if (lastX == Integer.MIN_VALUE || lastY == Integer.MIN_VALUE) {
            mousePos(x, y);
            return;
        }
        onDelta.apply(lastX, lastY, x, y);
        lastX = (int) x;
        lastY = (int) y;
    }

    @Override
    public void moveOrDrag(long window, float x, float y) {
        if (captureSupplier.getAsBoolean() || leftDown) {
            if (captureSupplier.getAsBoolean()) {
                mousePos(x, y);
            } else {
                mouseDragged(x, y);
            }
        } else {
            mousePos(x, y);
        }
    }

    @Override
    public void paintUpdate(float shiftMod) {
        if (queuedMouseWheelTicks != 0 && onScroll != null) {
            int ticks = queuedMouseWheelTicks;
            queuedMouseWheelTicks = 0;
            onScroll.accept(ticks);
        } else {
            queuedMouseWheelTicks = 0;
        }
    }
}
