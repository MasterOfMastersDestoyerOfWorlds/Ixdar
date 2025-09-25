#version 300 es
precision highp float;
#define PI 3.1415926538

in vec4 vertexColor;
in vec2 textureCoord;
in vec2 scaledTextureCoord;

out vec4 fragColor;

uniform vec2 pointA;
uniform vec2 pointB;
uniform float lineLengthSq;

uniform float dashPhase;
uniform float dashLength;

uniform float edgeDist;
uniform float edgeSharpness;

uniform vec4 linearGradientColor;

void main() {
    float sigDist = -1.;
    float t = max(0., min(1., dot(scaledTextureCoord - pointA, pointB - pointA) / lineLengthSq));
    vec2 projection = pointA + t * (pointB - pointA);

    sigDist = distance(scaledTextureCoord, projection);
    float opacity = smoothstep(edgeDist, edgeDist - edgeSharpness, sigDist);
    fragColor = vec4(mix(vertexColor.rgb, linearGradientColor.rgb, textureCoord.x), 1. * opacity * linearGradientColor.a);

    float dashWave = (sin((scaledTextureCoord.x * PI) / dashLength + dashPhase) + 1.) / 2.;
    float dashOpac = smoothstep(0.5, 0.5 + edgeSharpness, dashWave);
    float x = (scaledTextureCoord.x * PI) / dashLength + dashPhase;

    float distA = distance(scaledTextureCoord, pointA);
    float distB = distance(scaledTextureCoord, pointB);
    float opacA = smoothstep(edgeDist + edgeSharpness, edgeDist, min(distA, distB));
    fragColor = vec4(fragColor.rgb, fragColor.a * dashOpac);
}