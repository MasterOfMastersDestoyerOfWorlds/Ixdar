#version 300 es
precision mediump float;

in vec4 vertexColor;
in vec2 scaledTextureCoord;
in vec2 textureCoord;

uniform vec2 pointA;
uniform vec2 pointB;
uniform float inverseLineLengthSq;
uniform float widthToHeightRatio;
uniform float edgeDist;
uniform float edgeSharpness;
uniform vec4 linearGradientColor;

out vec4 fragColor;

void main() {

    float t = max(0., min(1., dot(scaledTextureCoord - pointA, pointB - pointA) * inverseLineLengthSq));
    vec2 projection = pointA + t * (pointB - pointA);
    float sigDist = distance(scaledTextureCoord, projection);
    
    // The anti-aliasing band is one pixel measured on the texture coordinates, not fwidth(sigDist):
    // the distance to the axis has a kink on the axis, so a 2x2 derivative quad straddling it sees
    // two equal values, fwidth collapses to 0, and smoothstep with equal (or, as written before,
    // reversed) edges is undefined in GLSL - drivers return 0 there, a dark streak down the middle.
    float w = 0.70710678 * length(fwidth(scaledTextureCoord));
    float opacity = 1.0 - smoothstep(edgeDist - w, edgeDist + w, sigDist);
    fragColor = vec4(mix(vertexColor.rgb, linearGradientColor.rgb, textureCoord.x), opacity * linearGradientColor.a);

}