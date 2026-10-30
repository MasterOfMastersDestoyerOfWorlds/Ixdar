package ixdar.graphics.render.sdf;

import org.joml.Vector2f;

import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.shaders.ShaderProgram.ShaderType;
import ixdar.platform.Clock;

/**
 * One butt-ended quadratic piece of a lane whose chevrons scroll the way traffic runs: forward, back, both
 * (a half-chevron per side) or neither.
 */
public class SDFLane extends SDFBezier {
    public static final float DASH_RATE = 1.5f;

    /** Whether traffic runs from the piece's start to its end. */
    public boolean forward;
    /** Whether traffic runs from the piece's end back to its start. */
    public boolean backward;

    /**
     * A lane piece drawn with the lane shader.
     */
    public SDFLane() {
        super();
        shader = ShaderType.LaneSDF.getShader();
    }

    /**
     * Draw one piece from {@code a} to {@code b} bent toward {@code control}.
     *
     * @param a        start in world coordinates
     * @param control  control point
     * @param b        end
     * @param lane     the lane's colour
     * @param chevron  the chevrons' colour
     * @param forward  whether traffic runs from {@code a} to {@code b}
     * @param backward whether traffic runs from {@code b} to {@code a}
     * @param camera   camera providing transform and z-index
     */
    public void draw(Vector2f a, Vector2f control, Vector2f b, Color lane, Color chevron, boolean forward,
            boolean backward, Camera camera) {
        this.forward = forward;
        this.backward = backward;
        draw(a, control, b, lane, chevron, camera);
    }

    /**
     * The Bezier's uniforms plus the scroll phase and which ways traffic runs.
     */
    @Override
    protected void setUniforms() {
        super.setUniforms();
        shader.setFloat("dashPhase", Clock.spin(DASH_RATE));
        shader.setFloat("forward", forward ? 1f : 0f);
        shader.setFloat("backward", backward ? 1f : 0f);
    }
}
