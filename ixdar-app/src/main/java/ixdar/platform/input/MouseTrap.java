package ixdar.platform.input;

import org.joml.Vector2f;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.Clock;
import ixdar.graphics.render.text.HyperString;
import ixdar.platform.Platforms;
import ixdar.platform.Toggle;
import ixdar.scenes.main.MainScene;
import ixdar.scenes.main.PaneTypes;

import java.util.List;

import java.util.Map;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;

import java.util.ArrayList;
import java.util.HashMap;

public class MouseTrap {
    public static final float MIN_WINDOW_DIMENSION = 1f;
    public static final int SCROLL_TICKS_PER_UNIT = 4;
    public static final int SCROLL_DECAY_MS = 60;
    public static final float SCROLL_SPEED_SCALE = 100f;
    public static final int CLICK_DRAG_THRESHOLD_PX = 3;
    public static ArrayList<HyperString> hyperStrings = new ArrayList<>();

    private static Object automationRuntime;
    private static boolean automationChecked;

    private static final HashMap<Integer, List<ClickSubscription>> CLICK_SUBSCRIPTIONS_BY_PLATFORM = new HashMap<>();

    /**
     * Raw platform scroll deltas summed since the last frame drained them, never rounded: a
     * trackpad's many small deltas add up exactly as a wheel's whole notches do.
     */
    public double queuedScrollDelta = 0;
    public int lastX = Integer.MIN_VALUE;
    public int lastY = Integer.MIN_VALUE;
    public Camera camera;
    public Canvas3D canvas;
    public boolean active = true;
    public float normalizedPosX;
    public float normalizedPosY;
    MainScene main;
    long timeLastScroll;
    int width;
    int height;
    double startX;
    double startY;

    float leftMouseDown = -1;
    Vector2f leftMouseDownPos;

    /**
     * Build a mouse controller bound to a scene, camera, and canvas.
     *
     * @param main owning {@link MainScene}, or {@code null} for non-main scenes
     * @param camera camera to drive (pan, hover, zoom)
     * @param canvas owning canvas (provides platform ID)
     */
    public MouseTrap(MainScene main, Camera camera, Canvas3D canvas) {
        this.main = main;
        this.camera = camera;
        this.canvas = canvas;
    }

    private static Object getAutomationRuntime() {
        if (!automationChecked) {
            automationChecked = true;
            try {
                Class<?> cls = Class.forName(
                        String.join(".", "ixdar", "platform", "automation", "endpoints", "AutomationRuntime"));
                automationRuntime = cls.getMethod("get").invoke(null);
            } catch (Throwable ignored) {}
        }
        return automationRuntime;
    }

    static void recordAbstractAction(String action, Object... keyValues) {
        Object rt = getAutomationRuntime();
        if (rt == null) return;
        try {
            Map<String, Object> payload = new HashMap<>();
            for (int i = 0; i < keyValues.length; i += 2) {
                payload.put((String) keyValues[i], keyValues[i + 1]);
            }
            rt.getClass().getMethod("recordAbstractActionMap", String.class, Map.class).invoke(rt, action, payload);
        } catch (Throwable ignored) {}
    }

    private static List<ClickSubscription> getClickSubscriptionsForCurrentPlatform() {
        int id = Platforms.gl().getPlatformID();
        return CLICK_SUBSCRIPTIONS_BY_PLATFORM.computeIfAbsent(id, k -> new ArrayList<>());
    }

    /**
     * Register a region that captures clicks; the first matching region inside {@code bounds}
     * wins and short-circuits further click handling for that event.
     *
     * @param bounds screen region
     * @param handler callback invoked when a click lands inside {@code bounds}
     */
    public static void subscribeClickRegion(Bounds bounds, ClickHandler handler) {
        getClickSubscriptionsForCurrentPlatform().add(new ClickSubscription(bounds, handler));
    }

    /**
     * Remove every click subscription whose handler is {@code handler} on the current platform.
     *
     * @param handler handler to unregister
     */
    public static void unsubscribeClickRegion(ClickHandler handler) {
        List<ClickSubscription> list = getClickSubscriptionsForCurrentPlatform();
        list.removeIf(s -> s.handler == handler);
    }

    /**
     * Fired on a press/release pair that didn't move far enough to count as a drag. Routes to
     * the focused pane's tool/terminal, calls hover handlers on every {@code HyperString}, and
     * dispatches to any subscribed click region (which short-circuits if matched).
     *
     * @param xPos cursor x in window pixels
     * @param yPos cursor y in window pixels
     * @param button button index
     */
    public void mouseClicked(float xPos, float yPos, int button) {
        if (!active) {
            return;
        }
        normalizedPosX = camera.getNormalizePosX(xPos);
        normalizedPosY = camera.getNormalizePosY(yPos);

        PaneTypes inMainView = MainScene.inView(xPos, yPos);
        recordAbstractAction("mouse_click",
                "button", button,
                "pane", inMainView.name(),
                "xPx", xPos,
                "yPx", yPos,
                "xCoord", normalizedPosX,
                "yCoord", normalizedPosY,
                "xNorm", xPos / Math.max(MIN_WINDOW_DIMENSION, Platforms.get().getWindowWidth()),
                "yNorm", yPos / Math.max(MIN_WINDOW_DIMENSION, Platforms.get().getWindowHeight()));
        Toggle.setPanelFocus(inMainView);
        if (MainScene.manifoldKnot != null && MainScene.active) {
            if (inMainView == PaneTypes.KnotView) {
                MainScene.tool.calculateClick(normalizedPosX, normalizedPosY);
            } else if (inMainView == PaneTypes.Terminal) {
                MainScene.terminal.calculateClick(normalizedPosX, normalizedPosY);
            }
        }

        for (HyperString h : hyperStrings) {
            h.click(normalizedPosX, normalizedPosY);
        }
        if (canvas.menu != null) {

            canvas.menu.click(normalizedPosX, normalizedPosY);
        }

        // Region click subscriptions
        List<ClickSubscription> clickSubs = getClickSubscriptionsForCurrentPlatform();
        if (!clickSubs.isEmpty()) {
            for (ClickSubscription sub : clickSubs) {
                if (sub.bounds != null) {
                    sub.bounds.recalc();
                }
                if (sub.bounds != null) {
                    float windowHeight = (float) Platforms.get().getWindowHeight();
                    float yFromBottom = windowHeight - yPos;
                    boolean inside = xPos >= sub.bounds.offsetX && xPos <= sub.bounds.offsetX + sub.bounds.viewWidth
                            && yFromBottom >= sub.bounds.offsetY
                            && yFromBottom <= sub.bounds.offsetY + sub.bounds.viewHeight;
                    if (inside) {
                        sub.handler.onClick(button);
                        return;
                    }
                }
            }
        }
    }

    /**
     * Cache the normalized press position as the drag start point.
     *
     * @param x cursor x in window pixels
     * @param y cursor y in window pixels
     */
    public void mousePressed(float x, float y) {
        normalizedPosX = camera.getNormalizePosX(x);
        normalizedPosY = camera.getNormalizePosY(y);
        startX = normalizedPosX;
        startY = normalizedPosY;
    }

    /**
     * Pan the camera by the normalized-coordinate delta from the previous drag sample, but only
     * when the drag started inside the {@code KnotView} pane.
     *
     * @param x cursor x in window pixels
     * @param y cursor y in window pixels
     */
    public void mouseDragged(float x, float y) {

        normalizedPosX = camera.getNormalizePosX(x);
        normalizedPosY = camera.getNormalizePosY(y);

        PaneTypes inMainView = MainScene.inView((float) leftMouseDownPos.x, (float) leftMouseDownPos.y);
        if (inMainView == PaneTypes.KnotView) {
            camera.drag((float) (normalizedPosX - startX), (float) (normalizedPosY - startY));
            startX = normalizedPosX;
            startY = normalizedPosY;
        }
    }

    /**
     * Hook for subclasses to react to a release that did not produce a click; default is a
     * no-op.
     */
    public void mouseReleased() {
    }

    /**
     * Hook for cursor-enter events; default is a no-op.
     */
    public void mouseEntered() {
    }

    /**
     * Cursor-exit hook: clears any active tool hover so stale highlights don't linger.
     */
    public void mouseExited() {
        if (main != null) {
            MainScene.tool.clearHover();
        }
    }

    /**
     * Add the platform's scroll delta, untruncated, to {@link #queuedScrollDelta} and stamp the
     * time so {@link #paintUpdate} can decay it after {@link #SCROLL_DECAY_MS} ms of inactivity.
     *
     * @param y vertical scroll delta, 1.0 per wheel notch, fractions from a trackpad
     */
    public void scrollCallback(double y) {
        if (canvas != null) {
            Platforms.init(canvas.platform.getPlatformID());
        }
        queuedScrollDelta += y;
        timeLastScroll = System.currentTimeMillis();
        recordAbstractAction("mouse_scroll", "delta", y);
    }

    /**
     * Whether the trap puts the wheel to a use of its own, such as a zoom; the base trap only
     * refreshes hover with it.
     *
     * @return false for the base trap
     */
    public boolean usesWheel() {
        return false;
    }

    /**
     * Re-bind this trap to a different canvas (used during scene switches).
     *
     * @param canvas3d new owning canvas
     */
    public void setCanvas(Canvas3D canvas3d) {
        this.canvas = canvas3d;
    }

    /**
     * Move-without-drag entry point: updates normalized position and last pixel coordinates,
     * notifies the camera, runs menu hover, runs the active tool's hover (only inside KnotView),
     * and re-runs HyperString hover.
     *
     * @param x cursor x in window pixels
     * @param y cursor y in window pixels
     */
    public void mousePos(float x, float y) {
        if (!active) {
            return;
        }
        normalizedPosX = camera.getNormalizePosX(x);
        normalizedPosY = camera.getNormalizePosY(y);
        lastX = (int) x;
        lastY = (int) y;

        camera.mouseMove(lastX, lastY, x, y);
        if (canvas.menu != null && !(this.canvas == null)) {

            canvas.menu.setHover(normalizedPosX, normalizedPosY);
        }

        PaneTypes inMainView = MainScene.inView(x, y);
        if (main != null && MainScene.active) {
            if (inMainView == PaneTypes.KnotView) {
                MainScene.tool.calculateHover(normalizedPosX, normalizedPosY);
            } else {
                MainScene.tool.clearHover();
            }
        }
        updateHyperStrings();

        hyperStrings = new ArrayList<>();
    }

    /**
     * Per-frame: drain the queued scroll delta, refreshing HyperString hover when the cursor is
     * over a pane; a delta decays after {@link #SCROLL_DECAY_MS} ms.
     *
     * @param SHIFT_MOD speed multiplier (currently unused; kept for API parity)
     */
    public void paintUpdate(float SHIFT_MOD) {
        if (System.currentTimeMillis() - timeLastScroll > SCROLL_DECAY_MS) {
            queuedScrollDelta = 0;
        }
        PaneTypes view = MainScene.inView(lastX, lastY);

        if (queuedScrollDelta != 0) {
            if (view != PaneTypes.None) {
                updateHyperStrings();
            }
            queuedScrollDelta = 0;
        }
        MouseTrap.hyperStrings = new ArrayList<>();
    }

    private void updateHyperStrings() {
        for (HyperString h : hyperStrings) {
            h.calculateClearHover(normalizedPosX, normalizedPosY);
        }
        for (HyperString h : hyperStrings) {
            h.calculateHover(normalizedPosX, normalizedPosY);
        }
    }

    /**
     * Platform mouse-button entry point. On press, records the press position and time; on
     * release, dispatches to {@link #mouseClicked} when the cursor stayed within
     * {@link #CLICK_DRAG_THRESHOLD_PX} pixels, or to {@link #mouseReleased} otherwise.
     *
     * @param button button index
     * @param action {@code ACTION_PRESS} or {@code ACTION_RELEASE}
     * @param mods modifier-key bitmask
     */
    public void mouseButton(int button, int action, int mods) {
        Platforms.init(canvas.platform.getPlatformID());
        float x = lastX;
        float y = lastY;
        if (action == ACTION_PRESS) {
            leftMouseDown = Clock.time();
            leftMouseDownPos = new Vector2f(x, y);
            mousePressed(x, y);
        } else if (action == ACTION_RELEASE) {
            if (leftMouseDownPos != null) {
                Vector2f mouseReleasePos = new Vector2f(x, y);
                if (mouseReleasePos.distance(leftMouseDownPos) < CLICK_DRAG_THRESHOLD_PX) {
                    mouseClicked(x, y, button);
                } else {
                    mouseReleased();
                }
            }
        }
    }

    /**
     * Mouse-motion entry point: routes to {@link #mouseDragged} while the left button is held
     * and the cursor has moved past the {@link #CLICK_DRAG_THRESHOLD_PX}-pixel deadzone, otherwise to
     * {@link #mousePos}.
     *
     * @param window platform window handle (for GL mouse-state polling)
     * @param x cursor x in window pixels
     * @param y cursor y in window pixels
     */
    public void moveOrDrag(long window, float x, float y) {
        Platforms.init(canvas.platform.getPlatformID());
        boolean leftDown = Platforms.gl().getMouseButton(window, MouseButtons.MOUSE_BUTTON_LEFT);
        Vector2f mouseReleasePos = new Vector2f((float) x, (float) y);
        if (leftDown && leftMouseDownPos != null && mouseReleasePos.distance(leftMouseDownPos) > CLICK_DRAG_THRESHOLD_PX) {
            mouseDragged(x, y);
        } else {
            mousePos(x, y);
        }
    }

    @FunctionalInterface
    public interface ClickHandler {
        /**
         * Called when a click lands inside the registered region.
         *
         * @param button button index
         */
        void onClick(int button);
    }

    public static class ClickSubscription {
        public Bounds bounds;
        public ClickHandler handler;

        /**
         * Build a click subscription for a screen region.
         *
         * @param bounds screen region (recalculated each event)
         * @param handler callback invoked when a click lands inside {@code bounds}
         */
        public ClickSubscription(Bounds bounds, ClickHandler handler) {
            this.bounds = bounds;
            this.handler = handler;
        }
    }

}
