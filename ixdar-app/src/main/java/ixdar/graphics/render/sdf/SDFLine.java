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
    public static final float NUM_0_1 = 0.1f;
    public static final float NUM_0 = 0f;
    public static final float NUM_0_02 = 0.02f;
    public static final float NUM_0_35 = 0.35f;
    public static final float NUM_3 = 3f;
    public static final double NUM_0_1_2 = 0.1;

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

    /**
     * Cache shaders for every line variant (solid, dashed, round-cap, end-cap,
     * arrow) and start with the solid line shader and transparent border.
     */
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
    }

    /**
     * Build a line drawable using a custom SDF shader with a fixed border band.
     *
     * @param sdfShader explicit SDF program to use as the active line shader
     * @param borderColor color drawn in the border band
     * @param borderDist outer border radius (distance from edge)
     * @param borderOffset offset of the border start from the line edge
     */
    public SDFLine(SDFShader sdfShader, Color borderColor,
            float borderDist, float borderOffset) {
        lineShader = sdfShader;
        this.borderColor = borderColor;
        this.borderInner = borderDist - NUM_0_1;
        this.borderOuter = borderDist;
        this.borderOffsetInner = borderOffset - NUM_0_1;
        this.borderOffsetOuter = borderOffset;
        setShader();
    }

    float lengthSq(Vector2f a, Vector2f b) {
        Vector2f r = new Vector2f(a).sub(b);
        return r.x * r.x + r.y * r.y;
    }

    /**
     * Draw a solid-color line segment from {@code pA} to {@code pB}.
     *
     * @param pA start endpoint in world coordinates
     * @param pB end endpoint in world coordinates
     * @param c line color (used for both gradient stops)
     * @param camera camera supplying view/projection
     */
    public void draw(Vector2f pA, Vector2f pB, Color c, Camera camera) {
        draw(pA, pB, c, c, camera);
    }

    /**
     * Draw a line segment with a linear gradient from {@code c} at {@code pA}
     * to {@code c2} at {@code pB}.
     *
     * @param pA start endpoint in world coordinates
     * @param pB end endpoint in world coordinates
     * @param c color at {@code pA}
     * @param c2 color at {@code pB}
     * @param camera camera supplying view/projection
     */
    public void draw(Vector2f pA, Vector2f pB, Color c, Color c2, Camera camera) {
        this.pA = pA;
        this.pB = pB;
        this.c = c;
        this.c2 = c2;
        setShader();
        draw(camera);
    }

    /**
     * Pick the active shader from the dashed/round/end-cap/arrow flags.
     */
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

    /**
     * Set the border outer radius and a 0.1-unit feather inner edge.
     *
     * @param borderDist outer border radius
     */
    public void setBorderDist(float borderDist) {
        this.borderInner = borderDist - NUM_0_1;
        this.borderOuter = borderDist;
    }

    /**
     * Set the color used for the border band.
     *
     * @param borderColor color drawn between {@code borderInner} and {@code borderOuter}
     */
    public void setBorderColor(Color borderColor) {
        this.borderColor = borderColor;
    }

    /**
     * Set the border-band offset and a 0.1-unit feather inner edge.
     *
     * @param borderOffset offset of the border start from the line edge
     */
    public void setBorderOffset(float borderOffset) {
        this.borderOffsetInner = borderOffset - NUM_0_1;
        this.borderOffsetOuter = borderOffset;
    }

    /**
     * Set the color rendered inside the line body (behind the gradient).
     *
     * @param backgroundColor fill color
     */
    public void setBackgroundColor(Color backgroundColor) {
        this.backgroundColor = backgroundColor;
    }

    /**
     * Configure a feathered border band starting at the edge distance and
     * extending outward by {@code borderWidth} (clamped to non-negative).
     *
     * @param borderWidth thickness of the border band in distance-field units
     */
    public void setBorderBand(float borderWidth) {
        float clampedWidth = Math.max(NUM_0, borderWidth);
        float feather = NUM_0_02;
        this.borderInner = edgeDist;
        this.borderOuter = edgeDist + clampedWidth;
        this.borderOffsetInner = this.borderOuter;
        this.borderOffsetOuter = this.borderOuter + feather;
    }

    /**
     * Toggle dashed stroking and reset the edge distance.
     *
     * @param dashed {@code true} to use the dashed-line shader on next draw
     */
    public void setStroke(boolean dashed) {
        this.dashed = dashed;
        edgeDist = NUM_0_35;
        setShader();
    }

    /**
     * Set line thickness (clamped to {@code MIN_THICKNESS/3}) and dashed flag.
     *
     * @param lineWidth desired thickness in world units
     * @param dashed {@code true} to use the dashed-line shader on next draw
     */
    public void setStroke(float lineWidth, boolean dashed) {
        this.lineWidth = Math.max(lineWidth, Drawing.MIN_THICKNESS / NUM_3);
        this.dashed = dashed;
        edgeDist = NUM_0_35;
        setShader();
    }

    /**
     * Configure all stroke parameters at once and pick the matching shader.
     *
     * @param lineWidth thickness in world units (clamped to {@code MIN_THICKNESS})
     * @param dashed enable dashed rendering
     * @param dashLength dash period in world units
     * @param dashRate dash phase advance rate (animated via {@code Clock})
     * @param roundCaps render dashes with rounded ends
     * @param endCaps render rounded caps at the line endpoints
     * @param arrow render an arrowhead shader at the end
     */
    public void setStroke(float lineWidth, boolean dashed, float dashLength, float dashRate, boolean roundCaps,
            boolean endCaps, boolean arrow) {
        this.lineWidth = Math.max(lineWidth, Drawing.MIN_THICKNESS);
        this.dashed = dashed;
        this.dashLength = dashLength;
        this.dashRate = dashRate;
        this.roundCaps = roundCaps;
        this.endCaps = endCaps;
        this.arrow = arrow;
        edgeDist = NUM_0_35;
        setShader();
    }

    /**
     * Camera-aware overload of
     * {@link #setStroke(float, boolean, float, float, boolean, boolean, boolean)}
     * (camera kept for callers that thread it through; not currently used here).
     *
     * @param lineWidth thickness in world units (clamped to {@code MIN_THICKNESS})
     * @param dashed enable dashed rendering
     * @param dashLength dash period in world units
     * @param dashRate dash phase advance rate
     * @param roundCaps render dashes with rounded ends
     * @param endCaps render rounded caps at the line endpoints
     * @param arrow render an arrowhead shader at the end
     * @param camera2d 2D camera context (unused)
     */
    public void setStroke(float lineWidth, boolean dashed, float dashLength, float dashRate, boolean roundCaps,
            boolean endCaps, boolean arrow, Camera2D camera2d) {
        this.lineWidth = Math.max(lineWidth, Drawing.MIN_THICKNESS);
        this.dashed = dashed;
        this.dashLength = dashLength;
        this.dashRate = dashRate;
        this.roundCaps = roundCaps;
        this.endCaps = endCaps;
        this.arrow = arrow;
        edgeDist = NUM_0_35;
        setShader();
    }

    /**
     * Build the line's bounding quad: clip the segment to the camera viewport
     * (when culling is on), then expand to a rectangle thick enough to contain
     * the stroke and compute texture-space endpoints for the SDF shader.
     */
    @Override
    public void calculateQuad() {
        culled = false;
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
                    culled = true;
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

    /**
     * Push line endpoints, dash phase/length, edge sharpness, gradient color,
     * and border band parameters in texture space.
     */
    @Override
    protected void setUniforms() {
        shader.setFloat("edgeSharpness", (float) Math.min(1 / (lineWidth * 2), NUM_0_1_2));
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
     * Compute the intersection of two line segments, if any.
     *
     * @param pA first endpoint of segment 1
     * @param pB second endpoint of segment 1
     * @param pC first endpoint of segment 2
     * @param pD second endpoint of segment 2
     * @return ({@code true}, intersection point) when the segments cross;
     *         ({@code false}, {@code null}) otherwise
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

    /**
     * Set the line endpoints and current camera without triggering a draw.
     *
     * @param camera2d active 2D camera
     * @param pA start endpoint in world coordinates
     * @param pB end endpoint in world coordinates
     */
    public void setEndpoints(Camera2D camera2d, Vector2f pA, Vector2f pB) {
        this.camera = camera2d;
        this.pA = pA;
        this.pB = pB;
    }

    /**
     * Enable or disable viewport-clipping in {@link #calculateQuad()}.
     *
     * @param b {@code true} to clip segments to the camera rectangle
     */
    public void setCulling(boolean b) {
        culling = b;
    }

}