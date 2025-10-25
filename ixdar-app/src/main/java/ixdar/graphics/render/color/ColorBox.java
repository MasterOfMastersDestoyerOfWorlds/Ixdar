package ixdar.graphics.render.color;

import org.joml.Vector2f;

import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.graphics.render.shaders.ShaderProgram.ShaderType;

public class ColorBox {

    public ShaderProgram shader;

    public ColorBox() {
        shader = ShaderType.Color.getShader();
    }

    float lengthSq(Vector2f a, Vector2f b) {
        Vector2f r = new Vector2f(a).sub(b);
        return r.x * r.x + r.y * r.y;
    }

    public void drawCoords(float drawX1, float drawY1, float drawX2, float drawY2, Color c, Camera camera) {

        shader.begin();
        shader.drawColorRegion(drawX1, drawY1, drawX2, drawY2, camera.getZIndex(), c);
        shader.end();
        camera.incZIndex();
    }

    public void draw(float nomalizedPosX, float nomalizedPosY, float width, float height, Color c, Camera camera) {

        shader.begin();
        shader.drawColorRegion(nomalizedPosX, nomalizedPosY, nomalizedPosX + width, nomalizedPosY + height,
                camera.getZIndex(), c);
        shader.end();
        camera.incZIndex();
    }

    public void drawCentered(int drawX, int drawY, int width, int height, Color c, Camera camera) {
        draw(drawX - (width / 2), drawY - (height / 2), width, height, c, camera);
    }

    public void draw(Color c, Camera camera) {
        draw(0, 0, camera.getWidth(), camera.getHeight(), c, camera);
    }

}