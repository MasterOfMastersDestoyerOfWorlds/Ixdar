package ixdar.platform;

import java.util.HashMap;

import ixdar.platform.gl.GL;
import ixdar.platform.gl.Platform;

public final class Platforms {
    public static final String PLATFORM_NOT_INITIALIZED = "Platform not initialized";

    private static Platform instance;

    private static GL glInstance;
    private static HashMap<Integer, Platform> platformMap = new HashMap<>();
    private static HashMap<Integer, GL> glMap = new HashMap<>();

    private Platforms() {
    }

    /**
     * TODO: document {@code init}.
     *
     * @param platform TODO: describe
     * @param gl TODO: describe
     */
    public static void init(Platform platform, GL gl) {
        instance = platform;
        glInstance = gl;
        int p = gl.getPlatformID();
        platform.setPlatformID(p);
        gl.setPlatformID(p);
        platformMap.put(p, platform);
        glMap.put(p, gl);
    }

    /**
     * TODO: document {@code init}.
     *
     * @param p TODO: describe
     * @throws IllegalStateException TODO: describe
     */
    public static void init(Integer p) {
        if (!platformMap.containsKey(p)) {
            throw new IllegalStateException(PLATFORM_NOT_INITIALIZED);
        }
        instance = platformMap.get(p);
        glInstance = glMap.get(p);
    }

    /**
     * TODO: document {@code get}.
     *
     * @throws IllegalStateException TODO: describe
     * @return TODO: describe
     */
    public static Platform get() {
        if (instance == null) {
            throw new IllegalStateException(PLATFORM_NOT_INITIALIZED);
        }
        return instance;
    }

    /**
     * TODO: document {@code gl}.
     *
     * @throws IllegalStateException TODO: describe
     * @return TODO: describe
     */
    public static GL gl() {
        if (glInstance == null) {
            throw new IllegalStateException("GL not initialized");
        }
        return glInstance;
    }

}
