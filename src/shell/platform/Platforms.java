package shell.platform;

import shell.platform.gl.GL;

public final class Platforms {

    private static Platform instance;

    private static GL glInstance;

    private Platforms() {
    }

    public static void init(Platform platform, GL gl) {
        instance = platform;
        glInstance = gl;
    }

    public static Platform get() {
        if (instance == null) {
            throw new IllegalStateException("Platform not initialized");
        }
        return instance;
    }

    public static GL gl() {
        if (glInstance == null) {
            throw new IllegalStateException("GL not initialized");
        }
        return glInstance;
    }
}
