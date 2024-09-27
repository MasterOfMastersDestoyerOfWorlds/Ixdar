#version 330 core
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
uniform sampler2D outerTexture;
float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}
float map(float value, float min1, float max1, float min2, float max2) {
    return min2 + (value - min1) * (max2 - min2) / (max1 - min1);
}
void main() {
    vec4 sample = texture(innerTexture, textureCoord);
    float sigDist = median(sample.r, sample.g, sample.b);
    float w = fwidth(sigDist);
    float opacity = smoothstep(0.5 - w, 0.5 + w, sigDist);
    float borderOpac = 0;
    float borderOffsetOpac = 0;
    if(sharpCorners) {
        float newRange = map(sigDist, 0, 1, 0, 2);
        borderOpac = smoothstep(1 - borderOuter, 1 - borderInner, newRange);
        borderOffsetOpac = smoothstep(1 - borderOffsetOuter, 1 - borderOffsetInner, newRange);
    } else {
        borderOpac = smoothstep(1 - borderOuter, 1 - borderInner, sample.a);
        borderOffsetOpac = smoothstep(1 - borderOffsetOuter, 1 - borderOffsetInner, sample.a);
    }

    fragColor = mix(mix(vec4(borderColor.rgb, borderColor.a * borderOpac), vec4(0), borderOffsetOpac), vertexColor, opacity);
    float var = sigDist;
    //fragColor = vec4(var, var, var, 1);
}