package shell.render.sdf;

import shell.render.Color;
import shell.render.Texture;
import shell.render.shaders.ShaderProgram;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;

public class SignedDistanceField {

    private Texture texture;
    public ShaderProgram shader;

    public SignedDistanceField(ShaderProgram sdfShader, String sdfLocation) {
        texture = Texture.loadTexture("decal_sdf.png");
        shader = sdfShader;
    }

    public void draw(int drawX, int drawY, int width, int height, int zIndex, Color c) {
        texture.bind();
        shader.use();
        shader.setTexture("texImage", texture, GL_TEXTURE0, 0);
        shader.begin();

        shader.drawTextureRegion(texture, drawX, drawY, drawX + width, drawY + height, zIndex, 0, 0, texture.width,
                texture.height, c);

        shader.end();
    }

    public void drawCentered(int drawX, int drawY, int width, int height, int zIndex, Color c) {
        draw(drawX - (width / 2), drawY - (height / 2), width, height, zIndex, c);
    }

}