package ixdar.platform.input;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import org.joml.Vector2f;
import org.joml.Vector3f;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.Clock;
import ixdar.gui.render.text.HyperString;
import ixdar.platform.Platforms;

/**
 * Unified input handler abstraction that replaces the five separate classes
 * (TradeKeyGuy, TradeMouseTrap, KeyGuy, MouseTrap, Scene2DMousePanTrap).
 * 
 * Scenes configure input behavior declaratively by composing handler components:
 * - Camera panning (2D or 3D orbit)
 * - Key bindings (scene-specific or standard)
 * - Scroll/click region subscriptions
 * - Tool interaction hooks
 * 
 * This design reduces surface area for input bugs and makes new scene creation
 * simpler through configuration rather than inheritance.
 */
public class InputHandler {

    // === Core state ===
    public boolean active = true;
    public int lastX = Integer.MIN_VALUE;
    public int lastY = Integer.MIN_VALUE;
    public float normalizedPosX;
    public float normalizedPosY;
    public int queuedMouseWheelTicks = 0;
    public long timeLastScroll = 0L;

    protected Camera camera;
    protected Canvas3D canvas;

    // === Scroll/click subscriptions (static, shared across all handlers) ===
    private static final List<ScrollSubscription> scrollSubscriptions = new ArrayList<>();
    private static final List<ClickSubscription> clickSubscriptions = new ArrayList<>();

    // === Composable components ===
    private final KeyHandler keyHandler;
    private final MouseHandler mouseHandler;
    private final List<HyperString> hyperStrings = new ArrayList<>();

    // === 2D camera drag state ===
    protected float startX;
    protected float startY;
    protected Vector2f leftMouseDownPos;

    // === 3D orbit state (for OrbitMouseTrap replacement) ===
    private final OrbitConfig orbitConfig;

    // === Scene-specific hooks ===
    private final SceneContext sceneContext;

    /**
     * Context passed to scene-specific handlers, giving them access to scene state
     * without tight coupling.
     */
    public static class SceneContext {
        private final String sceneId;
        private final Map<String, Object> data;

        public SceneContext(String sceneId) {
            this(sceneId, Map.of());
        }

        public SceneContext(String sceneId, Map<String, Object> data) {
            this.sceneId = sceneId;
            this.data = data;
        }

        public String sceneId() { return sceneId; }
        public <T> T getData(String key) { return (T) data.get(key); }
        public <T> T getData(String key, T defaultValue) { return data.getOrDefault(key, defaultValue); }
    }

    /**
     * Configuration for 3D orbit camera behavior.
     */
    public static class OrbitConfig {
        private final Vector3f target = new Vector3f();
        private float azimuth = (float) Math.toRadians(90.0);
        private float elevation = (float) Math.toRadians(20.0);
        private float distance = 3.5f;
        private final float minDistance = 0.75f;
        private final float maxDistance = 40.0f;
        private final float minElevation = (float) Math.toRadians(-85.0);
        private final float maxElevation = (float) Math.toRadians(85.0);
        private final float dragRadsPerPixel = 0.01f;
        private final float zoomBase = 0.97f;
        private final Camera3D orbitCamera;

        public OrbitConfig(Camera3D camera) {
            this.orbitCamera = camera;
        }

        public void setTarget(Vector3f target) {
            this.target.set(target);
        }

        public void setOrbit(float azimuth, float elevation, float distance) {
            this.azimuth = azimuth;
            this.elevation = clamp(elevation, minElevation, maxElevation);
            this.distance = clamp(distance, minDistance, maxDistance);
            apply();
        }

        public void drag(float dx, float dy) {
            azimuth += dx * dragRadsPerPixel;
            elevation = clamp(elevation + dy * dragRadsPerPixel, minElevation, maxElevation);
        }

        public void zoom(int ticks) {
            if (ticks != 0) {
                distance = clamp(distance * (float) Math.pow(zoomBase, ticks), minDistance, maxDistance);
                apply();
            }
        }

        private void apply() {
            float cosElevation = (float) Math.cos(elevation);
            orbitCamera.position.set(
                    target.x + distance * cosElevation * (float) Math.cos(azimuth),
                    target.y + distance * (float) Math.sin(elevation),
                    target.z + distance * cosElevation * (float) Math.sin(azimuth));
            orbitCamera.target.set(target);
            orbitCamera.up.set(orbitCamera.worldUp);
            orbitCamera.updateViewFirstPerson();
        }

        private float clamp(float v, float min, float max) {
            return Math.max(min, Math.min(max, v));
        }
    }

    /**
     * Scroll subscription for region-based scroll handling.
     */
    public static class ScrollSubscription {
        public Bounds bounds;
        public Consumer<Boolean> onScroll;

        public ScrollSubscription(Bounds bounds, Consumer<Boolean> onScroll) {
            this.bounds = bounds;
            this.onScroll = onScroll;
        }
    }

    /**
     * Click subscription for region-based click handling.
     */
    public static class ClickSubscription {
        public Bounds bounds;
        public Consumer<Integer> onClick;

        public ClickSubscription(Bounds bounds, Consumer<Integer> onClick) {
            this.bounds = bounds;
            this.onClick = onClick;
        }
    }

    /**
     * Functional interface for scene-specific key handling.
     */
    @FunctionalInterface
    public interface KeyHandler {
        /**
         * Handle a key press event.
         * @return true if the key was handled, false to continue processing
         */
        boolean onKeyPress(int key, int mods, boolean repeated);

        /**
         * Handle a key release event.
         */
        default void onKeyRelease(int key, int mods) {}

        /**
         * Handle a character input event.
         */
        default void onCharInput(int codepoint) {}

        /**
         * Update handler state each frame.
         */
        default void onUpdate(float shiftMod, double deltaTime) {}
    }

    /**
     * Functional interface for scene-specific mouse handling.
     */
    @FunctionalInterface
    public interface MouseHandler {
        /**
         * Handle mouse button press.
         * @return true if handled, false to continue
         */
        boolean onMousePress(int button, float x, float y);

        /**
         * Handle mouse button release.
         */
        default void onMouseRelease(int button) {}

        /**
         * Handle mouse move (hover).
         */
        default void onMouseMove(float x, float y) {}

        /**
         * Handle mouse drag (pan).
         */
        default void onMouseDrag(float x, float y) {}

        /**
         * Handle scroll wheel event.
         */
        default void onScroll(double delta) {}

        /**
         * Update handler state each frame.
         */
        default void onUpdate(float shiftMod, double deltaTime) {}
    }

    // === Builder for declarative configuration ===
    public static class Builder {
        private final String sceneId;
        private Camera camera;
        private Canvas3D canvas;
        private KeyHandler keyHandler;
        private MouseHandler mouseHandler;
        private OrbitConfig orbitConfig;
        private SceneContext sceneContext;
        private List<HyperString> hyperStrings;

        public Builder(String sceneId) {
            this.sceneId = sceneId;
        }

        public Builder camera(Camera camera) {
            this.camera = camera;
            return this;
        }

        public Builder canvas(Canvas3D canvas) {
            this.canvas = canvas;
            return this;
        }

        public Builder keyHandler(KeyHandler handler) {
            this.keyHandler = handler;
            return this;
        }

        public Builder mouseHandler(MouseHandler handler) {
            this.mouseHandler = handler;
            return this;
        }

        public Builder orbitConfig(OrbitConfig config) {
            this.orbitConfig = config;
            return this;
        }

        public Builder sceneContext(SceneContext context) {
            this.sceneContext = context;
            return this;
        }

        public Builder hyperStrings(List<HyperString> hyperStrings) {
            this.hyperStrings = hyperStrings;
            return this;
        }

        public InputHandler build() {
            return new InputHandler(this);
        }
    }

    // === Static subscription API (preserves MouseTrap API) ===
    public static void subscribeScrollRegion(Bounds bounds, Consumer<Boolean> handler) {
        synchronized (scrollSubscriptions) {
            scrollSubscriptions.add(new ScrollSubscription(bounds, handler));
        }
    }

    public static void unsubscribeScrollRegion(Consumer<Boolean> handler) {
        synchronized (scrollSubscriptions) {
            scrollSubscriptions.removeIf(s -> s.onScroll == handler);
        }
    }

    public static void subscribeClickRegion(Bounds bounds, Consumer<Integer> handler) {
        synchronized (clickSubscriptions) {
            clickSubscriptions.add(new ClickSubscription(bounds, handler));
        }
    }

    public static void unsubscribeClickRegion(Consumer<Integer> handler) {
        synchronized (clickSubscriptions) {
            clickSubscriptions.removeIf(s -> s.onClick == handler);
        }
    }

    // === Constructor ===
    private InputHandler(Builder builder) {
        this.camera = builder.camera;
        this.canvas = builder.canvas;
        this.keyHandler = builder.keyHandler != null ? builder.keyHandler : createDefaultKeyHandler();
        this.mouseHandler = builder.mouseHandler != null ? builder.mouseHandler : createDefaultMouseHandler();
        this.orbitConfig = builder.orbitConfig;
        this.sceneContext = builder.sceneContext != null ? builder.sceneContext : new SceneContext(builder.sceneId);
        if (builder.hyperStrings != null) {
            this.hyperStrings.addAll(builder.hyperStrings);
        }
    }

    // === Default handlers ===
    private KeyHandler createDefaultKeyHandler() {
        return new KeyHandler() {
            @Override
            public boolean onKeyPress(int key, int mods, boolean repeated) {
                // Default: no key handling
                return false;
            }
        };
    }

    private MouseHandler createDefaultMouseHandler() {
        return new MouseHandler() {
            @Override
            public boolean onMousePress(int button, float x, float y) {
                return false;
            }
        };
    }

    // === Public API ===

    /**
     * Get the orbit config for 3D camera control. Returns null if not configured.
     */
    public OrbitConfig getOrbitConfig() {
        return orbitConfig;
    }

    /**
     * Get the scene context for scene-specific operations.
     */
    public SceneContext getSceneContext() {
        return sceneContext;
    }

    /**
     * Add a hyper string for click/hover handling.
     */
    public void addHyperString(HyperString hs) {
        hyperStrings.add(hs);
    }

    /**
     * Remove a hyper string.
     */
    public void removeHyperString(HyperString hs) {
        hyperStrings.remove(hs);
    }

    /**
     * Get all hyper strings.
     */
    public List<HyperString> getHyperStrings() {
        return hyperStrings;
    }

    // === Input callbacks (these would be wired by AutomationInputBinder or scene) ===

    /**
     * Handle key callback from platform.
     */
    public void keyCallback(long window, int key, int scancode, int action, int mods) {
        if (!active) return;
        Platforms.init(Platforms.get().getPlatformID());

        boolean handled = false;
        switch (action) {
            case Keys.ACTION_PRESS:
            case Keys.ACTION_REPEAT:
                handled = keyHandler.onKeyPress(key, mods, action == Keys.ACTION_REPEAT);
                break;
            case Keys.ACTION_RELEASE:
                keyHandler.onKeyRelease(key, mods);
                break;
        }

        // If not handled by scene, fall back to default key handling
        if (!handled) {
            handleDefaultKey(key, mods, action);
        }
    }

    /**
     * Handle char callback from platform.
     */
    public void charCallback(long window, int codepoint) {
        if (!active) return;
        keyHandler.onCharInput(codepoint);
    }

    /**
     * Handle mouse button callback from platform.
     */
    public void mouseButton(int button, int action, int mods) {
        if (!active) return;
        Platforms.init(Platforms.get().getPlatformID());

        float x = lastX;
        float y = lastY;

        boolean handled = false;
        if (action == Keys.ACTION_PRESS) {
            handled = mouseHandler.onMousePress(button, x, y);
            if (!handled) {
                leftMouseDownPos = new Vector2f(x, y);
                startX = normalizedPosX;
                startY = normalizedPosY;
            }
        } else if (action == Keys.ACTION_RELEASE) {
            if (leftMouseDownPos != null) {
                Vector2f releasePos = new Vector2f(x, y);
                if (releasePos.distance(leftMouseDownPos) < 3f) {
                    // Click - check subscriptions
                    handleClick(button, x, y);
                }
                leftMouseDownPos = null;
            }
            mouseHandler.onMouseRelease(button);
        }
    }

    /**
     * Handle mouse move/drag callback from platform.
     */
    public void moveOrDrag(long window, float x, float y) {
        if (!active) return;
        Platforms.init(Platforms.get().getPlatformID());

        boolean leftDown = Platforms.gl().getMouseButton(window, MouseButtons.MOUSE_BUTTON_LEFT);
        Vector2f currentPos = new Vector2f(x, y);

        boolean dragged = false;
        if (leftMouseDownPos != null && currentPos.distance(leftMouseDownPos) > 3f) {
            mouseHandler.onMouseDrag(x, y);
            dragged = true;
        }

        if (!dragged) {
            mouseHandler.onMouseMove(x, y);
        }
    }

    /**
     * Handle scroll callback from platform.
     */
    public void scrollCallback(double y) {
        if (!active) return;
        Platforms.init(Platforms.get().getPlatformID());
        queuedMouseWheelTicks += (int) (4 * y);
        timeLastScroll = System.currentTimeMillis();
        mouseHandler.onScroll(y);
    }

    /**
     * Update handler state each frame. Called by SceneInputFrameUpdater.
     */
    public void paintUpdate(float shiftMod) {
        if (!active) return;

        // Process scroll
        if (System.currentTimeMillis() - timeLastScroll > 60) {
            queuedMouseWheelTicks = 0;
        }

        if (queuedMouseWheelTicks != 0) {
            boolean scrollUp = queuedMouseWheelTicks < 0;
            processScroll(scrollUp, Clock.deltaTime() * 100f);
            queuedMouseWheelTicks = 0;
        }

        // Update hyper strings
        updateHyperStrings();

        // Update handlers
        keyHandler.onUpdate(shiftMod, Clock.deltaTime());
        mouseHandler.onUpdate(shiftMod, Clock.deltaTime());
    }

    // === Internal helpers ===

    private void handleClick(int button, float x, float y) {
        normalizedPosX = camera.getNormalizePosX(x);
        normalizedPosY = camera.getNormalizePosY(y);

        // Check click subscriptions
        synchronized (clickSubscriptions) {
            for (ClickSubscription sub : clickSubscriptions) {
                if (sub.bounds != null) {
                    sub.bounds.recalc();
                    float windowHeight = (float) Platforms.get().getWindowHeight();
                    float yFromBottom = windowHeight - y;
                    boolean inside = x >= sub.bounds.offsetX && x <= sub.bounds.offsetX + sub.bounds.viewWidth
                            && yFromBottom >= sub.bounds.offsetY && yFromBottom <= sub.bounds.offsetY + sub.bounds.viewHeight;
                    if (inside) {
                        sub.onClick.accept(button);
                        return;
                    }
                }
            }
        }
    }

    private void processScroll(boolean scrollUp, double deltaTime) {
        // Check scroll subscriptions first
        synchronized (scrollSubscriptions) {
            if (!scrollSubscriptions.isEmpty()) {
                float windowHeight = (float) Platforms.get().getWindowHeight();
                float yFromBottom = windowHeight - lastY;
                for (ScrollSubscription sub : scrollSubscriptions) {
                    if (sub.bounds != null) {
                        sub.bounds.recalc();
                        boolean inside = lastX >= sub.bounds.offsetX && lastX <= sub.bounds.offsetX + sub.bounds.viewWidth
                                && yFromBottom >= sub.bounds.offsetY && yFromBottom <= sub.bounds.offsetY + sub.bounds.viewHeight;
                        if (inside) {
                            sub.onScroll.accept(scrollUp);
                            return;
                        }
                    }
                }
            }
        }

        // Fall back to camera scroll
        if (camera != null) {
            camera.onScroll(scrollUp, deltaTime);
        }
    }

    private void updateHyperStrings() {
        for (HyperString h : hyperStrings) {
            h.calculateClearHover(normalizedPosX, normalizedPosY);
        }
        for (HyperString h : hyperStrings) {
            h.calculateHover(normalizedPosX, normalizedPosY);
        }
    }

    private void handleDefaultKey(int key, int mods, int action) {
        // Default key handling: standard camera movement and zoom
        if (action == Keys.ACTION_PRESS || action == Keys.ACTION_REPEAT) {
            if (KeyActions.ZoomIn.keyPressed(java.util.Set.of(key))) {
                if (camera != null) camera.onScroll(true, Clock.deltaTime());
            }
            if (KeyActions.ZoomOut.keyPressed(java.util.Set.of(key))) {
                if (camera != null) camera.onScroll(false, Clock.deltaTime());
            }
            if (camera != null && KeyActions.ControlMask.keyPressed(java.util.Set.of(key))) {
                if (KeyActions.MoveUp.keyPressed(java.util.Set.of(key))) camera.move(Camera.Direction.FORWARD);
                if (KeyActions.MoveDown.keyPressed(java.util.Set.of(key))) camera.move(Camera.Direction.BACKWARD);
                if (KeyActions.MoveLeft.keyPressed(java.util.Set.of(key))) camera.move(Camera.Direction.LEFT);
                if (KeyActions.MoveRight.keyPressed(java.util.Set.of(key))) camera.move(Camera.Direction.RIGHT);
            }
        }
    }
}
