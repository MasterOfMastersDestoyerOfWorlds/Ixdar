package ixdar.graphics.render.color;

import org.joml.Vector2f;

import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.graphics.render.shaders.ShaderProgram.ShaderType;

public class ColorBox {

    public ShaderProgram shader;

    /**
     * Construct a ColorBox bound to the shared {@code Color} shader program.
     * The shader is reused across all instances; no GPU state is owned here.
     */
    public ColorBox() {
        shader = ShaderType.Color.getShader();
    }

    float lengthSq(Vector2f a, Vector2f b) {
        Vector2f r = new Vector2f(a).sub(b);
        return r.x * r.x + r.y * r.y;
    }

    /**
     * Draw a solid-color rectangle defined by two opposite corners, using the
     * camera's current Z index. Advances the camera's Z index after drawing.
     *
     * @param drawX1 first corner x in camera-space coordinates
     * @param drawY1 first corner y in camera-space coordinates
     * @param drawX2 opposite corner x in camera-space coordinates
     * @param drawY2 opposite corner y in camera-space coordinates
     * @param c fill color
     * @param camera camera supplying the Z index and projection
     */
    public void drawCoords(float drawX1, float drawY1, float drawX2, float drawY2, Color c, Camera camera) {

        shader.begin();
        shader.drawColorRegion(drawX1, drawY1, drawX2, drawY2, camera.getZIndex(), c);
        shader.end();
        camera.incZIndex();
    }

    /**
     * Draw a solid-color rectangle anchored at its bottom-left corner with the
     * given size, using the camera's current Z index. Advances the camera's Z
     * index after drawing.
     *
     * @param nomalizedPosX bottom-left x in camera-space coordinates
     * @param nomalizedPosY bottom-left y in camera-space coordinates
     * @param width rectangle width
     * @param height rectangle height
     * @param c fill color
     * @param camera camera supplying the Z index and projection
     */
    public void draw(float nomalizedPosX, float nomalizedPosY, float width, float height, Color c, Camera camera) {

        shader.begin();
        shader.drawColorRegion(nomalizedPosX, nomalizedPosY, nomalizedPosX + width, nomalizedPosY + height,
                camera.getZIndex(), c);
        shader.end();
        camera.incZIndex();
    }

    /**
     * Draw a solid-color rectangle centered on {@code (drawX, drawY)}.
     * Equivalent to {@link #draw(float, float, float, float, Color, Camera)}
     * with the bottom-left shifted by half the size.
     *
     * @param drawX center x in camera-space coordinates
     * @param drawY center y in camera-space coordinates
     * @param width rectangle width
     * @param height rectangle height
     * @param c fill color
     * @param camera camera supplying the Z index and projection
     */
    public void drawCentered(int drawX, int drawY, int width, int height, Color c, Camera camera) {
        draw(drawX - (width / 2), drawY - (height / 2), width, height, c, camera);
    }

    /**
     * Fill the camera's full viewport with {@code c}, useful for background
     * clears or overlays.
     *
     * @param c fill color
     * @param camera camera supplying the viewport extents
     */
    public void draw(Color c, Camera camera) {
        draw(0, 0, camera.getWidth(), camera.getHeight(), c, camera);
    }

}