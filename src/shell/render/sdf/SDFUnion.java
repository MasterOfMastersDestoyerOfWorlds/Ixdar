package shell.render.sdf;

import shell.render.Color;
import shell.render.Texture;
import shell.render.shaders.ShaderProgram;
import shell.render.shaders.ShaderProgram.ShaderType;

import static org.lwjgl.opengl.GL13.*;

public class SDFUnion {

    public Texture outerTexture;
    public Color outerColor;
    public float outerScale;
    public Texture innerTexture;
    public Color innerColor;
    public float innerScale;
    public float innerOffsetX;
    public float innerOffsetY;
    public ShaderProgram shader;
    public float numberPinStripes;
    public float showPin;

    public SDFUnion(String sdfInnerLocation, Color innerColor, float innerScale,
            float innerOffsetX, float innerOffsetY, String sdfOuterLocation, Color outerColor, float alpha,
            float numberPinStripes, float showPin) {

        innerTexture = Texture.loadTexture(sdfInnerLocation);
        outerTexture = Texture.loadTexture(sdfOuterLocation);
        shader = ShaderType.UnionSDF.shader;
        this.innerColor = new Color(innerColor, alpha);
        this.innerScale = innerScale;
        this.outerColor = new Color(outerColor, alpha);
        this.innerOffsetX = innerOffsetX;
        this.innerOffsetY = innerOffsetY;
        this.numberPinStripes = numberPinStripes;
        this.showPin = showPin;
    }

    public void draw(float drawX, float drawY, float width, float height, float zIndex) {
        draw(drawX, drawY, width, height, zIndex, innerColor, outerColor);
    }

    public void draw(float drawX, float drawY, float width, float height, float zIndex, Color innerColor,
            Color outerColor) {
        outerTexture.bind();
        shader.use();
        shader.setTexture("outerTexture", outerTexture, GL_TEXTURE0, 0);
        shader.setTexture("innerTexture", innerTexture, GL_TEXTURE1, 1);
        shader.setVec4("borderColor", outerColor.toVector4f());
        float scale = 1 / innerScale;
        shader.setFloat("innerScaleX", scale);
        shader.setFloat("innerScaleY", scale);
        shader.setFloat("innerOffsetX", innerOffsetX);
        shader.setFloat("innerOffsetY", innerOffsetY);
        shader.setInt("numberPinStripes", (int) numberPinStripes);
        shader.setInt("showPin", (int) showPin);
        shader.begin();

        shader.drawTextureRegion(outerTexture, drawX, drawY, drawX + width, drawY + height, zIndex, 0, 0,
                outerTexture.width,
                outerTexture.height, innerColor);

        shader.end();
    }

    public void drawCentered(float drawX, float drawY, float scale, float zIndex, Color innerColor, Color outerColor) {
        float width = (float) (outerTexture.width * scale);
        float height = (float) (outerTexture.height * scale);
        draw(drawX - (width / 2f), drawY - (height / 2f), width, height, zIndex, innerColor, outerColor);
    }

    public void drawCentered(float drawX, float drawY, float scale, float zIndex) {
        float width = (float) (outerTexture.width * scale);
        float height = (float) (outerTexture.height * scale);
        draw(drawX - (width / 2f), drawY - (height / 2f), width, height, zIndex, innerColor, outerColor);
    }

}
