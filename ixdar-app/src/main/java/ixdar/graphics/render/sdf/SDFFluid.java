package ixdar.graphics.render.sdf;

import org.joml.Vector2f;

import ixdar.graphics.render.Clock;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorLerp;
import ixdar.graphics.render.shaders.ShaderProgram.ShaderType;

public class SDFFluid extends ShaderDrawable {
    public static final String TEXTURE_PIXEL_SIZE = "TEXTURE_PIXEL_SIZE";
    public static final float NUM_1 = 1f;
    public static final int NUM_3 = 3;
    public static final float NUM_0 = 0f;
    public static final float NUM_0_33 = 0.33f;
    public static final float NUM_0_27 = 0.27f;
    public static final float NUM_0_12 = 0.12f;
    public static final float NUM_2 = 2f;
    public static final float NUM_0_1 = 0.1f;
    public static final float NUM_40000 = 40000f;

    /**
     * Bind the fluid (animated swirl) SDF shader.
     */
    public SDFFluid() {
        shader = ShaderType.Fluid.getShader();
    }

    /**
     * Push fluid effect uniforms (palette, spin, contrast, time, aspect-aware
     * pixel size) for the current frame.
     */
    protected void setUniforms() {
        shader.setBool("polar_coordinates", false); // cool polar coordinates effect
        shader.setVec2("polar_center", new Vector2f(NUM_1));
        shader.setFloat("polar_zoom", NUM_1);
        shader.setFloat("polar_repeat", NUM_1);
        if (width > height) {
            shader.setVec2(TEXTURE_PIXEL_SIZE, new Vector2f(1, height / width));
        } else {
            shader.setVec2(TEXTURE_PIXEL_SIZE, new Vector2f(width / height, 1));
        }
        shader.setFloat("TIME", Clock.time());
        shader.setFloat("spin_rotation", 1);
        shader.setFloat("spin_speed", NUM_3);
        shader.setVec2("offset", new Vector2f(NUM_0, NUM_0));
        shader.setVec4("colour_1", new ColorLerp(Color.PURPLE, Color.NAVY, NUM_0_33).toVector4f());
        shader.setVec4("colour_2", new ColorLerp(Color.IXDAR, Color.LIGHT_NAVY, NUM_0_27).toVector4f());
        shader.setVec4("colour_3", new ColorLerp(Color.DARK_IXDAR, Color.DARK_PURPLE, NUM_0_12).toVector4f());
        shader.setFloat("contrast", NUM_2);
        shader.setFloat("spin_amount", NUM_0_1);
        shader.setFloat("pixel_filter", NUM_40000);
    }
}