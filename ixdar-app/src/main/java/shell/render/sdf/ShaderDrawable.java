package shell.render.sdf;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import shell.cameras.Camera;
import shell.platform.Platforms;
import shell.platform.gl.GL;
import shell.platform.gl.Platform;
import shell.render.Texture;
import shell.render.color.Color;
import shell.render.shaders.ShaderProgram;
import shell.render.text.ColorText;
import shell.ui.code.ParseText;

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

    public Map<String, ParseText> getUniformMap() {
        Map<String, ParseText> map = new HashMap<>();
        Map<String, Object> uniformMap = shader.uniformMap;
        for (String key : uniformMap.keySet()) {
            Object value = uniformMap.get(key);
            if (value instanceof Float) {
                Float f = (Float) value;
                ParseText.put(map, key, f);
            } else if (value instanceof Vector2f) {
                Vector2f vec2 = (Vector2f) value;
                ParseText.put(map, key, vec2.x, vec2.y);
            } else if (value instanceof Vector3f) {
                Vector3f vec3 = (Vector3f) value;
                ParseText.put(map, key, vec3.x, vec3.y, vec3.z);
            } else if (value instanceof Vector4f) {
                Vector4f vec4 = (Vector4f) value;
                ParseText.put(map, key, vec4.x, vec4.y, vec4.z, vec4.w);
            } else if (value instanceof FloatBuffer) {
                // skip
            } else if (value instanceof Matrix4f) {
                // skip
            } else if (value instanceof Texture) {
                Texture texture = (Texture) value;
                map.put(key, new ParseText(texture.toString(), key));
            }

        }
        return map;
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
        if (shader == null) {
            platform.log("Shader is null");
            return;
        }
        if(shader.platformId != Platforms.gl().getID()){
            platform.log("Shader is not for the current platform");
            return;
        }
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
