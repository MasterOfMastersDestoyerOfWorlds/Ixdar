package shell.render.sdf;

import org.joml.Vector2f;

import shell.cameras.Camera;
import shell.render.Clock;
import shell.render.color.Color;
import shell.render.shaders.ShaderProgram;
import shell.render.shaders.ShaderProgram.ShaderType;

public class SDFFluid {

    public ShaderProgram shader;

    public SDFFluid() {
        shader = ShaderType.Fluid.shader;
    }

    public void draw(float drawX, float drawY, float width, float height, Color c, Camera camera) {
        shader.use();

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
        shader.setVec4("colour_1", Color.IXDAR.toVector4f());
        shader.setVec4("colour_2", Color.IXDAR.toVector4f());
        shader.setVec4("colour_3", Color.DARK_IXDAR.toVector4f());
        shader.setFloat("contrast", 2f);
        shader.setFloat("spin_amount", 0.1f);
        shader.setFloat("pixel_filter", 40000f);

        Vector2f pA = new Vector2f(drawX, drawY);
        Vector2f tR = new Vector2f(pA).add(width, height);
        Vector2f bR = new Vector2f(pA).add(width, 0);
        Vector2f tL = new Vector2f(pA).add(0, height);
        Vector2f bL = new Vector2f(pA).add(0, 0);

        shader.begin();

        shader.drawSDFRegion(bL.x, bL.y, bR.x, bR.y, tL.x, tL.y, tR.x, tR.y, camera.getFarZIndex(), 0, 0, 1, 1,
                Color.WHITE);

        shader.end();
        camera.decFarZIndex();
    }

    public void drawCentered(float drawX, float drawY, float width, float height, Color c, Camera camera) {
        draw(drawX - (width / 2), drawY - (height / 2), width, height, c, camera);
    }

    public void drawRightBound(float drawX, float drawY, float width, float height, Color c, Camera camera) {
        draw(drawX - width, drawY, width, height, c, camera);
    }

}