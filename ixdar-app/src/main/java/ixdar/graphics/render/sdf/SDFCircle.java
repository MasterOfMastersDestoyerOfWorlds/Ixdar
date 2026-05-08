package ixdar.graphics.render.sdf;

import org.joml.Vector2f;

import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.Clock;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.shaders.ShaderProgram.ShaderType;

public class SDFCircle extends ShaderDrawable {
    public static final String POINTA = "pointA";
    public static final float NUM_0_15 = 0.15f;
    public static final int NUM_20 = 20;
    public static final float NUM_0_35 = 0.35f;
    public static final int NUM_8 = 8;

    private float borderThickness;
    private Vector2f pA;

    /**
     * TODO: document {@code SDFCircle}.
     */
    public SDFCircle() {
        shader = ShaderType.CircleSDF.getShader();
        this.borderThickness = NUM_0_15;
    }

    /**
     * TODO: document {@code draw}.
     *
     * @param pA TODO: describe
     * @param circleRadius TODO: describe
     * @param c TODO: describe
     * @param camera TODO: describe
     */
    public void draw(Vector2f pA, float circleRadius, Color c, Camera camera) {

        this.pA = pA;
        this.c = c;
        topRight = new Vector2f(pA).add(circleRadius, circleRadius);
        bottomRight = new Vector2f(pA).add(circleRadius, -circleRadius);
        topLeft = new Vector2f(pA).add(-circleRadius, circleRadius);
        bottomLeft = new Vector2f(pA).add(-circleRadius, -circleRadius);
        draw(camera);
    }

    /**
     * TODO: document {@code setUniforms}.
     */
    @Override
    protected void setUniforms() {
        shader.setFloat("borderThickness", borderThickness);
        shader.setVec4("borderColor", c.toVector4f());
        shader.setVec2(POINTA, pA);
        shader.setFloat("phase", Clock.spin(NUM_20));
        float edgeDist = NUM_0_35;
        shader.setFloat("edgeDist", edgeDist);
        shader.setFloat("edgeSharpness", edgeDist / (NUM_8 * edgeDist * camera.getScaleFactor()));

        shader.setVec2(POINTA, pA);
        shader.setFloat("width", width);
        shader.setFloat("height", height);
    }

}