package shell.render.sdf;

import shell.render.Color;
import shell.render.Texture;
import shell.render.shaders.SDFShader;
import shell.render.shaders.ShaderProgram;
import static org.lwjgl.opengl.GL13.*;

public class SDFUnion {

    public Texture outerTexture;
    Color outerColor;
    float outerScale;
    public Texture innerTexture;
    Color innerColor;
    float innerScale;
    public ShaderProgram shader;

    public SDFUnion(SDFShader sdfShader, String sdfInnerLocation, Color innerColor, float innerScale,
            String sdfOuterLocation, Color outerColor, float outerScale, float alpha) {
        innerTexture = Texture.loadTexture(sdfInnerLocation);
        outerTexture = Texture.loadTexture(sdfOuterLocation);
        shader = sdfShader;
        this.innerColor = new Color(innerColor, alpha);
        this.innerScale = innerScale;
        this.outerColor = new Color(outerColor, alpha);
        this.outerScale = outerScale;
    }

    public void draw(float drawX, float drawY, float width, float height, float zIndex, Color c) {
        outerTexture.bind();
        shader.use();
        shader.setTexture("outerTexture", outerTexture, GL_TEXTURE0, 0);
        shader.setTexture("innerTexture", innerTexture, GL_TEXTURE1, 1);
        shader.setVec4("borderColor", outerColor.toVector4f());
        shader.begin();

        shader.drawTextureRegion(outerTexture, drawX, drawY, drawX + width, drawY + height, zIndex, 0, 0,
                outerTexture.width,
                outerTexture.height, c);

        shader.end();
    }

    public void drawCentered(float drawX, float drawY, float scale, float zIndex, Color c) {
        float width = (float) (outerTexture.width * scale);
        float height = (float) (outerTexture.height * scale);
        draw(drawX - (width / 2f), drawY - (height / 2f), width, height, zIndex, c);
    }

}
