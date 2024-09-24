package shell.render.sdf;

import shell.render.Color;
import shell.render.Texture;
import shell.render.shaders.ShaderProgram;
import shell.render.shaders.SDFShapeShader;
import shell.render.shaders.SDFTextureShader;

import static org.lwjgl.opengl.GL13.GL_TEXTURE0;

public class SDFTexture {

    private Texture texture;
    public ShaderProgram shader;
    private Color borderColor;
    private float borderInner;
    private float borderOuter;
    private float borderOffsetInner;
    private float borderOffsetOuter;

    public SDFTexture(ShaderProgram sdfShader, String sdfLocation) {
        texture = Texture.loadTexture(sdfLocation);
        shader = sdfShader;
        this.borderColor = Color.TRANSPARENT;
        this.borderInner = 0;
        this.borderOuter = 0;
        this.borderOffsetInner = 0;
        this.borderOffsetOuter = 0;
    }

    public SDFTexture(SDFTextureShader sdfShader, String sdfLocation, Color borderColor,
            float borderDist, float borderOffset) {
        texture = Texture.loadTexture(sdfLocation);
        shader = sdfShader;
        this.borderColor = borderColor;
        this.borderInner = borderDist - 0.1f;
        this.borderOuter = borderDist;
        this.borderOffsetInner = borderOffset - 0.1f;
        this.borderOffsetOuter = borderOffset;
    }

    public void draw(int drawX, int drawY, int width, int height, int zIndex, Color c) {
        texture.bind();
        shader.use();
        shader.setTexture("texImage", texture, GL_TEXTURE0, 0);
        shader.setFloat("borderInner", borderInner);
        shader.setFloat("borderOuter", borderOuter);
        shader.setFloat("borderOffsetInner", borderOffsetInner);
        shader.setFloat("borderOffsetOuter", borderOffsetOuter);
        shader.setVec4("borderColor", borderColor.toVector4f());
        shader.begin();

        shader.drawTextureRegion(texture, drawX, drawY, drawX + width, drawY + height, zIndex, 0, 0, texture.width,
                texture.height, c);

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