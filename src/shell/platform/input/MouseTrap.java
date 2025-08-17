package shell.platform.input;

import static shell.platform.input.Keys.ACTION_PRESS;
import static shell.platform.input.Keys.ACTION_RELEASE;

import java.util.ArrayList;

import org.joml.Vector2f;

import shell.Toggle;
import shell.cameras.Camera;
import shell.platform.Platforms;
import shell.render.Clock;
import shell.render.text.HyperString;
import shell.ui.Canvas3D;
import shell.ui.main.Main;
import shell.ui.main.PanelTypes;

public class MouseTrap {

    public int queuedMouseWheelTicks = 0;
    Main main;
    long timeLastScroll;
    public static int lastX = Integer.MIN_VALUE;
    public static int lastY = Integer.MIN_VALUE;
    int width;
    int height;
    public Camera camera;
    public boolean active = true;
    public static ArrayList<HyperString> hyperStrings = new ArrayList<>();

    public MouseTrap(Main main, Camera camera) {
        this.main = main;
        this.camera = camera;
        this.canvas = Canvas3D.canvas;
    }

    public void mouseClicked(float xPos, float yPos) {
        if (!active) {
            return;
        }
        normalizedPosX = camera.getNormalizePosX(xPos);
        normalizedPosY = camera.getNormalizePosY(yPos);

        PanelTypes inMainView = Main.inView(xPos, yPos);
        Toggle.setPanelFocus(inMainView);
        if (Main.manifoldKnot != null && Main.active) {
            if (inMainView == PanelTypes.KnotView) {
                Main.tool.calculateClick(normalizedPosX, normalizedPosY);
            } else if (inMainView == PanelTypes.Terminal) {
                Main.terminal.calculateClick(normalizedPosX, normalizedPosY);
            }
        }

        for (HyperString h : hyperStrings) {
            h.click(normalizedPosX, normalizedPosY);
        }
        if (Canvas3D.menu != null) {

            Canvas3D.menu.click(normalizedPosX, normalizedPosY);
        }
    }

    double startX;
    double startY;
    private Canvas3D canvas;
    public float normalizedPosX;
    public float normalizedPosY;

    public void mousePressed(float x, float y) {
        normalizedPosX = camera.getNormalizePosX(x);
        normalizedPosY = camera.getNormalizePosY(y);
        startX = normalizedPosX;
        startY = normalizedPosY;
    }

    public void mouseDragged(float x, float y) {

        normalizedPosX = camera.getNormalizePosX(x);
        normalizedPosY = camera.getNormalizePosY(y);
        // update pan x and y to follow the mouse

        PanelTypes inMainView = Main.inView((float) leftMouseDownPos.x, (float) leftMouseDownPos.y);
        if (inMainView == PanelTypes.KnotView) {
            camera.drag((float) (normalizedPosX - startX), (float) (normalizedPosY - startY));
            startX = normalizedPosX;
            startY = normalizedPosY;
        }
    }

    public void mouseReleased() {
    }

    public void mouseEntered() {
    }

    public void mouseExited() {
        if (main != null) {
            Main.tool.clearHover();
        }
    }

    public void scrollCallback(double y) {
        queuedMouseWheelTicks += 4 * (int) y;
        timeLastScroll = System.currentTimeMillis();
    }

    public void setCanvas(Canvas3D canvas3d) {
        this.canvas = canvas3d;
    }

    public void mousePos(float x, float y) {
        if (!active) {
            return;
        }
        normalizedPosX = camera.getNormalizePosX(x);
        normalizedPosY = camera.getNormalizePosY(y);
        lastX = (int) x;
        lastY = (int) y;

        camera.mouseMove(lastX, lastY, x, y);
        if (Canvas3D.menu != null && !(this.canvas == null)) {

            Canvas3D.menu.setHover(normalizedPosX, normalizedPosY);
        }

        PanelTypes inMainView = Main.inView(x, y);
        if (main != null && Main.active) {
            if (inMainView == PanelTypes.KnotView) {
                Main.tool.calculateHover(normalizedPosX, normalizedPosY);
            } else {
                Main.tool.clearHover();
            }
        }
        updateHyperStrings();

        hyperStrings = new ArrayList<>();
    }

    public void paintUpdate(float SHIFT_MOD) {
        if (System.currentTimeMillis() - timeLastScroll > 60) {
            queuedMouseWheelTicks = 0;
        }
        PanelTypes view = Main.inView(lastX, lastY);
        if (queuedMouseWheelTicks < 0) {
            if (view == PanelTypes.KnotView) {
                camera.zoom(false);
            } else if (view == PanelTypes.Info) {
                Main.info.scrollInfoPanel(true);
            } else if (view == PanelTypes.Terminal) {
                Main.terminal.scrollTerminal(true);
            }
            if (view != PanelTypes.None) {
                updateHyperStrings();
            }
            Canvas3D.menu.scroll(true);
            queuedMouseWheelTicks++;
        }
        if (queuedMouseWheelTicks > 0) {
            if (view == PanelTypes.KnotView) {
                camera.zoom(true);
            } else if (view == PanelTypes.Info) {
                Main.info.scrollInfoPanel(false);
            } else if (view == PanelTypes.Terminal) {
                Main.terminal.scrollTerminal(false);
            }
            if (view != PanelTypes.None) {
                updateHyperStrings();
            }
            Canvas3D.menu.scroll(false);
            queuedMouseWheelTicks--;
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

    float leftMouseDown = -1;
    Vector2f leftMouseDownPos;

    public void mouseButton(int button, int action, int mods) {
        float x = lastX;
        float y = lastY;
        if (action == ACTION_PRESS) {
            leftMouseDown = Clock.time();
            leftMouseDownPos = new Vector2f(x, y);
            mousePressed(x, y);
        } else if (action == ACTION_RELEASE) {
            if (leftMouseDownPos != null) {
                Vector2f mouseReleasePos = new Vector2f(x, y);
                if (mouseReleasePos.distance(leftMouseDownPos) < 3) {
                    mouseClicked(x, y);
                } else {
                    mouseReleased();
                }
            }
        }
    }

    public void moveOrDrag(long window, float x, float y) {
        boolean leftDown = Platforms.gl().getMouseButton(window, MouseButtons.MOUSE_BUTTON_LEFT);
        Vector2f mouseReleasePos = new Vector2f((float) x, (float) y);
        if (leftDown && leftMouseDownPos != null && mouseReleasePos.distance(leftMouseDownPos) > 3) {
            mouseDragged(x, y);
        } else {
            mousePos(x, y);
        }
    }

}
