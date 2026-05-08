package ixdar.graphics.render.sdf;

import org.joml.Vector2f;

import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.shaders.ShaderProgram.ShaderType;

public class SDFCircleSimple extends ShaderDrawable {

    public Vector2f center;
    public float radius;

    /**
     * Bind the simple circle SDF shader.
     */
    public SDFCircleSimple() {
        shader = ShaderType.CircleSDFSimple.getShader();
    }

    /**
     * Store the circle parameters and submit the draw.
     *
     * @param center circle center in world coordinates
     * @param radius circle radius in world units
     * @param c fill color
     * @param camera camera providing transform and z-index
     */
    public void draw(Vector2f center, float radius, Color c, Camera camera) {
        this.radius = radius;
        this.center = center;
        this.c = c;
        draw(camera);
    }

    /**
     * Build the bounding quad as the axis-aligned square inscribing the circle.
     */
    @Override
    public void calculateQuad() {
        topRight = new Vector2f(center).add(radius, radius);
        bottomRight = new Vector2f(center).add(radius, -radius);
        topLeft = new Vector2f(center).add(-radius, radius);
        bottomLeft = new Vector2f(center).add(-radius, -radius);
    }

    /**
     * Push the center, radius, and zoom-adjusted edge sharpness uniforms.
     */
    @Override
    protected void setUniforms() {
        float edgeDist = 1.0f;
        float edgeSharpness = edgeDist / (2 * edgeDist * camera.getScaleFactor());
        shader.setVec2("pointA", center);
        shader.setFloat("edgeDist", edgeDist);
        shader.setFloat("edgeSharpness", edgeSharpness);
        shader.setFloat("radius", radius);
    }
}