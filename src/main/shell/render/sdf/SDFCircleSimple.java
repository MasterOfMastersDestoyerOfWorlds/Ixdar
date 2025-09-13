package shell.render.sdf;

import org.joml.Vector2f;

import shell.cameras.Camera;
import shell.render.color.Color;
import shell.render.shaders.ShaderProgram;
import shell.render.shaders.ShaderProgram.ShaderType;
import shell.ui.scenes.ShaderCodePane;

public class SDFCircleSimple implements ShaderCodePane.UniformProvider {

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

        float edgeDist = 1.0f;
        float edgeSharpness = edgeDist / (32 * edgeDist * camera.getScaleFactor());
        shader.setFloat("edgeDist", edgeDist);
        shader.setFloat("edgeSharpness", edgeSharpness);
        shader.setFloat("radius", circleRadius);

        // cache last used uniforms for code pane
        this.lastPointA = new Vector2f(pA);
        this.lastRadius = circleRadius;
        this.lastEdgeDist = edgeDist;
        this.lastEdgeSharpness = edgeSharpness;

        shader.begin();
        shader.drawSDFRegion(bL.x, bL.y, bR.x, bR.y, tL.x, tL.y, tR.x, tR.y, camera.getZIndex(), 0, 0, 1, 1,
                Color.BLUE_WHITE);
        shader.end();
        camera.incZIndex();
    }

    // Cached uniforms for UniformProvider
    private Vector2f lastPointA = new Vector2f();
    private float lastRadius = 0f;
    private float lastEdgeDist = 0f;
    private float lastEdgeSharpness = 0f;

    @Override
    public void populateEnv(java.util.Map<String, Double> env) {
        env.put("pointA_x", (double) lastPointA.x);
        env.put("pointA_y", (double) lastPointA.y);
        env.put("radius", (double) lastRadius);
        env.put("edgeDist", (double) lastEdgeDist);
        env.put("edgeSharpness", (double) lastEdgeSharpness);
    }

    @Override
    public String describe(java.util.Map<String, Double> env) {
        return "";
    }

}