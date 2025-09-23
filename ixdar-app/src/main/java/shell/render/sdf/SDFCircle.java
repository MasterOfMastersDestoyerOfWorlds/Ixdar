package shell.render.sdf;

import org.joml.Vector2f;

import shell.cameras.Camera;
import shell.render.Clock;
import shell.render.color.Color;
import shell.render.shaders.ShaderProgram.ShaderType;

public class SDFCircle extends ShaderDrawable {

    private float borderThickness;
    private Vector2f pA;

    public SDFCircle() {
        shader = ShaderType.CircleSDF.getShader();
        this.borderThickness = 0.15f;
    }

    public void draw(Vector2f pA, float circleRadius, Color c, Camera camera) {
        draw(pA, circleRadius, c, 0L, camera);
    }

    public void draw(Vector2f pA, float circleRadius, Color c, long id, Camera camera) {

        this.pA = pA;
        this.c = c;
        topRight = new Vector2f(pA).add(circleRadius, circleRadius);
        bottomRight = new Vector2f(pA).add(circleRadius, -circleRadius);
        topLeft = new Vector2f(pA).add(-circleRadius, circleRadius);
        bottomLeft = new Vector2f(pA).add(-circleRadius, -circleRadius);
        draw(camera, id);
    }

    @Override
    protected void setUniforms() {
        shader.setFloat("borderThickness", borderThickness);
        shader.setVec4("borderColor", c.toVector4f());
        shader.setVec2("pointA", pA);
        shader.setFloat("phase", Clock.spin(20));
        float edgeDist = 0.35f;
        shader.setFloat("edgeDist", edgeDist);
        shader.setFloat("edgeSharpness", edgeDist / (8 * edgeDist * camera.getScaleFactor()));

        shader.setVec2("pointA", pA);
        shader.setFloat("width", width);
        shader.setFloat("height", height);
    }

}