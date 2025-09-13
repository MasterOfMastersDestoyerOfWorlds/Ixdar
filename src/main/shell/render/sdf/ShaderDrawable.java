package shell.render.sdf;

import java.util.Map;
import java.util.Map.Entry;

import org.joml.Vector2f;

import shell.cameras.Camera;
import shell.platform.Platforms;
import shell.platform.gl.GL;
import shell.platform.gl.Platform;
import shell.render.color.Color;
import shell.render.shaders.ShaderProgram;

public abstract class ShaderDrawable {

    public ShaderProgram shader;

    public GL gl = Platforms.gl();
    public Platform platform = Platforms.get();
    public Camera camera;

    public float drawX;
    public float drawY;
    public float width;
    public float height;
    public Vector2f bottomLeft;
    public Vector2f bottomRight;
    public Vector2f topRight;
    public Vector2f topLeft;
    public Vector2f center;
    public Color c = Color.PINK;

    public void setup(Camera camera) {
        this.camera = camera;
        shader.use();
        shader.begin();
        calculateQuad();

        width = bottomLeft.distance(bottomRight);
        height = bottomLeft.distance(topLeft);

        center = new Vector2f(bottomLeft)
                .add(bottomRight)
                .add(topRight)
                .add(topLeft)
                .div(4f);

        setUniforms();
    }

    public void cleanup(Camera c) {
        shader.end();
        c.incZIndex();
    }

    public void cleanupFar(Camera c) {
        shader.end();
        c.decFarZIndex();
    }

    protected void setUniforms() {
        throw new UnsupportedOperationException("Unimplemented method");
    }

    public Map<String, Entry<String, Float>> getEnv() {
        return shader.uniformMap;
    }

    public void draw(float drawX, float drawY, float width, float height, Color c, Camera camera) {
        if (c != null) {
            this.c = c;
        }
        this.drawX = drawX;
        this.drawY = drawY;
        this.width = width;
        this.height = height;
        draw(camera);
    }

    public void draw(Camera camera) {
        this.camera = camera;
        setup(camera);
        shader.drawSDFRegion(bottomLeft.x, bottomLeft.y, bottomRight.x, bottomRight.y, topLeft.x, topLeft.y, topRight.x,
                topRight.y, camera.getZIndex(), 0, 0, 1, 1,
                c);
        cleanup(camera);
    }
    
    public void drawFar(Camera camera) {
        this.camera = camera;
        setup(camera);
        shader.drawSDFRegion(bottomLeft.x, bottomLeft.y, bottomRight.x, bottomRight.y, topLeft.x, topLeft.y, topRight.x,
                topRight.y, camera.getFarZIndex(), 0, 0, 1, 1,
                c);
        cleanupFar(camera);
    }

    public void calculateQuad() {
        bottomLeft = new Vector2f(drawX, drawY);
        bottomRight = new Vector2f(bottomLeft).add(width, 0);
        topLeft = new Vector2f(bottomLeft).add(0, height);
        topRight = new Vector2f(bottomLeft).add(width, height);
    }

    public void drawCentered(float drawX, float drawY, float width, float height, Color c, Camera camera) {
        draw(drawX - (width / 2), drawY - (height / 2), width, height, c, camera);
    }

    public void drawRightBound(float drawX, float drawY, float width, float height, Color c, Camera camera) {
        draw(drawX - width, drawY, width, height, c, camera);
    }
}
