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

    private TradeScene tradeScene;
    private Vector2f leftMouseDownPos;
    private boolean automationHoverLocked = false;
    private float automationHoverX = 0f;
    private float automationHoverY = 0f;
    private boolean automationInputInProgress = false;

    public TradeMouseTrap(TradeScene tradeScene, Camera camera, Canvas3D canvas) {
        super(null, camera, canvas);
        this.tradeScene = tradeScene;
    }

    public void beginAutomationInput() {
        automationInputInProgress = true;
    }

    public void endAutomationInput() {
        automationInputInProgress = false;
    }

    public void setAutomationHoverLock(float x, float y) {
        automationHoverLocked = true;
        automationHoverX = x;
        automationHoverY = y;
        applyHoverAt(x, y);
    }

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
        if (Math.abs(x - automationHoverX) > 2f || Math.abs(y - automationHoverY) > 2f) {
            automationHoverLocked = false;
        }
    }

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
                if (mouseReleasePos.distance(leftMouseDownPos) < 5) {
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
                recordAbstractAction("trade_city_click",
                        "button", button,
                        "xPx", x,
                        "yPx", y,
                        "tool", tradeScene.activeTool.displayName(),
                        "city", "",
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
                .println("[TradeMouseTrap] Click at screen(" + x + "," + y + ") world(" + worldX + "," + worldY + ")");

        City clickedCity = tradeScene.getCityAt(worldX, worldY);
        System.out.println("[TradeMouseTrap] clickedCity: " + (clickedCity != null ? clickedCity.name : "null"));
        System.out.println("[TradeMouseTrap] activeTool: " + tradeScene.activeTool.displayName());
        recordAbstractAction("trade_city_click",
                "button", button,
                "xPx", x,
                "yPx", y,
                "xCoord", normalizedPosX,
                "yCoord", normalizedPosY,
                "xNorm", x / Math.max(1f, Platforms.get().getWindowWidth()),
                "yNorm", y / Math.max(1f, Platforms.get().getWindowHeight()),
                "tool", tradeScene.activeTool.displayName(),
                "city", clickedCity == null ? "" : clickedCity.name);

        if (clickedCity != null) {
            tradeScene.onCityClick(clickedCity);
        }
    }

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

        if (leftDown && leftMouseDownPos != null && currentPos.distance(leftMouseDownPos) > 3) {
            // Dragging - pan camera
            mouseDragged(x, y);
        } else {
            // Just moving - update hover
            updateCityHover();
        }
    }

    @Override
    public void mousePos(float x, float y) {
        if (!active) {
            return;
        }
        clearAutomationLockOnUserMove(x, y);
        super.mousePos(x, y);
        updateCityHover();
    }

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

    @Override
    public void scrollCallback(double y) {
        Platforms.init(Platforms.get().getPlatformID());
        if (!active)
            return;
        queuedMouseWheelTicks += (int) (4 * y);
        timeLastScroll = System.currentTimeMillis();
    }

    @Override
    public void paintUpdate(float SHIFT_MOD) {
        if (!active)
            return;

        // Handle scroll for zooming
        if (System.currentTimeMillis() - timeLastScroll > 60) {
            queuedMouseWheelTicks = 0;
        }

        if (queuedMouseWheelTicks != 0) {
            boolean zoomIn = queuedMouseWheelTicks < 0;
            camera.onScroll(zoomIn, Clock.deltaTime() * 100f);
            queuedMouseWheelTicks = 0;
        }

        if (automationHoverLocked) {
            applyHoverAt(automationHoverX, automationHoverY);
        }
    }
}
