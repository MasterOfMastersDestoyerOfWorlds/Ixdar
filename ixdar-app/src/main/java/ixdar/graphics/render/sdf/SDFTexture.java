package ixdar.graphics.render.sdf;

import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.Texture;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.shaders.ShaderProgram.ShaderType;
import ixdar.platform.Platforms;

public class SDFTexture extends ShaderDrawable {
    public static final float MIN_EDGE_SHARPNESS = 0.1f;
    public static final float MIN_SCALE_FACTOR = 0.5f;
    public static final String BORDER_COLOR_UNIFORM = "borderColor";
    public static final String BORDER_INNER_UNIFORM = "borderInner";
    public static final String BORDER_OUTER_UNIFORM = "borderOuter";

    public Texture texture;
    boolean sharpCorners;
    private Color borderColor;
    private float borderInner;
    private float borderOuter;
    private float borderOffsetInner;
    private float borderOffsetOuter;
    private Color drawnBorderColor = Color.TRANSPARENT;
    private float drawnBorderOuter;

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
     * Asynchronously load the MSDF texture from a resource, drawn without a border band.
     *
     * @param sdfLocation resource path to the MSDF texture
     * @param sharpCorners {@code true} to disable corner rounding in the shader
     */
    public SDFTexture(String sdfLocation, boolean sharpCorners) {
        int id = Platforms.gl().getPlatformID();
        Platforms.get().loadTexture(sdfLocation, id, (t) -> {
            this.texture = t;
            this.shader = ShaderType.TextureSDF.getShader();
        });
        this.borderColor = Color.TRANSPARENT;
        this.sharpCorners = sharpCorners;
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
     * @param camera camera providing transform and z-index
     */
    @Override
    public void draw(float drawX, float drawY, float width, float height, Color c, Camera camera) {
        if (texture == null) {
            return;
        }
        super.draw(drawX, drawY, width, height, c, camera);
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
        shader.setFloat(BORDER_INNER_UNIFORM, borderInner);
        shader.setFloat(BORDER_OUTER_UNIFORM, borderOuter);
        shader.setFloat("borderOffsetInner", borderOffsetInner);
        shader.setFloat("borderOffsetOuter", borderOffsetOuter);
        shader.setVec4(BORDER_COLOR_UNIFORM, borderColor.toVector4f());
        drawnBorderColor = borderColor;
        drawnBorderOuter = borderOuter;
        shader.setBool("sharpCorners", sharpCorners);
        shader.setFloat("pxRange", pxRange);
        shader.setFloat("edgeDist", edgeDist);
        float scaleFactor = camera != null ? camera.getScaleFactor() : 1.0f;
        float edgeSharpness = Math.max(baseEdgeSharpness / Math.max(scaleFactor, MIN_SCALE_FACTOR), MIN_EDGE_SHARPNESS);
        shader.setFloat("edgeSharpness", edgeSharpness);
    }

    /**
     * Within a set-up pass, draw what follows with a border band of this colour and width round
     * each glyph, as a word asks; the glyphs batched so far are drawn first when the band changes.
     *
     * @param colour       band colour, {@link Color#TRANSPARENT} for none
     * @param widthPixels  band width outside the glyph edge, in screen pixels
     */
    public void setWordBorder(Color colour, float widthPixels) {
        if (colour == drawnBorderColor && widthPixels == drawnBorderOuter) {
            return;
        }
        shader.flush();
        drawnBorderColor = colour;
        drawnBorderOuter = widthPixels;
        shader.setVec4(BORDER_COLOR_UNIFORM, colour.toVector4f());
        shader.setFloat(BORDER_INNER_UNIFORM, 0f);
        shader.setFloat(BORDER_OUTER_UNIFORM, widthPixels);
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
        draw(drawX - (width / 2f), drawY - (height / 2f), width, height, c, camera);
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