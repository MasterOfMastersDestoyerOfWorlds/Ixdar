package shell.platform;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import shell.render.text.FontAtlasDTO;

public interface Platform {

    // Window/loop
    void setTitle(String title);

    int getWindowWidth();

    int getWindowHeight();

    void requestRepaint();

    // Time
    float timeSeconds();

    // Input registration (platform-specific)
    void setKeyCallback(KeyCallback callback);

    void setCharCallback(CharCallback callback);

    void setCursorPosCallback(CursorPosCallback callback);

    void setMouseButtonCallback(MouseButtonCallback callback);

    void setScrollCallback(ScrollCallback callback);

    // JSON parsing (platform-specific implementation)
    FontAtlasDTO parseFontAtlas(String json);

    // Texture loading (platform-specific)
    shell.render.Texture loadTexture(String resourceName);

    // Exit/application control
    void exit(int code);

    // Shader source loading (platform-specific)
    String loadShaderSource(String filename) throws UnsupportedEncodingException, IOException;

    // Callbacks
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
}
