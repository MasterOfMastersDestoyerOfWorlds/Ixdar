package shell.render.color;

import java.util.HashMap;

import org.joml.Vector3f;
import org.joml.Vector4f;

public class ColorFixedLerp implements Color {

    static HashMap<Color, ColorLerp> flashColors = new HashMap<>();

    public Color startColor;
    public Color endColor;
    public byte[] channelLerp = { 1, 1, 1, 0 };
    public float radsPerSecond = 6f;

    private String name;

    private float offset;

    public ColorFixedLerp(Color startColor, Color endColor, float offset) {
        this.startColor = startColor;
        this.endColor = endColor;
        this.offset = offset;
        this.radsPerSecond = 0f;
        this.name = startColor.getName() + "-" + endColor.getName() + "-Lerp";
    }

    /**
     * Returns the color as a (x,y,z)-Vector.
     *
     * @return The color as vec3.
     */
    @Override
    public Vector3f toVector3f() {
        Vector3f vec = startColor.toVector3f();
        float r = vec.x * (1 - offset * channelLerp[0]);
        float g = vec.y * (1 - offset * channelLerp[1]);
        float b = vec.z * (1 - offset * channelLerp[2]);
        return new Vector3f(r, g, b);
    }

    /**
     * Returns the color as a (x,y,z,w)-Vector.
     *
     * @return The color as vec4.
     */
    @Override
    public Vector4f toVector4f() {
        Vector4f vec = startColor.toVector4f();
        Vector4f other = endColor.toVector4f();
        Vector4f lerp = new Vector4f(vec);

        lerp.x = Math.fma(other.x() - lerp.x, offset * channelLerp[0], lerp.x);
        lerp.y = Math.fma(other.y() - lerp.y, offset * channelLerp[1], lerp.y);
        lerp.z = Math.fma(other.z() - lerp.z, offset * channelLerp[2], lerp.z);
        lerp.w = Math.fma(other.w() - lerp.w, offset * channelLerp[3], lerp.w);

        return lerp;
    }

    @Override
    public String getName() {
        return name;
    }
}
