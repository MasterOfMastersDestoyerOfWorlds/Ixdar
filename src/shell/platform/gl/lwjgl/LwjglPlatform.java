package shell.platform.gl.lwjgl;

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

import com.google.gson.Gson;
import java.io.File;
import java.nio.ByteBuffer;
// removed duplicate IntBuffer import
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImage;
import shell.render.Texture;
import shell.render.text.FontAtlasDTO;
import shell.ui.IxdarWindow;

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

    @Override
    public FontAtlasDTO parseFontAtlas(String json) {
        return new Gson().fromJson(json, FontAtlasDTO.class);
    }

    @Override
    public Texture loadTexture(String resourceName) {
        STBImage.stbi_set_flip_vertically_on_load(true);
        IntBuffer w = BufferUtils.createIntBuffer(1);
        IntBuffer h = BufferUtils.createIntBuffer(1);
        IntBuffer channels = BufferUtils.createIntBuffer(1);
        File file = new File("res/" + resourceName);
        String filePath = file.getAbsolutePath();
        ByteBuffer image = STBImage.stbi_load(filePath, w, h, channels, 4);
        if (image == null) {
            System.out.println("Can't load file " + resourceName + " " + STBImage.stbi_failure_reason());
        }
        int width = w.get(0);
        int height = h.get(0);
        // Defer GL upload to Texture.initGL()
        return new Texture(resourceName, image, width, height);
    }

    @Override
    public float startTime() {
        return IxdarWindow.startTime;
    }
}
