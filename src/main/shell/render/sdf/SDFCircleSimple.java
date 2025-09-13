package shell.render.sdf;

import org.joml.Vector2f;

import shell.cameras.Camera;
import shell.render.Clock;
import shell.render.color.Color;
import shell.render.shaders.ShaderProgram;
import shell.render.shaders.ShaderProgram.ShaderType;

public class SDFCircleSimple {

    public ShaderProgram shader;
    // private Color borderColor;       

    public SDFCircleSimple() {
        shader = ShaderType.CircleSDFSimple.shader;
    }

    public void draw(Vector2f pA, float circleRadius, Color c, Camera camera) {
        shader.use();
        shader.setVec2("pointA", pA);
        Vector2f tR = new Vector2f(pA).add(circleRadius, circleRadius);
        Vector2f bR = new Vector2f(pA).add(circleRadius, -circleRadius);
        Vector2f tL = new Vector2f(pA).add(-circleRadius, circleRadius);
        Vector2f bL = new Vector2f(pA).add(-circleRadius, -circleRadius);

        float edgeDist = 0.35f;
        shader.setFloat("edgeDist", edgeDist);
        shader.setFloat("edgeSharpness", edgeDist / (32 * edgeDist * camera.getScaleFactor()));
        shader.setFloat("radius", circleRadius);

        shader.begin();
        shader.drawSDFRegion(bL.x, bL.y, bR.x, bR.y, tL.x, tL.y, tR.x, tR.y, camera.getZIndex(), 0, 0, 1, 1,
                Color.BLUE_WHITE);
        shader.end();
        camera.incZIndex();
    }

}