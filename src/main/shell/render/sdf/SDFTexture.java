package shell.render.sdf;

import shell.cameras.Camera;
import shell.platform.Platforms;
import shell.platform.gl.GL;
import shell.render.Texture;
import shell.render.color.Color;
import shell.render.shaders.ShaderProgram;
import shell.render.shaders.ShaderProgram.ShaderType;

public class SDFTexture {

    public Texture texture;
    public ShaderProgram shader;
    private Color borderColor;
    private float borderInner;
    private float borderOuter;
    private float borderOffsetInner;
    private float borderOffsetOuter;
    boolean sharpCorners;

    public SDFTexture(ShaderProgram sdfShader, String sdfLocation) {
        Platforms.get().loadTexture(sdfLocation, t -> {
            this.texture = t;
            this.shader = sdfShader;
        });
        this.borderColor = Color.TRANSPARENT;
        this.borderInner = 0;
        this.borderOuter = 0;
        this.borderOffsetInner = 0;
        this.borderOffsetOuter = 0;
    }

    public SDFTexture(Texture texture) {
        this.texture = texture;
        this.shader = ShaderType.TextureSDF.shader;
        this.borderColor = Color.TRANSPARENT;
        this.borderInner = 0;
        this.borderOuter = 0;
        this.borderOffsetInner = 0;
        this.borderOffsetOuter = 0;
        this.sharpCorners = false;
    }

    public SDFTexture(String sdfLocation, Color borderColor,
            float borderDist, float borderOffset, boolean sharpCorners) {
        Platforms.get().loadTexture(sdfLocation, t -> {
            this.texture = t;
            this.shader = ShaderType.TextureSDF.shader;
        });
        this.borderColor = borderColor;
        this.borderInner = borderDist - 0.1f;
        this.borderOuter = borderDist;
        this.borderOffsetInner = borderOffset - 0.1f;
        this.borderOffsetOuter = borderOffset;
        this.sharpCorners = sharpCorners;
    }

    public void draw(float drawX, float drawY, float width, float height, Color c, Camera camera) {
        if(texture == null){
            return;
        }
        texture.bind();
        shader.use();
        GL gl = Platforms.gl();
        shader.setTexture("innerTexture", texture, gl.TEXTURE0(), 0);
        shader.setFloat("borderInner", borderInner);
        shader.setFloat("borderOuter", borderOuter);
        shader.setFloat("borderOffsetInner", borderOffsetInner);
        shader.setFloat("borderOffsetOuter", borderOffsetOuter);
        shader.setVec4("borderColor", borderColor.toVector4f());
        shader.setBool("sharpCorners", sharpCorners);
        shader.begin();

        shader.drawTextureRegion(texture, drawX, drawY, drawX + width, drawY + height, camera.getZIndex(), 0, 0,
                texture.width,
                texture.height, c);

        shader.end();
        camera.incZIndex();
    }

    public void drawRegion(float drawX, float drawY, float width, float height, int regX, int regY, int regWidth,
            int regHeight, Color c, Camera camera) {
        if(texture == null){
            return;
        }
        texture.bind();
        shader.use();
        GL gl = Platforms.gl();
        shader.setTexture("innerTexture", texture, gl.TEXTURE0(), 0);
        shader.setFloat("borderInner", borderInner);
        shader.setFloat("borderOuter", borderOuter);
        shader.setFloat("borderOffsetInner", borderOffsetInner);
        shader.setFloat("borderOffsetOuter", borderOffsetOuter);
        shader.setVec4("borderColor", borderColor.toVector4f());
        shader.setBool("sharpCorners", sharpCorners);
        shader.begin();
        shader.drawTextureRegion(texture, drawX, drawY, drawX + width, drawY + height, camera.getZIndex(), regX, regY,
                regWidth, regHeight, c);
        shader.end();
        camera.incZIndex();
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