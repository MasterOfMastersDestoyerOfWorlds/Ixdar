package shell.render.sdf;

import org.joml.Vector2f;

import shell.cameras.Camera;
import shell.render.Clock;
import shell.render.color.Color;
import shell.render.shaders.SDFShader;
import shell.render.shaders.ShaderProgram;
import shell.render.shaders.ShaderProgram.ShaderType;

public class SDFLine {

    public ShaderProgram shader;
    private Color borderColor;
    private float borderInner;
    private float borderOuter;
    private float borderOffsetInner;
    private float borderOffsetOuter;
    public float lineWidth;
    public float dashLength;
    public boolean dashed;
    public boolean roundCaps;
    public float dashRate;

    public SDFLine() {
        shader = ShaderType.LineSDF.shader;
        this.borderColor = Color.TRANSPARENT;
        this.borderInner = 0;
        this.borderOuter = 0;
        this.borderOffsetInner = 0;
        this.borderOffsetOuter = 0;
    }

    public SDFLine(SDFShader sdfShader, Color borderColor,
            float borderDist, float borderOffset) {
        shader = sdfShader;
        this.borderColor = borderColor;
        this.borderInner = borderDist - 0.1f;
        this.borderOuter = borderDist;
        this.borderOffsetInner = borderOffset - 0.1f;
        this.borderOffsetOuter = borderOffset;
    }

    float lengthSq(Vector2f a, Vector2f b) {
        Vector2f r = new Vector2f(a).sub(b);
        return r.x * r.x + r.y * r.y;
    }

    public void draw(Vector2f pA, Vector2f pB, Color c, Camera camera) {
        draw(pA, pB, c, c, camera);
    }

    public void draw(Vector2f pA, Vector2f pB, Color c, Color c2, Camera camera) {

        shader.use();
        shader.setFloat("borderInner", borderInner);
        shader.setFloat("borderOuter", borderOuter);
        shader.setFloat("borderOffsetInner", borderOffsetInner);
        shader.setFloat("borderOffsetOuter", borderOffsetOuter);
        shader.setVec4("borderColor", borderColor.toVector4f());
        shader.setVec2("pointA", pA);
        shader.setVec2("pointB", pB);
        shader.setBool("dashed", dashed);
        shader.setBool("roundCaps", roundCaps);
        shader.setFloat("dashPhase", Clock.spin(dashRate));
        shader.setFloat("dashLength", dashLength);
        shader.setFloat("edgeSharpness", 0.05f);
        shader.setFloat("lineLengthSq", lengthSq(pA, pB));
        float dx = pB.x - pA.x;
        float dy = pB.y - pA.y;
        float normalX = -dy;
        float normalY = dx;
        Vector2f normalUnitVector = new Vector2f(normalX, normalY);
        normalUnitVector = normalUnitVector.normalize().mul(lineWidth * 2);
        Vector2f line = new Vector2f(pA).sub(pB);
        Vector2f lineVectorA = line.normalize().mul(lineWidth * 2);
        Vector2f tL = new Vector2f(normalUnitVector).add(pA).add(lineVectorA);
        Vector2f bL = new Vector2f(pA).sub(normalUnitVector).add(lineVectorA);
        Vector2f tR = new Vector2f(normalUnitVector).add(pB).sub(lineVectorA);
        Vector2f bR = new Vector2f(pB).sub(normalUnitVector).sub(lineVectorA);

        float width = bL.distance(tL);
        float height = bL.distance(bR);

        float edgeDist = 0.25f;
        shader.setFloat("edgeDist", edgeDist);
        shader.setFloat("edgeSharpness", edgeDist / 10);

        shader.setVec2("pointA", pA);
        shader.setVec2("pointB", pB);
        shader.setFloat("width", width);
        shader.setFloat("height", height);

        shader.setFloat("dashes", (float) ((Math.PI * height) / (dashLength)));
        shader.setFloat("dashEdgeDist", (float) (Math.PI * width * edgeDist) / (dashLength));
        shader.setVec4("linearGradientColor", c2.toVector4f());
        shader.begin();
        shader.drawSDFRegion(bL.x, bL.y, bR.x, bR.y, tL.x, tL.y, tR.x, tR.y, camera.getZIndex(), 0, 0, 1, 1, c);
        shader.end();
        camera.incZIndex();
    }

    public void setBorderDist(float borderDist) {
        this.borderInner = borderDist - 0.1f;
        this.borderOuter = borderDist;
    }

    public void setStroke(float lineWidth, boolean dashed, float dashLength, float dashRate, boolean roundCaps) {
        this.lineWidth = lineWidth;
        this.dashed = dashed;
        this.dashLength = dashLength;
        this.dashRate = dashRate;
        this.roundCaps = roundCaps;

    }

}