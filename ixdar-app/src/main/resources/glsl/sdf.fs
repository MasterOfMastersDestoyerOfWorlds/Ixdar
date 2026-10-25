#version 300 es
precision highp float;
in vec4 vertexColor;
in vec2 textureCoord;

out vec4 fragColor;

uniform bool sharpCorners;

uniform float borderInner;
uniform float borderOuter;

uniform float borderOffsetInner;
uniform float borderOffsetOuter;

uniform vec4 borderColor;

uniform sampler2D innerTexture;

uniform float pxRange;
uniform float edgeDist;
uniform float edgeSharpness;

float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}
float map(float value, float min1, float max1, float min2, float max2) {
    return min2 + (value - min1) * (max2 - min2) / (max1 - min1);
}
void main() {
    vec4 smp = texture(innerTexture, textureCoord);
    float sigDist = median(smp.r, smp.g, smp.b);
    // The atlas edge band is pxRange texels wide; measured in screen pixels it shrinks with the
    // text, and below one pixel a smoothstep over fwidth turns binary and drops thin stems. Keep the
    // ramp at least one pixel wide (msdfgen's screenPxRange, github.com/Chlumsky/msdfgen, MIT).
    // Where the band is under two pixels the sample comes from a mipmap, whose averaged channels
    // break the median at corners, so fade to the alpha channel's true distance (MTSDF).
    vec2 unitRange = vec2(pxRange) / vec2(textureSize(innerTexture, 0));
    float screenPxRange = 0.5 * dot(unitRange, vec2(1.0) / fwidth(textureCoord));
    float distance = mix(smp.a, sigDist, clamp(screenPxRange - 1.0, 0.0, 1.0));
    float opacity = clamp(max(screenPxRange, 1.0) * (distance - edgeDist) + 0.5, 0.0, 1.0);
    float fillAlpha = vertexColor.a * opacity;
    // The border band runs from borderInner to borderOuter screen pixels outside the glyph edge,
    // measured on the alpha channel's true distance, which stays valid far from the edge where the
    // channels' median does not. It reaches only as far as the atlas's distance range, so the atlas
    // is generated with a range wider than any border drawn. A transparent border leaves the fill.
    float outsidePixels = max(screenPxRange, 1.0) * (edgeDist - smp.a);
    float band = clamp(borderOuter - outsidePixels + 0.5, 0.0, 1.0)
            * clamp(outsidePixels - borderInner + 0.5, 0.0, 1.0);
    float borderAlpha = borderColor.a * max(band, opacity) * (1.0 - fillAlpha);
    float alpha = fillAlpha + borderAlpha;
    vec3 colour = alpha > 0.0 ? (vertexColor.rgb * fillAlpha + borderColor.rgb * borderAlpha) / alpha
            : vertexColor.rgb;
    fragColor = vec4(colour, alpha);
}