package ixdar.platform.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;

import org.joml.Vector2f;

import ixdar.canvas.Canvas3D;
import ixdar.game.City;
import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.Clock;
import ixdar.gui.ui.tools.RoutePlanningTool;
import ixdar.platform.Platforms;
import ixdar.scenes.trade.TradeScene;

/**
 * Mouse input handler for the trade game scene. Extends MouseTrap but replaces
 * MainScene-specific behavior with city interaction.
 */
public class TradeMouseTrap extends MouseTrap {
    public static final String TRADE_CITY_CLICK = "trade_city_click";
    public static final String BUTTON = "button";
    public static final String XPX = "xPx";
    public static final String YPX = "yPx";
    public static final String TOOL = "tool";
    public static final String CITY = "city";
    public static final String STR = ",";
    public static final float NUM_2 = 2f;
    public static final int NUM_5 = 5;
    public static final float NUM_1 = 1f;
    public static final int NUM_3 = 3;
    public static final int NUM_4 = 4;
    public static final int NUM_60 = 60;
    public static final float NUM_100 = 100f;

    private TradeScene tradeScene;
    private Vector2f leftMouseDownPos;
    private boolean automationHoverLocked = false;
    private float automationHoverX = 0f;
    private float automationHoverY = 0f;
    private boolean automationInputInProgress = false;

    /**
     * Build a trade-scene mouse controller bound to the given camera and canvas.
     *
     * @param tradeScene owning trade scene (used for city hit-testing and tool dispatch)
     * @param camera camera the controller drives
     * @param canvas owning canvas
     */
    public TradeMouseTrap(TradeScene tradeScene, Camera camera, Canvas3D canvas) {
        super(null, camera, canvas);
        this.tradeScene = tradeScene;
    }

    /**
     * Mark the start of an automation-driven input burst so user mouse-move heuristics don't
     * interrupt programmatic hover locks.
     */
    public void beginAutomationInput() {
        automationInputInProgress = true;
    }

    /**
     * End the automation-input burst started by {@link #beginAutomationInput()}.
     */
    public void endAutomationInput() {
        automationInputInProgress = false;
    }

    /**
     * Pin hover at {@code (x, y)} from automation; survives subsequent paint frames until
     * either {@link #clearAutomationHoverLock()} or the user moves more than {@link #NUM_2}
     * pixels away.
     *
     * @param x window x to lock hover at
     * @param y window y to lock hover at
     */
    public void setAutomationHoverLock(float x, float y) {
        automationHoverLocked = true;
        automationHoverX = x;
        automationHoverY = y;
        applyHoverAt(x, y);
    }

    /**
     * Release the automation hover lock so live mouse movement controls hover again.
     */
    public void clearAutomationHoverLock() {
        automationHoverLocked = false;
    }

    private void applyHoverAt(float x, float y) {
        normalizedPosX = camera.getNormalizePosX(x);
        normalizedPosY = camera.getNormalizePosY(y);
        lastX = (int) x;
        lastY = (int) y;
        updateCityHover();
    }

    private void clearAutomationLockOnUserMove(float x, float y) {
        if (!automationHoverLocked || automationInputInProgress) {
            return;
        }
        if (Math.abs(x - automationHoverX) > NUM_2 || Math.abs(y - automationHoverY) > NUM_2) {
            automationHoverLocked = false;
        }
    }

    /**
     * Track press position; on release within {@link #NUM_5} pixels, treat it as a city click
     * via {@code handleCityClick}.
     *
     * @param button button index
     * @param action {@code ACTION_PRESS} or {@code ACTION_RELEASE}
     * @param mods modifier-key bitmask (unused)
     */
    @Override
    public void mouseButton(int button, int action, int mods) {
        Platforms.init(Platforms.get().getPlatformID());
        if (!active)
            return;

        float x = lastX;
        float y = lastY;

        if (action == ACTION_PRESS) {
            leftMouseDownPos = new Vector2f(x, y);
            mousePressed(x, y);
        } else if (action == ACTION_RELEASE) {
            if (leftMouseDownPos != null) {
                Vector2f mouseReleasePos = new Vector2f(x, y);
                if (mouseReleasePos.distance(leftMouseDownPos) < NUM_5) {
                    handleCityClick(x, y, button);
                }
            }
            leftMouseDownPos = null;
        }
    }

    private void handleCityClick(float x, float y, int button) {
        if (!active)
            return;

        if (tradeScene.activeTool instanceof RoutePlanningTool) {
            RoutePlanningTool routeTool = (RoutePlanningTool) tradeScene.activeTool;
            if (routeTool.onToolbarClick(x, y)) {
                recordAbstractAction(TRADE_CITY_CLICK,
                        BUTTON, button,
                        XPX, x,
                        YPX, y,
                        TOOL, tradeScene.activeTool.displayName(),
                        CITY, "",
                        "target", "toolbar");
                return;
            }
        }

        normalizedPosX = camera.getNormalizePosX(x);
        normalizedPosY = camera.getNormalizePosY(y);

        // Convert to world coordinates and check for city click
        float worldX = TradeScene.camera.screenTransformX(normalizedPosX);
        float worldY = TradeScene.camera.screenTransformY(normalizedPosY);

        System.out
                .println("[TradeMouseTrap] Click at screen(" + x + STR + y + ") world(" + worldX + STR + worldY + ")");

        City clickedCity = tradeScene.getCityAt(worldX, worldY);
        System.out.println("[TradeMouseTrap] clickedCity: " + (clickedCity != null ? clickedCity.name : "null"));
        System.out.println("[TradeMouseTrap] activeTool: " + tradeScene.activeTool.displayName());
        recordAbstractAction(TRADE_CITY_CLICK,
                BUTTON, button,
                XPX, x,
                YPX, y,
                "xCoord", normalizedPosX,
                "yCoord", normalizedPosY,
                "xNorm", x / Math.max(NUM_1, Platforms.get().getWindowWidth()),
                "yNorm", y / Math.max(NUM_1, Platforms.get().getWindowHeight()),
                TOOL, tradeScene.activeTool.displayName(),
                CITY, clickedCity == null ? "" : clickedCity.name);

        if (clickedCity != null) {
            tradeScene.onCityClick(clickedCity);
        }
    }

    /**
     * Trade-specific move/drag: pans the camera while left-dragged past {@link #NUM_3} pixels,
     * otherwise updates city hover. Real cursor motion past the deadzone clears any active
     * automation hover lock.
     *
     * @param window platform window handle
     * @param x cursor x in window pixels
     * @param y cursor y in window pixels
     */
    @Override
    public void moveOrDrag(long window, float x, float y) {
        Platforms.init(Platforms.get().getPlatformID());
        if (!active)
            return;

        clearAutomationLockOnUserMove(x, y);

        normalizedPosX = camera.getNormalizePosX(x);
        normalizedPosY = camera.getNormalizePosY(y);
        lastX = (int) x;
        lastY = (int) y;

        boolean leftDown = Platforms.gl().getMouseButton(window, MouseButtons.MOUSE_BUTTON_LEFT);
        Vector2f currentPos = new Vector2f(x, y);

        if (leftDown && leftMouseDownPos != null && currentPos.distance(leftMouseDownPos) > NUM_3) {
            // Dragging - pan camera
            mouseDragged(x, y);
        } else {
            // Just moving - update hover
            updateCityHover();
        }
    }

    /**
     * Plain move: clear automation lock on real motion, run the base hover, then refresh the
     * city-hover overlay.
     *
     * @param x cursor x in window pixels
     * @param y cursor y in window pixels
     */
    @Override
    public void mousePos(float x, float y) {
        if (!active) {
            return;
        }
        clearAutomationLockOnUserMove(x, y);
        super.mousePos(x, y);
        updateCityHover();
    }

    /**
     * Pan the camera by the normalized-coordinate delta from the previous drag sample.
     *
     * @param x cursor x in window pixels
     * @param y cursor y in window pixels
     */
    @Override
    public void mouseDragged(float x, float y) {
        normalizedPosX = camera.getNormalizePosX(x);
        normalizedPosY = camera.getNormalizePosY(y);

        // Pan the camera
        camera.drag((float) (normalizedPosX - startX), (float) (normalizedPosY - startY));
        startX = normalizedPosX;
        startY = normalizedPosY;
    }

    private void updateCityHover() {
        // Convert to world coordinates
        float worldX = TradeScene.camera.screenTransformX(normalizedPosX);
        float worldY = TradeScene.camera.screenTransformY(normalizedPosY);

        City newHover = tradeScene.getCityAt(worldX, worldY);
        tradeScene.updateHoveredCity(newHover);
        tradeScene.updateHoveredToolbar(lastX, lastY);
    }

    /**
     * Queue scroll ticks for {@link #paintUpdate}; ticks decay after {@link #NUM_60} ms.
     *
     * @param y vertical scroll delta
     */
    @Override
    public void scrollCallback(double y) {
        Platforms.init(Platforms.get().getPlatformID());
        if (!active)
            return;
        queuedMouseWheelTicks += (int) (NUM_4 * y);
        timeLastScroll = System.currentTimeMillis();
    }

    /**
     * Per-frame: drain queued scroll ticks into camera zoom and re-pin hover when the
     * automation lock is active.
     *
     * @param SHIFT_MOD speed multiplier (currently unused)
     */
    @Override
    public void paintUpdate(float SHIFT_MOD) {
        if (!active)
            return;

        // Handle scroll for zooming
        if (System.currentTimeMillis() - timeLastScroll > NUM_60) {
            queuedMouseWheelTicks = 0;
        }

        if (queuedMouseWheelTicks != 0) {
            boolean zoomIn = queuedMouseWheelTicks < 0;
            camera.onScroll(zoomIn, Clock.deltaTime() * NUM_100);
            queuedMouseWheelTicks = 0;
        }

        if (automationHoverLocked) {
            applyHoverAt(automationHoverX, automationHoverY);
        }
    }
}
