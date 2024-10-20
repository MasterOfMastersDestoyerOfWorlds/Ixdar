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

import shell.Main;
import shell.cameras.Camera;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.render.Clock;
import shell.render.text.HyperString;
import shell.ui.Canvas3D;
import shell.ui.IxdarWindow;
import shell.ui.tools.Tool;

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

    public boolean inView(float x, float y) {
        boolean inMainViewRightBound = x < Main.MAIN_VIEW_WIDTH + Main.MAIN_VIEW_OFFSET_X;
        boolean inMainViewLeftBound = x > Main.MAIN_VIEW_OFFSET_X;
        float invY = IxdarWindow.getHeight() - y;
        boolean inMainViewLowerBound = invY > Main.MAIN_VIEW_OFFSET_Y;
        boolean inMainViewUpperBound = invY < Main.MAIN_VIEW_HEIGHT + Main.MAIN_VIEW_OFFSET_Y;
        return inMainViewLeftBound && inMainViewRightBound && inMainViewLowerBound
                && inMainViewUpperBound;
    }

    public void mouseClicked(float xPos, float yPos) {
        if (!active) {
            return;
        }
        Knot manifoldKnot = Main.manifoldKnot;
        normalizedPosX = camera.getNormalizePosX(xPos);
        normalizedPosY = camera.getNormalizePosY(yPos);

        boolean inMainView = inView(xPos, yPos);
        if (Main.manifoldKnot != null && Main.active && inMainView) {
            Tool tool = Main.tool;
            camera.calculateCameraTransform();
            double x = camera.screenTransformX(normalizedPosX - tool.ScreenOffsetX);
            double y = camera.screenTransformY(normalizedPosY - tool.ScreenOffsetY);
            double minDist = Double.MAX_VALUE;
            Segment hoverSegment = null;
            for (Segment s : manifoldKnot.manifoldSegments) {
                double result = s.boundContains(x, y);
                if (result > 0) {
                    if (result < minDist) {
                        minDist = result;
                        hoverSegment = s;
                    }
                }
            }
            VirtualPoint kp = null, cp = null;
            if (hoverSegment != null) {
                VirtualPoint closestPoint = hoverSegment.closestPoint(x, y);
                if (closestPoint.equals(hoverSegment.first)) {
                    kp = hoverSegment.first;
                    cp = hoverSegment.last;
                } else {
                    kp = hoverSegment.last;
                    cp = hoverSegment.first;
                }
            }
            tool.click(hoverSegment, kp, cp);
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
        boolean inMainView = inView((float) leftMouseDownPos.x, (float) leftMouseDownPos.y);
        if (inMainView) {
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

        boolean inMainView = inView(x, y);
        if (main != null && Main.active) {
            if (inMainView) {
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
        if (queuedMouseWheelTicks < 0) {
            camera.zoom(false);
            Canvas3D.menu.scroll(true);
            queuedMouseWheelTicks++;
        }
        if (queuedMouseWheelTicks > 0) {
            camera.zoom(true);
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
