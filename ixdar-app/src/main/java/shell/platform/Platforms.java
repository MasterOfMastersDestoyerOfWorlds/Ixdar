package shell.platform;

import java.util.HashMap;

import shell.platform.gl.GL;
import shell.platform.gl.Platform;

public final class Platforms {

    private static Platform instance;

    private static GL glInstance;
    private static HashMap<Integer, Platform> platformMap = new HashMap<>();
    private static HashMap<Integer, GL> glMap = new HashMap<>();

    private Platforms() {
    }

    public static void init(Platform platform, GL gl) {
        instance = platform;
        glInstance = gl;
        Integer p = gl.getPlatformID();
        platform.setPlatformID(p);
        gl.setPlatformID(p);
        platformMap.put(p, platform);
        glMap.put(p, gl);
    }

    public static void init(Integer p) {
        if (!platformMap.containsKey(p)) {
            throw new IllegalStateException("Platform not initialized");
        }
        instance = platformMap.get(p);
        glInstance = glMap.get(p);
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
