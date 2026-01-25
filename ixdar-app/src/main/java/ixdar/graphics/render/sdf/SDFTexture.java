package ixdar.graphics.render.sdf;

import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.Texture;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.shaders.ShaderProgram.ShaderType;
import ixdar.platform.Platforms;

public class SDFTexture extends ShaderDrawable {

    public Texture texture;
    private Color borderColor;
    private float borderInner;
    private float borderOuter;
    private float borderOffsetInner;
    private float borderOffsetOuter;
    boolean sharpCorners;

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

    public SDFTexture(String sdfLocation, Color borderColor,
            float borderDist, float borderOffset, boolean sharpCorners) {
        int id = Platforms.gl().getPlatformID();
        Platforms.get().loadTexture(sdfLocation, id, (t) -> {
            this.texture = t;
            this.shader = ShaderType.TextureSDF.getShader();
        });
        this.borderColor = borderColor;
        this.borderInner = borderDist - 0.1f;
        this.borderOuter = borderDist;
        this.borderOffsetInner = borderOffset - 0.1f;
        this.borderOffsetOuter = borderOffset;
        this.sharpCorners = sharpCorners;
    }

    public void draw(float drawX, float drawY, float width, float height, Color c, Camera camera) {
        draw(drawX, drawY, width, height, c, 0L, camera);
    }

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

    public void drawRegionNoSetup(float drawX, float drawY, float width, float height, int regX, int regY, int regWidth,
            int regHeight, Color c, Camera camera) {
        shader.drawTextureRegion(getTexture(), drawX, drawY, drawX + width, drawY + height, camera.getZIndex(), regX,
                regY,
                regWidth, regHeight, c);
    }

    public Texture getTexture() {
        return texture;
    }

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
        float edgeSharpness = Math.max(baseEdgeSharpness / Math.max(scaleFactor, 0.5f), 0.1f);
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

    public void drawRegion(float drawX, float drawY, float width, float height, int regX, int regY, int regWidth,
            int regHeight, Color c, Camera camera) {
        drawRegion(drawX, drawY, width, height, regX, regY, regWidth, regHeight, c, 0L, camera);
    }

    public void drawRegion(float drawX, float drawY, float width, float height, int regX, int regY, int regWidth,
            int regHeight, Color c, long id, Camera camera) {
        setup(camera);
        drawRegionNoSetup(drawX, drawY, width, height, regX, regY, regWidth, regHeight, c, camera);
        cleanup(camera);
    }

    public void drawCentered(float drawX, float drawY, float width, float height, Color c, Camera camera) {
        draw(drawX - (width / 2), drawY - (height / 2), width, height, c, camera);
    }

    public void drawCentered(float drawX, float drawY, float scale, Color c, Camera camera) {
        float width = (float) (texture.width * scale);
        float height = (float) (texture.height * scale);
        draw(drawX - (width / 2f), drawY - (height / 2f), width, height, c, camera);
    }

    public void setBorderDist(float borderDist) {
        this.borderInner = borderDist - 0.1f;
        this.borderOuter = borderDist;
    }

    public void setSharpCorners(boolean sharpCorners) {
        this.sharpCorners = sharpCorners;
    }

    public void drawRightBound(float drawX, float drawY, float width, float height, Color c, Camera camera) {
        draw(drawX - width, drawY, width, height, c, camera);
    }

}