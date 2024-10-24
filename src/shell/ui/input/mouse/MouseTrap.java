package shell.ui.input.mouse;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.glfwGetCursorPos;
import static org.lwjgl.glfw.GLFW.glfwGetMouseButton;
import static org.lwjgl.system.MemoryStack.stackPush;

import java.awt.AWTException;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Point2D;
import java.nio.DoubleBuffer;
import java.util.ArrayList;

import org.joml.Vector2f;
import org.lwjgl.system.MemoryStack;

import shell.cameras.Camera;
import shell.render.Clock;
import shell.render.text.HyperString;
import shell.ui.Canvas3D;
import shell.ui.IxdarWindow;
import shell.ui.main.Main;
import shell.ui.main.MainPanel;

public class MouseTrap {

    public int queuedMouseWheelTicks = 0;
    Main main;
    long timeLastScroll;
    public static int lastX = Integer.MIN_VALUE;
    public static int lastY = Integer.MIN_VALUE;
    int width;
    int height;
    public Camera camera;
    public boolean captureMouse;
    public boolean active = true;
    public static ArrayList<HyperString> hyperStrings = new ArrayList<>();

    public MouseTrap(Main main, Camera camera, boolean captureMouse) {
        this.main = main;
        this.camera = camera;
        this.captureMouse = captureMouse;
        this.canvas = Canvas3D.canvas;
    }

    public void mouseClicked(float xPos, float yPos) {
        if (!active) {
            return;
        }
        normalizedPosX = camera.getNormalizePosX(xPos);
        normalizedPosY = camera.getNormalizePosY(yPos);

        MainPanel inMainView = Main.inView(xPos, yPos);
        if (Main.manifoldKnot != null && Main.active) {
            if (inMainView == MainPanel.KnotView) {
                Main.tool.calculateClick(normalizedPosX, normalizedPosY);
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
    private java.awt.geom.Point2D.Double center;
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

        MainPanel inMainView = Main.inView((float) leftMouseDownPos.x, (float) leftMouseDownPos.y);
        if (inMainView == MainPanel.KnotView) {
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

    public void mouseWheelMoved(MouseWheelEvent e) {
        queuedMouseWheelTicks += e.getWheelRotation();
        timeLastScroll = System.currentTimeMillis();
    }

    public void mouseScrollCallback(long window, double y) {
        queuedMouseWheelTicks += 4 * y;
        timeLastScroll = System.currentTimeMillis();
    }

    public void setCanvas(Canvas3D canvas3d) {
        this.canvas = canvas3d;
    }

    public void mouseMoved(float x, float y) {
        if (!active) {
            return;
        }
        normalizedPosX = camera.getNormalizePosX(x);
        normalizedPosY = camera.getNormalizePosY(y);
        if (captureMouse && center == null) {
            captureMouse(false);
            return;
        }
        lastX = (int) x;
        lastY = (int) y;
        if (captureMouse) {
            camera.mouseMove((int) center.x, (int) center.y, x, y);
        } else {
            camera.mouseMove(lastX, lastY, x, y);
        }
        if (Canvas3D.menu != null && !(this.canvas == null)) {

            Canvas3D.menu.setHover(normalizedPosX, normalizedPosY);
        }

        MainPanel inMainView = Main.inView(x, y);
        if (main != null && Main.active) {
            if (inMainView == MainPanel.KnotView) {
                Main.tool.calculateHover(normalizedPosX, normalizedPosY);
            } else {
                Main.tool.clearHover();
            }
        }
        for (HyperString h : hyperStrings) {
            h.calculateClearHover(normalizedPosX, normalizedPosY);
        }
        for (HyperString h : hyperStrings) {
            h.calculateHover(normalizedPosX, normalizedPosY);
        }
        if (captureMouse) {
            // captureMouse(false);
        }
        hyperStrings = new ArrayList<>();
    }

    public void paintUpdate(float SHIFT_MOD) {
        MouseTrap.hyperStrings = new ArrayList<>();
        if (System.currentTimeMillis() - timeLastScroll > 60) {
            queuedMouseWheelTicks = 0;
        }
        MainPanel view = Main.inView(lastX, lastY);
        if (queuedMouseWheelTicks < 0) {
            if (view == MainPanel.KnotView) {
                camera.zoom(false);
            } else if (view == MainPanel.Info) {
                Main.tool.scrollInfoPanel(true);
            }
            Canvas3D.menu.scroll(true);
            queuedMouseWheelTicks++;
        }
        if (queuedMouseWheelTicks > 0) {
            if (view == MainPanel.KnotView) {
                camera.zoom(true);
            } else if (view == MainPanel.Info) {
                Main.tool.scrollInfoPanel(false);
            }
            Canvas3D.menu.scroll(false);
            queuedMouseWheelTicks--;
        }

    }

    public void captureMouse(boolean force) {

        if (canvas != null && (IxdarWindow.frame.hasFocus() || force)) {
            Point2D topLeft = IxdarWindow.getLocationOnScreen();
            center = new Point2D.Double((int) (IxdarWindow.getWidth() / 2), (int) (IxdarWindow.getHeight() / 2));
            int x = (int) (topLeft.getX() + center.getX());
            int y = (int) (topLeft.getY() + center.getY());
            moveMouse(new Point2D.Double(x, y));
        }
    }

    public static void moveMouse(Point2D p) {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] gs = ge.getScreenDevices();

        // Search the devices for the one that draws the specified point.
        for (GraphicsDevice device : gs) {
            GraphicsConfiguration[] configurations = device.getConfigurations();
            for (GraphicsConfiguration config : configurations) {
                Rectangle bounds = config.getBounds();
                if (bounds.contains(p)) {
                    // Set point to screen coordinates.
                    Point2D b = bounds.getLocation();
                    Point2D s = new Point2D.Double(p.getX() - b.getX(), p.getY() - b.getY());

                    try {
                        Robot r = new Robot(device);
                        r.mouseMove((int) s.getX(), (int) s.getY());
                    } catch (AWTException e) {
                        e.printStackTrace();
                    }

                    return;
                }
            }
        }
        return;
    }

    float leftMouseDown = -1;
    Vector2f leftMouseDownPos;

    public void clickCallback(long window, int button, int action, int mods) {
        try (MemoryStack stack = stackPush()) {
            DoubleBuffer xPos = stack.mallocDouble(1);
            DoubleBuffer yPos = stack.mallocDouble(1);
            glfwGetCursorPos(IxdarWindow.window, xPos, yPos);
            float x = (float) xPos.get(0);
            float y = (float) yPos.get(0);
            if (action == GLFW_PRESS) {
                leftMouseDown = Clock.time();
                leftMouseDownPos = new Vector2f(x, y);
                mousePressed(x, y);
            } else if (action == GLFW_RELEASE) {
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
    }

    public void moveCallback(long window, double x, double y) {
        int state = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT);
        Vector2f mouseReleasePos = new Vector2f((float) x, (float) y);
        // System.out.println("mouseMove: " + x + " " + normalizedPosX);
        if (state == GLFW_PRESS && mouseReleasePos.distance(leftMouseDownPos) > 3) {
            mouseDragged((float) x, (float) y);
        } else {
            mouseMoved((float) x, (float) y);
        }
    }

}
