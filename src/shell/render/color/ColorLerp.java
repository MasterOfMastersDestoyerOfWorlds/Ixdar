
package shell.render.color;

import org.joml.Vector3f;
import org.joml.Vector4f;

import shell.render.Clock;

public class ColorLerp implements Color {

    public Color startColor;
    public Color endColor;
    public byte[] channelLerp = { 1, 1, 1, 0 };
    public float radsPerSecond = 6f;

    public ColorLerp(Color startColor, Color endColor) {
        this.startColor = startColor;
        this.endColor = endColor;
    }

    public ColorLerp(Color startColor, Color endColor, float alpha) {
        this.startColor = new ColorRGB(startColor, alpha);
        this.endColor = new ColorRGB(endColor, alpha);
    }

    public ColorLerp(Color startColor, Color endColor, byte[] channelLerp) {
        this.startColor = startColor;
        this.endColor = endColor;
        this.channelLerp = channelLerp;
    }

    public ColorLerp(Color startColor, Color endColor, byte[] channelLerp, float radsPerSecond) {
        this.startColor = startColor;
        this.endColor = endColor;
        this.channelLerp = channelLerp;
        this.radsPerSecond = radsPerSecond;
    }

    /**
     * Returns the color as a (x,y,z)-Vector.
     *
     * @return The color as vec3.
     */
    @Override
    public Vector3f toVector3f() {
        float occ = Clock.oscillate(1, 1, radsPerSecond);
        Vector3f vec = startColor.toVector3f();
        float r = vec.x * (1 - occ * channelLerp[0]);
        float g = vec.y * (1 - occ * channelLerp[1]);
        float b = vec.z * (1 - occ * channelLerp[2]);
        return new Vector3f(r, g, b);
    }

    /**
     * Returns the color as a (x,y,z,w)-Vector.
     *
     * @return The color as vec4.
     */
    @Override
    public Vector4f toVector4f() {
        float occ = Clock.oscillate(0, 1, radsPerSecond);
        Vector4f vec = startColor.toVector4f();
        Vector4f other = endColor.toVector4f();
        Vector4f lerp = new Vector4f(vec);

        lerp.x = Math.fma(other.x() - lerp.x, occ * channelLerp[0], lerp.x);
        lerp.y = Math.fma(other.y() - lerp.y, occ * channelLerp[1], lerp.y);
        lerp.z = Math.fma(other.z() - lerp.z, occ * channelLerp[2], lerp.z);
        lerp.w = Math.fma(other.w() - lerp.w, occ * channelLerp[3], lerp.w);

        return lerp;
    }

}
