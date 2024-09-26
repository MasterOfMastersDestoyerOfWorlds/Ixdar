package shell.render.sdf;

import shell.render.Canvas3D;
import shell.render.Clock;
import shell.render.Color;
import shell.render.shaders.ShaderProgram;
import shell.render.shaders.SDFShapeShader;

import org.joml.Vector2f;

public class SDFLine {

    public ShaderProgram shader;
    private Color borderColor;
    private float borderInner;
    private float borderOuter;
    private float borderOffsetInner;
    private float borderOffsetOuter;
    private float edgeDist;

    public SDFLine(ShaderProgram sdfShader) {
        shader = sdfShader;
        this.borderColor = Color.TRANSPARENT;
        this.borderInner = 0;
        this.borderOuter = 0;
        this.borderOffsetInner = 0;
        this.borderOffsetOuter = 0;
        this.edgeDist = 0.2f;
    }

    public SDFLine(SDFShapeShader sdfShader, Color borderColor,
            float borderDist, float borderOffset) {
        shader = sdfShader;
        this.borderColor = borderColor;
        this.borderInner = borderDist - 0.1f;
        this.borderOuter = borderDist;
        this.borderOffsetInner = borderOffset - 0.1f;
        this.borderOffsetOuter = borderOffset;
    }

    public void draw(int drawX, int drawY, int width, int height, int zIndex, Color c) {
        Vector2f pA = new Vector2f(Clock.sin(100, Canvas3D.framebufferWidth - 100, 0.3f, 5),
                0.2f * Canvas3D.framebufferWidth);
        Vector2f pB = new Vector2f(0.8f * Canvas3D.framebufferHeight,
                Clock.sin(100, Canvas3D.framebufferHeight - 100, 0.2f, 3.78f));
        shader.use();
        shader.setFloat("borderInner", borderInner);
        shader.setFloat("borderOuter", borderOuter);
        shader.setFloat("borderOffsetInner", borderOffsetInner);
        shader.setFloat("borderOffsetOuter", borderOffsetOuter);
        shader.setVec4("borderColor", borderColor.toVector4f());
        shader.setVec2("pointA", pA);
        shader.setVec2("pointB", pB);
        shader.setBool("dashed", true);
        shader.setFloat("dashPhase", Clock.spin(5));
        shader.setFloat("dashLength", 40f);
        shader.setFloat("edgeSharpness", 0.05f);
        shader.begin();
        float edgeDistScreenSpace =  5f;
        float edgeDist = edgeDistScreenSpace/50;
        float lineDistance = pA.distance(pB);
        shader.setFloat("edgeDist", edgeDist);

        float dx = pB.x - pA.x;
        float dy = pB.y - pA.y;
        float normalX = -dy;
        float normalY = dx;
        Vector2f normalUnitVector = new Vector2f(normalX, normalY);
        normalUnitVector = normalUnitVector.normalize().mul(edgeDistScreenSpace * 4);
        Vector2f line = new Vector2f(pA).sub(pB);
        Vector2f lineVectorA = line.normalize().mul(edgeDistScreenSpace * 4);
        Vector2f tL = new Vector2f(normalUnitVector).add(pA).add(lineVectorA);
        Vector2f bL = new Vector2f(pA).sub(normalUnitVector).add(lineVectorA);
        Vector2f tR = new Vector2f(normalUnitVector).add(pB).sub(lineVectorA);
        Vector2f bR = new Vector2f(pB).sub(normalUnitVector).sub(lineVectorA);

        float quadWidth = bL.distance(tL);
        float quadHeight = bL.distance(bR);

        shader.setFloat("edgeDist", 0.3f);

        /* Texture coordinates */
        float s1 = 0 / quadWidth;
        float t1 = 0 / quadHeight;
        float s2 = (0 + quadWidth) / quadWidth;
        float t2 = (0 + quadHeight) / quadHeight;

        Vector2f pATextureCoord = new Vector2f(pA).sub(bL);
        Vector2f pBTextureCoord = new Vector2f(pB).sub(bL);
        shader.setVec2("pointA", pA);
        shader.setVec2("pointB", pB);
        shader.setFloat("width", quadWidth);
        shader.setFloat("height", quadHeight);

        shader.drawSDFRegion(bL.x, bL.y, bR.x, bR.y, tL.x, tL.y, tR.x, tR.y, zIndex, s1, t1, s2, t2, c);
        shader.end();
    }

    public void drawCentered(int drawX, int drawY, int width, int height, int zIndex, Color c) {
        draw(drawX - (width / 2), drawY - (height / 2), width, height, zIndex, c);
    }

    public void setBorderDist(float borderDist) {
        this.borderInner = borderDist - 0.1f;
        this.borderOuter = borderDist;
    }

}