package shell.render.sdf;

import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.GL_TEXTURE1;

import shell.cameras.Camera;
import shell.render.Texture;
import shell.render.color.Color;
import shell.render.color.ColorRGB;
import shell.render.shaders.ShaderProgram;
import shell.render.shaders.ShaderProgram.ShaderType;

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

        innerTexture = Texture.loadTextureThreaded(sdfInnerLocation);
        outerTexture = Texture.loadTextureThreaded(sdfOuterLocation);
        shader = ShaderType.UnionSDF.shader;
        this.innerColor = new ColorRGB(innerColor, alpha);
        this.innerScale = innerScale;
        this.outerColor = new ColorRGB(outerColor, alpha);
        this.innerOffsetX = innerOffsetX;
        this.innerOffsetY = innerOffsetY;
        this.numberPinStripes = numberPinStripes;
        this.showPin = showPin;
    }

    public void draw(float drawX, float drawY, float width, float height, Camera camera) {
        draw(drawX, drawY, width, height, innerColor, outerColor, camera);
    }

    public void draw(float drawX, float drawY, float width, float height, Color innerColor,
            Color outerColor, Camera camera) {
        outerTexture.bind();
        innerTexture.bind();
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

        shader.drawTextureRegion(outerTexture, drawX, drawY, drawX + width, drawY + height, camera.getZIndex(), 0, 0,
                outerTexture.width,
                outerTexture.height, innerColor);

        shader.end();
        camera.incZIndex();
    }

    public void drawCentered(float drawX, float drawY, float scale, Color innerColor, Color outerColor, Camera camera) {
        float width = (float) (outerTexture.width * scale);
        float height = (float) (outerTexture.height * scale);
        draw(drawX - (width / 2f), drawY - (height / 2f), width, height, innerColor, outerColor, camera);

    }

    public void drawCentered(float drawX, float drawY, float scale, Camera camera) {
        float width = (float) (outerTexture.width * scale);
        float height = (float) (outerTexture.height * scale);
        draw(drawX - (width / 2f), drawY - (height / 2f), width, height, innerColor, outerColor, camera);
    }

}
