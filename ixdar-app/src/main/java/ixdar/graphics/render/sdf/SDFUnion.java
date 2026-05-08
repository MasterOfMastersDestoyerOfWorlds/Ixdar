package ixdar.graphics.render.sdf;

import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.GL_TEXTURE1;

import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.Texture;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorRGB;
import ixdar.graphics.render.shaders.ShaderProgram.ShaderType;
import ixdar.platform.Platforms;

public class SDFUnion extends ShaderDrawable {

    public Texture outerTexture;
    public Color outerColor;
    public float outerScale;
    public Texture innerTexture;
    public Color innerColor;
    public float innerScale;
    public float innerOffsetX;
    public float innerOffsetY;
    public float numberPinStripes;
    public float showPin;

    /**
     * TODO: document {@code SDFUnion}.
     *
     * @param sdfInnerLocation TODO: describe
     * @param innerColor TODO: describe
     * @param innerScale TODO: describe
     * @param innerOffsetX TODO: describe
     * @param innerOffsetY TODO: describe
     * @param sdfOuterLocation TODO: describe
     * @param outerColor TODO: describe
     * @param alpha TODO: describe
     * @param numberPinStripes TODO: describe
     * @param showPin TODO: describe
     */
    public SDFUnion(String sdfInnerLocation, Color innerColor, float innerScale,
            float innerOffsetX, float innerOffsetY, String sdfOuterLocation, Color outerColor, float alpha,
            float numberPinStripes, float showPin) {
        int id = Platforms.gl().getPlatformID();
        Platforms.get().loadTexture(sdfInnerLocation, id, (t) -> {
            this.innerTexture = t;
        });
        Platforms.get().loadTexture(sdfOuterLocation, id, (t) -> {
            this.outerTexture = t;
        });
        shader = ShaderType.UnionSDF.getShader();
        this.innerColor = new ColorRGB(innerColor, alpha);
        this.innerScale = innerScale;
        this.outerColor = new ColorRGB(outerColor, alpha);
        this.innerOffsetX = innerOffsetX;
        this.innerOffsetY = innerOffsetY;
        this.numberPinStripes = numberPinStripes;
        this.showPin = showPin;
    }

    /**
     * TODO: document {@code draw}.
     *
     * @param drawX TODO: describe
     * @param drawY TODO: describe
     * @param width TODO: describe
     * @param height TODO: describe
     * @param innerColor TODO: describe
     * @param outerColor TODO: describe
     * @param camera TODO: describe
     */
    public void draw(float drawX, float drawY, float width, float height, Color innerColor,
            Color outerColor, Camera camera) {
        draw(drawX, drawY, width, height, innerColor, outerColor, 0L, camera);
    }

    /**
     * TODO: document {@code draw}.
     *
     * @param drawX TODO: describe
     * @param drawY TODO: describe
     * @param width TODO: describe
     * @param height TODO: describe
     * @param innerColor TODO: describe
     * @param outerColor TODO: describe
     * @param id TODO: describe
     * @param camera TODO: describe
     */
    public void draw(float drawX, float drawY, float width, float height, Color innerColor,
            Color outerColor, long id, Camera camera) {
        this.outerColor = outerColor;
        setup(camera);
        shader.drawTextureRegion(outerTexture, drawX, drawY, drawX + width, drawY + height, camera.getZIndex(), 0, 0,
                outerTexture.width,
                outerTexture.height, innerColor);
        cleanup(camera);
    }

    /**
     * TODO: document {@code setUniforms}.
     */
    protected void setUniforms() {
        if (innerTexture == null || outerTexture == null) {
            return;
        }
        outerTexture.bind();
        innerTexture.bind();
        shader.setTexture("outerTexture", outerTexture, GL_TEXTURE0, 0);
        shader.setTexture("innerTexture", innerTexture, GL_TEXTURE1, 1);
        shader.setVec4("borderColor", outerColor.toVector4f());
        float scale = 1 / innerScale;
        shader.setFloat("innerScaleX", scale);
        shader.setFloat("innerScaleY", scale);
        shader.setFloat("innerOffsetX", innerOffsetX);
        shader.setFloat("innerOffsetY", innerOffsetY);
        shader.setFloat("numberPinStripes", (float) numberPinStripes);
        shader.setFloat("showPin", (float) showPin);
    }

    /**
     * TODO: document {@code drawCentered}.
     *
     * @param drawX TODO: describe
     * @param drawY TODO: describe
     * @param scale TODO: describe
     * @param innerColor TODO: describe
     * @param outerColor TODO: describe
     * @param camera TODO: describe
     */
    public void drawCentered(float drawX, float drawY, float scale, Color innerColor, Color outerColor, Camera camera) {
        float width = (float) (outerTexture.width * scale);
        float height = (float) (outerTexture.height * scale);
        draw(drawX - (width / 2f), drawY - (height / 2f), width, height, innerColor, outerColor, camera);

    }

    /**
     * TODO: document {@code drawCentered}.
     *
     * @param drawX TODO: describe
     * @param drawY TODO: describe
     * @param scale TODO: describe
     * @param camera TODO: describe
     */
    public void drawCentered(float drawX, float drawY, float scale, Camera camera) {
        float width = (float) (outerTexture.width * scale);
        float height = (float) (outerTexture.height * scale);
        draw(drawX - (width / 2f), drawY - (height / 2f), width, height, innerColor, outerColor, camera);
    }

}
