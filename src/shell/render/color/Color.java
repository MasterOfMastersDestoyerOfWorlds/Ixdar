package shell.render.color;

import org.joml.Vector3f;
import org.joml.Vector4f;

public interface Color {
    public static final Color WHITE = new ColorRGB(1f, 1f, 1f);
    public static final Color BLACK = new ColorRGB(0f, 0f, 0f);
    public static final Color RED = new ColorRGB(1f, 0f, 0f);
    public static final Color GREEN = new ColorRGB(0f, 1f, 0f);
    public static final Color BLUE = new ColorRGB(0f, 0f, 1f);
    public static final Color NAVY = new ColorRGB(5, 37, 53);
    public static final Color BLUE_WHITE = new ColorRGB(98, 142, 166);
    public static final Color CYAN = new ColorRGB(0, 255, 255);
    public static final Color IXDAR = new ColorRGB(150, 0, 36);
    public static final Color TRANSPARENT = new ColorRGB(0, 0, 0, 0);
    public static final Color YELLOW = new ColorRGB(0, 255, 255);
    public Vector3f toVector3f();
    public Vector4f toVector4f();

}
