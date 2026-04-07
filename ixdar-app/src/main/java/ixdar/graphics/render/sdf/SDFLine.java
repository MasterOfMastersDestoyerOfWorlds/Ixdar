package ixdar.graphics.render.sdf;

import org.apache.commons.math3.util.Pair;
import org.joml.Vector2f;

import ixdar.graphics.cameras.Camera;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.Clock;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.shaders.SDFShader;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.graphics.render.shaders.ShaderProgram.ShaderType;
import ixdar.gui.ui.Drawing;

public class SDFLine extends ShaderDrawable {

    private ShaderProgram lineShader;
    private ShaderProgram dashedLineShader;
    private ShaderProgram dashedLineRoundShader;
    private ShaderProgram dashedLineEndCapsShader;
    private ShaderProgram arrowLineShader;
    private Color borderColor;
    private float borderInner;
    private float borderOuter;
    private float borderOffsetInner;
    private float borderOffsetOuter;
    private float lineWidth;
    private float dashLength;
    private boolean dashed;
    private boolean roundCaps;
    private float dashRate;
    private boolean endCaps;
    private float edgeDist;
    private boolean culling = true;
    private Vector2f pA;
    private Vector2f pB;
    private Color c2;
    private Color backgroundColor;
    private Vector2f pATex;
    private Vector2f pBTex;
    private boolean arrow;

    // Reusable fields for clipping to avoid per-call allocations
    private final Vector2f[] edgeCorners = new Vector2f[4];
    private final Vector2f[] intersections = new Vector2f[4];
    private final Vector2f dir = new Vector2f();
    private final Vector2f temp = new Vector2f();

    public SDFLine() {
        super();
        lineShader = ShaderType.LineSDF.getShader();
        dashedLineShader = ShaderType.DashedLineSDF.getShader();
        dashedLineRoundShader = ShaderType.DashedLineRoundSDF.getShader();
        dashedLineEndCapsShader = ShaderType.DashedLineEndCapsSDF.getShader();
        arrowLineShader = ShaderType.ArrowLineSDF.getShader();
        shader = lineShader;
        this.borderColor = Color.TRANSPARENT;
        this.backgroundColor = Color.TRANSPARENT;
        this.borderInner = 0;
        this.borderOuter = 0;
        this.borderOffsetInner = 0;
        this.borderOffsetOuter = 0;
        // Initialize reusable clipping fields
        edgeCorners[0] = new Vector2f();
        edgeCorners[1] = new Vector2f();
        edgeCorners[2] = new Vector2f();
        edgeCorners[3] = new Vector2f();
        intersections[0] = new Vector2f();
        intersections[1] = new Vector2f();
        intersections[2] = new Vector2f();
        intersections[3] = new Vector2f();
    }

    public SDFLine(SDFShader sdfShader, Color borderColor,
            float borderDist, float borderOffset) {
        lineShader = sdfShader;
        this.borderColor = borderColor;
        this.borderInner = borderDist - 0.1f;
        this.borderOuter = borderDist;
        this.borderOffsetInner = borderOffset - 0.1f;
        this.borderOffsetOuter = borderOffset;
        setShader();
    }

    float lengthSq(Vector2f a, Vector2f b) {
        Vector2f r = new Vector2f(a).sub(b);
        return r.x * r.x + r.y * r.y;
    }

    public void draw(Vector2f pA, Vector2f pB, Color c, Camera camera) {
        draw(pA, pB, c, c, camera);
    }

    public void draw(Vector2f pA, Vector2f pB, Color c, Color c2, Camera camera) {
        this.pA = pA;
        this.pB = pB;
        this.c = c;
        this.c2 = c2;
        setShader();
        draw(camera);
    }

    public void setShader() {
        shader = lineShader;
        if (dashed) {
            shader = dashedLineShader;
            if (endCaps) {
                shader = dashedLineEndCapsShader;
            } else if (roundCaps) {
                shader = dashedLineRoundShader;
            }
        } else if (arrow) {
            shader = arrowLineShader;
        }
    }

    public void setBorderDist(float borderDist) {
        this.borderInner = borderDist - 0.1f;
        this.borderOuter = borderDist;
    }

    public void setBorderColor(Color borderColor) {
        this.borderColor = borderColor;
    }

    public void setBorderOffset(float borderOffset) {
        this.borderOffsetInner = borderOffset - 0.1f;
        this.borderOffsetOuter = borderOffset;
    }

    public void setBackgroundColor(Color backgroundColor) {
        this.backgroundColor = backgroundColor;
    }

    public void setBorderBand(float borderWidth) {
        float clampedWidth = Math.max(0f, borderWidth);
        float feather = 0.02f;
        this.borderInner = edgeDist;
        this.borderOuter = edgeDist + clampedWidth;
        this.borderOffsetInner = this.borderOuter;
        this.borderOffsetOuter = this.borderOuter + feather;
    }

    public void setStroke(boolean dashed) {
        this.dashed = dashed;
        edgeDist = 0.35f;
        setShader();
    }

    public void setStroke(float lineWidth, boolean dashed) {
        this.lineWidth = Math.max(lineWidth, Drawing.MIN_THICKNESS / 3f);
        this.dashed = dashed;
        edgeDist = 0.35f;
        setShader();
    }

    public void setStroke(float lineWidth, boolean dashed, float dashLength, float dashRate, boolean roundCaps,
            boolean endCaps, boolean arrow) {
        this.lineWidth = Math.max(lineWidth, Drawing.MIN_THICKNESS);
        this.dashed = dashed;
        this.dashLength = dashLength;
        this.dashRate = dashRate;
        this.roundCaps = roundCaps;
        this.endCaps = endCaps;
        this.arrow = arrow;
        edgeDist = 0.35f;
        setShader();
    }

    public void setStroke(float lineWidth, boolean dashed, float dashLength, float dashRate, boolean roundCaps,
            boolean endCaps, boolean arrow, Camera2D camera2d) {
        this.lineWidth = Math.max(lineWidth, Drawing.MIN_THICKNESS);
        this.dashed = dashed;
        this.dashLength = dashLength;
        this.dashRate = dashRate;
        this.roundCaps = roundCaps;
        this.endCaps = endCaps;
        this.arrow = arrow;
        edgeDist = 0.35f;
        setShader();
    }

    @Override
    public void calculateQuad() {
        culled = false;
        if (culling) {
            boolean containsA = camera.contains(pA);
            boolean containsB = camera.contains(pB);
            
            if (!containsA && !containsB) {
                // Both endpoints outside - need to find all intersections
                float width = camera.getWidth();
                float height = camera.getHeight();
                
                // Initialize edge corners (reuse field)
                edgeCorners[0].set(0, 0);  // botLeft
                edgeCorners[1].set(width, 0);  // botRight
                edgeCorners[2].set(width, height);  // topRight
                edgeCorners[3].set(0, height);  // topLeft
                
                // Collect all intersection points
                int numIntersections = 0;
                
                // Check each edge of the viewport
                for (int i = 0; i < 4; i++) {
                    int next = (i + 1) % 4;
                    Pair<Boolean, Vector2f> result = get_line_intersection(pA, pB, edgeCorners[i], edgeCorners[next]);
                    if (result.getFirst() && result.getSecond() != null) {
                        // Store intersection in reusable field
                        intersections[numIntersections].set(result.getSecond());
                        numIntersections++;
                    }
                }
                
                if (numIntersections == 0) {
                    // No intersections - line is completely outside
                    culled = true;
                    return;
                } else if (numIntersections == 1) {
                    // Tangent case - line touches but doesn't cross
                    culled = true;
                    return;
                } else if (numIntersections >= 2) {
                    // Line crosses viewport - use first two intersections
                    // These are the entry and exit points
                    Vector2f newA = intersections[0];
                    Vector2f newB = intersections[1];
                    
                    // Update pA and pB with the clipped segment
                    pA.x = newA.x;
                    pA.y = newA.y;
                    pB.x = newB.x;
                    pB.y = newB.y;
                    
                    return;
                }
            } else if (!containsA || !containsB) {
                // One endpoint inside, one outside - find the single intersection
                float width = camera.getWidth();
                float height = camera.getHeight();
                
                // Initialize edge corners
                edgeCorners[0].set(0, 0);  // botLeft
                edgeCorners[1].set(width, 0);  // botRight
                edgeCorners[2].set(width, height);  // topRight
                edgeCorners[3].set(0, height);  // topLeft
                
                // Find the intersection with the viewport boundary
                for (int i = 0; i < 4; i++) {
                    int next = (i + 1) % 4;
                    Pair<Boolean, Vector2f> result = get_line_intersection(pA, pB, edgeCorners[i], edgeCorners[next]);
                    if (result.getFirst() && result.getSecond() != null) {
                        Vector2f intersection = intersections[0];
                        intersection.set(result.getSecond());
                        
                        // Update the endpoint that was outside
                        if (!containsA) {
                            pA.x = intersection.x;
                            pA.y = intersection.y;
                        } else {
                            pB.x = intersection.x;
                            pB.y = intersection.y;
                        }
                        return;
                    }
                }
            }
            // Both endpoints inside - no clipping needed
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
        uAxis = new Vector2f(bottomRight).sub(bottomLeft);
        vAxis = new Vector2f(topLeft).sub(bottomLeft);
        width = uAxis.length();
        height = vAxis.length();
        widthToHeightRatio = width / height;
        texWidth = widthToHeightRatio;
        texHeight = 1;
        pATex = toScaledTextureSpace(pA);
        pBTex = toScaledTextureSpace(pB);
    }

    @Override
    protected void setUniforms() {
        shader.setFloat("edgeSharpness", (float) Math.min(1 / (lineWidth * 2), 0.1));
        shader.setFloat("dashPhase", Clock.spin(dashRate));
        float inverseLineLengthSq = 1 / lengthSq(pATex, pBTex);
        shader.setFloat("lineLengthSq", lengthSq(pATex, pBTex));
        shader.setFloat("inverseLineLengthSq", inverseLineLengthSq);
        shader.setVec2("pointA", pATex);
        shader.setVec2("pointB", pBTex);
        shader.setFloat("dashes", (float) (pATex.distance(pBTex) / (dashLength)));
        shader.setFloat("dashEdgeDist", (float) (Math.PI * width * edgeDist) / (dashLength));
        shader.setVec4("linearGradientColor", c2.toVector4f());
        shader.setFloat("borderInner", borderInner);
        shader.setFloat("borderOuter", borderOuter);
        shader.setFloat("borderOffsetInner", borderOffsetInner);
        shader.setFloat("borderOffsetOuter", borderOffsetOuter);
        shader.setVec4("borderColor", borderColor.toVector4f());
        shader.setVec4("backgroundColor", backgroundColor.toVector4f());
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

    public void setEndpoints(Camera2D camera2d, Vector2f pA, Vector2f pB) {
        this.camera = camera2d;
        this.pA = pA;
        this.pB = pB;
    }

    public void setCulling(boolean b) {
        culling = b;
    }

}