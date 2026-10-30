package ixdar.graphics.render.sdf;

import org.joml.Vector2f;

import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.shaders.ShaderProgram.ShaderType;

/**
 * An anti-aliased filled disc drawn by the {@code CircleSDF} shader, optionally with a border band
 * of its own colour along the inside of its edge. Distances are measured in radii.
 */
public class SDFCircle extends ShaderDrawable {
    public final Vector2f circleCenter = new Vector2f();
    public float radius;
    public float edgeDist = 1f;
    public float borderThickness;
    public Color borderColor = Color.TRANSPARENT;

    /**
     * Bind the circle SDF shader.
     */
    public SDFCircle() {
        shader = ShaderType.CircleSDF.getShader();
    }

    /**
     * Draw a disc of {@code radius} around {@code center} filled with {@code c}.
     *
     * @param center circle center in world coordinates
     * @param radius circle radius in world units
     * @param c fill color
     * @param camera camera providing transform and z-index
     */
    public void draw(Vector2f center, float radius, Color c, Camera camera) {
        draw(center, radius, c, c, camera);
    }

    /**
     * Draw a disc of {@code radius} around {@code center}, its outer {@link #borderThickness}
     * radii in {@code border} and the rest in {@code fill}.
     *
     * @param center circle center in world coordinates
     * @param radius circle radius in world units
     * @param fill fill color
     * @param border border band color, also blended into the anti-aliased rim
     * @param camera camera providing transform and z-index
     */
    public void draw(Vector2f center, float radius, Color fill, Color border, Camera camera) {
        this.circleCenter.set(center);
        this.radius = radius;
        this.c = fill;
        this.borderColor = border;
        draw(camera);
    }

    /**
     * Build the bounding quad as the axis-aligned square around the circle.
     */
    @Override
    public void calculateQuad() {
        topRight = new Vector2f(circleCenter).add(radius, radius);
        bottomRight = new Vector2f(circleCenter).add(radius, -radius);
        topLeft = new Vector2f(circleCenter).add(-radius, radius);
        bottomLeft = new Vector2f(circleCenter).add(-radius, -radius);
    }

    /**
     * Push the center, radius, edge and border band uniforms; the shader ramps the edge over one pixel.
     */
    @Override
    protected void setUniforms() {
        shader.setVec2("pointA", circleCenter);
        shader.setFloat("radius", radius);
        shader.setFloat("edgeDist", edgeDist);
        shader.setFloat("borderThickness", borderThickness);
        shader.setVec4("borderColor", borderColor.toVector4f());
    }
}
