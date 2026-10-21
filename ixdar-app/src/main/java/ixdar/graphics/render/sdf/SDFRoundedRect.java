package ixdar.graphics.render.sdf;

import org.joml.Vector2f;

import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.shaders.ShaderProgram.ShaderType;

/**
 * A filled rounded rectangle with a border band, drawn by the {@code RoundedRectSDF} shader in
 * screen pixels: the corner radius and border width keep their pixel size at any zoom.
 */
public class SDFRoundedRect extends ShaderDrawable {
    public static final float EDGE_SHARPNESS_PX = 0.75f;
    public static final float ANTIALIAS_QUAD_MARGIN_PX = 2f;

    public final Vector2f rectCenter = new Vector2f();
    public final Vector2f rectHalfSize = new Vector2f();
    public float cornerRadius;
    public float borderWidth;
    public Color borderColor = Color.TRANSPARENT;
    public float edgeSharpness = EDGE_SHARPNESS_PX;

    /**
     * Bind the rounded-rectangle SDF shader.
     */
    public SDFRoundedRect() {
        shader = ShaderType.RoundedRectSDF.getShader();
    }

    /**
     * Draw the rectangle with lower-left corner {@code (x, y)}, filled with {@code fill} inside a
     * {@code borderWidth} band of {@code border}.
     *
     * @param x lower-left x in screen pixels
     * @param y lower-left y in screen pixels
     * @param width rectangle width in pixels
     * @param height rectangle height in pixels
     * @param cornerRadius corner radius in pixels, clamped to half the shorter side
     * @param borderWidth border band width in pixels; 0 for none
     * @param fill fill color
     * @param border border color
     * @param camera camera providing transform and z-index
     */
    public void draw(float x, float y, float width, float height, float cornerRadius, float borderWidth,
            Color fill, Color border, Camera camera) {
        rectHalfSize.set(width / 2, height / 2);
        rectCenter.set(x + rectHalfSize.x, y + rectHalfSize.y);
        this.cornerRadius = Math.min(cornerRadius, Math.min(rectHalfSize.x, rectHalfSize.y));
        this.borderWidth = borderWidth;
        this.borderColor = border;
        draw(x - ANTIALIAS_QUAD_MARGIN_PX, y - ANTIALIAS_QUAD_MARGIN_PX, width + 2 * ANTIALIAS_QUAD_MARGIN_PX,
                height + 2 * ANTIALIAS_QUAD_MARGIN_PX, fill, camera);
    }

    /**
     * Push the rectangle's center, half size, corner radius and border band.
     */
    @Override
    protected void setUniforms() {
        shader.setVec2("center", rectCenter);
        shader.setVec2("halfSize", rectHalfSize);
        shader.setFloat("cornerRadius", cornerRadius);
        shader.setFloat("borderWidth", borderWidth);
        shader.setVec4("fillColor", c.toVector4f());
        shader.setVec4("borderColor", borderColor.toVector4f());
        shader.setFloat("edgeSharpness", edgeSharpness);
    }
}
