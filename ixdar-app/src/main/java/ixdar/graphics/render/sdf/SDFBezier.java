package ixdar.graphics.render.sdf;

import org.joml.Vector2f;

import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.graphics.render.shaders.ShaderProgram.ShaderType;

public class SDFBezier extends ShaderDrawable {
    public static final float NUM_1 = 1f;
    public static final float NUM_0 = 0f;
    public static final float NUM_2 = 2f;
    public static final float NUM_0_5 = 0.5f;
    public static final double NUM_0_1 = 0.1;

    public ShaderProgram bezierShader;
    public float lineWidth;
    public float dashLength;
    public boolean dashed;
    public boolean roundCaps;
    public float dashRate;
    public boolean endCaps;
    public boolean culling = true;
    public Vector2f pA;
    public Vector2f pControl;
    public Vector2f pB;
    public Color c2;
    public Vector2f pATex;
    public Vector2f pBTex;
    private Vector2f controlTex;
    private float edgeDistUnits = 0.35f;

    /**
     * Build a Bezier SDF drawable, wiring the {@code BezierSDF} shader
     * program so {@link ShaderDrawable#draw(ixdar.graphics.cameras.Camera)}
     * uses it.
     */
    public SDFBezier() {
        super();
        bezierShader = ShaderType.BezierSDF.getShader();
        shader = bezierShader;
    }

    /**
     * Compute the oriented bounding box enclosing the quadratic Bezier through {@code pA},
     * {@code pControl}, {@code pB}, expanded by {@code edgeDistUnits} to leave the shader an
     * anti-aliasing margin.
     *
     * <p>Writes the corner vectors, the {@code uAxis}/{@code vAxis} basis, and the
     * texture-space control points that {@link #setUniforms()} reads.
     */
    @Override
    public void calculateQuad() {
        // Oriented bounding box for quadratic Bezier (per Vlad Jukov / iq)
        Vector2f dirB = new Vector2f(pB).sub(pA);
        Vector2f normalizedDirB = new Vector2f(dirB).normalize();
        Vector2f xAxis = new Vector2f(NUM_1, NUM_0);
        float sinB = normalizedDirB.x * xAxis.y - normalizedDirB.y * xAxis.x;
        float cosB = new Vector2f(pB).sub(pA).normalize().dot(xAxis);

        Vector2f dirControl = new Vector2f(pControl).sub(pA);
        Vector2f p1 = new Vector2f(pA).add(rot(dirControl, cosB, sinB));
        Vector2f p2 = new Vector2f(pA).add(new Vector2f(dirB.length(), NUM_0));

        Vector2f mi = new Vector2f(Math.min(pA.x, p2.x), Math.min(pA.y, p2.y));
        Vector2f ma = new Vector2f(Math.max(pA.x, p2.x), Math.max(pA.y, p2.y));
        if (p1.x < mi.x || p1.x > ma.x || p1.y < mi.y || p1.y > ma.y) {
            Vector2f num = new Vector2f(pA).sub(p1);
            Vector2f den = new Vector2f(pA).sub(new Vector2f(p1).mul(NUM_2)).add(p2);
            Vector2f t = new Vector2f(clamp(num.x / den.x), clamp(num.y / den.y));
            Vector2f s = new Vector2f(NUM_1, NUM_1).sub(t);
            // Component-wise evaluation of the quadratic Bezier at t.x (for x) and t.y (for
            // y)
            float qx = s.x * s.x * pA.x + NUM_2 * s.x * t.x * p1.x + t.x * t.x * p2.x;
            float qy = s.y * s.y * pA.y + NUM_2 * s.y * t.y * p1.y + t.y * t.y * p2.y;
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

        uAxis = new Vector2f(bottomRight).sub(bottomLeft);
        vAxis = new Vector2f(topLeft).sub(bottomLeft);
        float h0 = vAxis.length();
        float w0 = uAxis.length();

        

        // Compute final dimensions
        float hFinal = h0 / (NUM_1 - NUM_2 * edgeDistUnits);
        float wFinal = w0 / (NUM_1 - NUM_2 * edgeDistUnits);

        // Compute how much to expand (half per side)
        float edgeDistV = (hFinal - h0) * NUM_0_5;
        float edgeDistU = (wFinal - w0) * NUM_0_5 * (h0/w0);
        Vector2f edgeDistWorldV = new Vector2f(vAxis).normalize().mul(edgeDistV);
        Vector2f edgeDistWorldU = new Vector2f(uAxis).normalize().mul(edgeDistU);
        topLeft = new Vector2f(topLeft).add(edgeDistWorldV).sub(edgeDistWorldU);
        topRight = new Vector2f(topRight).add(edgeDistWorldV).add(edgeDistWorldU);
        bottomLeft = new Vector2f(bottomLeft).sub(edgeDistWorldV).sub(edgeDistWorldU);
        bottomRight = new Vector2f(bottomRight).sub(edgeDistWorldV).add(edgeDistWorldU);
        uAxis = new Vector2f(bottomRight).sub(bottomLeft);
        vAxis = new Vector2f(topLeft).sub(bottomLeft);

        pATex = toScaledTextureSpace(pA);
        pBTex = toScaledTextureSpace(pB);
        controlTex = toScaledTextureSpace(pControl);
    }

    private Vector2f rot(Vector2f p, float cosb, float sinb) {
        return new Vector2f(cosb * p.x - sinb * p.y, sinb * p.x + cosb * p.y);
    }

    private float clamp(float t) {
        return Math.max(NUM_0, Math.min(NUM_1, t));
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
        shader.setFloat("edgeDist", edgeDistUnits);
        shader.setFloat("edgeSharpness", (float) Math.min(1 / (lineWidth * 2), NUM_0_1));
    }

    float lengthSq(Vector2f a, Vector2f b) {
        Vector2f r = new Vector2f(a).sub(b);
        return r.x * r.x + r.y * r.y;
    }

}