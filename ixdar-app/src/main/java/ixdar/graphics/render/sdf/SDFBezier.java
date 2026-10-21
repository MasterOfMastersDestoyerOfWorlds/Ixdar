package ixdar.graphics.render.sdf;

import org.joml.Vector2f;

import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.graphics.render.shaders.ShaderProgram.ShaderType;

public class SDFBezier extends ShaderDrawable {
    public static final double MAX_EDGE_SHARPNESS = 0.1;
    public static final float STROKE_MARGIN_PER_WIDTH = 2f;

    public ShaderProgram bezierShader;
    public float lineWidth;
    public Vector2f pA;
    public Vector2f pControl;
    public Vector2f pB;
    public Color c2;
    public Vector2f pATex;
    public Vector2f pBTex;
    private Vector2f controlTex;

    /**
     * Build a Bezier SDF drawable, wiring the {@code BezierSDF} shader
     * program so {@link ShaderDrawable#draw(Camera)}
     * uses it.
     */
    public SDFBezier() {
        super();
        bezierShader = ShaderType.BezierSDF.getShader();
        shader = bezierShader;
    }

    /**
     * Draw the quadratic curve from {@code pA} to {@code pB} bent toward {@code pControl}, a
     * {@link #lineWidth} stroke graded from {@code c} at {@code pA} to {@code c2} at {@code pB}.
     *
     * @param pA start point in world coordinates
     * @param pControl control point in world coordinates
     * @param pB end point in world coordinates
     * @param c color at the start
     * @param c2 color at the end
     * @param camera camera providing transform and z-index
     */
    public void draw(Vector2f pA, Vector2f pControl, Vector2f pB, Color c, Color c2, Camera camera) {
        this.pA = pA;
        this.pControl = pControl;
        this.pB = pB;
        this.c = c;
        this.c2 = c2;
        draw(camera);
    }

    /**
     * Compute the oriented bounding box of the quadratic Bezier, padded by a margin proportional
     * to {@link #lineWidth} so the stroke keeps its pixel width at any curve size.
     */
    @Override
    public void calculateQuad() {
        // Oriented bounding box for quadratic Bezier (per Vlad Jukov / iq)
        Vector2f dirB = new Vector2f(pB).sub(pA);
        Vector2f normalizedDirB = new Vector2f(dirB).normalize();
        Vector2f xAxis = new Vector2f(1f, 0f);
        float sinB = normalizedDirB.x * xAxis.y - normalizedDirB.y * xAxis.x;
        float cosB = new Vector2f(pB).sub(pA).normalize().dot(xAxis);

        Vector2f dirControl = new Vector2f(pControl).sub(pA);
        Vector2f p1 = new Vector2f(pA).add(rot(dirControl, cosB, sinB));
        Vector2f p2 = new Vector2f(pA).add(new Vector2f(dirB.length(), 0f));

        Vector2f mi = new Vector2f(Math.min(pA.x, p2.x), Math.min(pA.y, p2.y));
        Vector2f ma = new Vector2f(Math.max(pA.x, p2.x), Math.max(pA.y, p2.y));
        if (p1.x < mi.x || p1.x > ma.x || p1.y < mi.y || p1.y > ma.y) {
            Vector2f num = new Vector2f(pA).sub(p1);
            Vector2f den = new Vector2f(pA).sub(new Vector2f(p1).mul(2f)).add(p2);
            Vector2f t = new Vector2f(clamp(num.x / den.x), clamp(num.y / den.y));
            Vector2f s = new Vector2f(1f, 1f).sub(t);
            // Component-wise evaluation of the quadratic Bezier at t.x (for x) and t.y (for
            // y)
            float qx = s.x * s.x * pA.x + 2f * s.x * t.x * p1.x + t.x * t.x * p2.x;
            float qy = s.y * s.y * pA.y + 2f * s.y * t.y * p1.y + t.y * t.y * p2.y;
            Vector2f q = new Vector2f(qx, qy);
            mi = new Vector2f(Math.min(mi.x, q.x), Math.min(mi.y, q.y));
            ma = new Vector2f(Math.max(ma.x, q.x), Math.max(ma.y, q.y));
        }

        Vector2f maRot = new Vector2f(pA).add(rot(new Vector2f(ma).sub(pA), cosB, -sinB));
        Vector2f miRot = new Vector2f(pA).add(rot(new Vector2f(mi).sub(pA), cosB, -sinB));
        float proj = normalizedDirB.dot(new Vector2f(miRot).sub(maRot));
        Vector2f offset = new Vector2f(normalizedDirB).mul(proj);
        Vector2f b = new Vector2f(maRot).add(offset);
        Vector2f d = new Vector2f(miRot).sub(offset);

        bottomLeft = miRot;
        bottomRight = d;
        topRight = maRot;
        topLeft = b;

        Vector2f uDirection = new Vector2f(bottomRight).sub(bottomLeft);
        if (uDirection.lengthSquared() > 0) {
            uDirection.normalize();
        } else {
            uDirection.set(normalizedDirB);
        }
        Vector2f vDirection = new Vector2f(topLeft).sub(bottomLeft);
        if (vDirection.lengthSquared() > 0) {
            vDirection.normalize();
        } else {
            vDirection.set(-uDirection.y, uDirection.x);
        }

        float margin = lineWidth * STROKE_MARGIN_PER_WIDTH;
        Vector2f edgeDistWorldV = vDirection.mul(margin);
        Vector2f edgeDistWorldU = uDirection.mul(margin);
        topLeft = new Vector2f(topLeft).add(edgeDistWorldV).sub(edgeDistWorldU);
        topRight = new Vector2f(topRight).add(edgeDistWorldV).add(edgeDistWorldU);
        bottomLeft = new Vector2f(bottomLeft).sub(edgeDistWorldV).sub(edgeDistWorldU);
        bottomRight = new Vector2f(bottomRight).sub(edgeDistWorldV).add(edgeDistWorldU);
        uAxis = new Vector2f(bottomRight).sub(bottomLeft);
        vAxis = new Vector2f(topLeft).sub(bottomLeft);
        texWidth = uAxis.length() / vAxis.length();
        texHeight = 1;

        pATex = toScaledTextureSpace(pA);
        pBTex = toScaledTextureSpace(pB);
        controlTex = toScaledTextureSpace(pControl);
    }

    private Vector2f rot(Vector2f p, float cosb, float sinb) {
        return new Vector2f(cosb * p.x - sinb * p.y, sinb * p.x + cosb * p.y);
    }

    private float clamp(float t) {
        return Math.max(0f, Math.min(1f, t));
    }

    /**
     * Push Bezier-specific uniforms to the active shader: the inverse of the
     * squared chord length (used to normalize distances), the gradient
     * end color, the three texture-space control points, the edge-anti-alias
     * margin, and an edge-sharpness factor scaled by line width.
     */
    @Override
    protected void setUniforms() {

        float inverseLineLengthSq = 1 / lengthSq(pATex, pBTex);
        shader.setFloat("inverseLineLengthSq", inverseLineLengthSq);
        shader.setVec4("linearGradientColor", c2.toVector4f());
        shader.setVec2("pointA", pATex);
        shader.setVec2("pointB", pBTex);
        shader.setVec2("control", controlTex);
        float quadHeight = vAxis.length();
        shader.setFloat("edgeDist", lineWidth * SDFLine.DEFAULT_EDGE_DIST * 2 * STROKE_MARGIN_PER_WIDTH / quadHeight);
        shader.setFloat("edgeSharpness", (float) Math.min(1 / (lineWidth * 2), MAX_EDGE_SHARPNESS)
                * 2 * STROKE_MARGIN_PER_WIDTH * lineWidth / quadHeight);
    }

    float lengthSq(Vector2f a, Vector2f b) {
        Vector2f r = new Vector2f(a).sub(b);
        return r.x * r.x + r.y * r.y;
    }

}