package ixdar.graphics.render.sdf;

import org.joml.Vector2f;

import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.Clock;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.shaders.ShaderProgram.ShaderType;

public class SDFCircle extends ShaderDrawable {

    private float borderThickness;
    private Vector2f pA;

    public SDFCircle() {
        shader = ShaderType.CircleSDF.getShader();
        this.borderThickness = 0.15f;
    }

    public void draw(Vector2f pA, float circleRadius, Color c, Camera camera) {

        this.pA = pA;
        this.c = c;
        topRight = new Vector2f(pA).add(circleRadius, circleRadius);
        bottomRight = new Vector2f(pA).add(circleRadius, -circleRadius);
        topLeft = new Vector2f(pA).add(-circleRadius, circleRadius);
        bottomLeft = new Vector2f(pA).add(-circleRadius, -circleRadius);
        draw(camera);
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