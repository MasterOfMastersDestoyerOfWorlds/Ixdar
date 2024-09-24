package shell.render.sdf;

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
        this.edgeDist = 0.01f;
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
        shader.use();
        shader.setFloat("borderInner", borderInner);
        shader.setFloat("borderOuter", borderOuter);
        shader.setFloat("borderOffsetInner", borderOffsetInner);
        shader.setFloat("borderOffsetOuter", borderOffsetOuter);
        shader.setVec4("borderColor", borderColor.toVector4f());
        shader.setVec2("pointA", new Vector2f(Clock.sin(0, 1, 0.3f, 5), 0.2f));
        shader.setVec2("pointB", new Vector2f(0.8f, Clock.sin(0, 1, 0.2f, 3.78f)));
        shader.setBool("dashed", true);
        shader.setFloat("dashPhase", Clock.spin(5));
        shader.setFloat("dashLength", 0.03f);
        shader.setFloat("edgeDist", edgeDist);
        shader.begin();

        shader.drawBlankTextureRegion(drawX, drawY, drawX + width, drawY + height, zIndex, 0, 0, width, height, c);

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