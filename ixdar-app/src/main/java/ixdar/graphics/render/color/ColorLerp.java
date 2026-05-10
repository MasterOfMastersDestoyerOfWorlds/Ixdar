
package ixdar.graphics.render.color;

import java.util.HashMap;

import org.joml.Vector3f;
import org.joml.Vector4f;
import ixdar.graphics.render.Clock;

import ixdar.common.utils.Compat;

public class ColorLerp implements Color {
    public static final int NUM_3 = 3;

    static HashMap<Color, ColorLerp> flashColors = new HashMap<>();

    public Color startColor;
    public Color endColor;
    public byte[] channelLerp = { 1, 1, 1, 0 };
    public float radsPerSecond = 6f;

    private String name;

    /**
     * Animated color that swings between {@code startColor} and {@code endColor}
     * on every channel (default RGB-only) at the given oscillator rate.
     *
     * @param startColor color at the start of each oscillation cycle.
     * @param endColor color at the peak of each oscillation cycle.
     * @param radsPerSecond angular frequency passed to {@link Clock#oscillate}.
     */
    public ColorLerp(Color startColor, Color endColor, float radsPerSecond) {
        this.startColor = startColor;
        this.endColor = endColor;
        this.radsPerSecond = radsPerSecond;
        lerpName(startColor, endColor);
    }

    /**
     * Animated lerp between {@code startColor} and {@code endColor} with both
     * endpoints rebuilt at a fixed alpha. Useful for fade-friendly variants
     * of opaque palette colors.
     *
     * @param startColor color at the start of each oscillation cycle.
     * @param endColor color at the peak of each oscillation cycle.
     * @param radsPerSecond angular frequency passed to {@link Clock#oscillate}.
     * @param alpha shared alpha applied to both endpoints. Range from 0f to 1f.
     */
    public ColorLerp(Color startColor, Color endColor, float radsPerSecond, float alpha) {
        this.startColor = new ColorRGB(startColor, alpha);
        this.endColor = new ColorRGB(endColor, alpha);
        this.radsPerSecond = radsPerSecond;
        lerpName(startColor, endColor);
    }

    /**
     * Animated lerp with a per-channel mask: a 1 in {@code channelLerp[i]}
     * lets channel i animate, a 0 freezes it at the start value.
     *
     * @param startColor color at the start of each oscillation cycle.
     * @param endColor color at the peak of each oscillation cycle.
     * @param channelLerp four-byte RGBA mask (1 = animate, 0 = hold).
     */
    public ColorLerp(Color startColor, Color endColor, byte[] channelLerp) {
        this.startColor = startColor;
        this.endColor = endColor;
        this.channelLerp = channelLerp;
        lerpName(startColor, endColor);
    }

    /**
     * Animated lerp with both a per-channel mask and a custom rate.
     *
     * @param startColor color at the start of each oscillation cycle.
     * @param endColor color at the peak of each oscillation cycle.
     * @param channelLerp four-byte RGBA mask (1 = animate, 0 = hold).
     * @param radsPerSecond angular frequency passed to {@link Clock#oscillate}.
     */
    public ColorLerp(Color startColor, Color endColor, byte[] channelLerp, float radsPerSecond) {
        this.startColor = startColor;
        this.endColor = endColor;
        this.channelLerp = channelLerp;
        this.radsPerSecond = radsPerSecond;
        lerpName(startColor, endColor);
    }

    private void lerpName(Color startColor, Color endColor) {
        this.name = startColor.getName() + "-" + endColor.getName() + "-Lerp";
    }

    /**
     * Cached lerp that flashes the alpha of {@code c} between full opacity
     * and {@link Color#TRANSPARENT25}. Subsequent calls with the same
     * {@code c} return the same instance so callers share its phase.
     *
     * @param c color whose alpha should pulse.
     * @param radsPerSecond angular frequency of the flash.
     * @return the cached alpha-only lerp for {@code c}.
     */
    public static ColorLerp flashColor(Color c, float radsPerSecond) {
        flashColors.putIfAbsent(c, new ColorLerp(c, Color.TRANSPARENT25, new byte[] { 0, 0, 0, 1 }, radsPerSecond));
        return flashColors.get(c);
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

        lerp.x = Compat.fmaf(other.x() - lerp.x, occ * channelLerp[0], lerp.x);
        lerp.y = Compat.fmaf(other.y() - lerp.y, occ * channelLerp[1], lerp.y);
        lerp.z = Compat.fmaf(other.z() - lerp.z, occ * channelLerp[2], lerp.z);
        lerp.w = Compat.fmaf(other.w() - lerp.w, occ * channelLerp[NUM_3], lerp.w);

        return lerp;
    }

    /**
     * Auto-generated label of the form
     * {@code "<startName>-<endName>-Lerp"}, set during construction.
     *
     * @return composite name of the two endpoints.
     */
    @Override
    public String getName() {
        return name;
    }

}
