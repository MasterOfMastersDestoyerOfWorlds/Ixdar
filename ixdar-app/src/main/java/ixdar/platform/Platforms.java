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
     * Register {@code platform} + {@code gl} under the GL's platform ID and make them current.
     * Used at startup so subsequent {@link #get()}/{@link #gl()} calls resolve to this pairing.
     *
     * @param platform windowing / OS adapter to register
     * @param gl GL backend providing the platform ID; both sides are stamped with that ID
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
     * Switch the current platform/GL pair to a previously registered one (web supports multiple
     * canvases; this picks which one input/render calls bind to).
     *
     * @param p platform ID previously registered via {@link #init(Platform, GL)}
     * @throws IllegalStateException if {@code p} was never registered
     */
    public static void init(Integer p) {
        if (!platformMap.containsKey(p)) {
            throw new IllegalStateException(PLATFORM_NOT_INITIALIZED);
        }
        instance = platformMap.get(p);
        glInstance = glMap.get(p);
    }

    /**
     * Currently active platform adapter.
     *
     * @throws IllegalStateException if {@link #init} has not run yet
     * @return the registered {@link Platform}
     */
    public static Platform get() {
        if (instance == null) {
            throw new IllegalStateException(PLATFORM_NOT_INITIALIZED);
        }
        return instance;
    }

    /**
     * Currently active GL backend.
     *
     * @throws IllegalStateException if {@link #init} has not run yet
     * @return the registered {@link GL}
     */
    public static GL gl() {
        if (glInstance == null) {
            throw new IllegalStateException("GL not initialized");
        }
        return glInstance;
    }

}
