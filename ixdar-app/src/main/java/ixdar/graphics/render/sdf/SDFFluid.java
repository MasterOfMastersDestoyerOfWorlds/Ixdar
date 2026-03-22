package ixdar.graphics.render.sdf;

import org.joml.Vector2f;

import ixdar.graphics.render.Clock;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorLerp;
import ixdar.graphics.render.shaders.ShaderProgram.ShaderType;

public class SDFFluid extends ShaderDrawable {

    public SDFFluid() {
        shader = ShaderType.Fluid.getShader();
    }

    protected void setUniforms() {
        shader.setBool("polar_coordinates", false); // cool polar coordinates effect
        shader.setVec2("polar_center", new Vector2f(1f));
        shader.setFloat("polar_zoom", 1f);
        shader.setFloat("polar_repeat", 1f);
        if (width > height) {
            shader.setVec2("TEXTURE_PIXEL_SIZE", new Vector2f(1, height / width));
        } else {
            shader.setVec2("TEXTURE_PIXEL_SIZE", new Vector2f(width / height, 1));
        }
        shader.setFloat("TIME", Clock.time());
        shader.setFloat("spin_rotation", 1);
        shader.setFloat("spin_speed", 3);
        shader.setVec2("offset", new Vector2f(0f, 0f));
        shader.setVec4("colour_1", new ColorLerp(Color.PURPLE, Color.NAVY, 0.33f).toVector4f());
        shader.setVec4("colour_2", new ColorLerp(Color.IXDAR, Color.LIGHT_NAVY, 0.27f).toVector4f());
        shader.setVec4("colour_3", new ColorLerp(Color.DARK_IXDAR, Color.DARK_PURPLE, 0.12f).toVector4f());
        shader.setFloat("contrast", 2f);
        shader.setFloat("spin_amount", 0.1f);
        shader.setFloat("pixel_filter", 40000f);
    }
}