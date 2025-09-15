#version 300 es
precision highp float;
#define PI 3.1415926538
#define TAU 2.*3.1415926538
in vec4 vertexColor;
in vec2 textureCoord;
in vec3 pos;

out vec4 fragColor;

uniform vec2 pointA;
uniform vec2 pointB;
uniform float inverseLineLengthSq;

uniform float height;
uniform float width;

uniform float edgeDist;
uniform float edgeSharpness;

uniform vec4 linearGradientColor;

void main() {
    float sigDist = -1.;
    vec2 pos = pos.xy;


    float t = max(0., min(1., dot(pos - pointA, pointB - pointA) * inverseLineLengthSq));
    vec2 projection = pointA + t * (pointB - pointA);  // Projection falls on the segment
    sigDist = distance(pos, projection) / height;

    float opacity = smoothstep(edgeDist, edgeDist - edgeSharpness, sigDist);

    fragColor = vec4(mix(vertexColor.rgb, linearGradientColor.rgb, textureCoord.x), 1. * opacity * linearGradientColor.a);
    fragColor = vec4(fragColor.rgb, fragColor.a * opacity);
}