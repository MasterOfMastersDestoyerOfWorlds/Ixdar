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
// Which part of each glyph to draw. An outlined batch is drawn as LAYER_BORDER_ONLY then LAYER_FILL_ONLY,
// so no glyph's band lands on a neighbour's fill; SDFTexture holds the same values.
#define LAYER_FILL_OVER_BORDER 0
#define LAYER_BORDER_ONLY 1
#define LAYER_FILL_ONLY 2
uniform int layer;

uniform sampler2D innerTexture;

uniform float pxRange;
uniform float medianMaxLod;
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
    // A mipmap texel averages the channels under it, and where it spans two glyphs' atlas cells
    // their median reads as ink on the quad's edge. The channels come from no level coarser than
    // medianMaxLod, whose reach the atlas's padding holds inside each cell; the alpha stays trilinear.
    vec2 texels = vec2(textureSize(innerTexture, 0));
    float lod = log2(max(length(dFdx(textureCoord) * texels), length(dFdy(textureCoord) * texels)));
    vec3 channels = textureLod(innerTexture, textureCoord, clamp(lod, 0.0, medianMaxLod)).rgb;
    float sigDist = median(channels.r, channels.g, channels.b);
    // The atlas edge band is pxRange texels wide; measured in screen pixels it shrinks with the
    // text, and below one pixel a smoothstep over fwidth turns binary and drops thin stems. Keep the
    // ramp at least one pixel wide (msdfgen's screenPxRange, github.com/Chlumsky/msdfgen, MIT).
    // Where the band is under two pixels the averaged channels break the median at corners too, so
    // fade to the alpha channel's true distance (MTSDF).
    vec2 unitRange = vec2(pxRange) / texels;
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
    if (layer == LAYER_FILL_ONLY) {
        fragColor = vec4(vertexColor.rgb, fillAlpha);
        return;
    }
    if (layer == LAYER_BORDER_ONLY) {
        fragColor = vec4(borderColor.rgb, borderColor.a * max(band, opacity));
        return;
    }
    float borderAlpha = borderColor.a * max(band, opacity) * (1.0 - fillAlpha);
    float alpha = fillAlpha + borderAlpha;
    vec3 colour = alpha > 0.0 ? (vertexColor.rgb * fillAlpha + borderColor.rgb * borderAlpha) / alpha
            : vertexColor.rgb;
    fragColor = vec4(colour, alpha);
}