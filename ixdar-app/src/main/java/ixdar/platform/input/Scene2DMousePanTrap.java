package ixdar.platform.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;

import org.joml.Vector2f;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.Clock;
import ixdar.platform.Platforms;

/**
 * Simplified 2D mouse pan/zoom handler for scenes.
 * 
 * Uses the new InputHandler abstraction instead of extending MouseTrap.
 */
public class Scene2DMousePanTrap extends InputHandler {
    private Vector2f leftMouseDownPos;

    public Scene2DMousePanTrap(Camera camera, Canvas3D canvas) {
        super(new Builder("scene-2d")
            .camera(camera)
            .canvas(canvas)
            .mouseHandler(createPanHandler()));
        this.leftMouseDownPos = super.leftMouseDownPos;
    }

    private InputHandler.MouseHandler createPanHandler() {
        return new InputHandler.MouseHandler() {
            @Override
            public boolean onMousePress(int button, float x, float y) {
                if (!active) {
                    return false;
                }
                leftMouseDownPos = new Vector2f(x, y);
                normalizedPosX = camera.getNormalizePosX(x);
                normalizedPosY = camera.getNormalizePosY(y);
                startX = normalizedPosX;
                startY = normalizedPosY;
                return false;
            }

            @Override
            public void onMouseDrag(float x, float y) {
                if (!active) {
                    return;
                }
                normalizedPosX = camera.getNormalizePosX(x);
                normalizedPosY = camera.getNormalizePosY(y);
                lastX = (int) x;
                lastY = (int) y;

                // Pan the camera
                camera.drag((float) (normalizedPosX - startX), (float) (normalizedPosY - startY));
                startX = normalizedPosX;
                startY = normalizedPosY;
            }

            @Override
            public void onMouseMove(float x, float y) {
                if (!active) {
                    return;
                }
                normalizedPosX = camera.getNormalizePosX(x);
                normalizedPosY = camera.getNormalizePosY(y);
                lastX = (int) x;
                lastY = (int) y;
            }

            @Override
            public void onScroll(double delta) {
                if (!active) {
                    return;
                }
                Platforms.init(Platforms.get().getPlatformID());
                queuedMouseWheelTicks += (int) (4 * delta);
                timeLastScroll = System.currentTimeMillis();
            }

            @Override
            public void onUpdate(float shiftMod, double deltaTime) {
                if (!active) {
                    return;
                }
                if (System.currentTimeMillis() - timeLastScroll > 60) {
                    queuedMouseWheelTicks = 0;
                }
                if (queuedMouseWheelTicks != 0) {
                    boolean zoomIn = queuedMouseWheelTicks < 0;
                    camera.onScroll(zoomIn, Clock.deltaTime() * 100f);
                    queuedMouseWheelTicks = 0;
                }
            }
        };
    }

    // Expose mouseButton as public for backward compatibility
    public void mouseButton(int button, int action, int mods) {
        if (!active) {
            return;
        }
        Platforms.init(Platforms.get().getPlatformID());
        float x = lastX;
        float y = lastY;
        if (action == ACTION_PRESS) {
            leftMouseDownPos = new Vector2f(x, y);
            mousePressed(x, y);
        } else if (action == ACTION_RELEASE) {
            leftMouseDownPos = null;
        }
    }

    // Expose moveOrDrag as public for backward compatibility
    public void moveOrDrag(long window, float x, float y) {
        if (!active) {
            return;
        }
        Platforms.init(Platforms.get().getPlatformID());
        normalizedPosX = camera.getNormalizePosX(x);
        normalizedPosY = camera.getNormalizePosY(y);
        lastX = (int) x;
        lastY = (int) y;
        boolean leftDown = Platforms.gl().getMouseButton(window, MouseButtons.MOUSE_BUTTON_LEFT);
        Vector2f currentPos = new Vector2f(x, y);
        if (leftDown && leftMouseDownPos != null && currentPos.distance(leftMouseDownPos) > 3f) {
            mouseDragged(x, y);
        } else {
            mousePos(x, y);
        }
    }

    // Expose mouseDragged as public for backward compatibility
    public void mouseDragged(float x, float y) {
        if (!active) {
            return;
        }
        normalizedPosX = camera.getNormalizePosX(x);
        normalizedPosY = camera.getNormalizePosY(y);
        camera.drag((float) (normalizedPosX - startX), (float) (normalizedPosY - startY));
        startX = normalizedPosX;
        startY = normalizedPosY;
    }

    // Expose scrollCallback as public for backward compatibility
    public void scrollCallback(double y) {
        if (!active) {
            return;
        }
        Platforms.init(Platforms.get().getPlatformID());
        queuedMouseWheelTicks += (int) (4 * y);
        timeLastScroll = System.currentTimeMillis();
    }

    // Expose paintUpdate as public for backward compatibility
    public void paintUpdate(float shiftMod) {
        if (!active) {
            return;
        }
        if (System.currentTimeMillis() - timeLastScroll > 60) {
            queuedMouseWheelTicks = 0;
        }
        if (queuedMouseWheelTicks != 0) {
            boolean zoomIn = queuedMouseWheelTicks < 0;
            camera.onScroll(zoomIn, Clock.deltaTime() * 100f);
            queuedMouseWheelTicks = 0;
        }
    }

    // Expose mousePressed as public for backward compatibility
    public void mousePressed(float x, float y) {
        normalizedPosX = camera.getNormalizePosX(x);
        normalizedPosY = camera.getNormalizePosY(y);
        startX = normalizedPosX;
        startY = normalizedPosY;
    }

    // Expose mousePos as public for backward compatibility
    public void mousePos(float x, float y) {
        if (!active) {
            return;
        }
        normalizedPosX = camera.getNormalizePosX(x);
        normalizedPosY = camera.getNormalizePosY(y);
        lastX = (int) x;
        lastY = (int) y;
    }
}
