package shell.render.sdf;

import org.joml.Vector2f;

import shell.render.Clock;
import shell.render.color.Color;
import shell.render.shaders.ShaderProgram;
import shell.render.shaders.ShaderProgram.ShaderType;

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
    public Vector2f vAxis;
    public Vector2f uAxis;
    public Vector2f pATex;
    public Vector2f pBTex;

    public SDFBezier() {
        super();
        bezierShader = ShaderType.BezierSDF.getShader();
        shader = bezierShader;
    }

    @Override
    public void calculateQuad() {
        // direction of curve
        Vector2f dir = new Vector2f(pB).sub(pA);
        Vector2f ndir = new Vector2f(dir).normalize();
        Vector2f ox = new Vector2f(1f, 0f);

        float sinb = wedge(ndir, ox);
        float cosb = new Vector2f(pB).sub(pA).normalize().dot(ox);

        // align with X axis
        Vector2f p0 = new Vector2f(pA);
        Vector2f p0p1 = new Vector2f(pControl).sub(pA);
        Vector2f p1 = new Vector2f(p0).add(rot(p0p1, cosb, sinb));
        Vector2f p2 = new Vector2f(p0).add(new Vector2f(dir.length(), 0f));

        // bounding box in aligned space
        Vector2f mi = new Vector2f(
                Math.min(p0.x, p2.x),
                Math.min(p0.y, p2.y));
        Vector2f ma = new Vector2f(
                Math.max(p0.x, p2.x),
                Math.max(p0.y, p2.y));

        // check if control point lies outside bounding box
        if (p1.x < mi.x || p1.x > ma.x || p1.y < mi.y || p1.y > ma.y) {
            Vector2f num = new Vector2f(p0).sub(p1);
            Vector2f den = new Vector2f(p0).sub(new Vector2f(p1).mul(2f)).add(p2);
            Vector2f t = new Vector2f(
                    clamp(num.x / den.x),
                    clamp(num.y / den.y));
            Vector2f s = new Vector2f(1f, 1f).sub(t);

            Vector2f q = new Vector2f(p0).mul(s.x * s.x)
                    .add(new Vector2f(p1).mul(2f * s.x * t.x))
                    .add(new Vector2f(p2).mul(t.x * t.x));
            mi = new Vector2f(Math.min(mi.x, q.x), Math.min(mi.y, q.y));
            ma = new Vector2f(Math.max(ma.x, q.x), Math.max(ma.y, q.y));
        }

        // === inline boundingBoxFrame ===
        mi = new Vector2f(mi.x - lineWidth, mi.y - lineWidth);
        ma = new Vector2f(ma.x + lineWidth, ma.y + lineWidth);

        // rotate back
        Vector2f maRot = new Vector2f(p0).add(rot(new Vector2f(ma).sub(p0), cosb, -sinb));
        Vector2f miRot = new Vector2f(p0).add(rot(new Vector2f(mi).sub(p0), cosb, -sinb));

        float proj = ndir.dot(new Vector2f(miRot).sub(maRot));
        Vector2f offset = new Vector2f(ndir).mul(proj);

        Vector2f b = new Vector2f(maRot).add(offset);
        Vector2f d = new Vector2f(miRot).sub(offset);

        // assign quad corners (consistent winding order)
        topLeft = miRot;
        topRight = b;
        bottomRight = maRot;
        bottomLeft = d;

        uAxis = new Vector2f(bottomRight).sub(bottomLeft);
        vAxis = new Vector2f(topLeft).sub(bottomLeft);

        pATex = toScaledTextureSpace(pA);
        pBTex = toScaledTextureSpace(pB);
    }

    // --- helper methods ---

    private Vector2f rot(Vector2f p, float cosb, float sinb) {
        return new Vector2f(
                cosb * p.x - sinb * p.y,
                sinb * p.x + cosb * p.y);
    }

    private float wedge(Vector2f v1, Vector2f v2) {
        return v1.x * v2.y - v1.y * v2.x;
    }

    private float clamp(float t) {
        return Math.max(0f, Math.min(1f, t));
    }

    @Override
    protected void setUniforms() {
        /*
         * shader.setVec2("pointA", pA); shader.setFloat("phase", Clock.spin(20)); float
         * edgeDist = 0.35f; shader.setFloat("edgeDist", edgeDist);
         * shader.setFloat("edgeSharpness", edgeDist / (8 * edgeDist *
         * camera.getScaleFactor()));
         */
        shader.setVec2("iResolution", new Vector2f(width, height));
        shader.setFloat("iTime", Clock.time());
    }

}