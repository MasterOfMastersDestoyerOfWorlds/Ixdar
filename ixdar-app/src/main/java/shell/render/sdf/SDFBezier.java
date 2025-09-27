package shell.render.sdf;

import org.joml.Vector2f;

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
        // Oriented bounding box for quadratic Bezier (per Vlad Jukov / iq)

        // Transform points from point space to camera/screen space
        Vector2f a = new Vector2f(camera.pointTransformX(pA.x), camera.pointTransformY(pA.y));
        Vector2f c = new Vector2f(camera.pointTransformX(pB.x), camera.pointTransformY(pB.y));
        Vector2f ctrl = new Vector2f(camera.pointTransformX(pControl.x), camera.pointTransformY(pControl.y));

        Vector2f dir = new Vector2f(c).sub(a);
        Vector2f ndir = new Vector2f(dir).normalize();
        Vector2f ox = new Vector2f(1f, 0f);
        float sinb = wedge(ndir, ox);
        float cosb = new Vector2f(c).sub(a).normalize().dot(ox);

        Vector2f p0 = new Vector2f(a);
        Vector2f p0p1 = new Vector2f(ctrl).sub(a);
        Vector2f p1 = new Vector2f(p0).add(rot(p0p1, cosb, sinb));
        Vector2f p2 = new Vector2f(p0).add(new Vector2f(dir.length(), 0f));

        Vector2f mi = new Vector2f(Math.min(p0.x, p2.x), Math.min(p0.y, p2.y));
        Vector2f ma = new Vector2f(Math.max(p0.x, p2.x), Math.max(p0.y, p2.y));
        if (p1.x < mi.x || p1.x > ma.x || p1.y < mi.y || p1.y > ma.y) {
            Vector2f num = new Vector2f(p0).sub(p1);
            Vector2f den = new Vector2f(p0).sub(new Vector2f(p1).mul(2f)).add(p2);
            Vector2f t = new Vector2f(clamp(num.x / den.x), clamp(num.y / den.y));
            Vector2f s = new Vector2f(1f, 1f).sub(t);
            // Component-wise evaluation of the quadratic Bezier at t.x (for x) and t.y (for
            // y)
            float qx = s.x * s.x * p0.x + 2f * s.x * t.x * p1.x + t.x * t.x * p2.x;
            float qy = s.y * s.y * p0.y + 2f * s.y * t.y * p1.y + t.y * t.y * p2.y;
            Vector2f q = new Vector2f(qx, qy);
            mi = new Vector2f(Math.min(mi.x, q.x), Math.min(mi.y, q.y));
            ma = new Vector2f(Math.max(ma.x, q.x), Math.max(ma.y, q.y));
        }

        mi = new Vector2f(mi.x - lineWidth, mi.y - lineWidth);
        ma = new Vector2f(ma.x + lineWidth, ma.y + lineWidth);

        Vector2f maRot = new Vector2f(p0).add(rot(new Vector2f(ma).sub(p0), cosb, -sinb));
        Vector2f miRot = new Vector2f(p0).add(rot(new Vector2f(mi).sub(p0), cosb, -sinb));
        float proj = ndir.dot(new Vector2f(miRot).sub(maRot));
        Vector2f offset = new Vector2f(ndir).mul(proj);
        Vector2f b = new Vector2f(maRot).add(offset);
        Vector2f d = new Vector2f(miRot).sub(offset);

        topLeft = miRot;
        topRight = b;
        bottomRight = maRot;
        bottomLeft = d;

        uAxis = new Vector2f(bottomRight).sub(bottomLeft);
        vAxis = new Vector2f(topLeft).sub(bottomLeft);

        pATex = toScaledTextureSpace(a);
        pBTex = toScaledTextureSpace(c);
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
        shader.setVec2("iResolution", new Vector2f(width, height));
        shader.setFloat("widthToHeightRatio", widthToHeightRatio);
        // Convert points to camera/screen space then to scaled texture space
        Vector2f a = new Vector2f(camera.pointTransformX(pA.x), camera.pointTransformY(pA.y));
        Vector2f c = new Vector2f(camera.pointTransformX(pB.x), camera.pointTransformY(pB.y));
        Vector2f ctrl = new Vector2f(camera.pointTransformX(pControl.x), camera.pointTransformY(pControl.y));
        shader.setVec2("pointA", toScaledTextureSpace(a));
        shader.setVec2("pointB", toScaledTextureSpace(c));
        shader.setVec2("control", toScaledTextureSpace(ctrl));
        float thicknessTex = (height != 0f) ? (lineWidth / height) : lineWidth;
        shader.setFloat("thickness", Math.max(thicknessTex, 0.001f));
        shader.setFloat("width", width);
        shader.setFloat("height", height);
        float edgeDist = 0.15f;
        shader.setFloat("edgeDist", edgeDist);
        shader.setFloat("edgeSharpness", edgeDist / (20 * edgeDist * camera.getScaleFactor()));
    }

}