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
     * Build an SDF circle bound to the {@code CircleSDF} shader, with a
     * default border thickness of 0.15 (in normalized SDF units).
     */
    public SDFCircle() {
        shader = ShaderType.CircleSDF.getShader();
        this.borderThickness = NUM_0_15;
    }

    /**
     * Draw a circle of {@code circleRadius} centered on {@code pA}. The
     * underlying SDF shader rasterizes inside a square quad sized to enclose
     * the full circle.
     *
     * @param pA world-space center of the circle, also passed as the
     *           {@code pointA} uniform
     * @param circleRadius half-side of the bounding quad and effective radius
     * @param c border color (the SDF shader interprets this as
     *          {@code borderColor})
     * @param camera camera supplying transform and z-index
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
     * Push circle-specific uniforms: border thickness/color, the SDF center,
     * a time-driven {@code phase} for the border animation, edge-distance
     * and edge-sharpness terms scaled by the camera, and the quad extents.
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