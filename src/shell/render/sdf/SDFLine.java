package shell.render.sdf;

import shell.render.Canvas3D;
import shell.render.Clock;
import shell.render.Color;
import shell.render.shaders.SDFShader;
import shell.render.shaders.ShaderProgram;
import shell.render.shaders.ShaderProgram.ShaderType;

import org.joml.Vector2f;

public class SDFLine {

    public ShaderProgram shader;
    private Color borderColor;
    private float borderInner;
    private float borderOuter;
    private float borderOffsetInner;
    private float borderOffsetOuter;

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

    public void draw(int drawX, int drawY, float zIndex, Color c) {
        Vector2f pA = new Vector2f(Canvas3D.frameBufferWidth / 2,
                Canvas3D.frameBufferWidth);
        Vector2f pB = new Vector2f(Canvas3D.frameBufferWidth / 2,
                Clock.sin(100, Canvas3D.frameBufferHeight - 100, 0.2f, 3.78f));
        float dashLength = 60f;
        shader.use();
        shader.setFloat("borderInner", borderInner);
        shader.setFloat("borderOuter", borderOuter);
        shader.setFloat("borderOffsetInner", borderOffsetInner);
        shader.setFloat("borderOffsetOuter", borderOffsetOuter);
        shader.setVec4("borderColor", borderColor.toVector4f());
        shader.setVec2("pointA", pA);
        shader.setVec2("pointB", pB);
        shader.setBool("dashed", true);
        shader.setBool("roundCaps", true);
        shader.setFloat("dashPhase", Clock.spin(20));
        shader.setFloat("dashLength", dashLength);
        shader.setFloat("edgeSharpness", 0.05f);
        shader.setFloat("lineLengthSq", lengthSq(pA, pB));
        float lineWidth = 10f;
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

        shader.begin();
        shader.drawSDFRegion(bL.x, bL.y, bR.x, bR.y, tL.x, tL.y, tR.x, tR.y, zIndex, 0, 0, 1, 1, c);
        shader.end();
    }

    public void drawCentered(int drawX, int drawY, int width, int height, float zIndex, Color c) {
        draw(drawX - (width / 2), drawY - (height / 2), zIndex, c);
    }

    public void setBorderDist(float borderDist) {
        this.borderInner = borderDist - 0.1f;
        this.borderOuter = borderDist;
    }

}