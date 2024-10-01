package shell.render.color;

import shell.render.Color;
import shell.render.shaders.ShaderProgram;
import shell.render.shaders.ShaderProgram.ShaderType;

import org.joml.Vector2f;

public class ColorBox {

    public ShaderProgram shader;

    public ColorBox() {
        shader = ShaderType.Color.shader;
    }

    float lengthSq(Vector2f a, Vector2f b) {
        Vector2f r = new Vector2f(a).sub(b);
        return r.x * r.x + r.y * r.y;
    }

    public void drawCoords(float drawX1, float drawY1, float drawX2, float drawY2, float zIndex, Color c) {

        shader.begin();
        shader.drawColorRegion(drawX1, drawY1, drawX2, drawY2, zIndex, c);
        shader.end();
    }

    public void draw(int drawX, int drawY, float width, float height, float zIndex, Color c) {

        shader.begin();
        shader.drawColorRegion(drawX, drawY, drawX + width, drawY + height, zIndex, c);
        shader.end();
    }

    public void drawCentered(int drawX, int drawY, int width, int height, float zIndex, Color c) {
        draw(drawX - (width / 2), drawY - (height / 2), width, height, zIndex, c);
    }

}