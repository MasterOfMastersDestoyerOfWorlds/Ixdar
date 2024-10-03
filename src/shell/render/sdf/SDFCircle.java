package shell.render.sdf;

import shell.cameras.Camera;
import shell.render.Clock;
import shell.render.color.Color;
import shell.render.shaders.ShaderProgram;
import shell.render.shaders.ShaderProgram.ShaderType;

import org.joml.Vector2f;

public class SDFCircle {

    public ShaderProgram shader;
    private Color borderColor;
    private float borderInner;
    private float borderOuter;
    private float borderOffsetInner;
    private float borderOffsetOuter;

    public SDFCircle() {
        shader = ShaderType.CircleSDF.shader;
        this.borderColor = Color.TRANSPARENT;
        this.borderInner = 0;
        this.borderOuter = 0;
        this.borderOffsetInner = 0;
        this.borderOffsetOuter = 0;
    }

    public void draw(Vector2f pA, Color c, Camera camera) {
        shader.use();
        shader.setFloat("borderInner", borderInner);
        shader.setFloat("borderOuter", borderOuter);
        shader.setFloat("borderOffsetInner", borderOffsetInner);
        shader.setFloat("borderOffsetOuter", borderOffsetOuter);
        shader.setVec4("borderColor", borderColor.toVector4f());
        shader.setVec2("pointA", pA);
        shader.setFloat("phase", Clock.spin(20));
        float circleRadius = 10f;
        Vector2f tR = new Vector2f(pA).add(circleRadius, circleRadius);
        Vector2f bR = new Vector2f(pA).add(circleRadius, -circleRadius);
        Vector2f tL = new Vector2f(pA).add(-circleRadius, circleRadius);
        Vector2f bL = new Vector2f(pA).add(-circleRadius, -circleRadius);

        float width = bL.distance(tL);
        float height = bL.distance(bR);

        float edgeDist = 0.25f;
        shader.setFloat("edgeDist", edgeDist);
        shader.setFloat("edgeSharpness", edgeDist / 10);

        shader.setVec2("pointA", pA);
        shader.setFloat("width", width);
        shader.setFloat("height", height);

        shader.begin();
        shader.drawSDFRegion(bL.x, bL.y, bR.x, bR.y, tL.x, tL.y, tR.x, tR.y, camera.getZIndex(), 0, 0, 1, 1, c);
        shader.end();
        camera.incZIndex();
    }

    public void setBorderDist(float borderDist) {
        this.borderInner = borderDist - 0.1f;
        this.borderOuter = borderDist;
    }

}