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
    float w = fwidth(sigDist); 
    float opacity = smoothstep(edgeDist - w, edgeDist + w, sigDist);
    fragColor = vec4(vertexColor.r, vertexColor.g, vertexColor.b, vertexColor.a * opacity);
}