package ixdar.platform.gl;

import java.io.IOException;
import java.util.function.Consumer;

import ixdar.graphics.render.Texture;
import ixdar.graphics.render.text.FontAtlasDTO;
import ixdar.platform.file.TextFile;

public interface Platform {

    void setTitle(String title);

    int getWindowWidth();

    int getWindowHeight();

    void requestRepaint();

    float timeSeconds();

    void setKeyCallback(KeyCallback callback);

    void setCharCallback(CharCallback callback);

    void setCursorPosCallback(CursorPosCallback callback);

    void setMouseButtonCallback(MouseButtonCallback callback);

    void setScrollCallback(ScrollCallback callback);

    FontAtlasDTO parseFontAtlas(String json);

    void exit(int code);

    void loadTexture(String resourceName, int platformId, Consumer<Texture> callback);


    void loadSourceAsync(String resourceFolder, String filename, int platformId, Consumer<String> callback);

    void loadShaderSourceAsync(String resourceFolder, String filename, int platformId, Consumer<String> callback);

    TextFile loadFile(String path) throws IOException;

    void writeTextFile(TextFile path, boolean append) throws IOException;

    interface KeyCallback {
        void onKey(int key, int scancode, int action, int mods);
    }

    interface CharCallback {
        void onChar(int codepoint);
    }

    interface CursorPosCallback {
        void onMousePos(long window, double x, double y);
    }

    interface MouseButtonCallback {
        void onMouseButton(int button, int action, int mods);
    }

    interface ScrollCallback {
        void onScroll(double xoffset, double yoffset);
    }

    float startTime();

    void log(String msg);

    boolean canHotReload();

    IxBuffer allocateFloats(int i);

    void setFrameBufferSize(float f, float g);

    int getFrameBufferWidth();

    int getFrameBufferHeight();

    int getPlatformID();

    void setPlatformID(Integer p);
}
