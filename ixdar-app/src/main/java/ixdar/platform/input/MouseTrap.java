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

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;

import java.util.ArrayList;
import java.util.HashMap;

public class MouseTrap {
    public static final float NUM_1 = 1f;
    public static final int NUM_4 = 4;
    public static final int NUM_60 = 60;
    public static final float NUM_100 = 100f;
    public static final int NUM_3 = 3;
    public static ArrayList<HyperString> hyperStrings = new ArrayList<>();

    private static Object automationRuntime;
    private static boolean automationChecked;

    private static final HashMap<Integer, List<ScrollSubscription>> scrollSubscriptionsByPlatform = new HashMap<>();

    private static final HashMap<Integer, List<ClickSubscription>> clickSubscriptionsByPlatform = new HashMap<>();

    public int queuedMouseWheelTicks = 0;
    public int lastX = Integer.MIN_VALUE;
    public int lastY = Integer.MIN_VALUE;
    public Camera camera;
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
    private Canvas3D canvas;

    /**
     * TODO: document {@code MouseTrap}.
     *
     * @param main TODO: describe
     * @param camera TODO: describe
     * @param canvas TODO: describe
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
                        String.join(".", "ixdar", "platform", "automation", "AutomationRuntime"));
                automationRuntime = cls.getMethod("get").invoke(null);
            } catch (Throwable ignored) {}
        }
        return automationRuntime;
    }

    static void recordAbstractAction(String action, Object... keyValues) {
        Object rt = getAutomationRuntime();
        if (rt == null) return;
        try {
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            for (int i = 0; i < keyValues.length; i += 2) {
                payload.put((String) keyValues[i], keyValues[i + 1]);
            }
            rt.getClass().getMethod("recordAbstractActionMap", String.class, java.util.Map.class).invoke(rt, action, payload);
        } catch (Throwable ignored) {}
    }

    private static List<ScrollSubscription> getSubscriptionsForCurrentPlatform() {
        int id = Platforms.gl().getPlatformID();
        return scrollSubscriptionsByPlatform.computeIfAbsent(id, k -> new ArrayList<>());
    }

    /**
     * TODO: document {@code subscribeScrollRegion}.
     *
     * @param bounds TODO: describe
     * @param handler TODO: describe
     */
    public static void subscribeScrollRegion(Bounds bounds, ScrollHandler handler) {
        getSubscriptionsForCurrentPlatform().add(new ScrollSubscription(bounds, handler));
    }

    /**
     * TODO: document {@code unsubscribeScrollRegion}.
     *
     * @param handler TODO: describe
     */
    public static void unsubscribeScrollRegion(ScrollHandler handler) {
        List<ScrollSubscription> list = getSubscriptionsForCurrentPlatform();
        list.removeIf(s -> s.handler == handler);
    }

    private static List<ClickSubscription> getClickSubscriptionsForCurrentPlatform() {
        int id = Platforms.gl().getPlatformID();
        return clickSubscriptionsByPlatform.computeIfAbsent(id, k -> new ArrayList<>());
    }

    /**
     * TODO: document {@code subscribeClickRegion}.
     *
     * @param bounds TODO: describe
     * @param handler TODO: describe
     */
    public static void subscribeClickRegion(Bounds bounds, ClickHandler handler) {
        getClickSubscriptionsForCurrentPlatform().add(new ClickSubscription(bounds, handler));
    }

    /**
     * TODO: document {@code unsubscribeClickRegion}.
     *
     * @param handler TODO: describe
     */
    public static void unsubscribeClickRegion(ClickHandler handler) {
        List<ClickSubscription> list = getClickSubscriptionsForCurrentPlatform();
        list.removeIf(s -> s.handler == handler);
    }

    /**
     * TODO: document {@code mouseClicked}.
     *
     * @param xPos TODO: describe
     * @param yPos TODO: describe
     * @param button TODO: describe
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
                "xNorm", xPos / Math.max(NUM_1, Platforms.get().getWindowWidth()),
                "yNorm", yPos / Math.max(NUM_1, Platforms.get().getWindowHeight()));
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
     * TODO: document {@code mousePressed}.
     *
     * @param x TODO: describe
     * @param y TODO: describe
     */
    public void mousePressed(float x, float y) {
        normalizedPosX = camera.getNormalizePosX(x);
        normalizedPosY = camera.getNormalizePosY(y);
        startX = normalizedPosX;
        startY = normalizedPosY;
    }

    /**
     * TODO: document {@code mouseDragged}.
     *
     * @param x TODO: describe
     * @param y TODO: describe
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
     * TODO: document {@code mouseReleased}.
     */
    public void mouseReleased() {
    }

    /**
     * TODO: document {@code mouseEntered}.
     */
    public void mouseEntered() {
    }

    /**
     * TODO: document {@code mouseExited}.
     */
    public void mouseExited() {
        if (main != null) {
            MainScene.tool.clearHover();
        }
    }

    /**
     * TODO: document {@code scrollCallback}.
     *
     * @param y TODO: describe
     */
    public void scrollCallback(double y) {
        Platforms.init(canvas.platform.getPlatformID());
        queuedMouseWheelTicks += (int) (NUM_4 * y);
        timeLastScroll = System.currentTimeMillis();
        recordAbstractAction("mouse_scroll", "delta", y);
    }

    /**
     * TODO: document {@code setCanvas}.
     *
     * @param canvas3d TODO: describe
     */
    public void setCanvas(Canvas3D canvas3d) {
        this.canvas = canvas3d;
    }

    /**
     * TODO: document {@code mousePos}.
     *
     * @param x TODO: describe
     * @param y TODO: describe
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
     * TODO: document {@code paintUpdate}.
     *
     * @param SHIFT_MOD TODO: describe
     */
    public void paintUpdate(float SHIFT_MOD) {
        if (System.currentTimeMillis() - timeLastScroll > NUM_60) {
            queuedMouseWheelTicks = 0;
        }
        PaneTypes view = MainScene.inView(lastX, lastY);

        if (queuedMouseWheelTicks != 0) {
            List<ScrollSubscription> subs = getSubscriptionsForCurrentPlatform();
            for (ScrollSubscription sub : subs) {

                if (sub.bounds != null) {
                    sub.bounds.recalc();
                }
                if (sub.bounds != null) {
                    float windowHeight = (float) Platforms.get().getWindowHeight();
                    float yFromBottom = windowHeight - lastY;
                    boolean inside = lastX >= sub.bounds.offsetX && lastX <= sub.bounds.offsetX + sub.bounds.viewWidth
                            && yFromBottom >= sub.bounds.offsetY
                            && yFromBottom <= sub.bounds.offsetY + sub.bounds.viewHeight;
                    if (inside) {
                        boolean up = queuedMouseWheelTicks < 0;
                        sub.handler.onScroll(up, Clock.deltaTime() * NUM_100);
                        return;
                    }
                }
            }
        }
        if (queuedMouseWheelTicks < 0) {
            if (view != PaneTypes.None) {
                updateHyperStrings();
            }
            queuedMouseWheelTicks = 0;
        }
        if (queuedMouseWheelTicks > 0) {
            if (view != PaneTypes.None) {
                updateHyperStrings();
            }
            queuedMouseWheelTicks = 0;
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
     * TODO: document {@code mouseButton}.
     *
     * @param button TODO: describe
     * @param action TODO: describe
     * @param mods TODO: describe
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
                if (mouseReleasePos.distance(leftMouseDownPos) < NUM_3) {
                    mouseClicked(x, y, button);
                } else {
                    mouseReleased();
                }
            }
        }
    }

    /**
     * TODO: document {@code moveOrDrag}.
     *
     * @param window TODO: describe
     * @param x TODO: describe
     * @param y TODO: describe
     */
    public void moveOrDrag(long window, float x, float y) {
        Platforms.init(canvas.platform.getPlatformID());
        boolean leftDown = Platforms.gl().getMouseButton(window, MouseButtons.MOUSE_BUTTON_LEFT);
        Vector2f mouseReleasePos = new Vector2f((float) x, (float) y);
        if (leftDown && leftMouseDownPos != null && mouseReleasePos.distance(leftMouseDownPos) > NUM_3) {
            mouseDragged(x, y);
        } else {
            mousePos(x, y);
        }
    }

    public interface ScrollHandler {
        /**
         * TODO: document {@code onScroll}.
         *
         * @param scrollUp TODO: describe
         * @param deltaSeconds TODO: describe
         */
        void onScroll(boolean scrollUp, double deltaSeconds);
    }

    @FunctionalInterface
    public interface ClickHandler {
        /**
         * TODO: document {@code onClick}.
         *
         * @param button TODO: describe
         */
        void onClick(int button);
    }

    public static class ScrollSubscription {
        public Bounds bounds;
        public ScrollHandler handler;

        /**
         * TODO: document {@code ScrollSubscription}.
         *
         * @param bounds TODO: describe
         * @param handler TODO: describe
         */
        public ScrollSubscription(Bounds bounds, ScrollHandler handler) {
            this.bounds = bounds;
            this.handler = handler;
        }
    }

    public static class ClickSubscription {
        public Bounds bounds;
        public ClickHandler handler;

        /**
         * TODO: document {@code ClickSubscription}.
         *
         * @param bounds TODO: describe
         * @param handler TODO: describe
         */
        public ClickSubscription(Bounds bounds, ClickHandler handler) {
            this.bounds = bounds;
            this.handler = handler;
        }
    }

}
