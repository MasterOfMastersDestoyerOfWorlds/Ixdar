package shell.render.sdf;

import org.joml.Vector2f;

import shell.cameras.Camera;
import shell.render.color.Color;
import shell.render.shaders.ShaderProgram.ShaderType;

public class SDFCircleSimple extends ShaderDrawable {

    public Vector2f center;
    public float radius;

    public SDFCircleSimple() {
        shader = ShaderType.CircleSDFSimple.shader;
    }

    public void draw(Vector2f center, float radius, Color c, Camera camera) {
        this.radius = radius;
        this.center = center;
        this.c = c;
        draw(camera);
    }

    @Override
    public void calculateQuad() {
        topRight = new Vector2f(center).add(radius, radius);
        bottomRight = new Vector2f(center).add(radius, -radius);
        topLeft = new Vector2f(center).add(-radius, radius);
        bottomLeft = new Vector2f(center).add(-radius, -radius);
    }

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