#version 300 es
precision mediump float;
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
float screenPxRange() {
    vec2 unitRange = vec2(6.0) / vec2(textureSize(innerTexture, 0));
    vec2 screenTexSize = vec2(1.0) / fwidth(textureCoord);
    return max(0.5 * dot(unitRange, screenTexSize), 1.0);
}
float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}
float map(float value, float min1, float max1, float min2, float max2) {
    return min2 + (value - min1) * (max2 - min2) / (max1 - min1);
}
void main() {
    vec4 smp = texture(innerTexture, textureCoord);
    float sigDist = median(smp.r, smp.g, smp.b);
    float pxDist = screenPxRange() * (sigDist - 0.5);
    float opacity = clamp(pxDist + 0.5, 0.0, 1.0);

    float borderOpac = 0.;
    float borderOffsetOpac = 0.;
    if(sharpCorners) {
        float newRange = map(sigDist, 0., 1., 0., 2.);
        borderOpac = smoothstep(1. - borderOuter, 1. - borderInner, newRange);
        borderOffsetOpac = smoothstep(1. - borderOffsetOuter, 1. - borderOffsetInner, newRange);
    } else {
        borderOpac = smoothstep(1. - borderOuter, 1. - borderInner, smp.a);
        borderOffsetOpac = smoothstep(1. - borderOffsetOuter, 1. - borderOffsetInner, smp.a);
    }
    fragColor = mix(mix(vec4(borderColor.rgb, borderColor.a * borderOpac), vec4(0), borderOffsetOpac), vertexColor, opacity);
}