package shell.platform.lwjgl;

import static org.lwjgl.glfw.GLFW.glfwGetWindowSize;
import static org.lwjgl.glfw.GLFW.glfwSetCharCallback;
import static org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback;
import static org.lwjgl.glfw.GLFW.glfwSetKeyCallback;
import static org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback;
import static org.lwjgl.glfw.GLFW.glfwSetScrollCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowTitle;

import java.nio.IntBuffer;

import org.lwjgl.system.MemoryStack;

import shell.platform.Platform;

public class LwjglPlatform implements Platform {

    private final long window;

    public LwjglPlatform(long window) {
        this.window = window;
    }

    @Override
    public void setTitle(String title) {
        glfwSetWindowTitle(window, title);
    }

    @Override
    public int getWindowWidth() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pWidth = stack.mallocInt(1);
            IntBuffer pHeight = stack.mallocInt(1);
            glfwGetWindowSize(window, pWidth, pHeight);
            return pWidth.get(0);
        }
    }

    @Override
    public int getWindowHeight() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pWidth = stack.mallocInt(1);
            IntBuffer pHeight = stack.mallocInt(1);
            glfwGetWindowSize(window, pWidth, pHeight);
            return pHeight.get(0);
        }
    }

    @Override
    public void requestRepaint() {
        // no-op; loop-driven repaint in LWJGL
    }

    @Override
    public float timeSeconds() {
        return (float) (System.nanoTime() / 1e9);
    }

    @Override
    public void setKeyCallback(KeyCallback callback) {
        glfwSetKeyCallback(window, (w, key, scancode, action, mods) -> callback.onKey(key, scancode, action, mods));
    }

    @Override
    public void setCharCallback(CharCallback callback) {
        glfwSetCharCallback(window, (w, codepoint) -> callback.onChar(codepoint));
    }

    @Override
    public void setCursorPosCallback(CursorPosCallback callback) {
        glfwSetCursorPosCallback(window, (w, x, y) -> callback.onMousePos(window, x, y));
    }

    @Override
    public void setMouseButtonCallback(MouseButtonCallback callback) {
        glfwSetMouseButtonCallback(window, (w, button, action, mods) -> callback.onMouseButton(button, action, mods));
    }

    @Override
    public void setScrollCallback(ScrollCallback callback) {
        glfwSetScrollCallback(window, (w, x, y) -> callback.onScroll(x, y));
    }
}
