package ixdar.graphics.render.sdf;

import org.joml.Vector2f;

import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.graphics.render.shaders.ShaderProgram.ShaderType;

public class SDFBezier extends ShaderDrawable {

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

    public SDFBezier() {
        super();
        bezierShader = ShaderType.BezierSDF.getShader();
        shader = bezierShader;
    }

    @Override
    public void calculateQuad() {
        // Oriented bounding box for quadratic Bezier (per Vlad Jukov / iq)
        Vector2f dirB = new Vector2f(pB).sub(pA);
        Vector2f normalizedDirB = new Vector2f(dirB).normalize();
        Vector2f xAxis = new Vector2f(1f, 0f);
        float sinB = wedge(normalizedDirB, xAxis);
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

        uAxis = new Vector2f(bottomRight).sub(bottomLeft);
        vAxis = new Vector2f(topLeft).sub(bottomLeft);
        float h0 = vAxis.length();
        float w0 = uAxis.length();

        

        // Compute final dimensions
        float hFinal = h0 / (1f - 2f * edgeDistUnits);
        float wFinal = w0 / (1f - 2f * edgeDistUnits);

        // Compute how much to expand (half per side)
        float edgeDistV = (hFinal - h0) * 0.5f;
        float edgeDistU = (wFinal - w0) * 0.5f * (h0/w0);
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

    private float wedge(Vector2f v1, Vector2f v2) {
        return v1.x * v2.y - v1.y * v2.x;
    }

    private float clamp(float t) {
        return Math.max(0f, Math.min(1f, t));
    }

    @Override
    protected void setUniforms() {

        float inverseLineLengthSq = 1 / lengthSq(pATex, pBTex);
        shader.setFloat("inverseLineLengthSq", inverseLineLengthSq);
        shader.setVec4("linearGradientColor", c2.toVector4f());
        shader.setVec2("pointA", pATex);
        shader.setVec2("pointB", pBTex);
        shader.setVec2("control", controlTex);
        shader.setFloat("edgeDist", edgeDistUnits);
        shader.setFloat("edgeSharpness", (float) Math.min(1 / (lineWidth * 2), 0.1));
    }

    float lengthSq(Vector2f a, Vector2f b) {
        Vector2f r = new Vector2f(a).sub(b);
        return r.x * r.x + r.y * r.y;
    }

}