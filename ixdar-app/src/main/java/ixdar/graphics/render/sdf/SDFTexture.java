package ixdar.graphics.render.sdf;

import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.Texture;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.shaders.ShaderProgram.ShaderType;
import ixdar.platform.Platforms;

public class SDFTexture extends ShaderDrawable {
    public static final float NUM_0_1 = 0.1f;
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_2 = 2f;

    public Texture texture;
    boolean sharpCorners;
    private Color borderColor;
    private float borderInner;
    private float borderOuter;
    private float borderOffsetInner;
    private float borderOffsetOuter;

    /**
     * Distance range from MSDF atlas generation (pxRange parameter). Default is 4.0
     * which matches the opensans.json distanceRange.
     */
    private float pxRange = 4.0f;

    /**
     * Base edge sharpness (like SDFLine's max of 0.1). Controls the transition
     * width for anti-aliasing. Smaller values = sharper edges, larger values =
     * softer/more anti-aliased. Default 0.1 matches SDFLine's maximum for
     * consistent look.
     */
    private float baseEdgeSharpness = 0.8f;
    /**
     * Edge distance from the glyph border. Default 0.35f matches SDFLine's default
     * for consistent look.
     */
    private float edgeDist = 0.5f;

    /**
     * Wrap an already-loaded MSDF texture and bind the texture SDF shader with
     * a transparent border band and rounded corners.
     *
     * @param texture pre-loaded MSDF texture
     */
    public SDFTexture(Texture texture) {
        this.texture = texture;
        this.shader = ShaderType.TextureSDF.getShader();
        this.borderColor = Color.TRANSPARENT;
        this.borderInner = 0;
        this.borderOuter = 0;
        this.borderOffsetInner = 0;
        this.borderOffsetOuter = 0;
        this.sharpCorners = false;
    }

    /**
     * Asynchronously load the MSDF texture from a resource and configure a
     * fixed border band.
     *
     * @param sdfLocation resource path to the MSDF texture
     * @param borderColor color rendered in the border band
     * @param borderDist outer border radius (distance from edge)
     * @param borderOffset offset of the border start from the edge
     * @param sharpCorners {@code true} to disable corner rounding in the shader
     */
    public SDFTexture(String sdfLocation, Color borderColor,
            float borderDist, float borderOffset, boolean sharpCorners) {
        int id = Platforms.gl().getPlatformID();
        Platforms.get().loadTexture(sdfLocation, id, (t) -> {
            this.texture = t;
            this.shader = ShaderType.TextureSDF.getShader();
        });
        this.borderColor = borderColor;
        this.borderInner = borderDist - NUM_0_1;
        this.borderOuter = borderDist;
        this.borderOffsetInner = borderOffset - NUM_0_1;
        this.borderOffsetOuter = borderOffset;
        this.sharpCorners = sharpCorners;
    }

    /**
     * Draw the full MSDF texture into the given rectangle.
     *
     * @param drawX bottom-left x in world coordinates
     * @param drawY bottom-left y in world coordinates
     * @param width quad width in world units
     * @param height quad height in world units
     * @param c tint color
     * @param camera camera providing transform and z-index
     */
    public void draw(float drawX, float drawY, float width, float height, Color c, Camera camera) {
        draw(drawX, drawY, width, height, c, 0L, camera);
    }

    /**
     * Draw the full MSDF texture into the given rectangle. No-op if the
     * texture is still loading.
     *
     * @param drawX bottom-left x in world coordinates
     * @param drawY bottom-left y in world coordinates
     * @param width quad width in world units
     * @param height quad height in world units
     * @param c tint color
     * @param id legacy allocation id (currently unused)
     * @param camera camera providing transform and z-index
     */
    public void draw(float drawX, float drawY, float width, float height, Color c, long id, Camera camera) {
        if (texture == null) {
            return;
        }
        setup(camera);
        shader.drawTextureRegion(getTexture(), drawX, drawY, drawX + width, drawY + height, camera.getZIndex(), 0, 0,
                texture.width,
                texture.height, c);
        cleanup(camera);
    }

    /**
     * Submit a region draw without re-running shader setup/cleanup; intended
     * for batched glyph rendering inside an outer setup/cleanup pair.
     *
     * @param drawX destination x in world coordinates
     * @param drawY destination y in world coordinates
     * @param width destination width in world units
     * @param height destination height in world units
     * @param regX source x in atlas pixels
     * @param regY source y in atlas pixels
     * @param regWidth source width in atlas pixels
     * @param regHeight source height in atlas pixels
     * @param c tint color
     * @param camera camera providing z-index
     */
    public void drawRegionNoSetup(float drawX, float drawY, float width, float height, int regX, int regY, int regWidth,
            int regHeight, Color c, Camera camera) {
        shader.drawTextureRegion(getTexture(), drawX, drawY, drawX + width, drawY + height, camera.getZIndex(), regX,
                regY,
                regWidth, regHeight, c);
    }

    /**
     * The MSDF texture being rendered (may be {@code null} while still loading).
     *
     * @return current texture binding
     */
    public Texture getTexture() {
        return texture;
    }

    /**
     * Bind the texture and push border-band, corner, MSDF range, and
     * zoom-adjusted edge-sharpness uniforms.
     */
    @Override
    protected void setUniforms() {
        if (texture == null) {
            return;
        }
        texture.bind();
        shader.setTexture("innerTexture", texture, gl.TEXTURE0(), 0);
        shader.setFloat("borderInner", borderInner);
        shader.setFloat("borderOuter", borderOuter);
        shader.setFloat("borderOffsetInner", borderOffsetInner);
        shader.setFloat("borderOffsetOuter", borderOffsetOuter);
        shader.setVec4("borderColor", borderColor.toVector4f());
        shader.setBool("sharpCorners", sharpCorners);
        shader.setFloat("pxRange", pxRange);
        shader.setFloat("edgeDist", edgeDist);
        float scaleFactor = camera != null ? camera.getScaleFactor() : 1.0f;
        float edgeSharpness = Math.max(baseEdgeSharpness / Math.max(scaleFactor, NUM_0_5), NUM_0_1);
        shader.setFloat("edgeSharpness", edgeSharpness);
    }

    /**
     * Set the MSDF distance range (pxRange from atlas generation).
     *
     * @param pxRange the distance range, typically 2-8
     */
    public void setPxRange(float pxRange) {
        this.pxRange = pxRange;
    }

    /**
     * Set the base edge sharpness before zoom adjustment.
     *
     * @param sharpness 1.0 = default, lower = softer edges, higher = sharper
     */
    public void setBaseEdgeSharpness(float sharpness) {
        this.baseEdgeSharpness = sharpness;
    }

    /**
     * Draw a sub-region of the atlas into the given rectangle.
     *
     * @param drawX destination x in world coordinates
     * @param drawY destination y in world coordinates
     * @param width destination width in world units
     * @param height destination height in world units
     * @param regX source x in atlas pixels
     * @param regY source y in atlas pixels
     * @param regWidth source width in atlas pixels
     * @param regHeight source height in atlas pixels
     * @param c tint color
     * @param camera camera providing transform and z-index
     */
    public void drawRegion(float drawX, float drawY, float width, float height, int regX, int regY, int regWidth,
            int regHeight, Color c, Camera camera) {
        drawRegion(drawX, drawY, width, height, regX, regY, regWidth, regHeight, c, 0L, camera);
    }

    /**
     * Draw a sub-region of the atlas with explicit shader setup/cleanup.
     *
     * @param drawX destination x in world coordinates
     * @param drawY destination y in world coordinates
     * @param width destination width in world units
     * @param height destination height in world units
     * @param regX source x in atlas pixels
     * @param regY source y in atlas pixels
     * @param regWidth source width in atlas pixels
     * @param regHeight source height in atlas pixels
     * @param c tint color
     * @param id legacy allocation id (currently unused)
     * @param camera camera providing transform and z-index
     */
    public void drawRegion(float drawX, float drawY, float width, float height, int regX, int regY, int regWidth,
            int regHeight, Color c, long id, Camera camera) {
        setup(camera);
        drawRegionNoSetup(drawX, drawY, width, height, regX, regY, regWidth, regHeight, c, camera);
        cleanup(camera);
    }

    /**
     * Draw the texture centered on {@code (drawX, drawY)}.
     *
     * @param drawX center x in world coordinates
     * @param drawY center y in world coordinates
     * @param width quad width in world units
     * @param height quad height in world units
     * @param c tint color
     * @param camera camera providing transform and z-index
     */
    public void drawCentered(float drawX, float drawY, float width, float height, Color c, Camera camera) {
        draw(drawX - (width / 2), drawY - (height / 2), width, height, c, camera);
    }

    /**
     * Draw the texture centered on {@code (drawX, drawY)}, sized to the
     * texture's pixel dimensions multiplied by {@code scale}.
     *
     * @param drawX center x in world coordinates
     * @param drawY center y in world coordinates
     * @param scale uniform scale applied to the texture's pixel size
     * @param c tint color
     * @param camera camera providing transform and z-index
     */
    public void drawCentered(float drawX, float drawY, float scale, Color c, Camera camera) {
        float width = (float) (texture.width * scale);
        float height = (float) (texture.height * scale);
        draw(drawX - (width / NUM_2), drawY - (height / NUM_2), width, height, c, camera);
    }

    /**
     * Set the border outer radius and a 0.1-unit feather inner edge.
     *
     * @param borderDist outer border radius in distance-field units
     */
    public void setBorderDist(float borderDist) {
        this.borderInner = borderDist - NUM_0_1;
        this.borderOuter = borderDist;
    }

    /**
     * Toggle the {@code sharpCorners} shader uniform.
     *
     * @param sharpCorners {@code true} to disable corner rounding
     */
    public void setSharpCorners(boolean sharpCorners) {
        this.sharpCorners = sharpCorners;
    }

    /**
     * Draw the texture so that its right edge sits at {@code drawX} (i.e.
     * right-aligned at that x).
     *
     * @param drawX right-edge x in world coordinates
     * @param drawY bottom-left y in world coordinates
     * @param width quad width in world units
     * @param height quad height in world units
     * @param c tint color
     * @param camera camera providing transform and z-index
     */
    public void drawRightBound(float drawX, float drawY, float width, float height, Color c, Camera camera) {
        draw(drawX - width, drawY, width, height, c, camera);
    }

}