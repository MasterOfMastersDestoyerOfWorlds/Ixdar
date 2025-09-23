package shell.render.sdf;

import org.apache.commons.math3.util.Pair;
import org.joml.Vector2f;

import shell.cameras.Camera;
import shell.render.Clock;
import shell.render.color.Color;
import shell.render.shaders.SDFShader;
import shell.render.shaders.ShaderProgram;
import shell.render.shaders.ShaderProgram.ShaderType;
import shell.ui.Drawing;

public class SDFLine extends ShaderDrawable {

    public ShaderProgram line_shader;
    public ShaderProgram dashed_line_shader;
    private Color borderColor;
    private float borderInner;
    private float borderOuter;
    private float borderOffsetInner;
    private float borderOffsetOuter;
    public float lineWidth;
    public float dashLength;
    public boolean dashed;
    public boolean roundCaps;
    public float dashRate;
    public boolean endCaps;
    private float edgeDist;
    public boolean culling = true;
    public Vector2f pA;
    public Vector2f pB;
    public Color c2;

    public SDFLine() {
        line_shader = ShaderType.LineSDF.getShader();
        dashed_line_shader = ShaderType.DashedLineSDF.getShader();
        shader = line_shader;
        this.borderColor = Color.TRANSPARENT;
        this.borderInner = 0;
        this.borderOuter = 0;
        this.borderOffsetInner = 0;
        this.borderOffsetOuter = 0;
    }

    public SDFLine(SDFShader sdfShader, Color borderColor,
            float borderDist, float borderOffset) {
        line_shader = sdfShader;
        this.borderColor = borderColor;
        this.borderInner = borderDist - 0.1f;
        this.borderOuter = borderDist;
        this.borderOffsetInner = borderOffset - 0.1f;
        this.borderOffsetOuter = borderOffset;
    }

    float lengthSq(Vector2f a, Vector2f b) {
        Vector2f r = new Vector2f(a).sub(b);
        return r.x * r.x + r.y * r.y;
    }

    public void draw(Vector2f pA, Vector2f pB, Color c, long id, Camera camera) {
        draw(pA, pB, c, c, id, camera);
    }

    public void draw(Vector2f pA, Vector2f pB, Color c, Color c2, long id, Camera camera) {
        this.pA = pA;
        this.pB = pB;
        this.c = c;
        this.c2 = c2;
        shader = line_shader;
        if (dashed) {
            shader = dashed_line_shader;
        }
        draw(camera, id);
    }

    public void setBorderDist(float borderDist) {
        this.borderInner = borderDist - 0.1f;
        this.borderOuter = borderDist;
    }

    public void setStroke(float lineWidth, boolean dashed) {
        this.lineWidth = Math.max(lineWidth, Drawing.MIN_THICKNESS / 3f);
        this.dashed = dashed;
        edgeDist = 0.35f;
    }

    public void setStroke(float lineWidth, boolean dashed, float dashLength, float dashRate, boolean roundCaps,
            boolean endCaps) {
        this.lineWidth = Math.max(lineWidth, Drawing.MIN_THICKNESS);
        this.dashed = dashed;
        this.dashLength = dashLength;
        this.dashRate = dashRate;
        this.roundCaps = roundCaps;
        this.endCaps = endCaps;

        edgeDist = 0.35f;
    }

    @Override
    public void calculateQuad() {
        if (culling) {
            boolean containsA = camera.contains(pA);
            boolean containsB = camera.contains(pB);
            if (!containsA || !containsB) {
                // Test square intersection
                float width = camera.getWidth();
                float height = camera.getHeight();
                Vector2f botLeft = new Vector2f(0, 0);
                Vector2f topLeft = new Vector2f(0, height);
                Vector2f topRight = new Vector2f(width, height);
                Vector2f botRight = new Vector2f(width, 0);
                Pair<Boolean, Vector2f> right = get_line_intersection(pA, pB, topRight, botRight);
                Pair<Boolean, Vector2f> left = get_line_intersection(pA, pB, topLeft, botLeft);
                Pair<Boolean, Vector2f> up = get_line_intersection(pA, pB, topLeft, topRight);
                Pair<Boolean, Vector2f> down = get_line_intersection(pA, pB, botLeft, botRight);
                float diagonalDistance = botLeft.distance(topRight);
                Vector2f dir = new Vector2f(pA).sub(pB).normalize().mul(diagonalDistance);

                if (right.getFirst()) {
                    if (containsA) {
                        pB = right.getSecond();
                    } else if (containsB) {
                        pA = right.getSecond();
                    } else {
                        pA = right.getSecond();
                        pB = new Vector2f(pA).add(dir);
                        if (pB.x > width) {
                            pB = new Vector2f(pA).add(dir.negate());
                        }
                    }
                } else if (left.getFirst()) {
                    if (containsA) {
                        pB = left.getSecond();
                    } else if (containsB) {
                        pA = left.getSecond();
                    } else {
                        pA = left.getSecond();
                        pB = new Vector2f(pA).add(dir);
                        if (pB.x < 0) {
                            pB = new Vector2f(pA).add(dir.negate());
                        }
                    }
                } else if (up.getFirst()) {

                    if (containsA) {
                        pB = up.getSecond();
                    } else if (containsB) {
                        pA = up.getSecond();
                    } else {
                        pA = up.getSecond();
                        pB = new Vector2f(pA).add(dir);
                        if (pB.y > height) {
                            pB = new Vector2f(pA).add(dir.negate());
                        }
                    }
                } else if (down.getFirst()) {
                    if (containsA) {
                        pB = down.getSecond();
                    } else if (containsB) {
                        pA = down.getSecond();
                    } else {
                        pA = down.getSecond();
                        pB = new Vector2f(pA).add(dir);
                        if (pB.y < 0) {
                            pB = new Vector2f(pA).add(dir.negate());
                        }
                    }
                } else {
                    return;
                }
            }
        }
        float dx = pB.x - pA.x;
        float dy = pB.y - pA.y;
        float normalX = -dy;
        float normalY = dx;
        Vector2f normalUnitVector = new Vector2f(normalX, normalY);
        normalUnitVector = normalUnitVector.normalize().mul(lineWidth * 2);
        Vector2f line = new Vector2f(pA).sub(pB);
        Vector2f lineVectorA = line.normalize().mul(lineWidth * 2);
        topLeft = new Vector2f(normalUnitVector).add(pA).add(lineVectorA);
        bottomLeft = new Vector2f(pA).sub(normalUnitVector).add(lineVectorA);
        topRight = new Vector2f(normalUnitVector).add(pB).sub(lineVectorA);
        bottomRight = new Vector2f(pB).sub(normalUnitVector).sub(lineVectorA);
    }

    @Override
    protected void setUniforms() {
        shader.setFloat("edgeSharpness", (float) Math.min(1 / (lineWidth * 2), 0.1));
        shader.setFloat("dashPhase", Clock.spin(dashRate));
        float inverseLineLengthSq = 1 / lengthSq(pA, pB);
        shader.setFloat("lineLengthSq", lengthSq(pA, pB));
        shader.setFloat("inverseLineLengthSq", inverseLineLengthSq);
        shader.setVec2("pointA", pA);
        shader.setVec2("pointB", pB);
        shader.setFloat("width", width);
        shader.setFloat("height", height);
        shader.setFloat("dashes", (float) ((Math.PI * height) / (dashLength)));
        shader.setFloat("dashEdgeDist", (float) (Math.PI * width * edgeDist) / (dashLength));
        shader.setVec4("linearGradientColor", c2.toVector4f());
        shader.setFloat("borderInner", borderInner);
        shader.setFloat("borderOuter", borderOuter);
        shader.setFloat("borderOffsetInner", borderOffsetInner);
        shader.setFloat("borderOffsetOuter", borderOffsetOuter);
        shader.setVec4("borderColor", borderColor.toVector4f());
        shader.setBool("dashed", dashed);
        shader.setBool("endCaps", endCaps);
        shader.setBool("roundCaps", roundCaps);
        shader.setFloat("dashLength", dashLength);
        shader.setFloat("edgeDist", edgeDist);
    }

    /**
     * Returns 1 if the lines intersect, otherwise 0. In addition, if the lines /*
     * intersect the intersection point may be stored in the floats i_x and i_y.
     */
    public Pair<Boolean, Vector2f> get_line_intersection(Vector2f pA, Vector2f pB, Vector2f pC, Vector2f pD) {
        float s1_x, s1_y, s2_x, s2_y;
        s1_x = pB.x - pA.x;
        s1_y = pB.y - pA.y;
        s2_x = pD.x - pC.x;
        s2_y = pD.y - pC.y;

        float s, t;
        s = (-s1_y * (pA.x - pC.x) + s1_x * (pA.y - pC.y)) / (-s2_x * s1_y + s1_x * s2_y);
        t = (s2_x * (pA.y - pC.y) - s2_y * (pA.x - pC.x)) / (-s2_x * s1_y + s1_x * s2_y);

        if (s >= 0 && s <= 1 && t >= 0 && t <= 1) {
            return new Pair<Boolean, Vector2f>(true, new Vector2f(pA.x + (t * s1_x), pA.y + (t * s1_y)));
        }

        return new Pair<Boolean, Vector2f>(false, null);
    }

}