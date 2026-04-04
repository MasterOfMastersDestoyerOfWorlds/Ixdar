package ixdar.platform.gl.headless;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.google.gson.Gson;

import ixdar.graphics.render.Texture;
import ixdar.graphics.render.text.FontAtlasDTO;
import ixdar.platform.file.FileManagement;
import ixdar.platform.file.TextFile;
import ixdar.platform.gl.IxBuffer;
import ixdar.platform.gl.Platform;

/**
 * Headless platform for testing without GLFW/OpenGL. Supports file operations
 * needed for unit tests.
 */
public class HeadlessPlatform implements Platform {

    private int platformId;
    private float startTime;
    private int windowWidth = 800;
    private int windowHeight = 600;
    private int frameBufferWidth = 800;
    private int frameBufferHeight = 600;

    public HeadlessPlatform() {
        this.startTime = (float) (System.nanoTime() / 1e9);
    }

    @Override
    public IxBuffer allocateFloats(int capacity) {
        return new HeadlessBuffer(capacity);
    }

    @Override
    public void setTitle(String title) {
        // no-op
    }

    @Override
    public int getWindowWidth() {
        return windowWidth;
    }

    @Override
    public int getWindowHeight() {
        return windowHeight;
    }

    @Override
    public void requestRepaint() {
        // no-op
    }

    @Override
    public float timeSeconds() {
        return (float) (System.nanoTime() / 1e9);
    }

    @Override
    public void setKeyCallback(KeyCallback callback) {
        // no-op
    }

    @Override
    public void setCharCallback(CharCallback callback) {
        // no-op
    }

    @Override
    public void setCursorPosCallback(CursorPosCallback callback) {
        // no-op
    }

    @Override
    public void setMouseButtonCallback(MouseButtonCallback callback) {
        // no-op
    }

    @Override
    public void setScrollCallback(ScrollCallback callback) {
        // no-op
    }

    @Override
    public FontAtlasDTO parseFontAtlas(String json) {
        return new Gson().fromJson(json, FontAtlasDTO.class);
    }

    @Override
    public void loadTexture(String resourceName, int platformId, Consumer<Texture> callback) {
        // no-op for headless
    }

    @Override
    public float startTime() {
        return startTime;
    }

    @Override
    public void exit(int code) {
        // no-op for tests
    }

    @Override
    public String trySyncLoadSource(String resourceFolder, String filename) {
        try {
            String path = resourceFolder + "/" + filename;
            try (InputStream in = HeadlessPlatform.class.getClassLoader().getResourceAsStream(path)) {
                if (in == null) {
                    return null;
                }
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void loadSourceAsync(String resourceFolder, String filename, int platformId, Consumer<String> callback) {
        String sync = trySyncLoadSource(resourceFolder, filename);
        if (sync != null) {
            callback.accept(sync);
            return;
        }
        callback.accept("");
    }

    @Override
    public void loadShaderSourceAsync(String resourceFolder, String filename, int platformId,
            Consumer<String> callback) {
        loadSourceAsync("glsl", filename, platformId, callback);
    }

    @Override
    public TextFile loadFile(String path) throws IOException {
        path = path.replaceAll("./src/main/resources/", "");
        InputStream in = FileManagement.class.getClassLoader().getResourceAsStream(path);
        if (in == null) {
            String alt = path;
            if (!alt.startsWith("src/")) {
                alt = "src/" + path;
            }
            in = FileManagement.class.getClassLoader().getResourceAsStream(alt);
        }
        if (in == null) {
            throw new IOException("File not found: " + path);
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        ArrayList<String> lines = new ArrayList<>(reader.lines().collect(Collectors.toList()));
        return new TextFile(path, lines);
    }

    @Override
    public TextFile loadExternalFile(String absolutePath) throws IOException {
        Path path = Path.of(absolutePath);
        if (!Files.exists(path)) {
            throw new IOException("External asset file not found: " + absolutePath);
        }
        ArrayList<String> lines = new ArrayList<>(Files.readAllLines(path));
        return new TextFile(path.toString(), lines);
    }

    @Override
    public void writeTextFile(TextFile file, boolean append) throws IOException {
        File newFile = new File(file.getPath());
        File parent = newFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
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

    @Override
    public boolean canHotReload() {
        return false;
    }

    @Override
    public void setFrameBufferSize(float width, float height) {
        this.frameBufferWidth = (int) width;
        this.frameBufferHeight = (int) height;
    }

    @Override
    public int getFrameBufferWidth() {
        return frameBufferWidth;
    }

    @Override
    public int getFrameBufferHeight() {
        return frameBufferHeight;
    }

    @Override
    public int getPlatformID() {
        return platformId;
    }

    @Override
    public void setPlatformID(Integer p) {
        this.platformId = p == null ? -1 : p.intValue();
    }

    @Override
    public void processInputQueue() {
        // no-op for headless
    }
}
