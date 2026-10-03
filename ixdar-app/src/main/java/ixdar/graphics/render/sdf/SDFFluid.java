package ixdar.graphics.render.sdf;

import org.joml.Vector2f;

import ixdar.graphics.render.Clock;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorLerp;
import ixdar.graphics.render.shaders.ShaderProgram.ShaderType;

public class SDFFluid extends ShaderDrawable {
    public static final String TEXTURE_PIXEL_SIZE = "TEXTURE_PIXEL_SIZE";
    public static final int SPIN_SPEED = 3;
    public static final float COLOR_MIX_1 = 0.33f;
    public static final float COLOR_MIX_2 = 0.27f;
    public static final float COLOR_MIX_3 = 0.12f;
    public static final float CONTRAST = 2f;
    public static final float SPIN_AMOUNT = 0.1f;
    public static final float PIXEL_FILTER = 40000f;

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
        shader.setVec2("polar_center", new Vector2f(1f));
        shader.setFloat("polar_zoom", 1f);
        shader.setFloat("polar_repeat", 1f);
        if (width > height) {
            shader.setVec2(TEXTURE_PIXEL_SIZE, new Vector2f(1, height / width));
        } else {
            shader.setVec2(TEXTURE_PIXEL_SIZE, new Vector2f(width / height, 1));
        }
        shader.setFloat("TIME", Clock.time());
        shader.setFloat("spin_rotation", 1);
        shader.setFloat("spin_speed", SPIN_SPEED);
        shader.setVec2("offset", new Vector2f(0f, 0f));
        shader.setVec4("colour_1", new ColorLerp(Color.PURPLE, Color.NAVY, COLOR_MIX_1).toVector4f());
        shader.setVec4("colour_2", new ColorLerp(Color.IXDAR, Color.LIGHT_NAVY, COLOR_MIX_2).toVector4f());
        shader.setVec4("colour_3", new ColorLerp(Color.DARK_IXDAR, Color.DARK_PURPLE, COLOR_MIX_3).toVector4f());
        shader.setFloat("contrast", CONTRAST);
        shader.setFloat("spin_amount", SPIN_AMOUNT);
        shader.setFloat("pixel_filter", PIXEL_FILTER);
    }
}