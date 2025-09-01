package shell.platform.gl.lwjgl;

import static org.lwjgl.glfw.GLFW.glfwGetWindowSize;
import static org.lwjgl.glfw.GLFW.glfwSetCharCallback;
import static org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback;
import static org.lwjgl.glfw.GLFW.glfwSetKeyCallback;
import static org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback;
import static org.lwjgl.glfw.GLFW.glfwSetScrollCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowTitle;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

// removed duplicate IntBuffer import
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import com.google.gson.Gson;

import shell.file.FileManagement;
import shell.file.TextFile;
import shell.platform.gl.Platform;
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
    public void loadTexture(String resourceName, Consumer<Texture> callback) {
        STBImage.stbi_set_flip_vertically_on_load(true);
        IntBuffer w = BufferUtils.createIntBuffer(1);
        IntBuffer h = BufferUtils.createIntBuffer(1);
        IntBuffer channels = BufferUtils.createIntBuffer(1);
        File file = new File("src/main/resources/res/" + resourceName);
        String filePath = file.getAbsolutePath();
        ByteBuffer image = STBImage.stbi_load(filePath, w, h, channels, 4);
        if (image == null) {
            System.out.println("Can't load file " + resourceName + " " + STBImage.stbi_failure_reason());
        }
        int width = w.get(0);
        int height = h.get(0);
        // Defer GL upload to Texture.initGL()
        callback.accept(new Texture(resourceName, image, width, height));
    }

    @Override
    public float startTime() {
        return IxdarWindow.startTime;
    }

    @Override
    public void exit(int code) {
        System.exit(code);
    }

    @Override
    public String loadSource(String folder, String filename) throws IOException {
        String path = folder + "/" + filename;
        try (InputStream in = LwjglPlatform.class.getClassLoader().getResourceAsStream(path)) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Override
    public String loadShaderSource(String filename) throws IOException {
        return loadSource("glsl", filename);
    }

    @Override
    public TextFile loadFile(String path) {
        path = path.replaceAll("./src/main/resources/", "");
        InputStream in = FileManagement.class.getClassLoader().getResourceAsStream(path);
        if (in == null) {
            String alt = path;
            if (!alt.startsWith("src/")) {
                alt = "src/" + path;
            }
            in = FileManagement.class.getClassLoader().getResourceAsStream(alt);
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        ArrayList<String> lines = new ArrayList<>(reader.lines().collect(Collectors.toList()));
        return new TextFile(path, lines);
    }

    @Override
    public void writeTextFile(TextFile file, boolean append) throws IOException {
        File newFile = new File(file.getPath());
        File parent = newFile.getParentFile();
        if (parent != null)
            parent.mkdirs();
        try (FileWriter fw = new FileWriter(newFile, append);
                BufferedWriter out = new BufferedWriter(fw)) {
            for (String s : file.getLines()) {
                out.write(s);
                out.newLine();
            }
            out.flush();
        }
    }

    @Override
    public void log(String msg) {
       System.out.println(msg); 
    }
}
