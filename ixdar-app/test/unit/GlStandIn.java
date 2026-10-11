package unit;

import java.lang.reflect.Proxy;
import java.nio.IntBuffer;
import java.util.ArrayList;

import ixdar.platform.gl.GL;

/**
 * A GL that does nothing but report success, so code that uploads to the GPU (the drawing font's
 * atlas, a tool's overlay) runs in a unit test that never opens a window.
 */
public final class GlStandIn {

    private static final int STAND_IN_PLATFORM_ID = 9012;

    private GlStandIn() {
    }

    /**
     * Build the stand-in: every status query succeeds and every other call returns a neutral value.
     *
     * @return a GL proxy safe to pair with the suite's platform
     */
    public static GL create() {
        return (GL) Proxy.newProxyInstance(GL.class.getClassLoader(),
                new Class<?>[] {GL.class}, (proxy, method, arguments) -> {
                    Class<?> type = method.getReturnType();
                    for (Object argument : arguments == null ? new Object[0] : arguments) {
                        if (argument instanceof IntBuffer status) {
                            status.put(0, 1);
                        }
                    }
                    if (method.getName().equals("getPlatformID")) {
                        return STAND_IN_PLATFORM_ID;
                    }
                    if (type == int.class) {
                        return 1;
                    }
                    if (type == boolean.class) {
                        return false;
                    }
                    if (type == float.class) {
                        return 0f;
                    }
                    if (type == String.class) {
                        return "";
                    }
                    return type.isAssignableFrom(ArrayList.class) ? new ArrayList<>() : null;
                });
    }
}
