package shell.render.sdf;

import shell.cameras.Camera;
import shell.platform.Platforms;
import shell.render.Texture;
import shell.render.color.Color;
import shell.render.shaders.ShaderProgram.ShaderType;

public class SDFTexture extends ShaderDrawable {

    public Texture texture;
    private Color borderColor;
    private float borderInner;
    private float borderOuter;
    private float borderOffsetInner;
    private float borderOffsetOuter;
    boolean sharpCorners;

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
        int id = Platforms.gl().getID();
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
        shader.drawTextureRegion(texture, drawX, drawY, drawX + width, drawY + height, camera.getZIndex(), 0, 0,
                texture.width,
                texture.height, c);
        cleanup(camera);
    }

    public void drawRegionNoSetup(float drawX, float drawY, float width, float height, int regX, int regY, int regWidth,
            int regHeight, Color c, Camera camera) {
        shader.drawTextureRegion(texture, drawX, drawY, drawX + width, drawY + height, camera.getZIndex(), regX, regY,
                regWidth, regHeight, c);
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