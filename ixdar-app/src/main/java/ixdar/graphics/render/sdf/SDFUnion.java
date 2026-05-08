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
     * Asynchronously load inner and outer MSDF textures and configure the
     * union shader (used by knot pin / colored-shape composites).
     *
     * @param sdfInnerLocation resource path to the inner MSDF texture
     * @param innerColor inner-shape color (alpha-multiplied)
     * @param innerScale uniform scale applied to the inner shape (1/scale in shader)
     * @param innerOffsetX inner-shape x offset in texture space
     * @param innerOffsetY inner-shape y offset in texture space
     * @param sdfOuterLocation resource path to the outer MSDF texture
     * @param outerColor outer-shape color (alpha-multiplied)
     * @param alpha alpha applied uniformly to inner and outer colors
     * @param numberPinStripes pin-stripe count uniform
     * @param showPin pin-visibility uniform (0 hides, 1 shows)
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
     * Draw the inner+outer composite into the given rectangle.
     *
     * @param drawX bottom-left x in world coordinates
     * @param drawY bottom-left y in world coordinates
     * @param width quad width in world units
     * @param height quad height in world units
     * @param innerColor inner-shape tint
     * @param outerColor outer-shape tint
     * @param camera camera providing transform and z-index
     */
    public void draw(float drawX, float drawY, float width, float height, Color innerColor,
            Color outerColor, Camera camera) {
        draw(drawX, drawY, width, height, innerColor, outerColor, 0L, camera);
    }

    /**
     * Draw the inner+outer composite using the outer texture's atlas region.
     *
     * @param drawX bottom-left x in world coordinates
     * @param drawY bottom-left y in world coordinates
     * @param width quad width in world units
     * @param height quad height in world units
     * @param innerColor inner-shape tint (passed as the vertex color)
     * @param outerColor outer-shape tint (cached as border color)
     * @param id legacy allocation id (currently unused)
     * @param camera camera providing transform and z-index
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
     * Bind both atlases to texture units 0 and 1 and push inner/outer scale,
     * offset, border color, and pin-stripe uniforms.
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
     * Draw the composite centered on {@code (drawX, drawY)} sized as
     * {@code outerTexture.width/height * scale}.
     *
     * @param drawX center x in world coordinates
     * @param drawY center y in world coordinates
     * @param scale uniform scale applied to the outer-texture pixel size
     * @param innerColor inner-shape tint
     * @param outerColor outer-shape tint
     * @param camera camera providing transform and z-index
     */
    public void drawCentered(float drawX, float drawY, float scale, Color innerColor, Color outerColor, Camera camera) {
        float width = (float) (outerTexture.width * scale);
        float height = (float) (outerTexture.height * scale);
        draw(drawX - (width / 2f), drawY - (height / 2f), width, height, innerColor, outerColor, camera);

    }

    /**
     * Draw the composite centered using the previously-configured colors.
     *
     * @param drawX center x in world coordinates
     * @param drawY center y in world coordinates
     * @param scale uniform scale applied to the outer-texture pixel size
     * @param camera camera providing transform and z-index
     */
    public void drawCentered(float drawX, float drawY, float scale, Camera camera) {
        float width = (float) (outerTexture.width * scale);
        float height = (float) (outerTexture.height * scale);
        draw(drawX - (width / 2f), drawY - (height / 2f), width, height, innerColor, outerColor, camera);
    }

}
